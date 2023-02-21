package com.soapboxrace.core.jpa;

import javax.persistence.*;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "EVENT_DATA_SETUPS")
@NamedQueries({
    @NamedQuery(name = "EventDataSetupEntity.findByHash", query = "SELECT obj FROM EventDataSetupEntity obj WHERE obj.hash = :hash")
})
public class EventDataSetupEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID", nullable = false)
    @Getter @Setter private Long id;

    @Getter @Setter private String hash;
    @Getter @Setter private Long personaId;
    @Getter @Setter private Long carId;
    @Getter @Setter private String carName;
    @Getter @Setter private Integer carClassHash;
    @Getter @Setter private Integer carRating;
    @Getter @Setter private String performanceParts;
    @Getter @Setter private String skillmodParts;
    @Getter @Setter private String visualParts;

    @Lob
    @Column(length = 65535)
    @Getter @Setter private String carSetup;


}
