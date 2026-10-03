package com.github.shikejing.reward.service;

import com.github.shikejing.reward.config.RewardRuleConfig;
import com.github.shikejing.reward.domain.Model.MatchReport;
import com.github.shikejing.reward.domain.Model.PlayerScore;
import com.github.shikejing.reward.service.ServiceException.Reason;
import com.github.shikejing.reward.store.JournalStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对局上报与结论收敛。
 * <ul>
 *   <li>结论 = 未被撤销且 reportSeq 最大的上报；同序号重复上报幂等；</li>
 *   <li>修订（新序号上报或撤销）仅在修订窗口内且未结算时生效，
 *       窗口边界为 firstReportAt + revisionWindowMs（含边界，闭区间）；</li>
 *   <li>结算后结论冻结：迟到上报仅留痕，不改写已结算结论。</li>
 * </ul>
 */
@Service
public class MatchService {

    private final JournalStore store;
    private final RewardRuleConfig rule;
    private final Clock clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public MatchService(JournalStore store, RewardRuleConfig rule, Clock clock) {
        this.store = store;
        this.rule = rule;
        this.clock = clock;
    }

    public ReentrantLock lockOf(String matchId) {
        return locks.computeIfAbsent(matchId, k -> new ReentrantLock());
    }

    /** 提交上报。重复上报（同 matchId+seq 且内容一致）幂等返回首次记录。 */
    public MatchReport submitReport(String matchId, long reportSeq, List<PlayerScore> scores) {
        lockOf(matchId).lock();
        try {
            List<MatchReport> history = store.reportsOf(matchId);
            for (MatchReport r : history) {
                if (r.reportSeq() == reportSeq) {
                    if (r.scores().equals(scores)) {
                        return r; // 幂等：重复上报不产生新记录
                    }
                    throw new ServiceException(Reason.DUPLICATE_REPORT_CONFLICT,
                            "同一序号上报内容冲突: " + matchId + "#" + reportSeq);
                }
            }
            boolean settled = store.settlementOf(matchId).isPresent();
            if (settled || !withinWindow(history)) {
                // 迟到/超窗上报：留痕但不参与结论（结算后结论已冻结；
                // 未结算但超窗时窗口同样关闭，见修订语义）。
                if (!settled && history.isEmpty()) {
                    // 首条上报总是接受，用于开启窗口
                } else if (settled) {
                    MatchReport late = new MatchReport(matchId, reportSeq, List.copyOf(scores),
                            clock.millis(), false);
                    store.appendReport(late);
                    return late;
                } else {
                    throw new ServiceException(Reason.REVISION_WINDOW_CLOSED,
                            "修订窗口已关闭: " + matchId);
                }
            }
            MatchReport report = new MatchReport(matchId, reportSeq, List.copyOf(scores),
                    clock.millis(), false);
            store.appendReport(report);
            return report;
        } finally {
            lockOf(matchId).unlock();
        }
    }

    /** 撤销某次上报（结算前修订）。已撤销记录仍可查询。 */
    public void revokeReport(String matchId, long reportSeq) {
        lockOf(matchId).lock();
        try {
            List<MatchReport> history = store.reportsOf(matchId);
            MatchReport target = history.stream()
                    .filter(r -> r.reportSeq() == reportSeq).findFirst()
                    .orElseThrow(() -> new ServiceException(Reason.REPORT_NOT_FOUND,
                            "上报不存在: " + matchId + "#" + reportSeq));
            if (store.settlementOf(matchId).isPresent()) {
                throw new ServiceException(Reason.ALREADY_SETTLED, "已结算，禁止撤销: " + matchId);
            }
            if (!withinWindow(history)) {
                throw new ServiceException(Reason.REVISION_WINDOW_CLOSED,
                        "修订窗口已关闭: " + matchId);
            }
            if (!target.revoked()) {
                store.revokeReport(matchId, reportSeq);
            }
        } finally {
            lockOf(matchId).unlock();
        }
    }

    /** 当前唯一结论：结算后以已结算上报为准（冻结），否则取未撤销的最大序号上报。 */
    public MatchReport conclusionOf(String matchId) {
        lockOf(matchId).lock();
        try {
            return store.settlementOf(matchId)
                    .flatMap(st -> store.reportsOf(matchId).stream()
                            .filter(r -> r.reportSeq() == st.reportSeq()).findFirst())
                    .orElseGet(() -> store.reportsOf(matchId).stream()
                            .filter(r -> !r.revoked())
                            .max(Comparator.comparingLong(MatchReport::reportSeq))
                            .orElseThrow(() -> new ServiceException(Reason.MATCH_NOT_FOUND,
                                    "对局不存在或无有效上报: " + matchId)));
        } finally {
            lockOf(matchId).unlock();
        }
    }

    /** 全部上报历史（含被撤销与迟到留痕）。 */
    public List<MatchReport> historyOf(String matchId) {
        return store.reportsOf(matchId);
    }

    /** 窗口边界：now <= firstReportAt + revisionWindowMs（闭区间，行为确定）。 */
    private boolean withinWindow(List<MatchReport> history) {
        if (history.isEmpty()) return true;
        long firstAt = history.stream()
                .mapToLong(MatchReport::receivedAtMillis).min().orElseThrow();
        return clock.millis() <= firstAt + rule.getRevisionWindowMs();
    }
}
