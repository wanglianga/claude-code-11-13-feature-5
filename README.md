# 候选人注销（被遗忘权）工单与删除作业平台

招聘平台把候选人注销当成**要管到底的工单**，而不是在主库执行一条 `DELETE` 就关单。本平台围绕监管问询函场景，
覆盖从主体归并、范围枚举、不可变快照、四类处置、回执对账到证据包出具的完整闭环；并提供**候选人查询与身份错配复核入口**，
让申请人在发现另一份简历漏删或他人资料算到自己名下时，能凭限时凭证纠正归属并追踪处理全过程。

- 后端：Spring Boot 3.5（Java 25）+ Spring Security + JdbcTemplate + Flyway + PostgreSQL 18 + springdoc-openapi
- 前端：Vue 3 + Vite 6 + Element Plus + Pinia + Vue Router（HTTP Basic 登录；候选人门户免登录、凭限时凭证访问）
- 部署：Dockerfile + docker-compose（PostgreSQL + 后端 + 前端 Nginx 反代）一键启动

## 一、需求口径与实现对照

| 题目要求 | 实现位置 |
| --- | --- |
| 按手机号/邮箱/证件号哈希把多账号、多版简历归并为候选主体，**姓名相同不构成合并依据** | `SubjectService` 联合查找（连通分量），姓名仅用于展示，不进入主体哈希 |
| 合规专员逐条确认标识符匹配，再确认主体 | `/api/identifier-matches/{id}/decide`、`/api/subjects/{hash}/confirm`，存在待决匹配时禁止确认主体 |
| 注销渠道、授权撤回时点、15 个工作日期限随工单记录 | `work_order` 表；`WorkdayUtil` 跳过周末计算法定到期日 |
| 范围枚举沿血缘**实时全图遍历**（不用静态资产清单，避免新接入表漏掉） | `LineageService.traverse()` 从根资产 BFS，血缘断链不跨越、转人工确认点 |
| 接入登记与血缘注册绑定为一次操作 | `/api/assets/register` 单事务，父资产缺失整体回滚（有集成测试） |
| 枚举结果执行前冻结为**不可变快照**；事后补登记只生成增补工单，不能追溯改写 | `enum_snapshot/snapshot_item` 带清单哈希；`supplement_ticket` 独立处置，原快照条目数与哈希不变（有集成测试） |
| 四类处置分别记账：物理清除 / 重算聚合 / 等待保留期 / 已流出通知 | `disposition_record.category`，漏斗重算记录 before/after/diff，报表不会“自己跳一下” |
| 同主体多渠道重复提交按“主体哈希+渠道”幂等并单 | `work_order.idempotency_key` 唯一约束，重复提交返回 `idempotentHit` |
| 删除作业按“工单+存储位置”幂等重放，备份不被清两遍 | `disposition_record.idempotency_key` 唯一约束 + 已终态位置跳过 |
| 执行前影响面预览、可撤回延迟窗口、候选人撤回、漏登记/回执未到退回当前节点 | `/preview`（设置 `cancel_before`）、`/cancel`、`/return`、`/resume`；退回保留已确认主体与冻结结论 |
| 第三方销毁不在本地事务边界，凭回执推进，回执未到只能挂着 | `notification_outbox` + `WAITING_RECEIPT` 状态；无回执时对账接口拒绝关单 |
| 逐位置对账，对不上的单列交合规复核 | `reconciliation_issue`，存在 OPEN 问题阻止关单（有集成测试） |
| 样本进过模型、参数无法回退；是否重训与效果变化写入模型卡，与物理删除分开展示 | `model_card` 表与独立页签，记录决策、前后效果、理由 |
| 抽样反查哈希、执行时间、证据包索引统一落库 | `recheck_hash`、`executed_at`、`evidence_package`（含 SHA-256 包哈希与落盘 JSON） |
| 手机号相同只生成**待核验关系**，不自动归并；证件指纹冲突**阻断归并** | `SubjectService` 仅以邮箱/证件强标识符连通（手机号跳过）；`AttributionService.scanRelations()` 落 `account_relation` 的 `PENDING_VERIFICATION`/`BLOCKED` |
| 申请人用**限时凭证**查看隐藏敏感信息办理说明、补交账号线索 | `query_token`（有效期 `portal-token-minutes`，默认 30 分钟）+ `/api/public/portal/**`（免登录、不回显明文、不透露其他主体） |
| 客服核验证据后交合规专员裁定；联系方式失效转人工证明，证明不足保持待核验 | `/agent-verify`、`/manual-proof`、`/contact-unreachable`；异议状态机独立于账号关系与答复 |
| 两位复核员同时提交相反结论，只接受基于**当前证据版本**的裁定 | `adjudicate` 乐观锁：`baseEvidenceVersion` 与当前版本不符或状态已变更 → 409 拒绝 |
| 归属判定与证据**版本化只追加**，纠错另建修订，旧结论与更正关系同存 | `account_attribution`（`superseded_by_id` 指针，旧行不改写）+ `attribution_revision` |
| 撤销错误关联后重算受影响账号归属，反查历史删除范围与答复引用 | `applyCorrection()` 重算 `biz.account` 指针；`revision_impact_item` 逐项反查处置/快照/答复 |
| 新归属需补删位置进**补充工单**；共享资料先**拆引用**再定处置；已物理删除登记**不可恢复** | 影响项 `SUPPLEMENTAL_DELETE`/`SHARED_REFERENCE_SPLIT`/`IRRECOVERABLE_DELETED` 三类处置 |
| 作业取到旧归属后又收到裁定：未清除位置**暂停**重核版本，已处理位置出**事故**，暂停≠撤回成功 | `disposition_record.pause_status` + `execution_incident`（`PAUSED_VERSION_STALE`/`PROCESSED_ACCIDENT`），工单不置 CANCELLED |
| 重复异议合并补证；归属修订后**旧凭证立即失效** | 同主体同类型未结案异议走 `MERGED` 并递增证据版本；修订批量 `REVOKED` 旧凭证 |
| 全部影响项有明确结果才生成**新版对外说明**，原答复按日期查回 | `external_reply` 版本化 + 前置条件校验 + `/api/replies/by-date`（含 SUPERSEDED 历史版） |

