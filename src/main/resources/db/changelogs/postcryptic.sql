CREATE
OR REPLACE FUNCTION postcryptic_decrypt (ciphertext text) RETURNS text AS $$
DECLARE
    keyid text;
BEGIN
    -- Check if the data is PGP encrypted or not.
    IF ciphertext LIKE '-----BEGIN PGP MESSAGE-----%' THEN
        -- Data is encrypted.
        -- Get the keyid from the custom PGP header 'key' which we set in postcryptic_encrypt().
        keyid := (SELECT value FROM pgp_armor_headers(ciphertext) WHERE key = 'key' LIMIT 1);

        -- Decrypt the data using key from the session configuration variable (current_setting()).
        -- All the data encryption keys are stored in the session configuration variables, prefixed with 'postcryptic.dekid'.
        RETURN pgp_sym_decrypt(dearmor(ciphertext), current_setting('postcryptic.dekid' || keyid));
    ELSE
        -- If data is not encrypted, just return the data as-is.
        -- This is useful when not all data is encrypted, during migration from unencrypted to encrypted database.
        RETURN value;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE
OR REPLACE FUNCTION postcryptic_encrypt (cleartext text) RETURNS text AS $$
DECLARE
    keyid text;
BEGIN
    -- Get the current keyid from the session configuration variable.
    -- This is the keyid of the key which is used to encrypt the data.
    keyid := current_setting('postcryptic.currentkey');

    -- The data encryption keys are stored in the session configuration variables, prefixed with 'postcryptic.dekid'.
    RETURN armor(pgp_sym_encrypt(cleartext, current_setting('postcryptic.dekid' || keyid )), array['key'], array[keyid]);
END;
$$ LANGUAGE plpgsql;
