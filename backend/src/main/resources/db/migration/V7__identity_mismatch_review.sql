-- =====================================================================
-- V7：候选人查询与身份错配复核域
-- 设计要点：
--   1) 手机号相同只生成“待核验关系”，不自动归并；证件指纹冲突直接阻断归并
--   2) 账号归属判定版本化：旧结论永不改写，纠错另建修订（revision），证据带版本号
--   3) 账号关系、异议办理、对外答复三套状态各自独立维护
--   4) 撤销错误关联后重算受影响账号归属，逐项反查历史删除范围/处置/答复引用
--   5) 共享资料拆引用处置；已物理删除的位置记录不可恢复事实；作业进行中遇裁定
--      未清除位置暂停、已处理位置生成事故记录，暂停不等于撤回成功
--   6) 限时查询凭证；归属修订后旧凭证立即失效；答复仅在全部影响项有明确结果后生成新版本
-- =====================================================================

-- ---------------------------------------------------------------------
-- 演示业务侧新增：同手机号账号、证件指纹冲突账号、共享简历引用
-- ---------------------------------------------------------------------
INSERT INTO biz.account (account_id, subject_hash, phone_hash, email_hash, idcard_hash, name) VALUES
-- ACC-1003：与张伟 A 共用手机号（哈希相同）→ 只形成待核验关系；
-- 又与 ACC-1002 共用工作邮箱但证件指纹不同 → 证件冲突阻断归并（邮箱相同也不连通）
('ACC-1003','4e26169ea0fd88f585f992a4d423daed685b00f59bd1d490d0b06294ae4c6906',
 'b1c4769e3ad14f68ea1a96b73bbe5a83d90792f044828c5daf9d908b0738b177',
 '4a61631b42f1a4e8d8a1314e92bb7dc5ed9a8bde490996c2e9d291a91978ab77',
 '2d4300c50325ad08322aba60f07c7067a06d94ac2ef9be4ce1b023cc5e8bcb0f','陈晨'),
-- ACC-4001：历史上被误并入张伟 A（见 account_attribution 旧版本台账），
-- 其证件指纹与 A 完全不同 → 重新扫描时证件冲突必须阻断归并，等待异议纠错
('ACC-4001','9d0ac03c4cc30391128092e41131bccfeddfd6b1f0d6a7a61131ff2ac64e65f8',
 'd694cdadfdf802a16d1fdd46fa055fec33cd5a3ce2be6ed69b2681fce087da0b',
 '62d117feff6a60bea44377630038407de043f04c31fdf2ab07358673fc143486',
 '3c3741be8bfd3e18e6a125e8f3611629651e5e605deacd6db4fa851cff7db27f','王强');

