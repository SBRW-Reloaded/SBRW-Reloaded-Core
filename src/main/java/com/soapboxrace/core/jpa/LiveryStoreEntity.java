package com.soapboxrace.core.jpa;

import javax.persistence.*;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "LIVERYSTORE")
@NamedQueries({
    @NamedQuery(name = "LiveryStoreEntity.findLiveryByCode", query = "SELECT obj FROM LiveryStoreEntity obj WHERE obj.code = :code")
})
public class LiveryStoreEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID", nullable = false)
    @Getter @Setter private Long liveryId;

    @Getter @Setter private Long personaId;
    @Getter @Setter private String code;
    @Getter @Setter private LocalDateTime created;
    @Getter @Setter private String liveryname;
    @Getter @Setter private String carname;
    @Getter @Setter private Boolean isbanned;
    @Getter @Setter private Integer useCount;
}
