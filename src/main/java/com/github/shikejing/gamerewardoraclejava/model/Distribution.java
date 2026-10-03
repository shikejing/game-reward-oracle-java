package com.github.shikejing.gamerewardoraclejava.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 发放单。matchId 唯一，一局只能发放一次，不可重复。
 */
@Entity
@Table(name = "distributions",
        uniqueConstraints = @UniqueConstraint(name = "uk_distribution_match", columnNames = {"match_id"}))
public class Distribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, length = 64)
    private String matchId;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(nullable = false)
    private Instant distributedAt;

    @Column(nullable = false)
    private long totalAmount;

    @Column(nullable = false)
    private int recordCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public Instant getDistributedAt() { return distributedAt; }
    public void setDistributedAt(Instant distributedAt) { this.distributedAt = distributedAt; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
    public int getRecordCount() { return recordCount; }
    public void setRecordCount(int recordCount) { this.recordCount = recordCount; }
}
