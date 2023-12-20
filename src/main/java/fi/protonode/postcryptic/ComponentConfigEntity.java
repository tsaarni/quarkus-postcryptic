package fi.protonode.postcryptic;

import org.hibernate.annotations.ColumnTransformer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;


@Entity
@Table(name = "component_config", schema = "public")
public class ComponentConfigEntity {

    @Id
    @GeneratedValue
    public Long id;

    @Column
    public String name;

    @Column(columnDefinition = "text")
    @ColumnTransformer(read = "postcryptic_decrypt(value)", write = "postcryptic_encrypt(?)")
    public String value;

    public ComponentConfigEntity() {
    }

    public ComponentConfigEntity(String name, String value) {
        this.name = name;
        this.value = value;
    }
}
