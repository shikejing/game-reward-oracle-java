package com.github.shikejing.reward.service;

import com.github.shikejing.reward.domain.Model.ClaimFailure;
import com.github.shikejing.reward.domain.Model.ClaimRecord;
import com.github.shikejing.reward.domain.Model.GrantRecord;
import com.github.shikejing.reward.store.JournalStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 奖励领取。
 * <ul>
 *   <li>幂等：同一 (matchId, playerId) 重复领取只返回首次的 ClaimRecord；</li>
 *   <li>越权：requesterId 与 playerId 不一致即拒绝，原因 FORBIDDEN，
 *       与 NOT_FOUND（无此发放记录）可区分；</li>
 *   <li>按 (matchId, playerId) 粒度加锁，慢领取不阻塞其他对局的结算。</li>
 * </ul>
 */
@Service
public class ClaimService {

    public record ClaimResult(boolean ok, ClaimRecord record, ClaimFailure failure) {
        static ClaimResult ok(ClaimRecord r) { return new ClaimResult(true, r, null); }
        static ClaimResult fail(ClaimFailure f) { return new ClaimResult(false, null, f); }
    }

    private final JournalStore store;
    private final Clock clock;
    private final Map<String, ReentrantLock> claimLocks = new ConcurrentHashMap<>();

    public ClaimService(JournalStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ClaimResult claim(String matchId, String playerId, String requesterId) {
        if (!playerId.equals(requesterId)) {
            return ClaimResult.fail(ClaimFailure.FORBIDDEN); // 越权：与 NOT_FOUND 可区分
        }
        ReentrantLock lock = claimLocks.computeIfAbsent(
                matchId + "" + playerId, k -> new ReentrantLock());
        lock.lock();
        try {
            Optional<ClaimRecord> existing = store.claimOf(matchId, playerId);
            if (existing.isPresent()) {
                return ClaimResult.ok(existing.get()); // 幂等：返回首次结果
            }
            Optional<GrantRecord> grant = store.grantOf(matchId, playerId);
            if (grant.isEmpty()) {
                return ClaimResult.fail(ClaimFailure.NOT_FOUND);
            }
            ClaimRecord record = new ClaimRecord(matchId, playerId,
                    grant.get().amount(), clock.millis());
            store.commitClaim(record);
            return ClaimResult.ok(record);
        } finally {
            lock.unlock();
        }
    }

    public Optional<ClaimRecord> claimOf(String matchId, String playerId) {
        return store.claimOf(matchId, playerId);
    }
}