## 二、快速启动

### 方式 A：Docker Compose 一键启动（推荐）

```bash
docker compose up -d --build
```

- 前端操作台：http://localhost:8088
- 候选人查询门户（免登录）：http://localhost:8088/#/portal
- 后端 API：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui/index.html （或经前端 http://localhost:8088/swagger-ui/index.html）
- PostgreSQL：localhost:5432，库/用户/密码 `rtbf` 环境见 compose（默认 `ccdev/ccdev`）
- Flyway 在后端启动时自动建 schema（`compliance`、`biz`）并写入演示数据（V1~V7 共 7 个迁移）

停止：`docker compose down`（加 `-v` 同时清除数据卷）。

### 方式 B：本地开发启动（使用容器内预装运行时）

前置：本机 PostgreSQL 已运行，先建库：

```bash
PGPASSWORD=ccdev psql -h 127.0.0.1 -U ccdev -d postgres -c "CREATE DATABASE rtbf;"
```

后端（默认读取 `jdbc:postgresql://127.0.0.1:5432/rtbf`，可用 `DB_URL` 等环境变量覆盖）：

```bash
cd backend
mvn spring-boot:run
# 或打包后运行：mvn package -DskipTests && java -jar target/rtbf-console.jar
```

前端（Vite 已配置 `/api` 代理到 127.0.0.1:8080）：

```bash
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run dev          # http://localhost:5173
```

## 三、测试账号（HTTP Basic / 登录页通用）

| 角色 | 用户名 | 密码 | 权限 |
| --- | --- | --- | --- |
| 合规专员 | `compliance` | `Compliance@2026` | 主体确认、断链纳入/排除、对账复核、模型卡决策、证据包、**身份异议合规裁定、拆引用/影响项办结、对外答复** |
| 运维操作员 | `operator` | `Operator@2026` | 枚举冻结、预览、执行、回执登记、资产登记、增补执行、**关系扫描、人工证明、客服核验、补充删除、事故登记** |
| 只读审计 | `viewer` | `Viewer@2026` | 全量只读 |
| 候选人 | 免登录 | — | 经 `/#/portal` 用“账号+标识符知识”换限时凭证后访问，仅见本人数据 |