-- 共享简历引用：同一份简历资料被两个候选人引用，纠错时不能整份清除，必须先拆引用
CREATE TABLE IF NOT EXISTS biz.resume_reference (
    reference_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    resume_id      bigint NOT NULL,
    account_id     varchar(32) NOT NULL,
    subject_hash   varchar(64) NOT NULL,
    reference_status varchar(16) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (reference_status IN ('ACTIVE','DETACHED')),
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- 王强自己的简历（自然人主体 9d0ac03c，未被 A 的删除作业触及，修订后据此重算归属）
INSERT INTO biz.resume (account_id, subject_hash, version_no, content_pii) VALUES
('ACC-4001','9d0ac03c4cc30391128092e41131bccfeddfd6b1f0d6a7a61131ff2ac64e65f8',1,
 '王强 简历（历史上被误挂在张伟 A 主体名下）');

-- 同一份王强简历被两个候选人引用：王强本人合法引用 + 张伟 A 的 ACC-1001 错误引用。
-- 修订时不能整份清除：拆开 A 的错误引用（subject_hash 挂在错误主体 A 名下）后，
-- 资料仍被王强合法引用，须保留。
INSERT INTO biz.resume_reference (resume_id, account_id, subject_hash)
SELECT r.resume_id, 'ACC-4001', r.subject_hash FROM biz.resume r
 WHERE r.account_id = 'ACC-4001' AND r.version_no = 1;
INSERT INTO biz.resume_reference (resume_id, account_id, subject_hash)
SELECT r.resume_id, 'ACC-1001',
       'f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c'
  FROM biz.resume r
 WHERE r.account_id = 'ACC-4001' AND r.version_no = 1;

-- 错挂台账：显式记录“真实属于 ACC-4001（王强）却被挂在张伟 A 主体哈希下”的位置，
-- 作为修订影响项反查与逐项处置的确定性依据；物理删除后保留台账事实（不可恢复）。
CREATE TABLE IF NOT EXISTS biz.misfiled_record (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id            varchar(32) NOT NULL,
    natural_subject_hash  varchar(64) NOT NULL,
    wrong_subject_hash    varchar(64) NOT NULL,
    asset_code            varchar(64) NOT NULL,
    location_ref          varchar(256) NOT NULL,
    record_status         varchar(24) NOT NULL DEFAULT 'PRESENT'
                          CHECK (record_status IN
                          ('PRESENT','PHYSICALLY_DELETED','SUPPLEMENT_DELETED','REFERENCE_DETACHED')),
    created_at            timestamptz NOT NULL DEFAULT now()
);

INSERT INTO biz.misfiled_record (account_id, natural_subject_hash, wrong_subject_hash, asset_code, location_ref) VALUES
('ACC-4001','9d0ac03c4cc30391128092e41131bccfeddfd6b1f0d6a7a61131ff2ac64e65f8',
 'f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','AST_PDF','pdf/wangqiang-misfiled.pdf'),
('ACC-4001','9d0ac03c4cc30391128092e41131bccfeddfd6b1f0d6a7a61131ff2ac64e65f8',
 'f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','AST_SEARCH','doc-4001'),
('ACC-4001','9d0ac03c4cc30391128092e41131bccfeddfd6b1f0d6a7a61131ff2ac64e65f8',
 'f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','AST_BACKUP','backup/misfiled/wangqiang-account.dump');
-- 王强资料被错挂在张伟 A 主体哈希下：PDF/索引会随 A 的注销作业被物理删除（不可恢复事实），
-- 备份归档保留期未到仅被 SCHEDULED 调度、尚未清除（修订后进入补充工单/暂停核对场景）
INSERT INTO biz.resume_pdf (subject_hash, object_key, bucket) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','pdf/wangqiang-misfiled.pdf','resume-bucket');
INSERT INTO biz.search_index_doc (subject_hash, index_name, doc_ref) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','resume_search_v3','doc-4001');
INSERT INTO biz.backup_archive (subject_hash, asset_code, archive_path, retention_until) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','AST_BACKUP',
 'backup/misfiled/wangqiang-account.dump', CURRENT_DATE + 45);

-- ---------------------------------------------------------------------
-- 1. 限时查询凭证：申请人查看隐藏敏感信息办理说明、补交线索、追踪进度
--    归属修订后旧凭证立即失效（invalidated_by_revision 置位）
-- ---------------------------------------------------------------------
CREATE TABLE compliance.query_token (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_ref           varchar(80) NOT NULL UNIQUE,
    subject_hash        varchar(64) NOT NULL,
    purpose             varchar(24) NOT NULL DEFAULT 'PORTAL_INSTRUCTION'
                        CHECK (purpose IN ('PORTAL_INSTRUCTION','DISPUTE_TRACK')),
    status              varchar(16) NOT NULL DEFAULT 'ISSUED'
                        CHECK (status IN ('ISSUED','EXPIRED','REVOKED','USED')),
    issued_at           timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,
    used_at             timestamptz,
    invalidated_by_revision bigint,
    created_by          varchar(64) NOT NULL DEFAULT 'applicant'
);
CREATE INDEX idx_query_token_subject ON compliance.query_token (subject_hash);

-- ---------------------------------------------------------------------
-- 2. 账号归属判定（版本化，只追加）：每个账号在每个证据版本上的归属结论
--    旧行永不 UPDATE/DELETE；纠错 = 追加新版本行（revision 表驱动）
-- ---------------------------------------------------------------------
CREATE TABLE compliance.account_attribution (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id          varchar(32) NOT NULL,
    subject_hash        varchar(64) NOT NULL,
    evidence_version    integer NOT NULL DEFAULT 1,
    decision_status     varchar(24) NOT NULL
                        CHECK (decision_status IN
                        ('AUTO_MERGED','PENDING_VERIFICATION','BLOCKED_IDCARD_CONFLICT',
                         'CONFIRMED','REVISED_OUT','REASSIGNED')),
    basis_type          varchar(16) NOT NULL
                        CHECK (basis_type IN ('PHONE','EMAIL','IDCARD','MANUAL','DISPUTE')),
    basis_hash          varchar(64),
    decided_by          varchar(64),
    decided_at          timestamptz NOT NULL DEFAULT now(),
    superseded_by_id    bigint,
    revision_no         integer NOT NULL DEFAULT 0,
    detail_json         jsonb,
    UNIQUE (account_id, evidence_version, revision_no)
);
CREATE INDEX idx_attr_account ON compliance.account_attribution (account_id);
CREATE INDEX idx_attr_subject ON compliance.account_attribution (subject_hash);

