package com.github.shikejing.gamerewardoraclejava.repository;

import com.github.shikejing.gamerewardoraclejava.common.SettlementStatus;
import com.github.shikejing.gamerewardoraclejava.model.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByMatchIdAndStatus(String matchId, SettlementStatus status);

    List<Settlement> findByMatchIdOrderByIdDesc(String matchId);

    Optional<Settlement> findByMatchIdAndSeq(String matchId, long seq);
}