> 门户演示账号：王强 `ACC-4001`（手机号 `13600000004`）、陈晨 `ACC-1003`（手机号 `13800000001`）、李娜 `ACC-2001`（手机号 `13700000003`）。
> 凭证默认 30 分钟有效，可用 `PORTAL_TOKEN_MINUTES` 调整。

## 四、演示数据与验证方式

演示库内置五个自然人主体（V7 新增两个身份错配场景账号）：

- **张伟 A**（主体哈希 `f08840a3…94c8c`）：两个账号 `ACC-1001/1002`，靠证件号哈希归并；有 PDF、搜索文档、特征向量、
  训练样本、3 条投递、已到期/未到期导出、未到期备份、第三方外发、断链遗留磁带——覆盖全部四类处置。
- **李娜 B**（`d6cd18bb…4ae48`）：单账号，含未到期导出，适合演示撤回注销与保留期。
- **张伟 C**（`8dc68703…29fd7`）：**与 A 同名但标识符哈希完全不同**，系统必须识别为两个独立主体。
- **陈晨 `ACC-1003`**（`4e26169e…c6906`）：与 A **共用手机号**（只生成待核验关系），又与 `ACC-1002` 共用邮箱但证件指纹不同
  （证件冲突阻断归并）。
- **王强 `ACC-4001`**（自然人 `9d0ac03c…65f8`）：历史被误并入张伟 A（`account_attribution` 保留 `AUTO_MERGED` 旧版本台账）；
  其 PDF/索引被错挂在 A 名下并随 A 工单物理删除（不可恢复），未到期备份尚未清除（补充删除场景），简历被两候选人共享引用（拆引用场景）。

### 推荐验证路径（前端）

1. 用 `compliance` 登录 → “主体归并确认” → 点“扫描账号并归并主体”，确认出现 5 个自然人主体且两个张伟未被合并；
   逐条接受标识符匹配后“确认主体”。
2. 用 `operator` 登录 → “注销工单” → 选择张伟 A、渠道“监管问询函”建单；**重复提交一次**观察幂等并单提示。
3. 进工单处置台：冻结枚举快照（12 项，含 1 个断链 `PENDING_HUMAN`）→ 合规确认断链纳入 → 影响面预览
   （四类处置计数 8/1/2/1，可撤回窗口 30 分钟）→ 执行删除作业。
4. “处置记账”页签查看漏斗汇总 `before={JOB-01=2,JOB-02=2,JOB-03=1}`、`after` 与每职位 `-1` 差额；
   备份位置为 `SCHEDULED`（等保留期）。
5. “第三方回执”页签登记回执 → “对账复核”执行对账 → 工单关单 → 生成证据包。
6. “模型卡”页签对 `job-match-embedding` 做重训决策；“审计时间线”查看全过程留痕。
7. 对李娜 B 重复建单并在预览后“撤回注销”，核对其业务数据原样保留。
8. “资产与血缘”页用不存在的父资产编码登记，观察整事务回滚；冻结后接入新资产（如 `AST_INTERVIEW_VIDEO`），
   在工单里生成并执行增补工单，回查原快照仍为 12 条。

### 身份错配复核专项验证路径

**候选人门户（免登录，打开 `/#/portal`）**

1. 输入王强账号 `ACC-4001` + 手机号 `13600000004`，核验后拿到 30 分钟限时凭证；故意输错标识符观察 400 校验失败。
2. 查看“隐藏敏感信息办理说明”，确认只出现本人账号、不回显证件明文；提交 `WRONG_ATTRIBUTION` 异议（错挂主体填张伟 A 哈希），
   再提交一次观察**重复异议合并补证、证据版本递增**。
3. 在“办理追踪”查看异议状态、证据版本链与答复版本。

**内部工作台（operator / compliance 登录 → “身份错配复核”）**

