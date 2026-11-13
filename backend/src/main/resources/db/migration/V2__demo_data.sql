-- =====================================================================
-- V2：演示业务表（被删除作业真实操作）+ 资产登记/血缘 + 演示数据
-- 三个主体：
--   张伟(A) 两个账号 ACC-1001/ACC-1002，靠证件号哈希归并
--   张伟(C) 同名不同人，标识符哈希完全不同，禁止并入 A
--   李娜(B) 单一账号
-- =====================================================================

-- 业务表 ----------------------------------------------------------------
CREATE TABLE biz.account (
    account_id   varchar(32) PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    phone_hash   varchar(64),
    email_hash   varchar(64),
    idcard_hash  varchar(64),
    name         varchar(64) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE biz.resume (
    resume_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   varchar(32) NOT NULL,
    subject_hash varchar(64) NOT NULL,
    version_no   integer NOT NULL,
    content_pii  text NOT NULL
);

CREATE TABLE biz.resume_pdf (
    pdf_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    object_key   varchar(256) NOT NULL,
    bucket       varchar(64) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE biz.search_index_doc (
    doc_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    index_name   varchar(64) NOT NULL,
    doc_ref      varchar(128) NOT NULL
);

CREATE TABLE biz.recommend_feature (
    feature_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    model_name   varchar(128) NOT NULL,
    vector_ref   varchar(128) NOT NULL
);

CREATE TABLE biz.training_sample (
    sample_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    export_batch varchar(64) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE biz.application (
    application_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash   varchar(64) NOT NULL,
    job_id         varchar(32) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- 投递漏斗汇总（按职位聚合，删除投递后必须重算并留差额）
CREATE TABLE biz.funnel_daily (
    job_id           varchar(32) NOT NULL,
    apply_count      integer NOT NULL,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id)
);

CREATE TABLE biz.data_export (
    export_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    export_path  varchar(256) NOT NULL,
    exported_at  timestamptz NOT NULL DEFAULT now(),
    retention_until date NOT NULL
);

CREATE TABLE biz.backup_archive (
    backup_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash   varchar(64) NOT NULL,
    asset_code     varchar(64) NOT NULL,
    archive_path   varchar(256) NOT NULL,
    retention_until date NOT NULL
);

-- 第三方（合作招聘企业）下载留存
CREATE TABLE biz.external_sharing (
    sharing_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    party        varchar(128) NOT NULL,
    asset_code   varchar(64) NOT NULL,
    shared_at    timestamptz NOT NULL DEFAULT now()
);

-- 遗留归档库：血缘断链点，枚举时挂起人工确认
CREATE TABLE biz.legacy_archive (
    legacy_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    tape_ref     varchar(128) NOT NULL
);

-- 演示数据 ----------------------------------------------------------------
INSERT INTO biz.account (account_id, subject_hash, phone_hash, email_hash, idcard_hash, name) VALUES
('ACC-1001','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',
 'b1c4769e3ad14f68ea1a96b73bbe5a83d90792f044828c5daf9d908b0738b177',
 'dae92ea63028947efcc58b3ef81d8bff37a0782f9d253fab62c55600fa36569c',
 '04e7358a07d7a12d426f8a3091293b19fd7a278b56163c5e9f7f0c7b41e7d233','张伟'),
('ACC-1002','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',
 NULL,
 '4a61631b42f1a4e8d8a1314e92bb7dc5ed9a8bde490996c2e9d291a91978ab77',
 '04e7358a07d7a12d426f8a3091293b19fd7a278b56163c5e9f7f0c7b41e7d233','张伟'),
('ACC-2001','d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48',
 '965eac4d7b0698a18e3c59c34023d9cd529752c7e5fd4164209d2e7df010e2ad',
 '7440664a70a8af65857729eca8a636473994304b971a19aee68e4d6ba6209afe',
 'a844c45c60b52158aeb01e241e2c98bce9cfd583035c671c9ac08c19d7927df6','李娜'),
('ACC-3001','8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7',
 '412d65b4d8f37676654685a6a525363a8a635ed140a69ac30a30f344ae0dd3b1',
 'd5b6231d61840686113e975d519b97952bdd8803b28a1317ab692c5ace13c214',
 '7fe255b84945232919b4669bdaef6136e510a0dd6ce47b4b520c8d88ede5ad2d','张伟');

INSERT INTO biz.resume (account_id, subject_hash, version_no, content_pii) VALUES
('ACC-1001','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',1,'张伟 简历 v1 含电话邮箱'),
('ACC-1001','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',2,'张伟 简历 v2 更新工作经历'),
('ACC-1002','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',1,'张伟 工作邮箱账号简历'),
('ACC-2001','d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48',1,'李娜 简历'),
('ACC-3001','8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7',1,'张伟(另一人) 简历');

INSERT INTO biz.resume_pdf (subject_hash, object_key, bucket) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','pdf/zhangwei-v1.pdf','resume-bucket'),
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','pdf/zhangwei-v2.pdf','resume-bucket'),
('d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48','pdf/lina-v1.pdf','resume-bucket'),
('8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7','pdf/zhangwei-other-v1.pdf','resume-bucket');

INSERT INTO biz.search_index_doc (subject_hash, index_name, doc_ref) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','resume_search_v3','doc-1001'),
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','resume_search_v3','doc-1002'),
('d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48','resume_search_v3','doc-2001'),
('8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7','resume_search_v3','doc-3001');

INSERT INTO biz.recommend_feature (subject_hash, model_name, vector_ref) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','job-match-embedding','vec:1001'),
('d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48','job-match-embedding','vec:2001'),
('8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7','job-match-embedding','vec:3001');

-- 三个月前导出的训练样本
INSERT INTO biz.training_sample (subject_hash, export_batch, created_at) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','BATCH-20260815', now() - interval '90 days'),
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','BATCH-20260815', now() - interval '90 days'),
('d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48','BATCH-20260815', now() - interval '90 days'),
('8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7','BATCH-20260815', now() - interval '90 days');

INSERT INTO biz.application (subject_hash, job_id, created_at) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','JOB-01', now() - interval '20 days'),
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','JOB-02', now() - interval '10 days'),
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','JOB-03', now() - interval '5 days'),
('d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48','JOB-02', now() - interval '3 days'),
('8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7','JOB-01', now() - interval '2 days');

-- 漏斗汇总（删除前基线，作业重算并记录差额）
INSERT INTO biz.funnel_daily (job_id, apply_count) VALUES
('JOB-01', 2), ('JOB-02', 2), ('JOB-03', 1);

-- 导出：A 的导出保留期已过（可立即清），B 的保留期未到
INSERT INTO biz.data_export (subject_hash, export_path, exported_at, retention_until) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','s3://exports/BATCH-20260815/zhangwei.csv', now() - interval '90 days', CURRENT_DATE - 1),
('d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48','s3://exports/BATCH-20260815/lina.csv', now() - interval '90 days', CURRENT_DATE + 60);

-- 每日备份：保留期未到，只能到期清除
INSERT INTO biz.backup_archive (subject_hash, asset_code, archive_path, retention_until) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','AST_ACCOUNT','backup/2026-11-12/account.dump', CURRENT_DATE + 30),
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','AST_RESUME','backup/2026-11-12/resume.dump', CURRENT_DATE + 30);

-- 第三方企业下载过 A 的简历 PDF
INSERT INTO biz.external_sharing (subject_hash, party, asset_code) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','华信人力集团','AST_PDF');

INSERT INTO biz.legacy_archive (subject_hash, tape_ref) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','TAPE-LG-0091');

-- 资产登记（接入登记） -----------------------------------------------------
INSERT INTO compliance.data_asset (asset_code, asset_name, storage_type, target_ref, retention_days, disposition_default) VALUES
('AST_ACCOUNT','账号主表','TABLE','biz.account',NULL,'PHYSICAL_DELETE'),
('AST_RESUME','简历多版本表','TABLE','biz.resume',NULL,'PHYSICAL_DELETE'),
('AST_PDF','简历PDF对象存储','FILE','biz.resume_pdf',NULL,'PHYSICAL_DELETE'),
('AST_SEARCH','搜索索引文档','INDEX','biz.search_index_doc',NULL,'PHYSICAL_DELETE'),
('AST_FEATURE','推荐特征向量','FEATURE','biz.recommend_feature',NULL,'PHYSICAL_DELETE'),
('AST_SAMPLE','训练样本库','SAMPLE','biz.training_sample',NULL,'PHYSICAL_DELETE'),
('AST_APPLICATION','投递记录表','TABLE','biz.application',NULL,'PHYSICAL_DELETE'),
('AST_FUNNEL','投递漏斗汇总','TABLE','biz.funnel_daily',NULL,'RECOMPUTE_AGGREGATE'),
('AST_EXPORT','历史数据导出','EXPORT','biz.data_export',90,'WAIT_RETENTION'),
('AST_BACKUP','每日备份归档','BACKUP','biz.backup_archive',30,'WAIT_RETENTION'),
('AST_EXTERNAL','第三方企业留存','EXTERNAL','biz.external_sharing',NULL,'EXTERNAL_NOTIFY'),
('AST_LEGACY','遗留磁带归档','PARTITION','biz.legacy_archive',NULL,'PHYSICAL_DELETE');

-- 血缘注册（与资产登记同批提交）：从账号主表沿血缘逐层展开
INSERT INTO compliance.lineage_edge (from_asset_code, to_asset_code) VALUES
('AST_ACCOUNT','AST_RESUME'),
('AST_RESUME','AST_PDF'),
('AST_RESUME','AST_SEARCH'),
('AST_ACCOUNT','AST_FEATURE'),
('AST_ACCOUNT','AST_SAMPLE'),
('AST_ACCOUNT','AST_APPLICATION'),
('AST_APPLICATION','AST_FUNNEL'),
('AST_RESUME','AST_EXPORT'),
('AST_ACCOUNT','AST_BACKUP'),
('AST_PDF','AST_EXTERNAL');

-- 断链：遗留归档与简历库之间血缘不可自动解析
INSERT INTO compliance.lineage_edge (from_asset_code, to_asset_code, broken) VALUES
('AST_RESUME','AST_LEGACY', true);

INSERT INTO compliance.lineage_gap (asset_code, description) VALUES
('AST_LEGACY','简历库到遗留磁带归档的血缘在 2024 年迁移时断链，需人工确认该磁带是否含本次注销主体数据');
