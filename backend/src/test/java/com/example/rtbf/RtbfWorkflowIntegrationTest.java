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
 * 端到端集成测试：独立 rtbf_test 库，每个测试类运行前 Flyway clean+migrate 还原演示基线。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RtbfWorkflowIntegrationTest {

    private static final String SUBJ_A =
        "f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c";
    private static final String SUBJ_B =
        "d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48";
    private static final String SUBJ_C =
        "8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Flyway flyway;
    private final ObjectMapper json = new ObjectMapper();

    static {
        // 在 Spring 上下文（含 Flyway 自动迁移）启动前清空测试库，避免上次失败运行的残留对象冲突
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/rtbf_test", "ccdev", "ccdev");
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS compliance CASCADE");
            st.execute("DROP SCHEMA IF EXISTS biz CASCADE");
            // Flyway 历史表默认落在 public，必须一并清除，否则 Flyway 认为已是 v5 而跳过建表
            st.execute("DROP TABLE IF EXISTS public.flyway_schema_history CASCADE");
        } catch (java.sql.SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    void resetDatabase() {
        // 显式删除两个 schema，再由 Flyway 从零迁移，保证每个测试类拿到干净演示基线
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
            new HttpEntity<>(body == null ? "" : write(body), auth(user)), String.class);
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
    void unauthenticated_isRejected() {
        ResponseEntity<String> r = rest.getForEntity("/api/subjects", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
    }

    @Test
    @Order(2)
    void scan_merge_keepsSameNameButDifferentPersonApart() {
        JsonNode scanned = post("compliance", "/api/subjects/scan", Map.of());
        // V7 新增 ACC-1003（同手机号）、ACC-4001（历史误归并）两个独立自然人 → 共 5 个主体
        assertEquals(5, scanned.size(), "应归并出 5 个主体（手机号同号不自动归并）");
        JsonNode subjects = get("viewer", "/api/subjects");
        long zhangWei = java.util.stream.StreamSupport.stream(subjects.spliterator(), false)
            .filter(s -> "张伟".equals(s.get("display_name").asText())).count();
        assertEquals(2, zhangWei, "两个同名张伟必须是两个独立主体");
        JsonNode a = get("viewer", "/api/subjects/" + SUBJ_A);
        assertEquals(5, a.get("identifierMatches").size(), "A 跨两账号共 5 条标识符匹配");

        // 手机号相同只生成待核验关系；证件指纹冲突阻断归并
        JsonNode rel = post("compliance", "/api/identity/relations/scan", Map.of());
        assertTrue(rel.get("pendingPhoneRelations").asInt() >= 1, "同手机号必须生成待核验关系");
        assertTrue(rel.get("blockedIdcardConflicts").asInt() >= 1, "证件指纹冲突必须阻断归并");
        JsonNode pending = get("viewer", "/api/identity/relations?status=PENDING_VERIFICATION");
        assertTrue(pending.size() >= 1);
        JsonNode blocked = get("viewer", "/api/identity/relations?status=BLOCKED");
        assertTrue(blocked.size() >= 1);
    }

    @Test
    @Order(3)
    void fullLifecycle_subjectA_completesWithAllFourDispositions() {
        confirmAllMatches("compliance", SUBJ_A);

        JsonNode created = post("operator", "/api/tickets", Map.of(
            "subjectHash", SUBJ_A, "channel", "REGULATOR_LETTER", "sourceRef", "问询函-001"));
        long ticketId = created.get("ticketId").asLong();
        assertFalse(created.get("idempotentHit").asBoolean());

        // 同主体同渠道重复提交 → 幂等并单
        JsonNode dup = post("operator", "/api/tickets", Map.of(
            "subjectHash", SUBJ_A, "channel", "REGULATOR_LETTER", "sourceRef", "重复"));
        assertTrue(dup.get("idempotentHit").asBoolean());
        assertEquals(ticketId, dup.get("ticketId").asLong());

        JsonNode frozen = post("operator", "/api/tickets/" + ticketId + "/freeze-enumeration", Map.of());
        assertEquals(12, frozen.get("itemCount").asInt());
        assertNotNull(frozen.get("manifestHash").asText());

        JsonNode detail = get("viewer", "/api/tickets/" + ticketId);
        long pendingHuman = java.util.stream.StreamSupport.stream(detail.get("items").spliterator(), false)
            .filter(i -> "PENDING_HUMAN".equals(i.get("item_status").asText())).count();
        assertEquals(1, pendingHuman, "断链项必须挂起人工确认");

        // 未确认断链不得预览
        assertEquals(HttpStatus.CONFLICT,
            postRaw("operator", "/api/tickets/" + ticketId + "/preview", Map.of()).getStatusCode());

        long gapId = get("viewer", "/api/lineage/gaps").get(0).get("id").asLong();
        post("compliance", "/api/tickets/" + ticketId + "/gaps/" + gapId + "/confirm",
             Map.of("inScope", true));

        JsonNode preview = post("operator", "/api/tickets/" + ticketId + "/preview", Map.of());
        assertEquals(8, preview.get("categoryCount").get("PHYSICAL_DELETE").asInt());
        assertEquals(1, preview.get("categoryCount").get("RECOMPUTE_AGGREGATE").asInt());
        assertEquals(2, preview.get("categoryCount").get("WAIT_RETENTION").asInt());
        assertEquals(1, preview.get("categoryCount").get("EXTERNAL_NOTIFY").asInt());

        JsonNode exec = post("operator", "/api/tickets/" + ticketId + "/execute", Map.of());
        assertEquals(10, exec.get("executed").asInt());
        assertEquals(1, exec.get("scheduled").asInt(), "备份保留期未到，挂 SCHEDULED");
        assertEquals(1, exec.get("waitingReceipt").asInt());

        // 幂等重放：全部跳过，备份不会被清两遍
        JsonNode replay = post("operator", "/api/tickets/" + ticketId + "/execute", Map.of());
        assertEquals(0, replay.get("executed").asInt());

        // 重算差额留痕
        JsonNode afterExec = get("viewer", "/api/tickets/" + ticketId);
        JsonNode funnel = java.util.stream.StreamSupport.stream(afterExec.get("dispositions").spliterator(), false)
            .filter(d -> "AST_FUNNEL".equals(d.get("asset_code").asText())).findFirst().orElseThrow();
        assertEquals("EXECUTED", funnel.get("status").asText());
        assertTrue(funnel.get("diff_value").asText().contains("JOB-03=-1"));

        // 物理清除可反查：A 主体主表清零，其他主体不受影响（V7 后全库 6 个账号，删 2 剩 4）
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM biz.account WHERE subject_hash = ?", Integer.class, SUBJ_A));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM biz.account", Integer.class));
        // A 的 2 条每日备份保留期未到不清除；V7 新增 1 条错挂备份同属 A 主体哈希且未到期 → 共 3 条
        assertEquals(3, jdbc.queryForObject(
            "SELECT count(*) FROM biz.backup_archive WHERE subject_hash = ?", Integer.class, SUBJ_A));

        // 模型卡自动生成且待决策
        assertEquals(1, afterExec.get("modelCards").size());
        long cardId = afterExec.get("modelCards").get(0).get("id").asLong();
        post("compliance", "/api/model-cards/" + cardId + "/retrain-decision", Map.of(
            "decision", "RETRAINED",
            "performanceBefore", "AUC=0.812", "performanceAfter", "AUC=0.810",
            "justification", "样本占比0.3%，重训完成，参数不可回退已留痕"));

        // 第三方回执未到不能关单
        assertEquals(HttpStatus.CONFLICT,
            postRaw("compliance", "/api/tickets/" + ticketId + "/reconcile", Map.of()).getStatusCode());

        String noticeRef = afterExec.get("notifications").get(0).get("notice_ref").asText();
        post("operator", "/api/tickets/" + ticketId + "/receipts",
             Map.of("noticeRef", noticeRef, "receiptRef", "RCPT-001"));

        JsonNode reconcile = post("compliance", "/api/tickets/" + ticketId + "/reconcile", Map.of());
        assertTrue(reconcile.get("passed").asBoolean());
        assertEquals("COMPLETED",
            get("viewer", "/api/tickets/" + ticketId).get("status").asText());

        JsonNode evidence = post("compliance", "/api/tickets/" + ticketId + "/evidence-package", Map.of());
        assertTrue(evidence.get("packageHash").asText().length() == 64);
    }

    @Test
    @Order(4)
    void cancellation_leavesAllDataUntouched() {
        confirmAllMatches("compliance", SUBJ_B);
        JsonNode created = post("operator", "/api/tickets",
                                 Map.of("subjectHash", SUBJ_B, "channel", "APP"));
        long ticketId = created.get("ticketId").asLong();
        post("operator", "/api/tickets/" + ticketId + "/freeze-enumeration", Map.of());
        long gapId = get("viewer", "/api/lineage/gaps").get(0).get("id").asLong();
        post("compliance", "/api/tickets/" + ticketId + "/gaps/" + gapId + "/confirm",
             Map.of("inScope", false));
        post("operator", "/api/tickets/" + ticketId + "/preview", Map.of());
        post("compliance", "/api/tickets/" + ticketId + "/cancel",
             Map.of("reason", "候选人撤回注销"));

        assertEquals("CANCELLED",
            get("viewer", "/api/tickets/" + ticketId).get("status").asText());
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM biz.account WHERE subject_hash = ?", Integer.class, SUBJ_B));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM biz.resume WHERE subject_hash = ?", Integer.class, SUBJ_B));
    }

    @Test
    @Order(5)
    void assetRegistration_rollsBackWhenLineageParentMissing() {
        ResponseEntity<String> r = postRaw("operator", "/api/assets/register", Map.of(
            "assetCode", "AST_BAD", "assetName", "坏资产", "storageType", "TABLE",
            "targetRef", "biz.x", "dispositionDefault", "PHYSICAL_DELETE",
            "parentAssetCodes", List.of("AST_NOT_EXIST")));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM compliance.data_asset WHERE asset_code = 'AST_BAD'", Integer.class));
    }

    @Test
    @Order(6)
    void supplementTicket_doesNotRewriteFrozenSnapshot() {
        // 新资产在工单1快照冻结之后才接入登记
        JsonNode reg = post("operator", "/api/assets/register", Map.of(
            "assetCode", "AST_INTERVIEW_VIDEO", "assetName", "AI面试录像库",
            "storageType", "FILE", "targetRef", "biz.interview_video",
            "dispositionDefault", "PHYSICAL_DELETE",
            "parentAssetCodes", List.of("AST_ACCOUNT")));
        assertTrue(reg.get("boundInOneTransaction").asBoolean());

        // 已在快照内的资产不能走增补
        assertEquals(HttpStatus.CONFLICT,
            postRaw("compliance", "/api/tickets/1/supplements",
                    Map.of("assetCode", "AST_PDF", "reason", "x")).getStatusCode());

        JsonNode sup = post("compliance", "/api/tickets/1/supplements", Map.of(
            "assetCode", "AST_INTERVIEW_VIDEO", "reason", "冻结后新接入"));
        long supplementId = sup.get("supplementId").asLong();
        JsonNode executed = post("operator", "/api/supplements/" + supplementId + "/execute", Map.of());
        assertTrue(executed.get("deleted").asInt() >= 0);

        // 原快照条目数与清单哈希不变
        assertEquals(12, jdbc.queryForObject(
            "SELECT count(*) FROM compliance.snapshot_item WHERE snapshot_id = " +
            "(SELECT snapshot_id FROM compliance.work_order WHERE id = 1)", Integer.class));
    }

    @Test
    @Order(7)
    void reconciliation_flagsMismatchAndBlocksClosure() {
        confirmAllMatches("compliance", SUBJ_C);
        JsonNode created = post("operator", "/api/tickets",
                                 Map.of("subjectHash", SUBJ_C, "channel", "WEB"));
        long ticketId = created.get("ticketId").asLong();
        post("operator", "/api/tickets/" + ticketId + "/freeze-enumeration", Map.of());
        long gapId = get("viewer", "/api/lineage/gaps").get(0).get("id").asLong();
        post("compliance", "/api/tickets/" + ticketId + "/gaps/" + gapId + "/confirm",
             Map.of("inScope", true));
        post("operator", "/api/tickets/" + ticketId + "/preview", Map.of());
        post("operator", "/api/tickets/" + ticketId + "/execute", Map.of());

        // 人工篡改：重新插入一条本应删除的数据，对账必须发现对不上并阻止关单
        jdbc.update("INSERT INTO biz.resume (account_id, subject_hash, version_no, content_pii) " +
                    "VALUES ('ACC-3001', ?, 9, '篡改数据')", SUBJ_C);
        JsonNode failed = post("compliance", "/api/tickets/" + ticketId + "/reconcile", Map.of());
        assertFalse(failed.get("passed").asBoolean());
        assertTrue(failed.get("openIssues").asInt() > 0);

        // 篡改会同时产生抽样反查哈希不符与物理残留两类问题，需逐条复核解决
        List<Long> issueIds = jdbc.queryForList(
            "SELECT id FROM compliance.reconciliation_issue WHERE ticket_id = ? AND status = 'OPEN' ORDER BY id",
            Long.class, ticketId);
        assertTrue(issueIds.size() >= 1);
        for (Long issueId : issueIds) {
            post("compliance", "/api/issues/" + issueId + "/resolve",
                 Map.of("note", "确认为测试残留，已清除并复核"));
        }
        jdbc.update("DELETE FROM biz.resume WHERE subject_hash = ?", SUBJ_C);
        JsonNode passed = post("compliance", "/api/tickets/" + ticketId + "/reconcile", Map.of());
        assertTrue(passed.get("passed").asBoolean());
        assertEquals("COMPLETED",
            get("viewer", "/api/tickets/" + ticketId).get("status").asText());
    }
}
