package com.github.shikejing.reward;

import com.github.shikejing.reward.config.RewardRuleConfig;
import com.github.shikejing.reward.domain.Model.TiePolicy;
import com.github.shikejing.reward.service.ClaimService;
import com.github.shikejing.reward.service.MatchService;
import com.github.shikejing.reward.service.SettlementService;
import com.github.shikejing.reward.store.JournalStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 测试基建：可变时钟 + 手工组装的服务栈。 */
public class TestSupport {

    public static final class MutableClock extends Clock {
        private final AtomicLong millis;
        public MutableClock(long start) { this.millis = new AtomicLong(start); }
        public void advance(long ms) { millis.addAndGet(ms); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
    }

    public static RewardRuleConfig rule(long windowMs, TiePolicy tiePolicy) {
        RewardRuleConfig c = new RewardRuleConfig();
        c.setRankAmounts(Map.of(1, 100L, 2, 50L, 3, 20L));
        c.setTiePolicy(tiePolicy);
        c.setRevisionWindowMs(windowMs);
        return c;
    }

    public record Stack(JournalStore store, MatchService matches,
                        SettlementService settlements, ClaimService claims,
                        MutableClock clock) {}

    public static Stack newStack(Path dir, long windowMs, TiePolicy tiePolicy, long startMillis) {
        JournalStore store = new JournalStore(dir.toString());
        store.start();
        RewardRuleConfig rule = rule(windowMs, tiePolicy);
        MutableClock clock = new MutableClock(startMillis);
        MatchService matches = new MatchService(store, rule, clock);
        SettlementService settlements = new SettlementService(store, matches, rule, clock);
        ClaimService claims = new ClaimService(store, clock);
        return new Stack(store, matches, settlements, claims, clock);
    }

    /** 模拟重启：同一目录重建存储，服务层重新接线。 */
    public static Stack restart(Path dir, long windowMs, TiePolicy tiePolicy, long startMillis) {
        return newStack(dir, windowMs, tiePolicy, startMillis);
    }
}
