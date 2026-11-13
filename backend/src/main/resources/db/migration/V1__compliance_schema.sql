-- =====================================================================
-- V1：候选人注销（被遗忘权）合规平台 - 合规域表结构
-- 设计要点：
--   1) 主体归并只认手机号/邮箱/证件号哈希，姓名永不作为合并依据
--   2) 资产登记与血缘注册同表事务绑定；枚举沿血缘实时遍历后冻结不可变快照
--   3) 处置按四类记账：物理清除 / 重算聚合 / 等待保留期 / 流出通知
--   4) 第三方销毁仅凭回执推进；模型影响进模型卡，与物理删除分表陈列
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS compliance;
CREATE SCHEMA IF NOT EXISTS biz;

-- ---------------------------------------------------------------------
-- 主体（候选清单）与标识符匹配项
-- ---------------------------------------------------------------------
CREATE TABLE compliance.subject (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash    varchar(64)  NOT NULL,
    display_name    varchar(128) NOT NULL,
    name_hash       varchar(64),
    status          varchar(20)  NOT NULL DEFAULT 'CANDIDATE'
                    CHECK (status IN ('CANDIDATE','CONFIRMED','REJECTED','MERGED')),
    merged_into     varchar(64),
    confirmed_by    varchar(64),
    confirmed_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE compliance.identifier_match (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash        varchar(64) NOT NULL,
    id_type             varchar(16) NOT NULL
                        CHECK (id_type IN ('PHONE','EMAIL','IDCARD')),
    id_hash             varchar(64) NOT NULL,
    id_value_masked     varchar(128) NOT NULL,
    source_account_ref  varchar(64)  NOT NULL,
    match_status        varchar(16)  NOT NULL DEFAULT 'PENDING'
                        CHECK (match_status IN ('PENDING','ACCEPTED','REJECTED')),
    decided_by          varchar(64),
    decided_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_identifier_hash_type ON compliance.identifier_match (id_hash, id_type, source_account_ref);
CREATE INDEX idx_identifier_subject ON compliance.identifier_match (subject_hash);

-- ---------------------------------------------------------------------
-- 注销工单：主体哈希 + 渠道 幂等
-- ---------------------------------------------------------------------
CREATE TABLE compliance.work_order (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_no               varchar(40) NOT NULL UNIQUE,
    subject_hash            varchar(64) NOT NULL,
    channel                 varchar(24) NOT NULL
                            CHECK (channel IN ('APP','WEB','HOTLINE','REGULATOR_LETTER','EMAIL')),
    idempotency_key         varchar(128) NOT NULL UNIQUE,
    status                  varchar(24) NOT NULL DEFAULT 'CREATED'
                            CHECK (status IN ('CREATED','ENUM_FROZEN','PENDING_EXECUTION','EXECUTING',
                                              'WAITING_RECEIPT','RECONCILIATION','COMPLETED',
                                              'CANCELLED','RETURNED')),
    current_node            varchar(24) NOT NULL DEFAULT 'ENUMERATION'
                            CHECK (current_node IN ('ENUMERATION','PREVIEW','EXECUTION',
                                                    'RECEIPT','RECONCILE','DONE')),
    consent_withdrawn_at    timestamptz,
    legal_deadline          date,
    cancel_before           timestamptz,
    source_ref              varchar(128),
    return_reason           varchar(256),
    snapshot_id             bigint,
    closed_at               timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ticket_subject ON compliance.work_order (subject_hash);

-- ---------------------------------------------------------------------
-- 数据资产登记 + 血缘边（接入登记与血缘注册在同一事务提交）
-- ---------------------------------------------------------------------
CREATE TABLE compliance.data_asset (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_code          varchar(64) NOT NULL UNIQUE,
    asset_name          varchar(128) NOT NULL,
    storage_type        varchar(20) NOT NULL
                        CHECK (storage_type IN ('TABLE','PARTITION','FILE','INDEX','FEATURE',
                                                'SAMPLE','EXPORT','BACKUP','EXTERNAL')),
    target_ref          varchar(256) NOT NULL,
    external_party      varchar(128),
    retention_days      integer,
    root_subject_bound  boolean NOT NULL DEFAULT true,
    disposition_default varchar(24) NOT NULL
                        CHECK (disposition_default IN ('PHYSICAL_DELETE','RECOMPUTE_AGGREGATE',
                                                       'WAIT_RETENTION','EXTERNAL_NOTIFY')),
    registered_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE compliance.lineage_edge (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    from_asset_code     varchar(64) NOT NULL REFERENCES compliance.data_asset(asset_code),
    to_asset_code       varchar(64) NOT NULL REFERENCES compliance.data_asset(asset_code),
    edge_type           varchar(24) NOT NULL DEFAULT 'DERIVES',
    broken              boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (from_asset_code, to_asset_code)
);
CREATE INDEX idx_lineage_from ON compliance.lineage_edge (from_asset_code);
CREATE INDEX idx_lineage_to ON compliance.lineage_edge (to_asset_code);

-- 血缘断链人工确认点
CREATE TABLE compliance.lineage_gap (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_code      varchar(64) REFERENCES compliance.data_asset(asset_code),
    description     varchar(512) NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN','CONFIRMED_IN_SCOPE','CONFIRMED_OUT_SCOPE')),
    ticket_id       bigint,
    confirmed_by    varchar(64),
    confirmed_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 不可变枚举快照（执行前冻结，事后补登记只产生增补工单，不得回写）
-- ---------------------------------------------------------------------
CREATE TABLE compliance.enum_snapshot (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id       bigint NOT NULL,
    subject_hash    varchar(64) NOT NULL,
    frozen_at       timestamptz NOT NULL DEFAULT now(),
    frozen_by       varchar(64) NOT NULL,
    root_asset_count integer NOT NULL,
    item_count      integer NOT NULL,
    manifest_hash   varchar(64) NOT NULL,
    note            varchar(512)
);
CREATE UNIQUE INDEX uq_snapshot_ticket ON compliance.enum_snapshot (ticket_id);

CREATE TABLE compliance.snapshot_item (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    snapshot_id         bigint NOT NULL,
    asset_code          varchar(64) NOT NULL,
    storage_type        varchar(20) NOT NULL,
    target_ref          varchar(256) NOT NULL,
    lineage_path        text NOT NULL,
    item_status         varchar(20) NOT NULL
                        CHECK (item_status IN ('IN_SCOPE','PENDING_HUMAN','OUT_OF_SCOPE')),
    disposition_category varchar(24) NOT NULL
                         CHECK (disposition_category IN ('PHYSICAL_DELETE','RECOMPUTE_AGGREGATE',
                                                         'WAIT_RETENTION','EXTERNAL_NOTIFY')),
    retention_until     date,
    gap_id              bigint,
    row_count           integer NOT NULL DEFAULT 0,
    frozen_detail       jsonb
);
CREATE INDEX idx_snapitem_snapshot ON compliance.snapshot_item (snapshot_id);

-- ---------------------------------------------------------------------
-- 处置记录：工单 + 存储位置 幂等；重算前后值与差额留痕
-- ---------------------------------------------------------------------
CREATE TABLE compliance.disposition_record (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id           bigint NOT NULL,
    snapshot_item_id    bigint NOT NULL,
    asset_code          varchar(64) NOT NULL,
    location_ref        varchar(256) NOT NULL,
    category            varchar(24) NOT NULL,
    status              varchar(24) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','PREVIEWED','SCHEDULED','EXECUTED','FAILED',
                                          'WAITING_RECEIPT','RECEIPT_RECEIVED','SKIPPED')),
    idempotency_key     varchar(160) NOT NULL UNIQUE,
    before_value        varchar(128),
    after_value         varchar(128),
    diff_value          varchar(128),
    recheck_hash        varchar(64),
    attempt_count       integer NOT NULL DEFAULT 0,
    receipt_ref         varchar(128),
    receipt_at          timestamptz,
    detail              jsonb,
    executed_at         timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_disp_ticket ON compliance.disposition_record (ticket_id);

-- 对账问题：物理清除/重算差额/抽样反查对不上，单列交合规复核
CREATE TABLE compliance.reconciliation_issue (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id       bigint NOT NULL,
    disposition_id  bigint NOT NULL,
    issue_type      varchar(32) NOT NULL
                    CHECK (issue_type IN ('PHYSICAL_MISMATCH','RECHECK_HASH_MISMATCH',
                                          'RECOMPUTE_MISMATCH','RECEIPT_OVERDUE')),
    expected_value  varchar(256),
    actual_value    varchar(256),
    status          varchar(16) NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN','RESOLVED')),
    resolved_by     varchar(64),
    resolution_note varchar(512),
    created_at      timestamptz NOT NULL DEFAULT now(),
    resolved_at     timestamptz
);

-- ---------------------------------------------------------------------
-- 模型卡：样本进过模型无法回退参数，重训决策与效果变化单独陈列
-- ---------------------------------------------------------------------
CREATE TABLE compliance.model_card (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id           bigint NOT NULL,
    model_name          varchar(128) NOT NULL,
    affected_sample_count integer NOT NULL,
    trained_params_note varchar(512) NOT NULL,
    retrain_decision    varchar(24) NOT NULL DEFAULT 'PENDING'
                        CHECK (retrain_decision IN ('PENDING','RETRAINED','NO_RETRAIN_JUSTIFIED')),
    performance_before  varchar(256),
    performance_after   varchar(256),
    justification       varchar(1024),
    decided_by          varchar(64),
    decided_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);

-- 证据包索引
CREATE TABLE compliance.evidence_package (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id       bigint NOT NULL,
    package_hash    varchar(64) NOT NULL,
    item_count      integer NOT NULL,
    manifest_json   jsonb NOT NULL,
    file_path       varchar(512) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- 事后补登记资产 → 增补工单（不得追溯改写已冻结快照/已出具说明）
CREATE TABLE compliance.supplement_ticket (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    original_ticket_id  bigint NOT NULL,
    asset_code          varchar(64) NOT NULL,
    reason              varchar(512) NOT NULL,
    status              varchar(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','REGISTERED','EXECUTED')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    executed_at         timestamptz
);

-- 通知外发记录（已流出 → 通知第三方销毁）
CREATE TABLE compliance.notification_outbox (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id       bigint NOT NULL,
    party           varchar(128) NOT NULL,
    asset_code      varchar(64) NOT NULL,
    notice_ref      varchar(128),
    notice_status   varchar(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (notice_status IN ('PENDING','SENT','RECEIPT_RECEIVED','FAILED')),
    receipt_ref     varchar(128),
    sent_at         timestamptz,
    receipt_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- 审计日志
CREATE TABLE compliance.audit_log (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id       bigint,
    actor           varchar(64) NOT NULL,
    action          varchar(64) NOT NULL,
    detail_json     jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_ticket ON compliance.audit_log (ticket_id);
