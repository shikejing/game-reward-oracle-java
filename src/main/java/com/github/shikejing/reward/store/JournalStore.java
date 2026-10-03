package com.github.shikejing.reward.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.shikejing.reward.domain.Model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地持久化：追加式事件日志（journal.log），每行一条已提交事件。
 * <ul>
 *   <li>结算/发放作为单条事件整体落盘，要么全有要么全无，不会残留半份记录；</li>
 *   <li>崩溃恢复时重放日志，末尾不完整的半行被丢弃；</li>
 *   <li>重启后内存索引由日志完整还原，已提交与未完成的对局天然可区分。</li>
 * </ul>
 */
@Component
public class JournalStore {

    private static final String JOURNAL = "journal.log";

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    /** matchId -> 该对局的全部已提交状态。 */
    private final Map<String, MatchState> matches = new ConcurrentHashMap<>();
    private BufferedWriter writer;

    static final class MatchState {
        final Map<Long, MatchReport> reports = new LinkedHashMap<>();
        Settlement settlement;
        final Map<String, GrantRecord> grants = new LinkedHashMap<>();
        final Map<String, ClaimRecord> claims = new LinkedHashMap<>();
    }

    public JournalStore(@Value("${reward.store.dir:./data}") String dir) {
        this.dir = Paths.get(dir);
    }

    @PostConstruct
    public synchronized void start() {
        try {
            Files.createDirectories(dir);
            Path journal = dir.resolve(JOURNAL);
            if (Files.exists(journal)) {
                List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.isBlank()) continue;
                    try {
                        apply(mapper.readTree(line));
                    } catch (Exception e) {
                        if (i == lines.size() - 1) {
                            break; // 末尾半行：崩溃时未提交，丢弃
                        }
                        throw new IllegalStateException("journal 损坏，拒绝启动: " + line, e);
                    }
                }
            }
            writer = Files.newBufferedWriter(journal, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- 提交入口（先落盘再入内存，保证已提交即可恢复） ----------

    public synchronized void appendReport(MatchReport report) {
        commit("REPORT", mapper.valueToTree(report));
        state(report.matchId()).reports.put(report.reportSeq(), report);
    }

    public synchronized void revokeReport(String matchId, long reportSeq) {
        ObjectNode n = mapper.createObjectNode();
        n.put("matchId", matchId).put("reportSeq", reportSeq);
        commit("REVOKE", n);
        MatchState s = state(matchId);
        MatchReport r = s.reports.get(reportSeq);
        if (r != null) {
            s.reports.put(reportSeq, new MatchReport(r.matchId(), r.reportSeq(), r.scores(),
                    r.receivedAtMillis(), true));
        }
    }

    public synchronized void commitSettlement(Settlement settlement) {
        commit("SETTLEMENT", mapper.valueToTree(settlement));
        state(settlement.matchId()).settlement = settlement;
    }

    public synchronized void commitGrants(String matchId, List<GrantRecord> grants) {
        ObjectNode n = mapper.createObjectNode();
        n.put("matchId", matchId);
        n.set("grants", mapper.valueToTree(grants));
        commit("GRANTS", n);
        MatchState s = state(matchId);
        for (GrantRecord g : grants) s.grants.putIfAbsent(g.playerId(), g);
    }

    public synchronized void commitClaim(ClaimRecord claim) {
        commit("CLAIM", mapper.valueToTree(claim));
        state(claim.matchId()).claims.putIfAbsent(claim.playerId(), claim);
    }

    // ---------- 只读查询 ----------

    public synchronized List<MatchReport> reportsOf(String matchId) {
        return List.copyOf(state(matchId).reports.values());
    }

    public synchronized Optional<Settlement> settlementOf(String matchId) {
        return Optional.ofNullable(state(matchId).settlement);
    }

    public synchronized List<GrantRecord> grantsOf(String matchId) {
        return List.copyOf(state(matchId).grants.values());
    }

    public synchronized Optional<GrantRecord> grantOf(String matchId, String playerId) {
        return Optional.ofNullable(state(matchId).grants.get(playerId));
    }

    public synchronized Optional<ClaimRecord> claimOf(String matchId, String playerId) {
        return Optional.ofNullable(state(matchId).claims.get(playerId));
    }

    public synchronized List<String> matchIds() {
        return matches.keySet().stream().sorted().toList();
    }

    // ---------- 内部 ----------

    private MatchState state(String matchId) {
        return matches.computeIfAbsent(matchId, k -> new MatchState());
    }

    private void commit(String type, JsonNode data) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", type);
        n.set("data", data);
        try {
            writer.write(mapper.writeValueAsString(n));
            writer.newLine();
            writer.flush(); // 每条已提交事件立即刷盘
        } catch (IOException e) {
            throw new UncheckedIOException("journal 写入失败", e);
        }
    }

    private void apply(JsonNode event) {
        String type = event.get("type").asText();
        JsonNode d = event.get("data");
        switch (type) {
            case "REPORT" -> {
                MatchReport r = mapper.convertValue(d, MatchReport.class);
                state(r.matchId()).reports.put(r.reportSeq(), r);
            }
            case "REVOKE" -> {
                String matchId = d.get("matchId").asText();
                long seq = d.get("reportSeq").asLong();
                MatchState s = state(matchId);
                MatchReport r = s.reports.get(seq);
                if (r != null) {
                    s.reports.put(seq, new MatchReport(r.matchId(), r.reportSeq(), r.scores(),
                            r.receivedAtMillis(), true));
                }
            }
            case "SETTLEMENT" -> {
                Settlement st = mapper.convertValue(d, Settlement.class);
                state(st.matchId()).settlement = st;
            }
            case "GRANTS" -> {
                String matchId = d.get("matchId").asText();
                MatchState s = state(matchId);
                for (JsonNode g : d.get("grants")) {
                    GrantRecord gr = mapper.convertValue(g, GrantRecord.class);
                    s.grants.putIfAbsent(gr.playerId(), gr);
                }
            }
            case "CLAIM" -> {
                ClaimRecord c = mapper.convertValue(d, ClaimRecord.class);
                state(c.matchId()).claims.putIfAbsent(c.playerId(), c);
            }
            default -> throw new IllegalStateException("未知事件类型: " + type);
        }
    }
}
