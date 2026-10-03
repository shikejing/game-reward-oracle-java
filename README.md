# game-reward-oracle-java

进程内的对局奖励结算与发放服务：本地模拟预言机上报对局结果，按可配置规则确定性结算，
发放与领取分离，全部状态以本地事件日志持久化，不依赖任何外部服务与真实凭据。

## 运行与验证

```bash
mvn test          # 运行全部自动化测试（22 个用例）
mvn spring-boot:run
```

## 链路与接口

上报 → （窗口内可修订）→ 结算 → 发放 → 领取，全部接口在 `/api/matches/{matchId}` 下：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/reports` | 预言机上报 `{reportSeq, scores:[{playerId,score}]}` |
| POST | `/reports/{seq}/revoke` | 撤销某次上报（仅窗口内、未结算） |
| GET  | `/conclusion` | 当前唯一结论 |
| GET  | `/reports` | 全部上报历史（含被撤销与迟到留痕） |
| POST | `/settle` | 结算（幂等） |
| POST | `/grant` | 发放（幂等） |
| GET  | `/grants` | 查询发放记录 |
| POST | `/claims` | 领取 `{playerId, requesterId}` |
| GET  | `/claims/{playerId}` | 查询领取记录 |

`OracleSimulator` 提供本地模拟预言机（seed 固定则结果可复现），供联调与演示使用。

## 结算语义

- **结论收敛**：结论 = 未被撤销且 `reportSeq` 最大的上报。同 `(matchId, reportSeq)`
  且内容一致的重复上报幂等返回首次记录；同序号内容冲突以 `DUPLICATE_REPORT_CONFLICT` 拒绝。
- **迟到上报**：结算完成后结论冻结，迟到的上报仅留痕可查，不改写已结算并发布的奖励。
- **名次判定**：分数降序，同分并列同一名次（竞赛排名 1,2,2,4）；展示顺序按
  `playerId` 字典序，保证逐条可复现。
- **并列处理**（`reward.tie-policy`）：
  - `SPLIT_SUM`：并列组占用的所有名次额度合并后均分；余数按 `playerId` 字典序
    前 rem 名各 +1（确定性分配，总额守恒）。
  - `EACH_FULL`：并列者每人拿该名次全额。
- **确定性**：结算是输入（结论分数 + 规则配置）的纯函数，相同输入重复运行逐条一致；
  同一对局只会提交一个结算版本，重复调用返回首次已提交结果。

## 修订窗口

- 修订（更高序号的新上报、撤销旧上报）仅在窗口内且未结算时生效。
- 窗口 = 首次上报收到时刻 + `reward.revision-window-ms`，**闭区间**：
  恰好在边界时刻的修订被接受，超过 1ms 即拒绝（`REVISION_WINDOW_CLOSED`）。
- 结算完成后任何修订被拒绝（`ALREADY_SETTLED`），已发放奖励不会被回退；
  被撤销与被替换的上报保留在 `/reports` 历史中可查。

## 发放与领取

- 发放记录提交后即可查询且幂等：重复发放返回首次记录，不会重复入账。
- 领取幂等：同一 `(matchId, playerId)` 重复领取只返回首次 `ClaimRecord`。
- 越权：`requesterId != playerId` 返回 403 + `FORBIDDEN`；无发放记录返回 404 +
  `NOT_FOUND`，两种原因可区分。全部记录经事件日志可追溯。

## 并发与一致性

- 结算/发放按对局加锁，领取按 `(matchId, playerId)` 加锁：慢领取不阻塞其他对局推进。
- 任一时刻读到的对局结论与账目都是单个已提交版本，不存在字段级混合。

## 持久化与恢复

- `reward.store.dir` 下的 `journal.log` 为追加式事件日志，每条事件一行、落盘后生效。
- 结算与发放各为单条事件整体提交，不会残留半份记录；崩溃导致的末尾半行在恢复时丢弃。
- 重启时重放日志完整还原已提交状态；已结算/未结算对局天然可区分，
  重启后重复发放与领取均幂等，不漏发不重发。

## 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `reward.rank-amounts` | 1→100, 2→50, 3→20 | 名次到奖励额度的映射 |
| `reward.tie-policy` | `SPLIT_SUM` | 并列名次处理策略 |
| `reward.revision-window-ms` | `60000` | 结算前修订窗口（闭区间） |
| `reward.store.dir` | `./data` | 事件日志目录 |

## 测试覆盖

`src/test/java/com/github/shikejing/reward/`：

- `ReportConvergenceTest`：乱序、重复、冲突、迟到上报的收敛
- `TieRankingTest`：并列名次两种策略、余数确定性分配、重复运行逐条一致
- `RevisionTest`：窗口内撤销替换、边界闭区间、超窗拒绝、结算后禁修订
- `ConcurrencyTest`：并发结算/发放/领取的账目自洽、慢领取不阻塞新对局
- `ClaimAuthTest`：越权拒绝（可区分原因）、领取幂等
- `RecoveryTest`：重启完整还原、崩溃半行丢弃、跨重启不重复发放
