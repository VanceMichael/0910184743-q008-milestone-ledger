-- V1__init.sql — 科技成果转化里程碑账本初始模式
--
-- 设计要点：
--   * evidence_event / milestone_approval / payment_instruction / payment_evidence /
--     audit_log / idempotency_record 为只增不改的账本表，由触发器拒绝 UPDATE/DELETE。
--   * milestone 是“投影”表（status 会推进），project_version 承载版本生命周期
--     CURRENT -> EXPIRED -> ARCHIVED；每个项目同一时刻只允许一个 CURRENT 版本。
--   * payment_instruction.milestone_id 唯一：一个里程碑最多产生一笔放款指令。
--   * audit_log.seq 与 evidence_event.seq 使用 BIGSERIAL，跨重启单调递增（审计游标）。

CREATE TABLE party (
  id         UUID PRIMARY KEY,
  name       TEXT NOT NULL,
  role       TEXT NOT NULL CHECK (role IN ('PROJECT_OWNER','RIGHTS_HOLDER','VERIFIER')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 签名证书支持轮换：旧证书不删除（可吊销），历史事件仍按事件发生时的有效性验证。
CREATE TABLE signing_certificate (
  id             UUID PRIMARY KEY,
  party_id       UUID NOT NULL REFERENCES party(id),
  subject_cn     TEXT NOT NULL,
  public_key_pem TEXT NOT NULL,
  not_before     TIMESTAMPTZ NOT NULL,
  not_after      TIMESTAMPTZ NOT NULL,
  revoked_at     TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX signing_certificate_party ON signing_certificate (party_id);

CREATE TABLE project (
  id         UUID PRIMARY KEY,
  name       TEXT NOT NULL,
  status     TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project_permission (
  project_id UUID NOT NULL REFERENCES project(id),
  party_id   UUID NOT NULL REFERENCES party(id),
  permission TEXT NOT NULL CHECK (permission IN ('SUBMIT_EVIDENCE','APPROVE_MILESTONE','VIEW_COMMERCIAL','MANAGE_PROJECT')),
  PRIMARY KEY (project_id, party_id, permission)
);

CREATE TABLE project_version (
  id         UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES project(id),
  version_no INT NOT NULL,
  status     TEXT NOT NULL CHECK (status IN ('CURRENT','EXPIRED','ARCHIVED')),
  opened_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at  TIMESTAMPTZ,
  UNIQUE (project_id, version_no)
);
-- 每个项目至多一个 CURRENT 版本
CREATE UNIQUE INDEX project_version_single_current ON project_version (project_id) WHERE status = 'CURRENT';

CREATE TABLE milestone (
  id                      UUID PRIMARY KEY,
  project_version_id      UUID NOT NULL REFERENCES project_version(id),
  code                    TEXT NOT NULL,
  title                   TEXT NOT NULL,
  amount                  NUMERIC(19,2),
  currency                TEXT NOT NULL DEFAULT 'CNY',
  required_evidence       JSONB NOT NULL,           -- [{"type":"OWNERSHIP_CONFIRMATION","role":"RIGHTS_HOLDER"}, ...]
  required_approver_roles JSONB NOT NULL,           -- ["RIGHTS_HOLDER","VERIFIER"]
  status                  TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_REVIEW','PAID')),
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_version_id, code)
);

-- 不可变证据时间线：按项目版本写入；occurred_at_text 保留客户端原文用于验签。
CREATE TABLE evidence_event (
  seq               BIGSERIAL PRIMARY KEY,
  id                UUID NOT NULL UNIQUE,
  project_version_id UUID NOT NULL REFERENCES project_version(id),
  milestone_id      UUID NOT NULL REFERENCES milestone(id),
  party_id          UUID NOT NULL REFERENCES party(id),
  certificate_id    UUID NOT NULL REFERENCES signing_certificate(id),
  event_type        TEXT NOT NULL,
  payload           JSONB NOT NULL,
  payload_hash      TEXT NOT NULL,
  signature         TEXT NOT NULL,
  occurred_at       TIMESTAMPTZ NOT NULL,
  occurred_at_text  TEXT NOT NULL,
  recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX evidence_event_milestone_seq ON evidence_event (milestone_id, seq);
CREATE INDEX evidence_event_version ON evidence_event (project_version_id);

CREATE TABLE milestone_approval (
  milestone_id     UUID NOT NULL REFERENCES milestone(id),
  party_id         UUID NOT NULL REFERENCES party(id),
  certificate_id   UUID NOT NULL REFERENCES signing_certificate(id),
  signature        TEXT NOT NULL,
  approved_at      TIMESTAMPTZ NOT NULL,
  approved_at_text TEXT NOT NULL,
  recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (milestone_id, party_id)
);

CREATE TABLE payment_instruction (
  id                 UUID PRIMARY KEY,
  milestone_id       UUID NOT NULL UNIQUE REFERENCES milestone(id),
  project_version_id UUID NOT NULL REFERENCES project_version(id),
  amount             NUMERIC(19,2) NOT NULL,
  currency           TEXT NOT NULL,
  evidence_set_hash  TEXT NOT NULL,
  status             TEXT NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 放款所依据的证据集合（与放款指令同事务写入），用于证明未混用其他版本的材料。
CREATE TABLE payment_evidence (
  payment_id   UUID NOT NULL REFERENCES payment_instruction(id),
  evidence_seq BIGINT NOT NULL REFERENCES evidence_event(seq),
  PRIMARY KEY (payment_id, evidence_seq)
);

CREATE TABLE idempotency_record (
  idempotency_key TEXT NOT NULL,
  party_id        UUID NOT NULL,
  request_hash    TEXT NOT NULL,
  response_status INT NOT NULL,
  response_body   JSONB NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (idempotency_key, party_id)
);

CREATE TABLE audit_log (
  seq            BIGSERIAL PRIMARY KEY,
  project_id     UUID REFERENCES project(id),
  action         TEXT NOT NULL,
  actor_party_id UUID,
  detail         JSONB NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_project_seq ON audit_log (project_id, seq);

-- 账本表不可变：只允许插入
CREATE OR REPLACE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'immutable ledger table: %', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER evidence_event_immutable      BEFORE UPDATE OR DELETE ON evidence_event      FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER milestone_approval_immutable  BEFORE UPDATE OR DELETE ON milestone_approval  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER payment_instruction_immutable BEFORE UPDATE OR DELETE ON payment_instruction FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER payment_evidence_immutable    BEFORE UPDATE OR DELETE ON payment_evidence    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER audit_log_immutable           BEFORE UPDATE OR DELETE ON audit_log           FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER idempotency_record_immutable  BEFORE UPDATE OR DELETE ON idempotency_record  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
