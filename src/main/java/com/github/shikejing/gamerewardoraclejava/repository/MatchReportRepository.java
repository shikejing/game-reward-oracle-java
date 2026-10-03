package com.github.shikejing.gamerewardoraclejava.repository;

import com.github.shikejing.gamerewardoraclejava.model.MatchReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {

    List<MatchReport> findByMatchIdOrderBySeqDesc(String matchId);

    Optional<MatchReport> findByMatchIdAndSeq(String matchId, long seq);
}
