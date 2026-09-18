-- 里程碑账本核心 schema：不可变证据时间线 + 里程碑投影 + 幂等决定 + 放款指令
-- 所有时间均为 timestamptz；金额 NUMERIC(19,2)；JSON 负载使用 jsonb。

CREATE TABLE projects (
    id                  UUID PRIMARY KEY,
    name                TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'COMPLETED')),
    -- 商业字段：仅 PROJECT_PARTY / RIGHTS_HOLDER / AUDITOR / ADMIN 可见
    contract_amount     NUMERIC(19, 2),
    beneficiary_account TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project_versions (
    id           UUID PRIMARY KEY,
    project_id   UUID NOT NULL REFERENCES projects (id),
    version_no   INT  NOT NULL CHECK (version_no >= 1),
    status       TEXT NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE', 'EXPIRED', 'ARCHIVED')),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expired_at   TIMESTAMPTZ,
    archived_at  TIMESTAMPTZ,
    UNIQUE (project_id, version_no)
);
-- 每个项目同一时刻至多一个 ACTIVE 版本
CREATE UNIQUE INDEX one_active_version_per_project
    ON project_versions (project_id) WHERE status = 'ACTIVE';

CREATE TABLE milestones (
    id                 UUID PRIMARY KEY,
    project_id         UUID NOT NULL REFERENCES projects (id),
    version_id         UUID NOT NULL REFERENCES project_versions (id),
    code               TEXT NOT NULL,
    title              TEXT NOT NULL,
    -- 要求的证据类型，如 ["IP_ASSIGNMENT","PROTOTYPE_DEMO","ACCEPTANCE_REPORT"]
    required_evidence  JSONB NOT NULL DEFAULT '[]',
    -- 要求的审批角色，如 ["RIGHTS_HOLDER","VERIFIER"]
    required_approvers JSONB NOT NULL DEFAULT '[]',
    amount             NUMERIC(19, 2),
    status             TEXT NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'IN_REVIEW', 'DISBURSED')),
    UNIQUE (version_id, code)
);

-- 签名主体证书：轮换后旧证书保留，历史事件据此仍可验签
CREATE TABLE certificates (
    id          UUID PRIMARY KEY,
    subject     TEXT   NOT NULL,
    public_key  BYTEA  NOT NULL,           -- Ed25519 原始公钥（32 字节）
    valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to    TIMESTAMPTZ,               -- NULL 表示当前有效
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX certificates_subject_idx ON certificates (subject);
-- 同一主体至多一张“当前有效”证书
CREATE UNIQUE INDEX one_current_cert_per_subject
    ON certificates (subject) WHERE valid_to IS NULL;

CREATE TABLE project_permissions (
    project_id UUID NOT NULL REFERENCES projects (id),
    subject    TEXT NOT NULL,
    role       TEXT NOT NULL
               CHECK (role IN ('PROJECT_PARTY', 'RIGHTS_HOLDER', 'VERIFIER', 'AUDITOR')),
    PRIMARY KEY (project_id, subject)
);

-- 不可变证据时间线；seq 为审计游标（数据库序列，重启后继续递增）
CREATE TABLE evidence_events (
    seq             BIGSERIAL PRIMARY KEY,
    id              UUID NOT NULL UNIQUE,
    project_id      UUID NOT NULL REFERENCES projects (id),
    version_id      UUID NOT NULL REFERENCES project_versions (id),
    milestone_id    UUID REFERENCES milestones (id),
    event_type      TEXT NOT NULL CHECK (event_type IN (
                        'EVIDENCE_SUBMITTED',
                        'APPROVAL_GRANTED',
                        'MILESTONE_DISBURSED',
                        'VERSION_ACTIVATED',
                        'VERSION_EXPIRED',
                        'VERSION_ARCHIVED')),
    subject         TEXT NOT NULL,
    cert_id         UUID NOT NULL REFERENCES certificates (id),
    payload         JSONB NOT NULL,
    payload_hash    TEXT NOT NULL,         -- 规范化 JSON 的 SHA-256（hex）
    signature       BYTEA NOT NULL,        -- 对 signed_document 的 Ed25519 签名
    signed_document TEXT NOT NULL,         -- 签名原文，供事后独立验签
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX evidence_events_project_idx  ON evidence_events (project_id, seq);
CREATE INDEX evidence_events_milestone_idx ON evidence_events (milestone_id, seq)
    WHERE milestone_id IS NOT NULL;

-- 追加即永久：禁止 UPDATE / DELETE
CREATE OR REPLACE FUNCTION forbid_evidence_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'evidence_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER evidence_events_immutable
    BEFORE UPDATE OR DELETE ON evidence_events
    FOR EACH ROW EXECUTE FUNCTION forbid_evidence_mutation();

-- 幂等决定：同一 (project, action, key) 只处理一次，重试按字节回放原决定
CREATE TABLE idempotency_keys (
    project_id UUID NOT NULL REFERENCES projects (id),
    action     TEXT NOT NULL,              -- 例如 'event' / 'approval'
    idem_key   TEXT NOT NULL,
    subject    TEXT NOT NULL,
    http_status INT  NOT NULL,
    response   TEXT NOT NULL,              -- 原决定响应体原文（字节级回放）
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, action, idem_key)
);

CREATE TABLE approvals (
    milestone_id UUID NOT NULL REFERENCES milestones (id),
    role         TEXT NOT NULL,
    subject      TEXT NOT NULL,
    event_seq    BIGINT NOT NULL REFERENCES evidence_events (seq),
    decided_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (milestone_id, role)       -- 每个角色对每个里程碑只批准一次
);

-- 放款指令：每个里程碑至多一笔（唯一约束是并发下的最终兜底）
CREATE TABLE disbursements (
    id           UUID PRIMARY KEY,
    milestone_id UUID NOT NULL UNIQUE REFERENCES milestones (id),
    project_id   UUID NOT NULL REFERENCES projects (id),
    amount       NUMERIC(19, 2) NOT NULL,
    account      TEXT NOT NULL,
    instruction  JSONB NOT NULL,
    event_seq    BIGINT NOT NULL REFERENCES evidence_events (seq),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 里程碑投影历史：每次状态变化追加一行，支持指定时点快照
CREATE TABLE milestone_projections (
    milestone_id    UUID NOT NULL REFERENCES milestones (id),
    seq             BIGINT NOT NULL REFERENCES evidence_events (seq),
    project_id      UUID NOT NULL,
    version_id      UUID NOT NULL,
    status          TEXT NOT NULL,
    evidence_set    JSONB NOT NULL,        -- 截至该事件已提交的证据类型集合
    approvals       JSONB NOT NULL,        -- 截至该事件的审批集合
    disbursement_id UUID,
    created_at      TIMESTAMPTZ NOT NULL,  -- 与产生它的事件时间一致
    PRIMARY KEY (milestone_id, seq)
);