-- 账号间候选关系：手机号相同 → PENDING；证件指纹冲突 → BLOCKED
CREATE TABLE compliance.account_relation (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id_a        varchar(32) NOT NULL,
    account_id_b        varchar(32) NOT NULL,
    match_type          varchar(16) NOT NULL
                        CHECK (match_type IN ('PHONE','IDCARD_CONFLICT','EMAIL')),
    shared_hash         varchar(64) NOT NULL,
    relation_status     varchar(20) NOT NULL
                        CHECK (relation_status IN
                        ('PENDING_VERIFICATION','BLOCKED','VERIFIED_SAME','VERIFIED_DIFFERENT',
                         'REVOKED','SUPERSEDED')),
    evidence_version    integer NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    decided_by          varchar(64),
    decided_at          timestamptz,
    note                varchar(512),
    UNIQUE (account_id_a, account_id_b, match_type)
);

-- ---------------------------------------------------------------------
-- 3. 身份异议（办理状态独立维护）；重复异议合并补证，不新开单
-- ---------------------------------------------------------------------
CREATE TABLE compliance.identity_dispute (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dispute_no          varchar(40) NOT NULL UNIQUE,
    subject_hash        varchar(64) NOT NULL,
    applicant_account   varchar(32) NOT NULL,
    disputed_account    varchar(32),
    dispute_kind        varchar(24) NOT NULL
                        CHECK (dispute_kind IN
                        ('LEAKED_RESIDUE','WRONG_ATTRIBUTION','RELATION_REVOKE')),
    status              varchar(24) NOT NULL DEFAULT 'SUBMITTED'
                        CHECK (status IN
                        ('SUBMITTED','MERGED','PENDING_VERIFICATION','AWAITING_MANUAL_PROOF',
                         'VERIFIED','ESCALATED_COMPLIANCE','ADJUDICATED','CLOSED')),
    merged_into_id      bigint,
    contact_status      varchar(28) NOT NULL DEFAULT 'REACHABLE'
                        CHECK (contact_status IN ('REACHABLE','UNREACHABLE_MANUAL_PROOF')),
    current_evidence_version integer NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    closed_at           timestamptz
);
CREATE INDEX idx_dispute_subject ON compliance.identity_dispute (subject_hash);
CREATE INDEX idx_dispute_status ON compliance.identity_dispute (status);

