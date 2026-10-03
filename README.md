# 对局奖励结算与发放服务

进程内的对局奖励结算与发放能力：对局结果由本地模拟的预言机上报，奖励规则可配置，
整条链路只在本进程内配合本地文件持久化（H2）完成，不依赖任何外部服务与真实凭据，
结论可在本地复现与验证。

## 一、核心链路

```
预言机上报 ──> 结算 ──> 发放 ──> 领取
 (report)    (settle)  (distribute) (claim)
```

- **上报**：预言机上报对局结果（可乱序、重复、迟到），服务按 `(matchId, seq)` 收敛到唯一结论。
- **结算**：对当前结论做确定性结算，生成各参与者的奖励账目。
- **发放**：把结算账目正式发布为可领取状态，发放记录唯一且不可重复。
- **领取**：参与者领取属于本人的奖励，幂等，越权领取被拒绝。

## 二、对局状态机

```
REPORTED ──(settle)──> SETTLED ──(distribute)──> DISTRIBUTED
                          │
                          └──(窗口关闭)──> REVISION_CLOSED ──(distribute)──> DISTRIBUTED
```

| 状态 | 含义 |
|------|------|
| `REPORTED` | 已上报，尚未结算（未完成） |
| `SETTLED` | 已结算，修订窗口开放中 |
| `REVISION_CLOSED` | 修订窗口已关闭，结算最终化（仍可发放） |
| `DISTRIBUTED` | 已发放，奖励对外发布，不可撤销 |

## 三、结算语义

### 3.1 排名规则

参与者按**得分降序**排列；得分相同的参与者为并列名次。名次采用**标准竞赛排名**
（competition ranking）：并列者共享首名次，后续名次顺延。

示例（4 人，得分 100/90/90/80）：

| 参与者 | 得分 | 名次 |
|--------|------|------|
| a | 100 | 1 |
| b | 90 | 2（并列） |
| c | 90 | 2（并列） |
| d | 80 | 4 |

### 3.2 并列奖金处理

并列组的**奖金池**为该组所占各名次奖励之和，组内**均分**；除不尽时按 `playerId` 升序
依次多发 1（保证金额为整数且结果确定）。未配置名次的奖励取 `default-amount`。

示例（名次 1/2/3 奖励 100/60/30，b、c 并列第 2）：

- 并列组占名次 2、3，奖金池 = 60 + 30 = 90，均分 45/45。
- a 第 1 名得 100，d 第 4 名得 0（未配置）。

### 3.3 确定性

结算为纯函数：相同输入（参与者列表 + 奖励规则）重复运行，逐条结果完全一致。
组内分配顺序由 `playerId` 字典序决定，与上报顺序、调用时间无关。

## 四、上报收敛

- 每份上报携带 `seq`（上报序号，正整数）。服务维护每局的 `currentSeq`（已收敛的最大序号）。
- **重复上报**（seq 相同）：幂等返回 `DUPLICATE`，不改变任何结论。
- **乱序上报**（seq 更大）：接受并更新当前结论。
- **迟到上报**（seq 更小）：拒绝（`LATE`），不改写结论。
- 所有上报（含被拒绝的）均持久化留痕，可通过 `GET /api/matches/{id}/reports` 追溯。

## 五、修订窗口

- 结算后进入修订窗口，窗口时长由 `reward.rule.window-ms` 配置。
- 窗口截止时刻 `deadline = 结算时刻 + window-ms`，**闭区间**（`now <= deadline` 可修订）。
- 窗口内可重新上报并重新结算（修订）：旧结算单及其账目标记为 `SUPERSEDED`，新结算单生效。
- 窗口关闭后：拒绝修订（`REVISION_CLOSED`），结算最终化；但仍可发放。
- **已发放**的对局拒绝任何修订（`ALREADY_DISTRIBUTED`），已发布奖励不可回退。
- 重启时自动把窗口已关闭的 `SETTLED` 对局推进为 `REVISION_CLOSED`。