1. “账号关系”页点“扫描账号关系”：看到 1 条手机号 `PENDING_VERIFICATION` 与证件冲突 `BLOCKED`；归属版本链保留 `AUTO_MERGED` 旧结论。
2. 先跑完张伟 A 注销工单（制造已删除/未清除错挂位置），再进异议：联系方式失效 → 人工证明不足（保持待核验）→ 证明通过 → 客服核验升级。
3. 用过期证据版本号裁定观察 409；用当前版本 `CORRECT` 裁定 → 生成修订、账号重算到王强自然人、旧凭证立即失效、影响项自动反查。
4. “修订影响项”页：共享简历点“拆引用”（资料本体保留）、未清除备份点“补充删除”、已删除位置登记“不可恢复”，逐项办结。
5. 全部办结后到“对外答复”页生成 V1/V2，确认旧版变 `SUPERSEDED` 且能按日期查回；未办结时生成会被 409 拦截。
6. “作业事故/暂停”页对该工单登记事故：未清除位置 `PAUSED_VERSION_STALE`、已处理位置 `PROCESSED_ACCIDENT`，
   工单状态仍非 `CANCELLED`（暂停不等于撤回成功）。

### 命令行快速验证（curl）

```bash
# 主体归并 / 身份关系扫描
curl -s -u compliance:Compliance@2026 -X POST http://127.0.0.1:8080/api/subjects/scan
curl -s -u compliance:Compliance@2026 -X POST http://127.0.0.1:8080/api/identity/relations/scan
# 候选人门户：限时凭证（免登录）
curl -s -X POST http://127.0.0.1:8080/api/public/portal/tokens -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-4001","idType":"PHONE","idValue":"13600000004"}'
# 工单列表 / Swagger JSON
curl -s -u viewer:Viewer@2026 http://127.0.0.1:8080/api/tickets
curl -s http://127.0.0.1:8080/v3/api-docs | jq '.info.title, (.paths|keys|length)'
```

### 自动化集成测试

```bash
# 测试使用独立库 rtbf_test（需先建库），测试类启动前自动 DROP SCHEMA 重建
PGPASSWORD=ccdev psql -h 127.0.0.1 -U ccdev -d postgres -c "CREATE DATABASE rtbf_test;"
cd backend && mvn test
```

- `RtbfWorkflowIntegrationTest`（7 例）：未认证 401、同名不同人不归并、A 主体全生命周期（幂等并单/断链挂起/四类处置/重算差额/
  重放幂等/回执/模型卡/对账关单/证据包）、撤回数据不动、登记事务回滚、增补不改写快照、对账发现篡改并阻止关单。
- `IdentityMismatchReviewIntegrationTest`（9 例）：同号待核验/证件冲突阻断、限时凭证时效与隐私隔离、异议合并补证、
  人工证明不足挂起、客服核验、裁定版本乐观锁并发拒绝、版本化修订保留旧结论、影响项（不可恢复/补充删除/拆引用/历史反查）、
  答复前置条件与版本按日期查回、作业暂停与事故（暂停≠撤回）、跨主体不泄露。

## 五、实际执行过的验证结论（本环境）

以下均为在本容器内实际执行并通过的结果，非推测：

1. `mvn test`：**Tests run: 16, Failures: 0, Errors: 0**（两个测试类各 7 / 9 例），全部通过。
2. 全新空库 `rtbf_smoke` 上运行打包产物 `java -jar rtbf-console.jar`：Flyway 成功应用 V1~V7 共 7 个迁移，应用正常启动。
3. jar 上完整跑通张伟 A 工单：冻结 12 项 → 断链确认 → 预览（四类 8/1/2/1）→ 执行（10 执行/1 排期/1 等回执）；
   错挂的王强 PDF/索引随作业物理删除、未到期备份仍存活。
4. 身份错配全链路 curl 实测通过：限时凭证（错标识符 400、过期/修订失效 409）→ 异议合并补证 → 人工证明不足挂起/通过
   → 客服核验 → 旧版本裁定 409、当前版本 `CORRECT` 成功（影响项 16 条，含不可恢复 2/补充删除 1/拆引用 1/历史反查 12）
   → 旧凭证立即失效 → 拆引用（`kept=true` 资料本体保留）→ 补充删除（备份清零）→ 影响项全部办结。
