package com.github.shikejing.gamerewardoraclejava.model;

import com.github.shikejing.gamerewardoraclejava.common.RewardRecordStatus;
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
 * 奖励账目。同一 (settlementId, playerId) 唯一；
 * 结算 -> 发放 -> 领取 状态单向推进。
 */
@Entity
@Table(name = "reward_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_reward_settlement_player", columnNames = {"settlement_id", "player_id"}))
public class RewardRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "match_id", nullable = false, length = 64)
    private String matchId;

    @Column(name = "player_id", nullable = false, length = 64)
    private String playerId;

    @Column(nullable = false)
    private int rank;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RewardRecordStatus status;

    private Long claimId;

    private Instant claimedAt;

    @Version
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }
    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }
    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public RewardRecordStatus getStatus() { return status; }
    public void setStatus(RewardRecordStatus status) { this.status = status; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