-- 异议补充材料/线索（重复异议在此合并补证）
CREATE TABLE compliance.dispute_evidence (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dispute_id          bigint NOT NULL,
    evidence_version    integer NOT NULL,
    clue_type           varchar(24) NOT NULL
                        CHECK (clue_type IN
                        ('ACCOUNT_CLUE','ID_PROOF','MANUAL_PROOF','CONTACT_FAIL','AGENT_NOTE','RULING')),
    content             varchar(1024) NOT NULL,
    submitted_by        varchar(64) NOT NULL,
    status              varchar(20) NOT NULL DEFAULT 'SUBMITTED'
                        CHECK (status IN ('SUBMITTED','ACCEPTED','INSUFFICIENT','SUPERSEDED')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    reviewed_by         varchar(64),
    reviewed_at         timestamptz
);
CREATE INDEX idx_evidence_dispute ON compliance.dispute_evidence (dispute_id);

-- ---------------------------------------------------------------------
-- 4. 归属修订：纠错另建修订，旧结论与更正关系同时保存
-- ---------------------------------------------------------------------
CREATE TABLE compliance.attribution_revision (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dispute_id          bigint NOT NULL,
    revision_no         integer NOT NULL,
    evidence_version    integer NOT NULL,
    account_id          varchar(32) NOT NULL,
    from_subject_hash   varchar(64) NOT NULL,
    to_subject_hash     varchar(64),
    revision_kind       varchar(24) NOT NULL
                        CHECK (revision_kind IN
                        ('REVOKE_RELATION','REASSIGN','SPLIT_OUT','BLOCK_MERGE')),
    rationale           varchar(1024) NOT NULL,
    ruling_by           varchar(64) NOT NULL,
    ruling_at           timestamptz NOT NULL DEFAULT now(),
    ruling_decision     varchar(16) NOT NULL
                        CHECK (ruling_decision IN ('UPHOLD','CORRECT')),
    UNIQUE (dispute_id, revision_no)
);
CREATE INDEX idx_revision_account ON compliance.attribution_revision (account_id);

-- ---------------------------------------------------------------------
-- 5. 修订影响项：撤销一条关联后逐项反查受影响账号、历史删除范围与答复引用
--    每项必须取得明确处置结果，否则不能生成新的对外说明
-- ---------------------------------------------------------------------
CREATE TABLE compliance.revision_impact_item (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id         bigint NOT NULL,
    account_id          varchar(32) NOT NULL,
    asset_code          varchar(64),
    location_ref        varchar(256),
    impact_kind         varchar(28) NOT NULL
                        CHECK (impact_kind IN
                        ('SUPPLEMENTAL_DELETE','SHARED_REFERENCE_SPLIT',
                         'IRRECOVERABLE_DELETED','HISTORY_SCOPE_REFERENCE','REPLY_REFERENCE')),
    item_status         varchar(32) NOT NULL DEFAULT 'PENDING'
                        CHECK (item_status IN
                        ('PENDING','SUPPLEMENT_TICKETED','SPLIT_PENDING_DISPOSITION',
                         'DISPOSED','IRRECOVERABLE_RECORDED','NO_ACTION','RESOLVED')),
    source_ticket_id    bigint,
    source_disposition_id bigint,
    source_reply_id     bigint,
    supplement_ticket_id bigint,
    handling_note       varchar(1024),
    handled_by          varchar(64),
    handled_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_impact_revision ON compliance.revision_impact_item (revision_id);

-- ---------------------------------------------------------------------
-- 6. 执行作业事故/暂停记录：作业取到旧归属后又收到裁定
--    未清除位置暂停并重新核对版本；已处理位置生成事故记录
-- ---------------------------------------------------------------------
CREATE TABLE compliance.execution_incident (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id           bigint NOT NULL,
    disposition_id      bigint,
    revision_id         bigint NOT NULL,
    account_id          varchar(32) NOT NULL,
    incident_kind       varchar(28) NOT NULL
                        CHECK (incident_kind IN ('PAUSED_VERSION_STALE','PROCESSED_ACCIDENT')),
    expected_version    integer NOT NULL,
    actual_version      integer NOT NULL,
    incident_status     varchar(20) NOT NULL DEFAULT 'OPEN'
                        CHECK (incident_status IN ('OPEN','RESOLVED')),
    detail              varchar(1024),
    created_at          timestamptz NOT NULL DEFAULT now(),
    resolved_at         timestamptz,
    resolved_by         varchar(64)
);
CREATE INDEX idx_incident_ticket ON compliance.execution_incident (ticket_id);

-- 处置记录补充：证据版本与暂停标记（暂停 ≠ 撤回成功）
ALTER TABLE compliance.disposition_record
    ADD COLUMN evidence_version integer NOT NULL DEFAULT 1,
    ADD COLUMN pause_status varchar(28)
        CHECK (pause_status IS NULL OR pause_status IN ('PAUSED_VERSION_STALE')),
    ADD COLUMN paused_at timestamptz;

-- ---------------------------------------------------------------------
-- 7. 对外答复：版本化陈列，原答复按日期可查回；全部影响项有明确结果才生成新版本
-- ---------------------------------------------------------------------
CREATE TABLE compliance.external_reply (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dispute_id          bigint NOT NULL,
    subject_hash        varchar(64) NOT NULL,
    reply_version       integer NOT NULL,
    reply_no            varchar(48) NOT NULL UNIQUE,
    reply_status        varchar(20) NOT NULL DEFAULT 'ISSUED'
                        CHECK (reply_status IN ('DRAFT','ISSUED','SUPERSEDED')),
    title               varchar(200) NOT NULL,
    body_text           text NOT NULL,
    issued_at           timestamptz NOT NULL DEFAULT now(),
    superseded_by_id    bigint,
    UNIQUE (dispute_id, reply_version)
);
CREATE INDEX idx_reply_subject ON compliance.external_reply (subject_hash);

-- ---------------------------------------------------------------------
-- 演示基线：把“误关联”落为一条旧版本归属判定，保留旧结论供后续修订纠错
-- ---------------------------------------------------------------------
INSERT INTO compliance.account_attribution
    (account_id, subject_hash, evidence_version, decision_status, basis_type, basis_hash,
     decided_by, revision_no, detail_json)
VALUES
('ACC-4001','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',
 1,'AUTO_MERGED','IDCARD','04e7358a07d7a12d426f8a3091293b19fd7a278b56163c5e9f7f0c7b41e7d233',
 'system',0, CAST('{"note":"历史自动归并结论（含误关联风险），保留不可改写"}' AS jsonb));
