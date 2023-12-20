# postcryptic - prototype project

⚠️ Note: This project is **not functional**!

## Idea

Quarkus extension to help encrypting and decrypting columns when using Hibernate ORM together with PostgreSQL.


## Introduction

PostgreSQL has a built-in encryption module called [pgcrypto](https://www.postgresql.org/docs/current/pgcrypto.html).
The module provides low level functions for encrypting individual values before they are stored in the database or decrypting them after they are retrieved.
The functions are part of the SQL dialect of PostgreSQL.
The functions are executed on the server side, just like any other SQL statement.
The benefit is that searches over the encrypted data will work, since the data is decrypted as part of executing the SQL statement.


Following functions are provided by the module:

* [`pgp_sym_encrypt()`](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS-PGP-SYM-ENCRYPT) and [`pgp_sym_decrypt()`](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS-PGP-SYM-DECRYPT) - These functions implement symmetric encryption, as defined by OpenPGP standard [RFC 4880](https://tools.ietf.org/html/rfc4880).
* [`armor()`](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS-ARMOR) and [`dearmor()`](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS-ARMOR) - These functions convert binary data to ASCII armored format and vice versa according to OpenPGP standard.
* `encrypt()` and `decrypt()` - Raw encryption functions, which are [not recommended for use](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-RAW-ENC-FUNCS).

The benefits of using the OpenPGP standard over raw encryption functions is described [here](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-PGP-ENC-FUNCS) and [here](https://www.postgresql.org/docs/current/pgcrypto.html#PGCRYPTO-RAW-ENC-FUNCS).
Shortly, PGP is well-defined format for storing encrypted data, it uses salt (randomness) to the encrypted data and it provides integrity check.

So, how to use the functions in a Java application? 

In this project it is assumed that application uses hibernate ORM to access the database.
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


## Requirements

* It shall be possible to introduce encryption to an existing unencrypted database without downtime.
* It shall be possible to rotate encryption key without downtime.
* It shall be possible to support multiple (secondary) decryption keys at the same time, to avoid the need to re-encrypt all data at once when rotating keys.
* It shall be possible to manage the (primary) key outside of the application.
* It should be possible to introduce encryption to an existing application with relatively small changes to the application code.

## Design


```mermaid
sequenceDiagram
Alice->>John: Hello John, how are you?
loop Healthcheck
    John->>John: Fight against hypochondria
end
Note right of John: Rational thoughts!
John-->>Alice: Great!
John->>Bob: How about you?
Bob-->>John: Jolly good!
```




An active set of data encryption keys needs to be uploaded to the server by the application.


TODO

- Key versioning.
- Tooling for managing keys in use.




https://stackoverflow.com/a/22596845/458531


https://github.com/quarkusio/quarkus/issues/5219
https://github.com/quarkusio/quarkus/pull/9009


## Drawbacks

- Impact on performance can be significant.
- Cleartext data will still be stored in the database indexes, if the column is indexed.


## Developing

Start the database, pgAdmin and Vault:

```bash
docker-compose up
```

Run the application in dev mode:

```bash
./gradlew quarkusDev
./gradlew quarkusDev --debug # Stop waiting for debug connection
```


## Testing

Write data to the database:

```bash
http -v POST http://localhost:8080/config name=param1 value=value1
http -v POST http://localhost:8080/config name=param2 value=value2
```

Read data from the database:

```bash
http GET http://localhost:8080/config
```

Use pgAdmin to check the data http://localhost:8081/

Check the KEK status:

```bash
docker exec quarkus-postcryptic-vault-1 vault login token=root
docker exec quarkus-postcryptic-vault-1 vault read transit/keys/postcryptic.kek
```

Create new DEK and encrypt it with Vault:

```bash
DEK=$(docker exec quarkus-postcryptic-vault-1 vault write transit/encrypt/postcryptic.kek plaintext=$(echo -n "my secret passphrase" | base64) -format=json | jq -r .data.ciphertext)
echo $DEK
```

Insert the DEK to the database:

```bash
docker exec quarkus-postcryptic-postgres-1 psql postgresql://quarkus:quarkus@localhost/quarkus -c "INSERT INTO postcryptic_keyring (dek) VALUES ('$DEK')"
```

Check the DEK:

```bash
docker exec quarkus-postcryptic-postgres-1 psql postgresql://quarkus:quarkus@localhost/quarkus -c "SELECT * FROM postcryptic_keyring"
```

Enter new data into the database:

```bash
http -v POST http://localhost:8080/config name=param3 value=value3
```

Use the ID from the response to read the encrypted data from the database and write it to `data.gpg`:

```bash
ID=CHANGE_ME
docker exec quarkus-postcryptic-postgres-1 psql postgresql://quarkus:quarkus@localhost/quarkus -c "SELECT * FROM component_config WHERE id=$ID"
docker exec quarkus-postcryptic-postgres-1 psql postgresql://quarkus:quarkus@localhost/quarkus --no-align --quiet --tuples-only  -c "SELECT value FROM component_config WHERE id=$ID" > data.gpg
```

Data can be decrypted with `gpg`:

```bash
gpg --pinentry-mode=loopback --decrypt --passphrase="my secret key" --output - data.gpg
gpg --list-packets data.gpg
```