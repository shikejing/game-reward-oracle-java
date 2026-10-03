package com.github.shikejing.gamerewardoraclejava.repository;

import com.github.shikejing.gamerewardoraclejava.model.Distribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DistributionRepository extends JpaRepository<Distribution, Long> {

    Optional<Distribution> findByMatchId(String matchId);

    boolean existsByMatchId(String matchId);
}
