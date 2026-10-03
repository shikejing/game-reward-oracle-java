package com.github.shikejing.gamerewardoraclejava.model;

import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.common.PlayerResultConverter;
import com.github.shikejing.gamerewardoraclejava.common.ReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.List;

/**
 * 上报记录。同一 (matchId, seq) 唯一，重复上报幂等；
 * 迟到/被拒绝的上报也留痕，供追溯。
 */
@Entity
@Table(name = "match_reports",
        uniqueConstraints = @UniqueConstraint(name = "uk_report_match_seq", columnNames = {"match_id", "seq"}))
public class MatchReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, length = 64)
    private String matchId;

    @Column(nullable = false)
    private long seq;

    @Convert(converter = PlayerResultConverter.class)
    @Column(length = 4096)
    private List<PlayerResult> players;

    @Column(nullable = false)
    private Instant reportedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReportStatus status;

    @Column(length = 256)
    private String reason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public List<PlayerResult> getPlayers() { return players; }
    public void setPlayers(List<PlayerResult> players) { this.players = players; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }
    public ReportStatus getStatus() { return status; }
    public void setStatus(ReportStatus status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
