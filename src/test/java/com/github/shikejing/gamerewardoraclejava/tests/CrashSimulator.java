package com.github.shikejing.gamerewardoraclejava.tests;

import com.github.shikejing.gamerewardoraclejava.common.RewardRecordStatus;
import com.github.shikejing.gamerewardoraclejava.common.SettlementStatus;
import com.github.shikejing.gamerewardoraclejava.model.RewardRecord;
import com.github.shikejing.gamerewardoraclejava.model.Settlement;
import com.github.shikejing.gamerewardoraclejava.repository.RewardRecordRepository;
import com.github.shikejing.gamerewardoraclejava.repository.SettlementRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 测试辅助：模拟结算进行到一半时进程崩溃（事务回滚，不残留半份记录）。
 */
@Component
public class CrashSimulator {

    private final SettlementRepository settlementRepository;
    private final RewardRecordRepository rewardRecordRepository;

    public CrashSimulator(SettlementRepository settlementRepository,
                          RewardRecordRepository rewardRecordRepository) {
        this.settlementRepository = settlementRepository;
        this.rewardRecordRepository = rewardRecordRepository;
    }

    @Transactional
    public void crashAfterSettlement(String matchId) {
        Settlement s = new Settlement();
        s.setMatchId(matchId);
        s.setSeq(1L);
        s.setStatus(SettlementStatus.ACTIVE);
        s.setSettledAt(Instant.now());
        s.setTotalAmount(100L);
        s = settlementRepository.save(s);

        RewardRecord r = new RewardRecord();
        r.setSettlementId(s.getId());
        r.setMatchId(matchId);
        r.setPlayerId("a");
        r.setRank(1);
        r.setAmount(100L);
        r.setStatus(RewardRecordStatus.SETTLED);
        rewardRecordRepository.save(r);

        throw new RuntimeException("simulated crash mid-settlement");
    }
}
