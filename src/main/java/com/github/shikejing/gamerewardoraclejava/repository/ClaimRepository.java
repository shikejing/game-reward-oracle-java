package com.github.shikejing.gamerewardoraclejava.repository;

import com.github.shikejing.gamerewardoraclejava.model.Claim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    Optional<Claim> findByDistributionIdAndPlayerId(Long distributionId, String playerId);

    List<Claim> findByMatchIdOrderByClaimedAtAsc(String matchId);

    List<Claim> findByPlayerIdOrderByClaimedAtAsc(String playerId);
}
