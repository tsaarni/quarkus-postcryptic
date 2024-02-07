# postcryptic - prototype project

⚠️ Note: This project is **not functional**!

## Idea

This project represents an idea of Quarkus extension to help encrypting and decrypting columns when using Hibernate ORM together with PostgreSQL.


## Introduction

PostgreSQL has a built-in encryption module called [pgcrypto](https://www.postgresql.org/docs/current/pgcrypto.html).
The module provides low level functions for encrypting individual values before they are stored in the database and decrypting them after they are retrieved.
The functions are part of the SQL dialect of PostgreSQL.
The functions are executed on the server side, just like any other SQL statement.
The benefit is that searches over the encrypted data will work, since the server can decrypt the data while executing the SQL search.


Following functions are provided by the module:

* [`pgp_sym_encrypt()`](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS-PGP-SYM-ENCRYPT) and [`pgp_sym_decrypt()`](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS-PGP-SYM-DECRYPT) - These functions implement symmetric encryption, as defined by OpenPGP standard [RFC 4880](https://tools.ietf.org/html/rfc4880).
* [`armor()`](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS-ARMOR) and [`dearmor()`](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS-ARMOR) - These functions convert binary data to ASCII armored format and vice versa according to OpenPGP standard.
* `encrypt()` and `decrypt()` - Raw encryption functions, which are [not recommended for use](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-RAW-ENC-FUNCS).

The benefits of using the OpenPGP standard over raw encryption functions is described [here](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS) and [here](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-RAW-ENC-FUNCS).
Shortly, PGP is well-defined format for storing encrypted data, it uses salt (randomness) to the encrypted data and it provides integrity check.

So, how to use the functions in a Java application?

In this project it is assumed that application uses Hibernate ORM to access the database.
Hibernate ORM  [column transformers](https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html#mapping-column-read-and-write) allows injecting custom SQL code into Hibernate-generated SQL statements.
Java annotation `@ColumnTransformer` is applied to a field in an entity class like this:

```java
@Entity
public class User {
    @ColumnTranformer(
        read = "pgp_sym_decrypt(password, 'my secret passphrase'')",
        write = "pgp_sym_encrypt(?, 'my secret passphrase')"
    )
    private String password;
    ...
}
```

When Hibernate generates SQL statement for writing an object to the database, something like following is generated:

```sql
INSERT INTO user (password)
VALUES
  (
    pgp_sym_encrypt(?, 'my secret passphrase')
  )
```

Or for reading an object from the database:

```sql
SELECT
  pgp_sym_decrypt(
    password, 'my secret passphrase'
  )
FROM
  user
```


The low-level functions provided by pgcrypto are not practical to use directly in the application:
- It require the application to know the encryption key and to provide it as a parameter to the SQL statement.
- It leaves the application to manage the encryption key.
- It does not support key rotation.


## Requirements

* It shall be possible to introduce encryption to an existing unencrypted database.
* It shall be possible to rotate encryption key without downtime.
* It shall be possible to manage the (primary) key outside of the application.
* It shall be possible to support multiple (secondary) decryption keys at the same time, to avoid the need to re-encrypt all data at once when rotating keys.
* It should be possible to introduce encryption to an existing application with relatively small changes to the application code.

## Design

Following concepts are introduced:

* **Data encryption key (DEK)** - The key used to encrypt and decrypt the data.
* **Key encryption key (KEK)** - The key used to encrypt and decrypt the DEK.
* **Keyring** - A table in the database which stores the DEKs.

Vault is used to manage the KEK.
The application uses Vault transit to encrypt and decrypt the DEKs.
If access to Vault is lost, the application will not be able to decrypt the DEKs and the data cannot be decrypted.

### Initialization

Following steps are performed when the application starts:

```mermaid
sequenceDiagram

participant App
participant PostgreSQL
participant Vault

App->>Vault: Check if KEK exists

opt KEK does not exist
    App->>Vault: Generate new key encryption key
    activate Vault
    Note over Vault: Generate new key
    Vault-->>App: OK
    deactivate Vault
end

App->>PostgreSQL: Fetch active DEK

alt DEK does not exist
    activate App
    Note over App: Generate new key
    App->>Vault: Encrypt the new key with KEK
    activate Vault
    Vault-->>App: Encrypted key
    deactivate Vault
    App->>PostgreSQL: Store encrypted key + set as active
    deactivate App
else DEK exists
    activate App
    App->>PostgreSQL: Fetch DEK(s)
    PostgreSQL-->>App: Encrypted key(s)
    App->>Vault: Decrypt DEK(s)
    Vault-->>App: Decrypted key(s)
    deactivate App
end

note over App: Store DEK(s) in memory
```

### Data decryption and encryption sequence.

PostgreSQL supports [runtime session configuration parameters](https://www.postgresql.org/docs/current/sql-set.html) which can be used to avoid the need to have encryption keys as part of the SQL statements.
Session parameters are valid for the current session only, meaning that the active set of DEKs needs to be uploaded to PostgreSQL by the application every time a new session is created.

The parameters are set by calling [`set_config()`](https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADMIN-SET) function and accessed by calling [`current_setting()`](https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADMIN-SET) function.


```mermaid
sequenceDiagram

participant App
participant PostgreSQL

alt INSERT or UPDATE
    App->>PostgreSQL: set DEK(s) as session config parameters
    App->>PostgreSQL: Insert or update encrypted data
else SELECT
    App->>PostgreSQL: set DEK(s) as session config parameters
    App->>PostgreSQL: Select encrypted data
end
```




TODO

- Key versioning.
- Tooling for managing keys in use.




https://stackoverflow.com/a/22596845/458531


https://github.com/quarkusio/quarkus/issues/5219
https://github.com/quarkusio/quarkus/pull/9009


## Drawbacks

- Impact on performance can be significant.
- Cleartext data will still be stored in the database indexes, if the column is indexed.


## Demo

### Setup

First, start the services required by the application: PostgreSQL, pgAdmin and Vault

```bash
docker-compose up
```

Then run the application:

```bash
./gradlew quarkusDev
```

### Writing and reading data

Write some test data to the database via the REST API:

```bash
http -v POST http://localhost:8080/config name=param1 value=value1
http -v POST http://localhost:8080/config name=param2 value=value2
```

Check that the data can be read from the database in unencrypted form:

```bash
http GET http://localhost:8080/config
```

Use pgAdmin to access PostgreSQL directly, to check how the data is stored in the database http://localhost:8081/.
Navigate to `Servers` -> `Quarkus` -> `Databases` -> `quarkus` -> `Schemas` -> `public` -> `Tables` -> `component_config` -> `View/Edit Data` -> `All Rows`.
The `value` part of the data is stored in the column `value` in the table `component_config`.
Note that the data is encrypted with OpenGPG and it uses [OpenGPG ASCII armor format](https://datatracker.ietf.org/doc/html/rfc4880#section-6.2).

```
-----BEGIN PGP MESSAGE-----
key: 1

ww0EBwMCghPB1DDJ//9v0jcBQTNuS3n/z65mY1cPwhoSWldeVKz1YUObbUIUt3gfzivHBHFIll7o
6gJWfzrto7kCjC0aOdrx
=grJt
-----END PGP MESSAGE-----
```

The format allows storing the data as text, which can be useful when reusing the DB schema from existing application where the unencrypted data was stored in text column.

Note that the ASCII armor format allows attaching additional custom metadata to the encrypted data.
It can be used to store information about the encryption key used to encrypt the data.
The header `key: 1` refers to the data encryption key (DEK) and in the table `postcryptic_keyring` with `id=1`.
Use pgAdmin to check the `postcryptic_keyring` table.
The DEK is stored in the column `dek` in Vault transit format.


```
vault:v1:JwadIriDKtlWYbtTznCMkLBW9RXvKCdbYoj5UR43BhU52VmWjGhumZMKajWpjMgI7d7rqA==
```


### Rotating the DEK

Check the status of the KEK in Vault.
First login to Vault with the root token (hardcoded in `docker-compose.yml` as `root`)

```bash
docker exec quarkus-postcryptic-vault-1 vault login token=root
```

Read the status of the key:

```bash
docker exec quarkus-postcryptic-vault-1 vault read transit/keys/postcryptic.kek
```

To rotate the DEK, we need to generate a random key, encrypt it with Vault transit and store it in the database.
In this example we create new key with value `my secret passphrase`.
To encrypt the key use following command:

```bash
DEK=$(docker exec quarkus-postcryptic-vault-1 vault write transit/encrypt/postcryptic.kek plaintext=$(echo -n "my secret passphrase" | base64) -format=json | jq -r .data.ciphertext)
echo $DEK
```

Insert the DEK to the database:

```bash
docker exec quarkus-postcryptic-postgres-1 psql postgresql://quarkus:quarkus@localhost/quarkus -c "INSERT INTO postcryptic_keyring (dek) VALUES ('$DEK')"
```

Check the DEK by fetching the `postcryptic_keyring` table:

```bash
docker exec quarkus-postcryptic-postgres-1 psql postgresql://quarkus:quarkus@localhost/quarkus -c "SELECT * FROM postcryptic_keyring"
```

Enter new data into the database:

```bash
http -v POST http://localhost:8080/config name=param3 value=value3
```

Check that all data is still readable:

```bash
http GET http://localhost:8080/config
```


Use the ID from the response to read the encrypted data from the database and write it to `data.gpg`:

```bash
ID=CHANGE_ME
docker exec quarkus-postcryptic-postgres-1 psql postgresql://quarkus:quarkus@localhost/quarkus --no-align --quiet --tuples-only  -c "SELECT value FROM component_config WHERE id=$ID" > data.gpg
```

Observe that the ascii-armored data is stored in `data.gpg`:

```
-----BEGIN PGP MESSAGE-----
key: 2

ww0EBwMCEOnAET48y1t20jcBKBbdrWa6OWrBZ0fICtVgmc/7lt8yfUfEZ77Y4Fh3/UWyMtFFJ+xh
EFSFTPpK4vYa1/LLNaTY
=k/7U
-----END PGP MESSAGE-----
```


Data can be also decrypted with `gpg` on the command line:

```bash
gpg --pinentry-mode=loopback --decrypt --passphrase="my secret passphrase" --output - data.gpg
gpg --pinentry-mode=loopback --passphrase="my secret passphrase" --list-packets data.gpg
```



## Debugging

./gradlew quarkusDev --debug # Stops waiting for debug connection
