package fi.protonode.postcryptic;

import java.util.List;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

@Path("/config")
@ApplicationScoped
@Produces("application/json")
@Consumes("application/json")
public class ComponentConfigResource {

    private static final Logger LOG = Logger.getLogger(ComponentConfigResource.class.getName());
    private final EntityManager entityManager;

    @Inject
    public ComponentConfigResource(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @GET
    @Transactional
    public List<ComponentConfigEntity> get() {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<ComponentConfigEntity> criteriaQuery = criteriaBuilder.createQuery(ComponentConfigEntity.class);
        Root<ComponentConfigEntity> root = criteriaQuery.from(ComponentConfigEntity.class);
        criteriaQuery.select(root);

        return entityManager.createQuery(criteriaQuery).getResultList();
    }

    @POST
    @Transactional
    public Response create(ComponentConfigEntity config) {
        if (config.id != null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        entityManager.persist(config);
        LOG.infov("Created ComponentConfig id={0}", config.id);
        return Response.ok(config.id).status(Response.Status.CREATED).build();
    }
}
