package com.github.shikejing.gamerewardoraclejava.service;

import com.github.shikejing.gamerewardoraclejava.common.ApiException;
import com.github.shikejing.gamerewardoraclejava.common.ErrorCode;
import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.common.ReportStatus;
import com.github.shikejing.gamerewardoraclejava.model.Match;
import com.github.shikejing.gamerewardoraclejava.model.MatchReport;
import com.github.shikejing.gamerewardoraclejava.repository.MatchReportRepository;
import com.github.shikejing.gamerewardoraclejava.repository.MatchRepository;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportResponse;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 预言机上报收敛服务。
 * <p>
 * 收敛规则：按 (matchId, seq) 唯一标识一份上报；seq 最大的上报为当前结论。
 * 重复上报幂等返回；迟到（seq 落后）不改写结论；结算后修订仅在窗口内生效；
 * 已发放的对局拒绝任何修订。所有上报（含被拒绝的）均留痕可查。
 */
@Service
public class OracleService {

    private final MatchRepository matchRepository;
    private final MatchReportRepository reportRepository;
    private final EntityManager entityManager;

    public OracleService(MatchRepository matchRepository, MatchReportRepository reportRepository,
                         EntityManager entityManager) {
        this.matchRepository = matchRepository;
        this.reportRepository = reportRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public ReportResponse report(ReportRequest request) {
        validate(request);

        String matchId = request.matchId();
        long seq = request.seq();
        List<PlayerResult> players = request.players();

        Match match = loadOrCreate(matchId);

        // 重复上报：幂等返回已有结论
        MatchReport duplicate = reportRepository.findByMatchIdAndSeq(matchId, seq).orElse(null);
        if (duplicate != null) {
            // 重复上报：幂等返回 DUPLICATE，不改变任何结论
            return new ReportResponse(matchId, seq, ReportStatus.DUPLICATE,
                    "重复上报，幂等返回（原上报状态 " + duplicate.getStatus() + "）",
                    match.getCurrentSeq(), match.getStatus());
        }

        Decision decision = decide(match, seq);

        MatchReport report = new MatchReport();
        report.setMatchId(matchId);
        report.setSeq(seq);
        report.setPlayers(players);
        report.setReportedAt(Instant.now());
        report.setStatus(decision.status);
        report.setReason(decision.reason);
        try {
            reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException e) {
            // 并发下相同 seq 抢先插入：清空脏 session 后按重复上报处理
            entityManager.clear();
            MatchReport raced = reportRepository.findByMatchIdAndSeq(matchId, seq).orElse(null);
            if (raced != null) {
                return new ReportResponse(matchId, seq, ReportStatus.DUPLICATE,
                        "重复上报，幂等返回（并发抢先插入）",
                        match.getCurrentSeq(), match.getStatus());
            }
            throw e;
        }

        if (decision.status == ReportStatus.ACCEPTED) {
            match.setCurrentSeq(seq);
            match.setResultSnapshot(players);
            matchRepository.save(match);
        }

        return new ReportResponse(matchId, seq, decision.status, decision.reason,
                match.getCurrentSeq(), match.getStatus());
    }

    private Decision decide(Match match, long seq) {
        if (seq <= match.getCurrentSeq()) {
            return new Decision(ReportStatus.LATE,
                    "上报序号 " + seq + " 已落后于当前收敛序号 " + match.getCurrentSeq());
        }
        MatchStatus status = match.getStatus();
        if (status == MatchStatus.DISTRIBUTED) {
            return new Decision(ReportStatus.ALREADY_DISTRIBUTED, "对局奖励已发放，不可修订");
        }
        if (status == MatchStatus.REVISION_CLOSED) {
            return new Decision(ReportStatus.REVISION_CLOSED, "修订窗口已关闭，不可修订");
        }
        if (status == MatchStatus.SETTLED) {
            Instant deadline = match.getRevisionDeadline();
            if (deadline != null && Instant.now().isAfter(deadline)) {
                return new Decision(ReportStatus.REVISION_CLOSED, "修订窗口已于 " + deadline + " 关闭");
            }
            return new Decision(ReportStatus.ACCEPTED, "修订已接受，需重新结算");
        }
        return new Decision(ReportStatus.ACCEPTED, null);
    }

    private Match loadOrCreate(String matchId) {
        Match match = matchRepository.findForUpdate(matchId).orElse(null);
        if (match != null) {
            return match;
        }
        Match created = new Match();
        created.setMatchId(matchId);
        created.setStatus(MatchStatus.REPORTED);
        created.setCurrentSeq(0L);
        created.setResultSnapshot(List.of());
        try {
            matchRepository.saveAndFlush(created);
            return created;
        } catch (DataIntegrityViolationException e) {
            return matchRepository.findForUpdate(matchId)
                    .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "对局创建冲突"));
        }
    }

    private void validate(ReportRequest request) {
        if (request == null || request.matchId() == null || request.matchId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "matchId 不能为空");
        }
        if (request.seq() == null || request.seq() < 1) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "seq 必须为正整数");
        }
        if (request.players() == null || request.players().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "players 不能为空");
        }
        Set<String> seen = new HashSet<>();
        for (PlayerResult p : request.players()) {
            if (p == null || p.playerId() == null || p.playerId().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "playerId 不能为空");
            }
            if (p.score() == 0 && p.score() != 0) {
                // score 为基本类型 long，恒不为 null，此处仅占位
            }
            if (!seen.add(p.playerId())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                        "参与者重复: " + p.playerId());
            }
        }
    }

    private record Decision(ReportStatus status, String reason) {
    }
}
