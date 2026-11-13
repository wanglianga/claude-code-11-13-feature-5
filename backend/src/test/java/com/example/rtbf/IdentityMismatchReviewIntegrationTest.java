package com.example.rtbf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份错配复核域端到端测试：独立类、独立重置演示库。
 * 覆盖：同号待核验/证件冲突阻断、限时凭证、异议合并补证、人工证明、客服核验、
 * 合规裁定版本并发、版本化修订、影响项（不可恢复/补充删除/拆引用/历史反查）、
 * 作业暂停与事故、答复版本前置条件与按日期查回、修订后凭证失效、跨主体隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityMismatchReviewIntegrationTest {

    private static final String SUBJ_A =
        "f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c";
    private static final String SUBJ_WANG =
        "9d0ac03c4cc30391128092e41131bccfeddfd6b1f0d6a7a61131ff2ac64e65f8";
    private static final String ACC_WANG = "ACC-4001";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Flyway flyway;
    private final ObjectMapper json = new ObjectMapper();

    private String portalToken;
    private long disputeId;
    private long ticketIdA;
    private long revisionId;

    static {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/rtbf_test", "ccdev", "ccdev");
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS compliance CASCADE");
            st.execute("DROP SCHEMA IF EXISTS biz CASCADE");
            st.execute("DROP TABLE IF EXISTS public.flyway_schema_history CASCADE");
        } catch (java.sql.SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    void resetDatabase() {
        jdbc.execute("DROP SCHEMA IF EXISTS compliance CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS biz CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS public.flyway_schema_history CASCADE");
        flyway.migrate();
    }

    private HttpHeaders auth(String user) {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(user, switch (user) {
            case "compliance" -> "Compliance@2026";
            case "operator" -> "Operator@2026";
            default -> "Viewer@2026";
        });
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private JsonNode post(String user, String path, Object body) {
        ResponseEntity<String> r = rest.exchange(path, HttpMethod.POST,
            new HttpEntity<>(write(body), auth(user)), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(),
            () -> "POST " + path + " 失败: " + r.getStatusCode() + " " + r.getBody());
        return read(r.getBody());
    }

    private JsonNode get(String user, String path) {
        ResponseEntity<String> r = rest.exchange(path, HttpMethod.GET,
            new HttpEntity<>(auth(user)), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), () -> "GET " + path + " 失败: " + r.getBody());
        return read(r.getBody());
    }

    private ResponseEntity<String> postRaw(String user, String path, Object body) {
        return rest.exchange(path, HttpMethod.POST,
            new HttpEntity<>(body == null ? "" : write(body), auth(user)), String.class);
    }

    private JsonNode postPublic(String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.exchange(path, HttpMethod.POST,
            new HttpEntity<>(write(body), h), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(),
            () -> "公开 POST " + path + " 失败: " + r.getStatusCode() + " " + r.getBody());
        return read(r.getBody());
    }

    private JsonNode getPublic(String path) {
        ResponseEntity<String> r = rest.getForEntity(path, String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), () -> "公开 GET 失败: " + r.getBody());
        return read(r.getBody());
    }

    private ResponseEntity<String> postPublicRaw(String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(write(body), h), String.class);
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void confirmAllMatches(String user, String subjectHash) {
        JsonNode subject = get(user, "/api/subjects/" + subjectHash);
        for (JsonNode m : subject.get("identifierMatches")) {
            post(user, "/api/identifier-matches/" + m.get("id").asLong() + "/decide",
                 Map.of("accept", true));
        }
        post(user, "/api/subjects/" + subjectHash + "/confirm", Map.of());
    }

    @Test
    @Order(1)
    void relationScan_phonePending_idcardConflictBlocked() {
        post("compliance", "/api/subjects/scan", Map.of());
        JsonNode rel = post("compliance", "/api/identity/relations/scan", Map.of());
        assertTrue(rel.get("pendingPhoneRelations").asInt() >= 1, "同手机号只生成待核验关系");
        assertTrue(rel.get("blockedIdcardConflicts").asInt() >= 1, "证件指纹冲突阻断归并");

        JsonNode pending = get("viewer", "/api/identity/relations?status=PENDING_VERIFICATION");
        assertTrue(pending.size() >= 1);
        for (JsonNode r : pending) {
            assertEquals("PHONE", r.get("match_type").asText());
        }
        JsonNode blocked = get("viewer", "/api/identity/relations?status=BLOCKED");
        assertTrue(blocked.size() >= 1);
        for (JsonNode r : blocked) {
            assertEquals("IDCARD_CONFLICT", r.get("match_type").asText());
        }
        // 历史误归并台账保留旧结论
        JsonNode attrs = get("viewer", "/api/identity/attributions?accountId=" + ACC_WANG);
        boolean hasOldAutoMerged = java.util.stream.StreamSupport.stream(attrs.spliterator(), false)
            .anyMatch(a -> "AUTO_MERGED".equals(a.get("decision_status").asText()));
        assertTrue(hasOldAutoMerged, "旧版本归属判定必须保留不可改写");
    }

    @Test
    @Order(2)
    void portalToken_timeLimited_andPrivacyScoped() {
        // 门户免登录即可访问
        ResponseEntity<String> noAuth = rest.getForEntity(
            "/api/identity/disputes", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noAuth.getStatusCode(), "内部接口仍要求登录");

        // 标识符知识校验失败 → 400，且不暴露账号是否存在
        ResponseEntity<String> bad = postPublicRaw("/api/public/portal/tokens", Map.of(
            "accountId", ACC_WANG, "idType", "PHONE", "idValue", "13900000000"));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());

        JsonNode token = postPublic("/api/public/portal/tokens", Map.of(
            "accountId", ACC_WANG, "idType", "PHONE", "idValue", "13600000004",
            "purpose", "PORTAL_INSTRUCTION"));
        portalToken = token.get("tokenRef").asText();
        assertNotNull(portalToken);
        assertTrue(token.get("expiresInMinutes").asInt() > 0);

        JsonNode instruction = getPublic(
            "/api/public/portal/instruction?tokenRef=" + portalToken);
        assertTrue(instruction.get("instruction").asText().contains("证件号"));
        // 只返回本主体账号，不出现其他主体账号
        JsonNode accounts = instruction.get("ownAccounts");
        for (JsonNode a : accounts) {
            assertEquals(ACC_WANG, a.get("account_id").asText());
        }

        // 凭证过期 → 显式过期错误，不能继续查看
        jdbc.update("UPDATE compliance.query_token SET expires_at = now() - interval '1 minute' WHERE token_ref = ?",
                    portalToken);
        assertEquals(HttpStatus.CONFLICT,
            rest.getForEntity("/api/public/portal/instruction?tokenRef=" + portalToken, String.class)
                .getStatusCode());
        jdbc.update("UPDATE compliance.query_token SET expires_at = now() + interval '30 minute', status='ISSUED' WHERE token_ref = ?",
                    portalToken);
    }

    @Test
    @Order(3)
    void dispute_mergeSupplement_manualProof_agentVerify() {
        JsonNode created = postPublic("/api/public/portal/disputes", Map.of(
            "tokenRef", portalToken, "kind", "WRONG_ATTRIBUTION",
            "disputedAccount", ACC_WANG, "wrongSubjectHash", SUBJ_A,
            "clue", "王强发现自己的简历被算到张伟主体名下，提交账号 ACC-4001 线索"));
        disputeId = created.get("disputeId").asLong();
        assertFalse(created.get("merged").asBoolean());

        // 重复异议 → 合并补证，证据版本递增，不新开单
        JsonNode again = postPublic("/api/public/portal/disputes", Map.of(
            "tokenRef", portalToken, "kind", "WRONG_ATTRIBUTION",
            "disputedAccount", ACC_WANG, "wrongSubjectHash", SUBJ_A,
            "clue", "补充注册邮箱线索"));
        assertTrue(again.get("merged").asBoolean());
        assertEquals(disputeId, again.get("disputeId").asLong());

        // 联系方式失效 → 人工证明；证明不足保持待核验
        post("operator", "/api/identity/disputes/" + disputeId + "/contact-unreachable", Map.of());
        JsonNode insufficient = post("operator",
            "/api/identity/disputes/" + disputeId + "/manual-proof",
            Map.of("sufficient", false, "note", "材料缺少证件水印"));
        assertFalse(insufficient.get("sufficient").asBoolean());
        assertEquals("AWAITING_MANUAL_PROOF",
            get("viewer", "/api/identity/disputes/" + disputeId).get("status").asText());

        JsonNode sufficient = post("operator",
            "/api/identity/disputes/" + disputeId + "/manual-proof",
            Map.of("sufficient", true, "note", "线下核验身份证原件通过"));
        assertTrue(sufficient.get("sufficient").asBoolean());

        JsonNode verified = post("operator",
            "/api/identity/disputes/" + disputeId + "/agent-verify",
            Map.of("accepted", true, "note", "证据链完整，升级合规"));
        assertEquals("ESCALATED_COMPLIANCE", verified.get("status").asText());
    }

    @Test
    @Order(4)
    void subjectA_ticketExecutes_misfiledDataPartlyDeleted() {
        // 先让张伟 A 的注销工单跑完：错挂的 PDF/索引被物理删除，备份仅 SCHEDULED 未清除
        confirmAllMatches("compliance", SUBJ_A);
        JsonNode created = post("operator", "/api/tickets", Map.of(
            "subjectHash", SUBJ_A, "channel", "REGULATOR_LETTER", "sourceRef", "问询函-错配复核"));
        ticketIdA = created.get("ticketId").asLong();
        post("operator", "/api/tickets/" + ticketIdA + "/freeze-enumeration", Map.of());
        long gapId = get("viewer", "/api/lineage/gaps").get(0).get("id").asLong();
        post("compliance", "/api/tickets/" + ticketIdA + "/gaps/" + gapId + "/confirm",
             Map.of("inScope", true));
        post("operator", "/api/tickets/" + ticketIdA + "/preview", Map.of());
        JsonNode exec = post("operator", "/api/tickets/" + ticketIdA + "/execute", Map.of());
        assertTrue(exec.get("executed").asInt() >= 1);

        // 错挂 PDF/索引已随作业物理删除；备份行仍在
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM biz.resume_pdf WHERE object_key = 'pdf/wangqiang-misfiled.pdf'",
            Integer.class));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM biz.search_index_doc WHERE doc_ref = 'doc-4001'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM biz.backup_archive WHERE archive_path = 'backup/misfiled/wangqiang-account.dump'",
            Integer.class));
    }

    @Test
    @Order(5)
    void adjudicate_versionOptimisticLock_andRevisionKeepsOldConclusion() {
        // 再补一条证据抬高当前版本，随后用旧版本号裁定必须被拒
        post("operator", "/api/identity/disputes/" + disputeId + "/evidence",
             Map.of("clueType", "ACCOUNT_CLUE", "content", "客服补充账号注册时间截图"));
        int currentVersion = get("viewer", "/api/identity/disputes/" + disputeId)
            .get("current_evidence_version").asInt();
        assertEquals(HttpStatus.CONFLICT, postRaw("compliance",
            "/api/identity/disputes/" + disputeId + "/adjudicate",
            Map.of("decision", "CORRECT", "baseEvidenceVersion", currentVersion - 1,
                   "rationale", "过期版本裁定")).getStatusCode());

        JsonNode ruling = post("compliance",
            "/api/identity/disputes/" + disputeId + "/adjudicate",
            Map.of("decision", "CORRECT", "baseEvidenceVersion", currentVersion,
                   "rationale", "证据证明 ACC-4001 属王强自然人，历史误并入张伟 A，裁定更正归属"));
        revisionId = ruling.get("revisionId").asLong();
        assertEquals(SUBJ_WANG, ruling.get("toSubject").asText());
        assertTrue(ruling.get("impactItems").asInt() >= 4);

        // 已结案异议不能再次裁定（并发相反结论的第二个提交被拒）
        assertEquals(HttpStatus.CONFLICT, postRaw("compliance",
            "/api/identity/disputes/" + disputeId + "/adjudicate",
            Map.of("decision", "UPHOLD", "baseEvidenceVersion", currentVersion,
                   "rationale", "相反结论")).getStatusCode());

        // 旧归属判定保留并被标记取代；新版本 REASSIGNED 追加
        JsonNode attrs = get("viewer", "/api/identity/attributions?accountId=" + ACC_WANG);
        boolean oldKept = java.util.stream.StreamSupport.stream(attrs.spliterator(), false)
            .anyMatch(a -> "AUTO_MERGED".equals(a.get("decision_status").asText())
                           && !a.get("superseded_by_id").isNull());
        boolean newReassigned = java.util.stream.StreamSupport.stream(attrs.spliterator(), false)
            .anyMatch(a -> "REASSIGNED".equals(a.get("decision_status").asText()));
        assertTrue(oldKept, "旧结论必须保留且仅打取代指针");
        assertTrue(newReassigned, "必须追加新版本归属结论");

        // 业务主表归属重算到王强自然人
        assertEquals(SUBJ_WANG, jdbc.queryForObject(
            "SELECT subject_hash FROM biz.account WHERE account_id = ?", String.class, ACC_WANG));

        // 修订后旧凭证立即失效
        assertEquals(HttpStatus.CONFLICT,
            rest.getForEntity("/api/public/portal/instruction?tokenRef=" + portalToken, String.class)
                .getStatusCode());

        // 影响项尚未全部办结 → 生成对外说明必须被阻断
        assertEquals(HttpStatus.CONFLICT, postRaw("compliance",
            "/api/replies/disputes/" + disputeId,
            Map.of("title", "提前生成的说明")).getStatusCode());

        // 影响项分类齐全
        JsonNode impacts = get("viewer", "/api/identity/impact-items?revisionId=" + revisionId);
        long kinds = java.util.stream.StreamSupport.stream(impacts.spliterator(), false)
            .map(i -> i.get("impact_kind").asText()).distinct().count();
        assertTrue(kinds >= 4, "影响项应覆盖不可恢复/补充删除/共享引用/历史反查等类型");
    }

    @Test
    @Order(6)
    void impactItems_splitReference_supplementalDelete_irrecoverable() {
        JsonNode impacts = get("viewer", "/api/identity/impact-items?revisionId=" + revisionId);
        long splitId = -1, supplementId = -1;
        for (JsonNode i : impacts) {
            if ("SHARED_REFERENCE_SPLIT".equals(i.get("impact_kind").asText())) {
                splitId = i.get("id").asLong();
            }
            if ("SUPPLEMENTAL_DELETE".equals(i.get("impact_kind").asText())) {
                supplementId = i.get("id").asLong();
            }
        }
        assertTrue(splitId > 0 && supplementId > 0);

        // 拆引用：错误主体引用解除，简历本体因仍被王强合法引用而保留
        long resumeId = jdbc.queryForObject(
            "SELECT resume_id FROM biz.resume_reference WHERE subject_hash = ? LIMIT 1",
            Long.class, SUBJ_A);
        JsonNode split = post("compliance", "/api/identity/impact-items/" + splitId + "/split", Map.of());
        assertTrue(split.get("kept").asBoolean(), "仍被合法引用的资料不能整份清除");
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM biz.resume_reference WHERE resume_id = ? AND subject_hash = ? AND reference_status='ACTIVE'",
            Integer.class, resumeId, SUBJ_A));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM biz.resume WHERE resume_id = ?", Integer.class, resumeId),
            "简历本体保留未被删除");

        // 补充删除：未清除的错挂备份位置补删
        JsonNode supp = post("operator",
            "/api/identity/impact-items/" + supplementId + "/supplemental-delete", Map.of());
        assertTrue(supp.get("deleted").asInt() >= 1);
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM biz.backup_archive WHERE archive_path = 'backup/misfiled/wangqiang-account.dump'",
            Integer.class));

        // 已物理删除位置登记为不可恢复事实
        int irrecoverable = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.revision_impact_item WHERE revision_id = ? AND impact_kind='IRRECOVERABLE_DELETED'",
            Integer.class, revisionId);
        assertTrue(irrecoverable >= 2, "PDF 与索引两个已删除位置均须登记不可恢复");
        int physicallyDeleted = jdbc.queryForObject(
            "SELECT count(*) FROM biz.misfiled_record WHERE record_status = 'PHYSICALLY_DELETED'",
            Integer.class);
        assertTrue(physicallyDeleted >= 2);

        // 剩余非终态影响项人工办结
        JsonNode remaining = get("viewer", "/api/identity/impact-items?revisionId=" + revisionId);
        for (JsonNode i : remaining) {
            String st = i.get("item_status").asText();
            if ("PENDING".equals(st) || "SPLIT_PENDING_DISPOSITION".equals(st)
                || "SUPPLEMENT_TICKETED".equals(st)) {
                post("compliance", "/api/identity/impact-items/" + i.get("id").asLong() + "/resolve",
                     Map.of("note", "人工确认办结"));
            }
        }
    }

    @Test
    @Order(7)
    void reply_gatedByImpactResolution_versionedAndDateTraceable() {
        // 前置条件不满足时不能生成答复（本用例在影响项办结后应可生成；先验证重复生成的版本化）
        JsonNode v1 = post("compliance", "/api/replies/disputes/" + disputeId,
                           Map.of("title", "身份错配复核处理说明 V1"));
        assertEquals(1, v1.get("replyVersion").asInt());

        JsonNode v2 = post("compliance", "/api/replies/disputes/" + disputeId,
                           Map.of("title", "身份错配复核处理说明 V2", "bodyText", "补充删除与拆引用均已办结"));
        assertEquals(2, v2.get("replyVersion").asInt());

        JsonNode list = get("viewer", "/api/replies?disputeId=" + disputeId);
        assertEquals(2, list.size());
        long superseded = java.util.stream.StreamSupport.stream(list.spliterator(), false)
            .filter(r -> "SUPERSEDED".equals(r.get("reply_status").asText())).count();
        assertEquals(1, superseded, "旧答复保留并标记被取代");

        // 按日期查回：不带日期默认取今天，应取到最新 V2；原答复仍可按更早日期查回
        JsonNode byDate = get("viewer", "/api/replies/by-date?subjectHash=" + SUBJ_A);
        assertEquals(2, byDate.get("reply_version").asInt());
        // 极早日期没有答复 → 404
        assertEquals(HttpStatus.NOT_FOUND,
            rest.exchange("/api/replies/by-date?subjectHash=" + SUBJ_A + "&date=2000-01-01",
                          HttpMethod.GET, new HttpEntity<>(auth("viewer")), String.class)
                .getStatusCode());
    }

    @Test
    @Order(8)
    void executionIncident_pausedNotCancelled_processedIsAccident() {
        JsonNode incident = post("operator",
            "/api/identity/tickets/" + ticketIdA + "/incidents",
            Map.of("revisionId", revisionId));
        assertTrue(incident.get("paused").asInt() >= 1, "未清除位置必须暂停");
        assertTrue(incident.get("processedAccidents").asInt() >= 1, "已处理位置必须生成事故");

        // 暂停不是撤回成功：工单未被取消，处置记录带暂停标记
        assertNotCancelled();
        Long pausedDisp = jdbc.queryForObject(
            "SELECT id FROM compliance.disposition_record WHERE ticket_id = ? AND pause_status='PAUSED_VERSION_STALE' LIMIT 1",
            Long.class, ticketIdA);
        assertNotNull(pausedDisp);
        post("compliance", "/api/identity/dispositions/" + pausedDisp + "/resume-paused", Map.of());
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM compliance.disposition_record WHERE id = ? AND pause_status IS NOT NULL",
            Integer.class, pausedDisp));
    }

    private void assertNotCancelled() {
        String status = jdbc.queryForObject(
            "SELECT status FROM compliance.work_order WHERE id = ?", String.class, ticketIdA);
        assertFalse("CANCELLED".equals(status), "暂停不得显示为撤回成功");
    }

    @Test
    @Order(9)
    void crossSubject_isolation_neverLeakOtherSubject() {
        // 另一主体（李娜）的凭证只能看到自己的数据
        JsonNode tokenB = postPublic("/api/public/portal/tokens", Map.of(
            "accountId", "ACC-2001", "idType", "PHONE", "idValue", "13700000003"));
        JsonNode trackB = getPublic("/api/public/portal/track?tokenRef=" + tokenB.get("tokenRef").asText());
        assertEquals(0, trackB.get("disputes").size(), "看不到其他主体的异议");
        for (JsonNode r : trackB.get("relations")) {
            String a = r.get("account_id_a").asText() + r.get("account_id_b").asText();
            assertTrue(a.contains("ACC-2001"), "关系视图只允许出现本主体账号");
        }
        // 跨主体补交线索 → 404
        ResponseEntity<String> cross = postPublicRaw(
            "/api/public/portal/disputes/" + disputeId + "/clues",
            Map.of("tokenRef", tokenB.get("tokenRef").asText(),
                   "clueType", "ACCOUNT_CLUE", "content", "试图越权访问"));
        assertEquals(HttpStatus.NOT_FOUND, cross.getStatusCode());
    }
}