5. 版本化与反查实测：`account_attribution` 旧 `AUTO_MERGED` 行仅打 `superseded_by_id` 指针并追加 `REASSIGNED` 新版本；
   王强账号归属重算到自然人 `9d0ac03c…`；对外答复 V1→V2，旧版 `SUPERSEDED` 保留，按日期查回取到现行 V2。
6. 作业事故实测：登记后未清除位置 `paused=2`、已处理位置 `accidents=11`，工单仍为 `WAITING_RECEIPT`（未变 `CANCELLED`），
   暂停位置带 `PAUSED_VERSION_STALE` 标记，核对版本后可恢复。
7. 隐私隔离实测：李娜门户凭证 `track` 仅见本人数据（异议数 0、关系只含本人账号），跨主体补线索返回 404。
8. Swagger UI HTTP 200，`/v3/api-docs` 返回 54 个接口路径（其中错配复核/门户/答复新增 26 个）；前端 `npm run build` 成功，
   `vite preview`（8081）HTTP 200；内部接口未认证正确返回 401，公共门户路径免登录可访问。

> 说明：本容器未安装 docker 命令，`docker compose up` 未在本容器内实际执行；Dockerfile/compose 已按标准多阶段
> 构建编写，后端镜像构建逻辑与本地 `mvn package` 产物一致，前端镜像与 `npm run build` 产物一致。

## 六、目录结构

```
.
├── docker-compose.yml          # 一键启动 postgres + backend + frontend
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/rtbf/
│       │   ├── config/         # Security 三角色 + 公共门户放行、OpenAPI
│       │   ├── service/        # Subject/Lineage/WorkOrder/Audit + Attribution/CandidatePortal/ExternalReply
│       │   ├── web/            # REST 控制器（含门户、身份复核、对外答复）与全局异常
│       │   └── util/           # SHA-256 主体哈希、脱敏、工作日计算
│       └── resources/db/migration/  # V1~V7 Flyway 迁移（V7 为身份错配复核域 + 演示数据）
└── frontend/
    ├── Dockerfile + nginx.conf # Nginx 静态托管并反代 /api、/swagger-ui
    └── src/views/              # 候选人门户、身份复核工作台、对外答复、主体、工单、资产血缘
```

## 七、关键设计边界

- **快照不可变是硬约束**：冻结后只允许更新断链快照项的纳入/排除状态，不允许增删条目；补登记资产走增补工单。
- **第三方回执是异步边界**：本地数据库事务无法覆盖合作方销毁动作，工单状态机在 `WAITING_RECEIPT` 挂起，
  禁止无回执人工推进。
- **模型参数不承诺回退**：训练样本物理删除与模型参数处置分表陈列，模型卡如实记录重训决策与效果变化，供法务出具说明。
- **对账是关单前置条件**：任何 OPEN 对账问题都会阻止工单进入 `COMPLETED`。
- **身份归并的强/弱标识符边界**：手机号是弱标识符，同号只产生待核验关系；证件指纹冲突一票否决，邮箱相同也不连通。
- **归属判定只追加不改写**：纠错通过 `attribution_revision` 另建修订，旧结论与证据版本永久保留，保证旧证据包始终能解释
  当时为何删除；业务主表归属指针可重算，但历史快照/处置记录的旧哈希不动。
- **三套状态相互独立**：账号关系（`account_relation`）、异议办理（`identity_dispute`）、对外答复（`external_reply`）
  各自维护状态机；答复只能在影响项全部办结后生成新版本，旧答复按日期可查回。
- **暂停不是撤回**：作业进行中收到归属裁定，未清除位置暂停并重新核对证据版本，已处理位置只能登记事故并走影响项补救，
  系统不会把暂停呈现为撤回成功。
- **门户最小知情**：限时凭证只承载主体级访问，所有视图按主体过滤，证件等敏感字段不回显明文，绝不透露其他主体信息。
