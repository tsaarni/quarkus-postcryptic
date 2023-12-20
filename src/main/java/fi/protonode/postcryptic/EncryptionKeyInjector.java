package fi.protonode.postcryptic;

import java.util.Optional;

import org.jboss.logging.Logger;

import io.agroal.api.AgroalPoolInterceptor;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vault.VaultTransitSecretEngine;
import io.quarkus.vault.transit.ClearData;
import io.quarkus.vault.transit.VaultTransitKeyDetail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;

@ApplicationScoped
public class EncryptionKeyInjector implements AgroalPoolInterceptor {

    private static final Logger LOG = Logger.getLogger(EncryptionKeyInjector.class.getName());

    // Name of the key encryption key in Vault.
    private static final String PG_KEK_NAME = "postcryptic.kek";

    @Inject
    private VaultTransitSecretEngine transitSecretEngine;

    @Inject
    private EntityManager em;

    private boolean isInitialized = false;

    @Transactional
    public void onApplicationStartup(@Observes StartupEvent event) {
        LOG.info("onApplicationStartup");
        initialize();
        isInitialized = true;
    }


    @Override
    public void onConnectionAcquire(Connection connection) {
        LOG.infov("onConnectionAcquire {0}", connection);

        if (!isInitialized) {
            LOG.info("Skipping onConnectionAcquire, not initialized yet");
            return;
        }

        try {
            LOG.infov("Fetching DEKs from keyring in the database");
            try (Statement fetchDeksStatement = connection.createStatement();
                    Statement setConfigStatement = connection.createStatement()) {
                ResultSet rs = fetchDeksStatement
                        .executeQuery("SELECT id, dek FROM postcryptic_keyring WHERE active = true ORDER BY id ASC");

                int count = 0;
                Long id = null;
                while (rs.next()) {
                    id = rs.getLong(1);
                    String encryptedDek = rs.getString(2);
                    ClearData dek = transitSecretEngine.decrypt(PG_KEK_NAME, encryptedDek);
                    fetchDeksStatement
                            .addBatch("SELECT set_config('postcryptic.dekid" + id + "', '" + dek.asString()
                                    + "', false)");
                    count++;
                }
                if (id != null) {
                    fetchDeksStatement.addBatch("SELECT set_config('postcryptic.currentkey', '" + id + "', false)");
                }

                LOG.infov("Injecting {0} DEKs to session", count);

                fetchDeksStatement.executeBatch();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initialize() {
        LOG.infov("Initializing database");

        // Check if KEK exists.
        Optional<VaultTransitKeyDetail<?>> key = transitSecretEngine.readKey(PG_KEK_NAME);

        if (key.isEmpty()) {
            LOG.info("No KEK found, creating a new one");
            transitSecretEngine.createKey(PG_KEK_NAME, null);
        } else {
            LOG.infov("Existing KEK found, using versions={0}", key.get().getVersions());
        }

        // Check if DEK exists, if not, create a new one.
        if (hasResults(em, "SELECT dek FROM postcryptic_keyring WHERE active = true")) {
            LOG.info("DEKs found, skipping DEK generation");
            return;
        }

        LOG.info("No encrypted DEK found, creating a new one");

        // Create random data that will be used as data encryption key.
        String dek = createRandomDek();

        // Encrypt DEK with KEK.
        String encryptedDek = transitSecretEngine.encrypt(PG_KEK_NAME, dek);
        LOG.infov("Created DEK: {0}", encryptedDek);

        // Store encrypted DEK to database.
        Query query = em.createNativeQuery("INSERT INTO postcryptic_keyring (dek) VALUES (:dek)");
        query.setParameter("dek", encryptedDek);
        query.executeUpdate();
    }

    private static boolean hasResults(EntityManager em, String sql) {
        Query query = em.createNativeQuery(sql);
        return !query.getResultList().isEmpty();
    }

    private static String createRandomDek() {
        byte[] keyBytes = new byte[16];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

}
