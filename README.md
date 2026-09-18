# 科技成果转化里程碑账本

纯后端里程碑账本：项目方、权利方、验证机构提交的证据事件按**项目版本**写入不可变时间线；
权属确认、样机证据与多方签收齐备后，**证据集合、里程碑投影与放款指令在同一事务提交**。
Java 21 + PostgreSQL 16，无框架（JDK 内置 HTTP 服务 + 显式 JDBC 事务），Compose 负责迁移、健康检查与重启恢复。

## 核心保证

| 关注点 | 机制 |
| --- | --- |
| 不可变时间线 | `evidence_events` 仅追加（触发器禁 UPDATE/DELETE），`seq BIGSERIAL` 为审计游标，重启后继续递增 |
| 幂等 | 写端点要求 `Idempotency-Key`；决定（含 409 拒绝）与业务写入同事务落库，同键重试原样回放并带 `X-Idempotent-Replay: true` |
| 并发审批 | 里程碑行锁（`SELECT ... FOR UPDATE`）串行化两个审批方；证据/投影/放款单事务提交；`disbursements.milestone_id` 唯一约束兜底，全库至多一笔放款 |
| 版本生命周期 | `ACTIVE → EXPIRED → ARCHIVED`；过期版本只接受归档，证据/审批返回 409 领域决定（同样幂等回放） |
| 签名与权限 | 每个请求 Ed25519 签名；证书轮换后旧证书不能签新请求，但历史事件凭钉住的 `cert_id` 永远可验 |
| 商业字段裁剪 | `VERIFIER` 看不到金额/账户（视图与时间线 payload 均裁剪），权利方/项目方/审计可见 |
| 查询 | 指定时点快照（投影历史）、待补证据、版本差异、审计游标续读 |

## 快速开始

```bash
# 本地：单元测试 + 集成测试（集成测试拉起真实 PostgreSQL 16 二进制，无需 Docker）
mvn verify

# Compose：迁移在应用启动时执行，postgres 健康检查通过后才启动应用
docker compose build
docker compose up -d
docker compose ps            # 两个服务均为 healthy
curl localhost:8080/health   # {"status":"ok","db":"up"}

# 重启恢复：数据卷持久化，应用自动重启，审计游标继续递增
docker compose restart app
```

设置 `IT_JDBC_URL`/`IT_DB_USER`/`IT_DB_PASSWORD` 可让集成测试改打外部库（如 Compose 中的 postgres），
此时重启恢复用例自动跳过。

## 签名方案

除 `/health` 外，所有请求必须携带：

```
X-Ledger-Subject:   主体名（须持有当前有效证书）
X-Ledger-Timestamp: ISO-8601 时间（偏差 ≤300s）
X-Ledger-Signature: Base64( Ed25519( method + "\n" + path + "\n" + subject + "\n" + timestamp + "\n" + sha256hex(body) ) )
Idempotency-Key:    写端点必填（事件提交与审批）
```

签名原文（`signed_document`）与签名一起存入事件行，负载经 jsonb 规范化后算 SHA-256 存入
`payload_hash`，法务事后可用 `GET /api/projects/{pid}/events/{seq}/verify` 独立验签：
返回 `signatureValid`、`payloadHashMatch` 及所用证书（含已轮换的旧证书）。

证书管理：`POST /api/certificates`（ADMIN）。为同一主体登记新证书即轮换，旧证书置
`valid_to` 但永久保留。ADMIN 主体来自环境变量 `LEDGER_ADMIN_SUBJECTS`，其首张证书
通过自举登记（用请求体中的公钥验证本次签名）。

## 角色与权限

| 角色 | 能力 |
| --- | --- |
| ADMIN（全局，配置注入） | 建项目、登记证书、授予项目角色；可读任意项目（审计视角） |
| PROJECT_PARTY | 提交证据、激活/过期/归档版本 |
| RIGHTS_HOLDER | 提交证据、审批（若在 requiredApprovers 中）、归档版本 |
| VERIFIER | 提交证据、审批；**看不到商业字段** |
| AUDITOR | 只读，全字段 |

## API 一览

```
GET  /health
POST /api/projects                                  ADMIN 建项目（含 v1 与里程碑定义）
POST /api/projects/{pid}/permissions                ADMIN 授权 {subject, role}
POST /api/certificates                              ADMIN 登记/轮换证书
POST /api/projects/{pid}/versions                   PROJECT_PARTY 激活新版本（旧版本自动过期）
POST /api/projects/{pid}/versions/{vid}/expire      PROJECT_PARTY 手动过期
POST /api/projects/{pid}/versions/{vid}/archive     PROJECT_PARTY/RIGHTS_HOLDER 归档（仅 EXPIRED）
POST /api/projects/{pid}/events                     三方提交证据（Idempotency-Key）
POST /api/projects/{pid}/milestones/{mid}/approvals 审批方签收（Idempotency-Key）
GET  /api/projects/{pid}/milestones/{mid}           里程碑投影（按角色裁剪）
GET  /api/projects/{pid}/milestones/{mid}/snapshot?at=<iso>   指定时点快照
GET  /api/projects/{pid}/milestones/{mid}/pending-evidence    待补证据
GET  /api/projects/{pid}/versions/diff?from=1&to=2            版本差异
GET  /api/projects/{pid}/timeline?afterSeq=0&limit=100        审计时间线（游标续读）
GET  /api/projects/{pid}/events/{seq}/verify                  历史事件验签
```

审批响应：`202`（已签收，等待他方/证据）或 `201`（条件齐备，已放款，含 `disbursementId`）。
放款触发点是谁最后补齐条件：审批齐但证据缺时，最后一类证据的提交同样会在同一事务内完成放款。

## 故障与恢复语义

- 任何一步失败 → 整个事务回滚：无残留审批、无放款、无幂等记录，同键重试安全。
- 集成测试通过故障注入（`TestHooks`，仅测试 JVM 内启用）验证「放款落库后、提交前崩溃」
  场景：回滚干净，重试后全库仍只有一笔放款。
- 数据库重启后 `seq` 序列继续递增；应用进程重启无状态，连接池自动重连。

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `DB_JDBC_URL` | `jdbc:postgresql://localhost:5432/milestone` | 数据源 |
| `DB_USER` / `DB_PASSWORD` | `milestone` / `milestone` | 仅本地 Compose 使用 |
| `PORT` | `8080` | HTTP 端口 |
| `LEDGER_ADMIN_SUBJECTS` | `admin` | 逗号分隔的全局 ADMIN 主体 |
| `LEDGER_TIMESTAMP_SKEW_SECONDS` | `300` | 签名时间戳容差 |

## 项目结构

```
src/main/java/com/example/milestone/
├── Main.java / App.java / Healthcheck.java   装配与探活
├── api/Api.java                              路由
├── http/                                     极简 HTTP 层（虚拟线程）
├── auth/                                     Ed25519 请求认证、角色
├── crypto/SignatureService.java              Ed25519（JDK 内置）
├── service/                                  事件、幂等、终结放款、版本、查询、裁剪
├── db/                                       数据源、事务边界、Flyway
└── util/                                     规范化 JSON、哈希
src/main/resources/db/migration/V1__init.sql  不可变时间线、投影、唯一约束
src/test/java/com/example/milestone/          单元测试 + it/ 集成测试（真实 PG16）
```
