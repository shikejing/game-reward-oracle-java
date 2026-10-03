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
 * 领取记录。同一 (distributionId, playerId) 唯一，幂等领取。
 */
@Entity
@Table(name = "claims",
        uniqueConstraints = @UniqueConstraint(name = "uk_claim_distribution_player", columnNames = {"distribution_id", "player_id"}))
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "distribution_id", nullable = false)
    private Long distributionId;

    @Column(name = "match_id", nullable = false, length = 64)
    private String matchId;

    @Column(name = "player_id", nullable = false, length = 64)
    private String playerId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private Instant claimedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDistributionId() { return distributionId; }
    public void setDistributionId(Long distributionId) { this.distributionId = distributionId; }
    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }
    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
}