## 六、结算 / 发放 / 领取分离与幂等

| 操作 | 幂等性 | 唯一约束 |
|------|--------|----------|
| 结算 | 同一 `(matchId, seq)` 只结算一次，重复结算返回原结算单 | `(matchId, seq)` |
| 发放 | 每局只发放一次，重复发放返回首次结果 | `match_id` |
| 领取 | 同一参与者重复领取返回首次结果 | `(distribution_id, player_id)` |

- 领取时校验参与者身份：无该对局奖励记录者返回 `403 CLAIM_NOT_PARTICIPANT`（越权）。
- 未发放对局领取返回 `409 CLAIM_NOT_DISTRIBUTED`。
- 领取事务**不锁定对局行**，慢领取不会阻塞新对局的结算推进。

## 七、并发与事务

- 结算与发放通过对局行悲观锁串行化，防止同一对局被结算/发放两次。
- 领取通过唯一约束 + `REQUIRES_NEW` 独立事务处理并发插入：冲突只回滚嵌套事务，
  外层事务不受影响，随后重查返回首次领取结果。
- 所有读写均在数据库事务内进行，任一时刻读取到的对局结论与账目为单个已提交版本，
  不会出现字段级混合。

## 八、重启恢复

- H2 文件持久化保证：**已提交**事务完整还原，**未完成**事务自动回滚（不残留半份记录）。
- 启动时 `RecoveryService` 输出各状态对局计数：`REPORTED` 为未完成（结算未提交），
  其余为已提交，便于恢复后核对。
- 恢复后重复领取幂等、重复上报不重复发放、已发放对局不可重新结算。

## 九、API 列表

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/oracle/reports` | 预言机上报对局结果 |
| POST | `/api/matches/{id}/settle` | 结算（幂等，支持修订） |
| POST | `/api/matches/{id}/distribute` | 发放（幂等） |
| POST | `/api/matches/{id}/claim` | 领取（幂等，限本人） |
| GET | `/api/matches/{id}` | 对局详情（含状态、当前结论） |
| GET | `/api/matches/{id}/reports` | 上报历史 |
| GET | `/api/matches/{id}/settlements` | 结算历史（含账目） |
| GET | `/api/matches/{id}/distribution` | 发放记录 |
| GET | `/api/matches/{id}/claims` | 领取记录 |
| GET | `/api/players/{playerId}/claims` | 参与者跨对局领取记录 |

### 上报请求示例

```json
{
  "matchId": "match-001",
  "seq": 1,
  "players": [
    { "playerId": "a", "score": 100 },
    { "playerId": "b", "score": 90 }
  ]
}
```

## 十、配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `reward.rule.window-ms` | `300000` | 修订窗口时长（毫秒） |
| `reward.rule.default-amount` | `0` | 未配置名次的默认奖励 |
| `reward.rule.rank-rewards.1` | `100` | 第 1 名奖励 |
| `reward.rule.rank-rewards.2` | `60` | 第 2 名奖励 |
| `reward.rule.rank-rewards.3` | `30` | 第 3 名奖励 |
| `reward.db.path` | `./data` | H2 文件持久化目录 |
| `reward.test.slow-claim-ms` | `0` | 测试用：慢领取延迟（毫秒） |

## 十一、运行测试

```bash
mvn test
```

测试覆盖：

- `RewardRuleTest`：排名、并列奖金拆分、确定性（纯函数单测）。
- `RewardFlowIntegrationTest`：乱序/重复/迟到上报、并列结算、结算前修订、
  发放与领取幂等、越权拒绝、全链路追溯。
- `RewardRevisionWindowIntegrationTest`：修订窗口边界行为。
- `RewardConcurrencyIntegrationTest`：并发结算/发放/领取、账目自洽。
- `RewardSlowClaimIntegrationTest`：慢领取不阻塞跨对局结算。
- `RewardRecoveryIntegrationTest`：重启恢复、未完成事务回滚。
