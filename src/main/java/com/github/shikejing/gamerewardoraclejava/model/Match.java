package com.github.shikejing.gamerewardoraclejava.model;

import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.common.PlayerResultConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.List;

/**
 * 对局。matchId 为业务主键，全局唯一。
 */
@Entity
@Table(name = "matches")
public class Match {

    @Id
    @Column(name = "match_id", length = 64)
    private String matchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MatchStatus status;

    /** 当前已收敛的最大上报序号 */
    @Column(nullable = false)
    private long currentSeq;

    /** 最近一次结算所依据的上报序号 */
    private Long settledSeq;

    private Instant settledAt;

    /** 修订窗口截止时间（含边界） */
    private Instant revisionDeadline;

    private Instant distributedAt;

    /** 当前结论快照（参与者成绩） */
    @Convert(converter = PlayerResultConverter.class)
    @Column(length = 4096)
    private List<PlayerResult> resultSnapshot;

    @Version
    private Long version;

    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }
    public MatchStatus getStatus() { return status; }
    public void setStatus(MatchStatus status) { this.status = status; }
    public long getCurrentSeq() { return currentSeq; }
    public void setCurrentSeq(long currentSeq) { this.currentSeq = currentSeq; }
    public Long getSettledSeq() { return settledSeq; }
    public void setSettledSeq(Long settledSeq) { this.settledSeq = settledSeq; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public Instant getRevisionDeadline() { return revisionDeadline; }
    public void setRevisionDeadline(Instant revisionDeadline) { this.revisionDeadline = revisionDeadline; }
    public Instant getDistributedAt() { return distributedAt; }
    public void setDistributedAt(Instant distributedAt) { this.distributedAt = distributedAt; }
    public List<PlayerResult> getResultSnapshot() { return resultSnapshot; }
    public void setResultSnapshot(List<PlayerResult> resultSnapshot) { this.resultSnapshot = resultSnapshot; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
