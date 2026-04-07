package com.soapboxrace.core.jpa;

import javax.persistence.*;

@Entity
@Table(name = "AUTO_TUNE_CACHE",
       uniqueConstraints = @UniqueConstraint(columnNames = {"physics_hash", "class_name", "priority", "level"}))
@NamedQueries({
    @NamedQuery(name = "AutoTuneCacheEntity.findAll", query = "SELECT obj FROM AutoTuneCacheEntity obj"),
    @NamedQuery(name = "AutoTuneCacheEntity.findAllKeys", query = "SELECT obj.physicsHash, obj.className, obj.priority, obj.level FROM AutoTuneCacheEntity obj"),
    @NamedQuery(name = "AutoTuneCacheEntity.findByKey", query = "SELECT obj FROM AutoTuneCacheEntity obj WHERE obj.physicsHash = :physicsHash AND obj.className = :className AND obj.priority = :priority AND obj.level = :level"),
    @NamedQuery(name = "AutoTuneCacheEntity.findBestForLevel", query = "SELECT obj FROM AutoTuneCacheEntity obj WHERE obj.physicsHash = :physicsHash AND obj.className = :className AND obj.priority = :priority AND obj.level <= :level ORDER BY obj.level DESC"),
    @NamedQuery(name = "AutoTuneCacheEntity.deleteByPhysicsHash", query = "DELETE FROM AutoTuneCacheEntity obj WHERE obj.physicsHash = :physicsHash"),
    @NamedQuery(name = "AutoTuneCacheEntity.deleteAll", query = "DELETE FROM AutoTuneCacheEntity")
})
public class AutoTuneCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "physics_hash", nullable = false)
    private int physicsHash;

    @Column(name = "class_name", nullable = false, length = 10)
    private String className;

    @Column(name = "priority", nullable = false, length = 20)
    private String priority;

    @Column(name = "level", nullable = false)
    private int level = 100;

    @Column(name = "part_hashes", nullable = false, length = 1000)
    private String partHashes;

    @Column(name = "achieved_rating", nullable = false)
    private int achievedRating;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getPhysicsHash() { return physicsHash; }
    public void setPhysicsHash(int physicsHash) { this.physicsHash = physicsHash; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public String getPartHashes() { return partHashes; }
    public void setPartHashes(String partHashes) { this.partHashes = partHashes; }

    public int getAchievedRating() { return achievedRating; }
    public void setAchievedRating(int achievedRating) { this.achievedRating = achievedRating; }
}
