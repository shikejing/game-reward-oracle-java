package com.github.shikejing.gamerewardoraclejava.repository;

import com.github.shikejing.gamerewardoraclejava.model.RewardRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RewardRecordRepository extends JpaRepository<RewardRecord, Long> {

    List<RewardRecord> findBySettlementIdOrderByRankAscPlayerIdAsc(Long settlementId);

    List<RewardRecord> findByMatchId(String matchId);

    Optional<RewardRecord> findByMatchIdAndPlayerId(String matchId, String playerId);

    Optional<RewardRecord> findBySettlementIdAndPlayerId(Long settlementId, String playerId);
}
