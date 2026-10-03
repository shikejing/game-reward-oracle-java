package com.github.shikejing.gamerewardoraclejava.model;

import com.github.shikejing.gamerewardoraclejava.common.SettlementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 结算单。同一 (matchId, seq) 唯一，重复结算幂等。
 */
@Entity
@Table(name = "settlements",
        uniqueConstraints = @UniqueConstraint(name = "uk_settlement_match_seq", columnNames = {"match_id", "seq"}))
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, length = 64)
    private String matchId;

    @Column(nullable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SettlementStatus status;

    @Column(nullable = false)
    private Instant settledAt;

    @Column(nullable = false)
    private long totalAmount;

    @Version
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public SettlementStatus getStatus() { return status; }
    public void setStatus(SettlementStatus status) { this.status = status; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
