package com.github.shikejing.gamerewardoraclejava.repository;

import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.model.Match;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MatchRepository extends JpaRepository<Match, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Match m where m.matchId = :matchId")
    Optional<Match> findForUpdate(@Param("matchId") String matchId);

    List<Match> findByStatus(MatchStatus status);

    long countByStatus(MatchStatus status);

    @Query("select m from Match m where m.status = 'SETTLED' and m.revisionDeadline < :now")
    List<Match> findSettledWithExpiredWindow(@Param("now") Instant now);
}
