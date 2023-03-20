package com.soapboxrace.core.jpa;

import javax.persistence.*;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "LIVERYSTORE_DATA")
@NamedQueries({
	@NamedQuery(name = "LiveryStoreDataEntity.getVinylsByCode", query = "SELECT obj FROM LiveryStoreDataEntity obj WHERE obj.liverycode = :liverycode")
})
public class LiveryStoreDataEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID", nullable = false)
    @Getter @Setter private Long id;

	@Getter @Setter private Integer orderid;
	@Getter @Setter private Integer hash;
	@Getter @Setter private Integer hue1;
	@Getter @Setter private Integer hue2;
	@Getter @Setter private Integer hue3;
	@Getter @Setter private Integer hue4;
	@Getter @Setter private Integer layer;
	@Getter @Setter private boolean mir;
	@Getter @Setter private Integer rot;
	@Getter @Setter private Integer sat1;
	@Getter @Setter private Integer sat2;
	@Getter @Setter private Integer sat3;
	@Getter @Setter private Integer sat4;
	@Getter @Setter private Integer scalex;
	@Getter @Setter private Integer scaley;
	@Getter @Setter private Integer shear;
	@Getter @Setter private Integer tranx;
	@Getter @Setter private Integer trany;
	@Getter @Setter private Integer var1;
	@Getter @Setter private Integer var2;
	@Getter @Setter private Integer var3;
	@Getter @Setter private Integer var4;
	@Getter @Setter private String liverycode;
}