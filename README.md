# 科技成果转化里程碑账本

Java 21 + PostgreSQL 16 纯后端服务：把项目方、权利方、验证机构提交的证据事件按项目版本写入
不可变时间线；两个审批方确认后，**证据集合、里程碑投影与放款指令在同一事务中提交**；
法务可凭放款记录回溯其依据的确切证据集，证明没有混用不同版本的材料。

## 核心不变式

| 不变式 | 实现 |
| --- | --- |
| 证据不可变、按版本隔离 | `evidence_event` 只增不改（触发器拒绝 UPDATE/DELETE），事件强制落在里程碑所属版本 |
| 同一 `Idempotency-Key` 重试返回原决定 | `(key, party)` 咨询锁串行 + 结果落库，重放带 `Idempotent-Replay: true` 头；同键不同体 → `409 IDEMPOTENCY_CONFLICT` |
| 并发审批只产生一笔放款 | 里程碑行 `SELECT ... FOR UPDATE` 串行化 + `payment_instruction.milestone_id` 唯一约束兜底；审批/投影/放款/审计/幂等记录同事务提交 |
| 过期版本只能归档 | 版本生命周期 `CURRENT → EXPIRED → ARCHIVED`；非 CURRENT 版本拒绝写入证据与审批（`409 VERSION_NOT_CURRENT`）；每项目至多一个 CURRENT（部分唯一索引） |
| 签名主体与项目权限校验 | Ed25519 验签；证书主体必须等于调用方，且在事件发生时有效；调用方须持项目级 `SUBMIT_EVIDENCE` / `APPROVE_MILESTONE` 权限 |
| 商业字段按调用者裁剪 | 无 `VIEW_COMMERCIAL` 权限（含匿名）看不到金额与证据 payload 的 `commercial` 子树 |
| 重启后审计游标继续递增 | `audit_log.seq BIGSERIAL` 随数据卷持久化；应用重启后迁移幂等重放、序列不回退 |
| 旧证书历史记录仍可验证 | 证书只吊销不删除；验签按**事件发生时**的有效性判定，与当前是否过期/吊销无关 |

## 签名方案（Ed25519）

签名原文为换行拼接的规范化串（防篡改、防跨版本混用）：

```
证据事件: ledger/evidence/v1\n{projectVersionId}\n{milestoneId}\n{eventType}\n{occurredAt原文}\n{payloadHash}
审批:     ledger/approval/v1\n{milestoneId}\n{partyId}\n{approvedAt原文}
```

`payloadHash = sha256hex(规范化JSON(payload))`；规范化 = 对象键递归字典序、无空白。
`occurredAt` 以客户端原文落库（`occurred_at_text`），历史验签按原文重算，不受时区归一化影响。

## API 一览

调用者身份经 `X-Party-Id` 头传递（生产环境应置于 mTLS/网关之后）；写操作需 `Idempotency-Key` 头。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/parties` | 注册参与方（PROJECT_OWNER / RIGHTS_HOLDER / VERIFIER） |
| POST | `/api/parties/{id}/certificates` | 注册签名证书（支持轮换，多证书并存） |
| POST | `/api/certificates/{id}/revoke` | 吊销证书（历史事件仍可验证） |
| POST | `/api/projects` | 建项目（自动开启 v1 CURRENT） |
| POST | `/api/projects/{id}/permissions` | 授予项目权限 |
| POST | `/api/projects/{id}/versions` | 开启新版本（当前版本原子置为 EXPIRED） |
| POST | `/api/projects/{id}/versions/{vid}/archive` | 归档过期版本 |
| POST | `/api/projects/{id}/milestones` | 在 CURRENT 版本上建里程碑（可自定义 requiredEvidence / requiredApproverRoles） |
| POST | `/api/evidence-events` | 提交签名证据（幂等） |
| POST | `/api/milestones/{id}/approvals` | 审批（幂等）；末位审批同事务完成放款 |
| GET | `/api/milestones/{id}` | 里程碑投影（按调用者裁剪） |
| GET | `/api/milestones/{id}/events` | 证据时间线 |
| GET | `/api/milestones/{id}/snapshot?at=<ISO>` | 指定时点快照（由不可变事实推导当时状态） |
| GET | `/api/milestones/{id}/pending-evidence` | 待补证据清单 |
| GET | `/api/projects/{id}/versions/diff?from=&to=` | 版本差异（里程碑增删改 + 证据计数） |
| GET | `/api/projects/{id}/audit?afterSeq=&limit=` | 审计日志游标分页 |
| GET | `/api/evidence-events/{id}/verify` | 历史验签（含证书当前状态与发生时有效性） |
| GET | `/api/payments/{id}` | 放款指令 |
| GET | `/api/payments/{id}/evidence-set` | 放款依据的证据集合（`singleVersion` + 哈希重算一致性） |
| GET | `/api/meta/audit-cursor` | 当前审计游标 |
| GET | `/health` | 健康检查（真实 SELECT 1） |

## 审批事务（单事务提交）

`ApprovalService.approve` 在一个事务内依次：

1. `pg_advisory_xact_lock(hash(key,party))` —— 同键并发串行，命中幂等记录直接返回原决定；
2. `SELECT ... FROM milestone ... FOR UPDATE` —— 不同审批方在里程碑行上串行；
3. 校验版本 CURRENT、项目权限、审批角色、证书主体与签名；
4. 写入审批；若审批角色集齐：校验证据齐备（权属确认/样机证据/多方签收）→ 计算证据集哈希 →
   写 `payment_instruction` + `payment_evidence` → 里程碑投影置 `PAID`；
5. 写审计与幂等记录，整体提交。

故障注入测试（`FaultInjectionIT`）证明：提交前崩溃整体回滚、提交后崩溃靠幂等重放返回原决定，
两条路径最终都只有一笔放款。

## 运行

```bash
mvn test                                   # 全部测试（内嵌真实 PostgreSQL 16，无需 Docker）
docker compose up -d --build --wait        # 启动应用 + 数据库（应用启动时自动迁移）
scripts/restart-recovery-check.sh          # 重启恢复验证：应用/数据库重启后审计游标不回退
```

集成测试覆盖：审批并发（`ApprovalConcurrencyIT`）、故障注入（`FaultInjectionIT`）、
重启恢复与旧证书验签（`RestartRecoveryIT`）、版本生命周期与差异（`VersionLifecycleIT`）、
时点快照与审计分页（`SnapshotQueryIT`）、证据校验与幂等（`EvidenceFlowIT`）。

数据库口令仅用于本地 Compose，不写入其他环境。
