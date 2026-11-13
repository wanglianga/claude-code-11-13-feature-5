package com.example.rtbf.service;

import com.example.rtbf.util.HashUtil;
import com.example.rtbf.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 身份错配复核编排。
 *
 * 规则硬约束：
 *  1. 手机号相同只生成“待核验关系”，不自动归并；证件指纹冲突直接阻断归并。
 *  2. 归属判定只追加版本，旧结论不改写；纠错另建 attribution_revision，并同时保存旧结论与更正关系。
 *  3. 账号关系(account_relation)、异议办理(identity_dispute)、对外答复(external_reply) 三套状态各自独立。
 *  4. 撤销错误关联 → 重算受影响账号归属，并逐项反查历史删除范围、处置记录与答复引用。
 *  5. 共享资料先拆引用再定处置；已物理删除的位置只记录不可恢复事实；未清除位置进补充工单。
 *  6. 作业取到旧归属后又收到裁定：未清除位置暂停并重新核对版本，已处理位置生成事故；暂停不是撤回成功。
 *  7. 两位复核员同时提交相反结论：仅接受基于当前证据版本的裁定（乐观锁，旧版本提交被拒）。
 *  8. 联系方式失效转人工证明；证明不足保持待核验；任何对外视图都不透露其他主体信息。
 */
@Service
public class AttributionService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.portal-token-minutes:30}")
    private int portalTokenMinutes;

    public AttributionService(NamedParameterJdbcTemplate jdbc, AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // 1. 扫描账号关系：手机号同号 → 待核验；证件指纹冲突 → 阻断
    // ==================================================================
    @Transactional
    public Map<String, Object> scanRelations(Principal principal) {
        List<Map<String, Object>> accounts = jdbc.queryForList(
            "SELECT account_id, subject_hash, phone_hash, email_hash, idcard_hash FROM biz.account ORDER BY account_id",
            Map.of());

        int pendingPhone = 0, blockedIdcard = 0;
        for (int i = 0; i < accounts.size(); i++) {
            for (int j = i + 1; j < accounts.size(); j++) {
                Map<String, Object> a = accounts.get(i);
                Map<String, Object> b = accounts.get(j);
                String accA = (String) a.get("account_id");
                String accB = (String) b.get("account_id");

                // 手机号相同：只生成待核验关系，绝不自动归并
                if (a.get("phone_hash") != null && a.get("phone_hash").equals(b.get("phone_hash"))) {
                    int n = upsertRelation(accA, accB, "PHONE", (String) a.get("phone_hash"),
                                           "PENDING_VERIFICATION");
                    if (n > 0) {
                        pendingPhone++;
                        upsertAttribution(accB, (String) b.get("subject_hash"), "PHONE",
                                          (String) a.get("phone_hash"), "PENDING_VERIFICATION", principal);
                        audit.log(null, principal.getName(), "PHONE_MATCH_PENDING",
                                  Map.of("accountA", accA, "accountB", accB,
                                         "note", "手机号相同仅生成待核验关系，不自动归并"));
                    }
                }

                // 证件指纹冲突：双方各执不同证件号 → 阻断归并
                if (a.get("idcard_hash") != null && b.get("idcard_hash") != null
                    && !a.get("idcard_hash").equals(b.get("idcard_hash"))) {
                    boolean sharedEmail = a.get("email_hash") != null
                                          && a.get("email_hash").equals(b.get("email_hash"));
                    boolean sharedPhone = a.get("phone_hash") != null
                                          && a.get("phone_hash").equals(b.get("phone_hash"));
                    if (sharedEmail || sharedPhone) {
                        String sharedHash = sharedEmail ? (String) a.get("email_hash") : (String) a.get("phone_hash");
                        int n = upsertRelation(accA, accB, "IDCARD_CONFLICT", sharedHash, "BLOCKED");
                        if (n > 0) {
                            blockedIdcard++;
                            upsertAttribution(accB, (String) b.get("subject_hash"), "IDCARD",
                                              (String) b.get("idcard_hash"), "BLOCKED_IDCARD_CONFLICT", principal);
                            audit.log(null, principal.getName(), "IDCARD_CONFLICT_BLOCKED",
                                      Map.of("accountA", accA, "accountB", accB,
                                             "note", "证件指纹冲突，阻断归并，转身份异议"));
                        }
                    }
                }
            }
        }
        return Map.of("pendingPhoneRelations", pendingPhone, "blockedIdcardConflicts", blockedIdcard);
    }

    private int upsertRelation(String accA, String accB, String type, String sharedHash, String status) {
        // 规范化账号顺序，保证唯一约束稳定
        String lo = accA.compareTo(accB) <= 0 ? accA : accB;
        String hi = accA.compareTo(accB) <= 0 ? accB : accA;
        return jdbc.update("""
            INSERT INTO compliance.account_relation
              (account_id_a, account_id_b, match_type, shared_hash, relation_status, evidence_version)
            VALUES (:a, :b, :t, :h, :st, 1)
            ON CONFLICT (account_id_a, account_id_b, match_type) DO NOTHING
            """, new MapSqlParameterSource().addValue("a", lo).addValue("b", hi)
                .addValue("t", type).addValue("h", sharedHash).addValue("st", status));
    }

    private void upsertAttribution(String accountId, String subjectHash, String basisType,
                                   String basisHash, String decision, Principal principal) {
        jdbc.update("""
            INSERT INTO compliance.account_attribution
              (account_id, subject_hash, evidence_version, decision_status, basis_type, basis_hash,
               decided_by, revision_no, detail_json)
            VALUES (:acc, :sh, 1, :dec, :bt, :bh, :by, 0, CAST(:d AS jsonb))
            ON CONFLICT (account_id, evidence_version, revision_no) DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("acc", accountId).addValue("sh", subjectHash).addValue("dec", decision)
            .addValue("bt", basisType).addValue("bh", basisHash)
            .addValue("by", principal == null ? "system" : principal.getName())
            .addValue("d", "{\"source\":\"relation-scan\"}"));
    }

    public List<Map<String, Object>> listRelations(String status) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (status != null && !status.isBlank()) {
            where = " WHERE relation_status = :st";
            p.addValue("st", status);
        }
        return jdbc.queryForList("""
            SELECT * FROM compliance.account_relation %s ORDER BY id
            """.formatted(where), p);
    }

    public List<Map<String, Object>> listAttribution(String accountId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (accountId != null && !accountId.isBlank()) {
            where = " WHERE account_id = :acc";
            p.addValue("acc", accountId);
        }
        return jdbc.queryForList("""
            SELECT * FROM compliance.account_attribution %s ORDER BY account_id, evidence_version, revision_no
            """.formatted(where), p);
    }

    // ==================================================================
    // 2. 候选人提交身份异议（重复异议合并补证，不新开单）
    // ==================================================================
    @Transactional
    public Map<String, Object> submitDispute(String subjectHash, String applicantAccount, String disputedAccount,
                                             String kind, String clue, Principal principal) {
        // 以业务库真实存在的账号主体为准；内部主体扫描（compliance.subject）不是异议的前置条件
        Integer subjectExists = jdbc.queryForObject(
            "SELECT count(*) FROM biz.account WHERE subject_hash = :h",
            new MapSqlParameterSource("h", subjectHash), Integer.class);
        if (subjectExists == null || subjectExists == 0) {
            throw ApiException.notFound("主体不存在，无法提交异议: " + subjectHash);
        }
        String actor = principal == null ? "applicant" : principal.getName();

        // 同一主体、同一类型且未结案的异议：合并补证，不重复开单
        Map<String, Object> open = jdbc.query("""
            SELECT id, dispute_no, status FROM compliance.identity_dispute
             WHERE subject_hash = :sh AND dispute_kind = :k
               AND status NOT IN ('CLOSED','ADJUDICATED','MERGED')
             ORDER BY id LIMIT 1
            """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("k", kind),
            rs -> rs.next() ? Map.of("id", rs.getLong("id"), "disputeNo", rs.getString("dispute_no"),
                                     "status", rs.getString("status")) : null);
        if (open != null) {
            long disputeId = ((Number) open.get("id")).longValue();
            int version = nextEvidenceVersion(disputeId);
            insertEvidence(disputeId, version, "ACCOUNT_CLUE",
                clue == null || clue.isBlank() ? "重复异议合并：申请人补充账号线索" : clue, actor);
            jdbc.update("UPDATE compliance.identity_dispute SET updated_at = now() WHERE id = :id",
                        new MapSqlParameterSource("id", disputeId));
            audit.log(null, actor, "DISPUTE_MERGED_SUPPLEMENT",
                      Map.of("disputeId", disputeId, "evidenceVersion", version));
            return Map.of("merged", true, "disputeId", disputeId,
                          "disputeNo", open.get("disputeNo"), "evidenceVersion", version);
        }

        String disputeNo = "DSP-" + LocalDate.now().toString().replace("-", "") + "-"
                           + Integer.toHexString((subjectHash + kind + random.nextLong()).hashCode()).toUpperCase();
        Long disputeId = jdbc.queryForObject("""
            INSERT INTO compliance.identity_dispute
              (dispute_no, subject_hash, applicant_account, disputed_account, dispute_kind, status,
               contact_status, current_evidence_version)
            VALUES (:no, :sh, :acc, :dacc, :k, 'SUBMITTED', 'REACHABLE', 1)
            RETURNING id
            """, new MapSqlParameterSource()
            .addValue("no", disputeNo).addValue("sh", subjectHash).addValue("acc", applicantAccount)
            .addValue("dacc", disputedAccount).addValue("k", kind), Long.class);
        insertEvidence(disputeId, 1, "ACCOUNT_CLUE",
            clue == null || clue.isBlank() ? "申请人初始账号线索" : clue, actor);
        audit.log(null, actor, "DISPUTE_SUBMITTED",
                  Map.of("disputeId", disputeId, "disputeNo", disputeNo, "kind", kind));
        return Map.of("merged", false, "disputeId", disputeId, "disputeNo", disputeNo,
                      "status", "SUBMITTED", "evidenceVersion", 1);
    }

    public List<Map<String, Object>> listDisputes(String status) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (status != null && !status.isBlank()) {
            where = " WHERE status = :st";
            p.addValue("st", status);
        }
        return jdbc.queryForList("""
            SELECT d.*, (SELECT count(*) FROM compliance.dispute_evidence e WHERE e.dispute_id = d.id) AS evidence_count
            FROM compliance.identity_dispute d %s ORDER BY d.id
            """.formatted(where), p);
    }

    public Map<String, Object> getDispute(long disputeId) {
        Map<String, Object> dispute = jdbc.query(
            "SELECT * FROM compliance.identity_dispute WHERE id = :id",
            new MapSqlParameterSource("id", disputeId), rs -> rs.next() ? rsRow(rs) : null);
        if (dispute == null) {
            throw ApiException.notFound("异议不存在: " + disputeId);
        }
        dispute.put("evidenceList", jdbc.queryForList(
            "SELECT * FROM compliance.dispute_evidence WHERE dispute_id = :id ORDER BY evidence_version, id",
            new MapSqlParameterSource("id", disputeId)));
        dispute.put("revisions", jdbc.queryForList(
            "SELECT * FROM compliance.attribution_revision WHERE dispute_id = :id ORDER BY revision_no",
            new MapSqlParameterSource("id", disputeId)));
        dispute.put("impactItems", jdbc.queryForList(
            "SELECT * FROM compliance.revision_impact_item WHERE revision_id IN "
            + "(SELECT id FROM compliance.attribution_revision WHERE dispute_id = :id) ORDER BY id",
            new MapSqlParameterSource("id", disputeId)));
        dispute.put("replies", jdbc.queryForList(
            "SELECT * FROM compliance.external_reply WHERE dispute_id = :id ORDER BY reply_version",
            new MapSqlParameterSource("id", disputeId)));
        return dispute;
    }

    /** 申请人/客服补交线索或证明材料（证据版本递增）。 */
    @Transactional
    public Map<String, Object> addEvidence(long disputeId, String clueType, String content, Principal principal) {
        requireDispute(disputeId);
        int version = nextEvidenceVersion(disputeId);
        long evidenceId = insertEvidence(disputeId, version, clueType, content,
                                         principal == null ? "applicant" : principal.getName());
        jdbc.update("UPDATE compliance.identity_dispute SET current_evidence_version = :v, updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("v", version).addValue("id", disputeId));
        audit.log(null, principal == null ? "applicant" : principal.getName(), "DISPUTE_EVIDENCE_ADDED",
                  Map.of("disputeId", disputeId, "evidenceVersion", version, "clueType", clueType));
        return Map.of("evidenceId", evidenceId, "evidenceVersion", version);
    }

    private long insertEvidence(long disputeId, int version, String clueType, String content, String actor) {
        return jdbc.queryForObject("""
            INSERT INTO compliance.dispute_evidence
              (dispute_id, evidence_version, clue_type, content, submitted_by, status)
            VALUES (:did, :v, :t, :c, :by, 'SUBMITTED') RETURNING id
            """, new MapSqlParameterSource().addValue("did", disputeId).addValue("v", version)
                .addValue("t", clueType).addValue("c", content).addValue("by", actor), Long.class);
    }

    private int nextEvidenceVersion(long disputeId) {
        Integer v = jdbc.queryForObject(
            "SELECT max(evidence_version) FROM compliance.dispute_evidence WHERE dispute_id = :id",
            new MapSqlParameterSource("id", disputeId), Integer.class);
        return (v == null ? 0 : v) + 1;
    }

    // ==================================================================
    // 3. 联系方式失效 → 转人工证明；证明不足保持待核验
    // ==================================================================
    @Transactional
    public Map<String, Object> markContactUnreachable(long disputeId, Principal principal) {
        Map<String, Object> d = requireDispute(disputeId);
        if ("CLOSED".equals(d.get("status")) || "ADJUDICATED".equals(d.get("status"))) {
            throw ApiException.conflict("已结案异议不能变更联系方式状态");
        }
        jdbc.update("""
            UPDATE compliance.identity_dispute
               SET status = 'AWAITING_MANUAL_PROOF', contact_status = 'UNREACHABLE_MANUAL_PROOF',
                   updated_at = now()
             WHERE id = :id
            """, new MapSqlParameterSource("id", disputeId));
        insertEvidence(disputeId, nextEvidenceVersion(disputeId), "CONTACT_FAIL",
                       "申请人预留联系方式失效，转人工身份证明通道", principal.getName());
        audit.log(null, principal.getName(), "CONTACT_UNREACHABLE_MANUAL_PROOF",
                  Map.of("disputeId", disputeId));
        return Map.of("disputeId", disputeId, "contactStatus", "UNREACHABLE_MANUAL_PROOF",
                      "status", "AWAITING_MANUAL_PROOF");
    }

    /** 客服审核人工证明：不足则维持待核验，不透露其他主体信息。 */
    @Transactional
    public Map<String, Object> reviewManualProof(long disputeId, boolean sufficient, String note, Principal principal) {
        Map<String, Object> d = requireDispute(disputeId);
        int version = nextEvidenceVersion(disputeId);
        long evidenceId = insertEvidence(disputeId, version, "MANUAL_PROOF",
            (note == null || note.isBlank() ? "人工身份证明材料" : note), principal.getName());
        if (!sufficient) {
            jdbc.update("""
                UPDATE compliance.dispute_evidence SET status = 'INSUFFICIENT', reviewed_by = :by, reviewed_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource().addValue("by", principal.getName()).addValue("id", evidenceId));
            // 证明不足：保持待核验（AWAITING_MANUAL_PROOF），不推进、不泄露其他主体
            jdbc.update("UPDATE compliance.identity_dispute SET updated_at = now() WHERE id = :id",
                        new MapSqlParameterSource("id", disputeId));
            audit.log(null, principal.getName(), "MANUAL_PROOF_INSUFFICIENT",
                      Map.of("disputeId", disputeId, "evidenceVersion", version));
            return Map.of("disputeId", disputeId, "sufficient", false,
                          "status", d.get("status"), "note", "证明不足，保持待核验");
        }
        jdbc.update("""
            UPDATE compliance.dispute_evidence SET status = 'ACCEPTED', reviewed_by = :by, reviewed_at = now()
             WHERE id = :id
            """, new MapSqlParameterSource().addValue("by", principal.getName()).addValue("id", evidenceId));
        jdbc.update("""
            UPDATE compliance.identity_dispute SET status = 'PENDING_VERIFICATION', updated_at = now() WHERE id = :id
            """, new MapSqlParameterSource("id", disputeId));
        audit.log(null, principal.getName(), "MANUAL_PROOF_ACCEPTED", Map.of("disputeId", disputeId));
        return Map.of("disputeId", disputeId, "sufficient", true, "status", "PENDING_VERIFICATION");
    }

    // ==================================================================
    // 4. 客服核验证据 → 交合规专员裁定
    // ==================================================================
    @Transactional
    public Map<String, Object> agentVerify(long disputeId, boolean accepted, String note, Principal principal) {
        Map<String, Object> d = requireDispute(disputeId);
        String status = (String) d.get("status");
        if ("AWAITING_MANUAL_PROOF".equals(status)) {
            throw ApiException.conflict("仍在等待人工证明且证明未被采纳，不能完成客服核验");
        }
        if (!"SUBMITTED".equals(status) && !"PENDING_VERIFICATION".equals(status)
            && !"MERGED".equals(status)) {
            throw ApiException.conflict("异议当前状态 " + status + " 不允许客服核验");
        }
        int version = nextEvidenceVersion(disputeId);
        insertEvidence(disputeId, version, "AGENT_NOTE",
            (note == null || note.isBlank() ? "客服核验结论 " + (accepted ? "采纳" : "存疑") : note),
            principal.getName());
        if (!accepted) {
            // 客服核验不通过：退回补证，保持待核验
            jdbc.update("UPDATE compliance.identity_dispute SET status = 'PENDING_VERIFICATION', updated_at = now() WHERE id = :id",
                        new MapSqlParameterSource("id", disputeId));
            audit.log(null, principal.getName(), "AGENT_VERIFY_PENDING", Map.of("disputeId", disputeId));
            return Map.of("disputeId", disputeId, "accepted", false, "status", "PENDING_VERIFICATION");
        }
        jdbc.update("""
            UPDATE compliance.identity_dispute
               SET status = 'ESCALATED_COMPLIANCE', current_evidence_version = :v, updated_at = now()
             WHERE id = :id
            """, new MapSqlParameterSource().addValue("v", version).addValue("id", disputeId));
        audit.log(null, principal.getName(), "AGENT_VERIFIED_ESCALATED",
                  Map.of("disputeId", disputeId, "evidenceVersion", version));
        return Map.of("disputeId", disputeId, "accepted", true,
                      "status", "ESCALATED_COMPLIANCE", "evidenceVersion", version);
    }

    // ==================================================================
    // 5. 合规裁定：乐观锁，只接受基于当前证据版本的裁定（并发相反结论之一被拒）
    // ==================================================================
    @Transactional
    public Map<String, Object> adjudicate(long disputeId, String decision, int baseEvidenceVersion,
                                          String rationale, Principal principal) {
        Map<String, Object> d = requireDispute(disputeId);
        if (!"ESCALATED_COMPLIANCE".equals(d.get("status"))) {
            throw ApiException.conflict("仅已升级合规（ESCALATED_COMPLIANCE）的异议可裁定，当前状态: " + d.get("status"));
        }
        int currentVersion = ((Number) d.get("current_evidence_version")).intValue();
        if (baseEvidenceVersion != currentVersion) {
            throw ApiException.conflict("证据版本已过期：提交基于 v" + baseEvidenceVersion
                + "，当前为 v" + currentVersion + "，请基于最新证据重新裁定");
        }
        // 乐观锁：两位复核员并发提交相反结论时，只有一个能成功
        int locked = jdbc.update("""
            UPDATE compliance.identity_dispute
               SET status = 'ADJUDICATED', updated_at = now(), closed_at = now()
             WHERE id = :id AND status = 'ESCALATED_COMPLIANCE' AND current_evidence_version = :v
            """, new MapSqlParameterSource().addValue("id", disputeId).addValue("v", currentVersion));
        if (locked == 0) {
            throw ApiException.conflict("并发裁定冲突：该异议已被其他复核员基于当前证据版本裁定");
        }

        Map<String, Object> result;
        if ("CORRECT".equals(decision)) {
            result = applyCorrection(d, currentVersion, rationale, principal);
        } else if ("UPHOLD".equals(decision)) {
            long revisionId = insertRevision(disputeId, currentVersion,
                (String) d.get("applicant_account"), (String) d.get("subject_hash"),
                (String) d.get("subject_hash"), "BLOCK_MERGE", rationale, "UPHOLD", principal);
            insertEvidence(disputeId, nextEvidenceVersion(disputeId), "RULING",
                "合规维持原归属判定（UPHOLD）：" + rationale, principal.getName());
            result = Map.of("disputeId", disputeId, "decision", "UPHOLD", "revisionId", revisionId);
        } else {
            throw ApiException.badRequest("裁定结论只能是 CORRECT 或 UPHOLD");
        }
        audit.log(null, principal.getName(), "DISPUTE_ADJUDICATED",
                  Map.of("disputeId", disputeId, "decision", decision,
                         "evidenceVersion", currentVersion));
        return result;
    }

    /** 纠错另建修订：旧归属判定保留，追加新版本；重算账号归属并逐项反查影响。 */
    private Map<String, Object> applyCorrection(Map<String, Object> dispute, int evidenceVersion,
                                                String rationale, Principal principal) {
        long disputeId = ((Number) dispute.get("id")).longValue();
        String accountId = (String) dispute.get("disputed_account");
        if (accountId == null || accountId.isBlank()) {
            accountId = (String) dispute.get("applicant_account");
        }
        String wrongSubject = (String) dispute.get("subject_hash");
        String naturalSubject = jdbc.queryForObject(
            "SELECT subject_hash FROM biz.account WHERE account_id = :acc",
            new MapSqlParameterSource("acc", accountId), String.class);
        if (naturalSubject == null) {
            throw ApiException.notFound("受影响账号不存在: " + accountId);
        }

        long revisionId = insertRevision(disputeId, evidenceVersion, accountId, wrongSubject,
            naturalSubject, "REASSIGN", rationale, "CORRECT", principal);

        // 5.1 旧归属判定不改写，仅打“被哪条新版本取代”的指针；追加新版本归属结论
        int newVersion = evidenceVersion + 1;
        jdbc.update("""
            UPDATE compliance.account_attribution SET superseded_by_id = :rid
             WHERE account_id = :acc AND superseded_by_id IS NULL
            """, new MapSqlParameterSource().addValue("rid", revisionId).addValue("acc", accountId));
        jdbc.update("""
            INSERT INTO compliance.account_attribution
              (account_id, subject_hash, evidence_version, decision_status, basis_type, basis_hash,
               decided_by, revision_no, superseded_by_id, detail_json)
            VALUES (:acc, :sh, :v, 'REASSIGNED', 'DISPUTE', NULL, :by, :rn, NULL, CAST(:d AS jsonb))
            """, new MapSqlParameterSource()
            .addValue("acc", accountId).addValue("sh", naturalSubject).addValue("v", newVersion)
            .addValue("by", principal.getName()).addValue("rn", evidenceVersion)
            .addValue("d", toJson(Map.of("revisionId", revisionId, "fromSubject", wrongSubject,
                                         "toSubject", naturalSubject))));

        // 5.2 重算受影响账号的当前归属（业务主表指针更正；历史快照/处置保留旧哈希不动）
        jdbc.update("UPDATE biz.account SET subject_hash = :to WHERE account_id = :acc",
                    new MapSqlParameterSource().addValue("to", naturalSubject).addValue("acc", accountId));

        // 5.3 账号关系状态：错误关联撤销/标记不同主体
        jdbc.update("""
            UPDATE compliance.account_relation SET relation_status = 'REVOKED', decided_by = :by,
                   decided_at = now(), note = '合规裁定撤销错误关联'
             WHERE (account_id_a = :acc OR account_id_b = :acc) AND relation_status IN
                   ('PENDING_VERIFICATION','BLOCKED','VERIFIED_SAME')
            """, new MapSqlParameterSource().addValue("by", principal.getName()).addValue("acc", accountId));

        // 5.4 旧版查询凭证在归属修订后立即失效
        int revokedTokens = jdbc.update("""
            UPDATE compliance.query_token SET status = 'REVOKED', invalidated_by_revision = :rid
             WHERE subject_hash IN (:wrong, :natural) AND status IN ('ISSUED','USED')
            """, new MapSqlParameterSource().addValue("rid", revisionId)
                .addValue("wrong", wrongSubject).addValue("natural", naturalSubject));

        // 5.5 逐项反查影响：历史删除范围、共享引用、答复引用
        int impactCount = buildImpactItems(revisionId, accountId, wrongSubject, naturalSubject);

        insertEvidence(disputeId, nextEvidenceVersion(disputeId), "RULING",
            "合规裁定更正归属（CORRECT）：账号 " + accountId + " 由 " + wrongSubject
            + " 重算至 " + naturalSubject + "；影响项 " + impactCount + " 条逐项处置中",
            principal.getName());

        return Map.of("disputeId", disputeId, "decision", "CORRECT", "revisionId", revisionId,
                      "accountId", accountId, "fromSubject", wrongSubject,
                      "toSubject", naturalSubject, "impactItems", impactCount,
                      "revokedTokens", revokedTokens, "newEvidenceVersion", newVersion);
    }

    private long insertRevision(long disputeId, int evidenceVersion, String accountId, String fromSubject,
                                String toSubject, String kind, String rationale, String ruling,
                                Principal principal) {
        Integer maxNo = jdbc.queryForObject(
            "SELECT max(revision_no) FROM compliance.attribution_revision WHERE dispute_id = :id",
            new MapSqlParameterSource("id", disputeId), Integer.class);
        int revisionNo = (maxNo == null ? 0 : maxNo) + 1;
        return jdbc.queryForObject("""
            INSERT INTO compliance.attribution_revision
              (dispute_id, revision_no, evidence_version, account_id, from_subject_hash, to_subject_hash,
               revision_kind, rationale, ruling_by, ruling_decision)
            VALUES (:did, :rn, :ev, :acc, :from, :to, :kind, :why, :by, :dec) RETURNING id
            """, new MapSqlParameterSource()
            .addValue("did", disputeId).addValue("rn", revisionNo).addValue("ev", evidenceVersion)
            .addValue("acc", accountId).addValue("from", fromSubject).addValue("to", toSubject)
            .addValue("kind", kind).addValue("why", rationale).addValue("by", principal.getName())
            .addValue("dec", ruling), Long.class);
    }

    /**
     * 逐项反查修订影响：
     *  - 已物理删除位置 → IRRECOVERABLE_DELETED（记录不可恢复事实）
     *  - 尚未清除位置 → SUPPLEMENTAL_DELETE（进补充工单）
     *  - 共享资料 → SHARED_REFERENCE_SPLIT（先拆引用再定处置）
     *  - 历史删除范围/答复引用 → 留痕引用项（原记录不改写，按日期可查回）
     */
    private int buildImpactItems(long revisionId, String accountId, String wrongSubject, String naturalSubject) {
        int count = 0;
        List<Map<String, Object>> misfiled = jdbc.queryForList("""
            SELECT * FROM biz.misfiled_record WHERE account_id = :acc ORDER BY id
            """, new MapSqlParameterSource("acc", accountId));
        for (Map<String, Object> m : misfiled) {
            String assetCode = (String) m.get("asset_code");
            String location = (String) m.get("location_ref");
            // 反查该错挂位置对应的历史处置记录（处置 location_ref 是资产目标表，故按资产+工单关联）
            Map<String, Object> disp = jdbc.query("""
                SELECT d.id, d.status FROM compliance.disposition_record d
                JOIN compliance.work_order w ON w.id = d.ticket_id
                 WHERE w.subject_hash = :sh AND d.asset_code = :code
                ORDER BY d.id LIMIT 1
                """, new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("code", assetCode),
                rs -> rs.next() ? Map.of("id", rs.getLong("id"), "status", rs.getString("status")) : null);
            // 以物理行是否仍存在为准判定是否已被清除（处置记录 location_ref 为表名，不能按对象键匹配）
            boolean physicallyGone = !locationStillPresent(assetCode, location, wrongSubject);

            if (physicallyGone) {
                Long dispId = disp == null ? null : ((Number) disp.get("id")).longValue();
                Long ticketId = disp == null ? null : jdbc.queryForObject(
                    "SELECT ticket_id FROM compliance.disposition_record WHERE id = :id",
                    new MapSqlParameterSource("id", dispId), Long.class);
                long impactId = insertImpact(revisionId, accountId, assetCode, location,
                    "IRRECOVERABLE_DELETED", "IRRECOVERABLE_RECORDED", dispId, null, ticketId,
                    "该位置已在原主体注销作业中物理删除，证据包保留当时删除依据，事实不可恢复");
                jdbc.update("UPDATE biz.misfiled_record SET record_status = 'PHYSICALLY_DELETED' WHERE id = :id",
                            new MapSqlParameterSource().addValue("id", m.get("id")));
                resolveImpactNote(impactId, "system", "不可恢复事实已登记，旧证据包可解释当时删除原因");
                count++;
            } else {
                // 未清除（SCHEDULED/PENDING/WAITING 或物理行仍在）→ 补充工单
                Long supplementId = jdbc.queryForObject("""
                    INSERT INTO compliance.supplement_ticket (original_ticket_id, asset_code, reason, status)
                    SELECT COALESCE((SELECT id FROM compliance.work_order WHERE subject_hash = :sh LIMIT 1), 0),
                           :code, :reason, 'PENDING'
                    RETURNING id
                    """, new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("code", assetCode)
                        .addValue("reason", "身份归属修订补充删除：" + location), Long.class);
                insertImpact(revisionId, accountId, assetCode, location,
                    "SUPPLEMENTAL_DELETE", "SUPPLEMENT_TICKETED",
                    disp == null ? null : ((Number) disp.get("id")).longValue(), supplementId, null,
                    "位置尚未清除，进入补充工单，按新归属补删");
                count++;
            }
        }

        // 共享简历引用：错误主体的引用须拆开；资料若仍被其他候选人合法引用则保留
        List<Map<String, Object>> sharedRefs = jdbc.queryForList("""
            SELECT DISTINCT resume_id FROM biz.resume_reference
             WHERE subject_hash = :sh AND reference_status = 'ACTIVE'
            """, new MapSqlParameterSource("sh", wrongSubject));
        for (Map<String, Object> ref : sharedRefs) {
            long resumeId = ((Number) ref.get("resume_id")).longValue();
            insertImpact(revisionId, accountId, "AST_RESUME", "resume_id=" + resumeId,
                "SHARED_REFERENCE_SPLIT", "SPLIT_PENDING_DISPOSITION", null, null, null,
                "拆开错误主体引用后，需确认该资料是否仍被其他候选人合法引用");
            count++;
        }

        // 历史删除范围引用：原工单快照/处置不改写，仅登记可追溯关系
        List<Map<String, Object>> historyScopes = jdbc.queryForList("""
            SELECT d.id, d.asset_code, d.location_ref, d.ticket_id FROM compliance.disposition_record d
            JOIN compliance.work_order w ON w.id = d.ticket_id
             WHERE w.subject_hash = :sh ORDER BY d.id
            """, new MapSqlParameterSource("sh", wrongSubject));
        for (Map<String, Object> h : historyScopes) {
            long impactId = insertImpact(revisionId, accountId, (String) h.get("asset_code"),
                (String) h.get("location_ref"), "HISTORY_SCOPE_REFERENCE", "NO_ACTION",
                ((Number) h.get("id")).longValue(), null, ((Number) h.get("ticket_id")).longValue(),
                "历史删除范围与证据包保留不改写，按日期可查回当时结论");
            resolveImpactNote(impactId, "system", "历史引用已留痕，旧证据包不变");
            count++;
        }

        // 对外答复引用：原答复保留可查回，待新答复生成时整体 supersede
        List<Map<String, Object>> priorReplies = jdbc.queryForList("""
            SELECT id, reply_no, reply_version FROM compliance.external_reply WHERE subject_hash = :sh
            """, new MapSqlParameterSource("sh", wrongSubject));
        for (Map<String, Object> r : priorReplies) {
            long impactId = insertImpact(revisionId, accountId, null, (String) r.get("reply_no"),
                "REPLY_REFERENCE", "NO_ACTION", null, null, ((Number) r.get("id")).longValue(),
                "原对外答复 " + r.get("reply_no") + " 按日期保留，待影响项全部办结后生成新版本");
            resolveImpactNote(impactId, "system", "原答复引用已登记，支持按日期查回");
            count++;
        }
        return count;
    }

    private long insertImpact(long revisionId, String accountId, String assetCode, String location,
                              String kind, String status, Long dispositionId, Long supplementId,
                              Long sourceRef, String note) {
        Map<String, Object> existing = jdbc.query("""
            SELECT id FROM compliance.revision_impact_item
             WHERE revision_id = :rid AND impact_kind = :k AND COALESCE(location_ref,'') = COALESCE(:loc,'')
            """, new MapSqlParameterSource().addValue("rid", revisionId).addValue("k", kind)
                .addValue("loc", location), rs -> rs.next() ? Map.of("id", rs.getLong(1)) : null);
        if (existing != null) {
            return ((Number) existing.get("id")).longValue();
        }
        if ("HISTORY_SCOPE_REFERENCE".equals(kind)) {
            return jdbc.queryForObject("""
                INSERT INTO compliance.revision_impact_item
                  (revision_id, account_id, asset_code, location_ref, impact_kind, item_status,
                   source_disposition_id, source_ticket_id, handling_note)
                VALUES (:rid, :acc, :code, :loc, :k, :st, :did, :tid, :note) RETURNING id
                """, new MapSqlParameterSource().addValue("rid", revisionId).addValue("acc", accountId)
                    .addValue("code", assetCode).addValue("loc", location).addValue("k", kind)
                    .addValue("st", status).addValue("did", dispositionId).addValue("tid", sourceRef)
                    .addValue("note", note), Long.class);
        }
        if ("REPLY_REFERENCE".equals(kind)) {
            return jdbc.queryForObject("""
                INSERT INTO compliance.revision_impact_item
                  (revision_id, account_id, asset_code, location_ref, impact_kind, item_status,
                   source_reply_id, handling_note)
                VALUES (:rid, :acc, :code, :loc, :k, :st, :rid2, :note) RETURNING id
                """, new MapSqlParameterSource().addValue("rid", revisionId).addValue("acc", accountId)
                    .addValue("code", assetCode).addValue("loc", location).addValue("k", kind)
                    .addValue("st", status).addValue("rid2", sourceRef).addValue("note", note), Long.class);
        }
        return jdbc.queryForObject("""
            INSERT INTO compliance.revision_impact_item
              (revision_id, account_id, asset_code, location_ref, impact_kind, item_status,
               source_disposition_id, supplement_ticket_id, handling_note)
            VALUES (:rid, :acc, :code, :loc, :k, :st, :did, :sid, :note) RETURNING id
            """, new MapSqlParameterSource().addValue("rid", revisionId).addValue("acc", accountId)
                .addValue("code", assetCode).addValue("loc", location).addValue("k", kind)
                .addValue("st", status).addValue("did", dispositionId).addValue("sid", supplementId)
                .addValue("note", note), Long.class);
    }

    private void resolveImpactNote(long impactId, String actor, String note) {
        jdbc.update("""
            UPDATE compliance.revision_impact_item SET handled_by = :by, handled_at = now(),
                   handling_note = COALESCE(handling_note,'') || :note
             WHERE id = :id
            """, new MapSqlParameterSource().addValue("by", actor).addValue("note", " | " + note)
                .addValue("id", impactId));
    }

    // ==================================================================
    // 6. 影响项逐项处置：拆引用 / 补充删除 / 不可恢复登记
    // ==================================================================
    @Transactional
    public Map<String, Object> splitSharedReference(long impactId, Principal principal) {
        Map<String, Object> item = requireImpact(impactId);
        if (!"SHARED_REFERENCE_SPLIT".equals(item.get("impact_kind"))) {
            throw ApiException.conflict("该影响项不是共享引用类型，不能拆引用");
        }
        long revisionId = ((Number) item.get("revision_id")).longValue();
        Map<String, Object> revision = jdbc.queryForObject(
            "SELECT * FROM compliance.attribution_revision WHERE id = :id",
            new MapSqlParameterSource("id", revisionId), (rs, n) -> rsRow(rs));
        String wrongSubject = (String) revision.get("from_subject_hash");

        long resumeId = Long.parseLong(((String) item.get("location_ref")).replace("resume_id=", ""));
        // 仅拆开错误主体的引用，不动简历本身
        int detached = jdbc.update("""
            UPDATE biz.resume_reference SET reference_status = 'DETACHED'
             WHERE resume_id = :rid AND subject_hash = :sh AND reference_status = 'ACTIVE'
            """, new MapSqlParameterSource().addValue("rid", resumeId).addValue("sh", wrongSubject));
        Integer stillReferenced = jdbc.queryForObject(
            "SELECT count(*) FROM biz.resume_reference WHERE resume_id = :rid AND reference_status = 'ACTIVE'",
            new MapSqlParameterSource("rid", resumeId), Integer.class);
        String note = "已拆开错误主体引用 " + detached + " 条；该资料仍被 " + stillReferenced
                      + " 个合法候选人引用，资料本体保留，不做整份清除";
        // 简历本体仍被合法引用 → 保留；若无任何合法引用则可由补充工单清除（此处先保留并留痕）
        jdbc.update("""
            UPDATE compliance.revision_impact_item SET item_status = 'DISPOSED', handled_by = :by,
                   handled_at = now(), handling_note = :note WHERE id = :id
            """, new MapSqlParameterSource().addValue("by", principal.getName())
                .addValue("note", note).addValue("id", impactId));
        audit.log(null, principal.getName(), "SHARED_REFERENCE_SPLIT",
                  Map.of("impactId", impactId, "resumeId", resumeId, "stillReferenced", stillReferenced));
        return Map.of("impactId", impactId, "detached", detached,
                      "stillReferencedByOthers", stillReferenced, "kept", stillReferenced != null && stillReferenced > 0);
    }

    @Transactional
    public Map<String, Object> executeSupplementalDelete(long impactId, Principal principal) {
        Map<String, Object> item = requireImpact(impactId);
        if (!"SUPPLEMENTAL_DELETE".equals(item.get("impact_kind"))) {
            throw ApiException.conflict("该影响项不是补充删除类型");
        }
        String assetCode = (String) item.get("asset_code");
        String location = (String) item.get("location_ref");
        long revisionId = ((Number) item.get("revision_id")).longValue();
        Map<String, Object> revision = jdbc.queryForObject(
            "SELECT * FROM compliance.attribution_revision WHERE id = :id",
            new MapSqlParameterSource("id", revisionId), (rs, n) -> rsRow(rs));
        String wrongSubject = (String) revision.get("from_subject_hash");

        int deleted = deleteMisfiledLocation(assetCode, location, wrongSubject);
        jdbc.update("UPDATE biz.misfiled_record SET record_status = 'SUPPLEMENT_DELETED' WHERE asset_code = :code AND location_ref = :loc",
                    new MapSqlParameterSource().addValue("code", assetCode).addValue("loc", location));
        if (item.get("supplement_ticket_id") != null) {
            jdbc.update("UPDATE compliance.supplement_ticket SET status = 'EXECUTED', executed_at = now() WHERE id = :id",
                        new MapSqlParameterSource("id", item.get("supplement_ticket_id")));
        }
        String idem = "REVISION-" + revisionId + "-IMPACT-" + impactId;
        jdbc.update("""
            INSERT INTO compliance.disposition_record
              (ticket_id, snapshot_item_id, asset_code, location_ref, category, status, idempotency_key,
               before_value, after_value, recheck_hash, detail, executed_at, evidence_version)
            SELECT COALESCE((SELECT id FROM compliance.work_order WHERE subject_hash = :sh LIMIT 1), 0),
                   0, :code, :loc, 'PHYSICAL_DELETE', 'EXECUTED', :k, :b, '0', :rh,
                   CAST(:d AS jsonb), now(), :ev
            ON CONFLICT (idempotency_key) DO NOTHING
            """, new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("code", assetCode)
                .addValue("loc", location).addValue("k", idem).addValue("b", String.valueOf(deleted))
                .addValue("rh", HashUtil.sha256("revision:" + location))
                .addValue("d", toJson(Map.of("revisionId", revisionId, "impactId", impactId,
                                             "supplemental", true)))
                .addValue("ev", ((Number) revision.get("evidence_version")).intValue()));
        jdbc.update("""
            UPDATE compliance.revision_impact_item SET item_status = 'DISPOSED', handled_by = :by,
                   handled_at = now(), handling_note = COALESCE(handling_note,'') || :note WHERE id = :id
            """, new MapSqlParameterSource().addValue("by", principal.getName())
                .addValue("note", " | 补充删除完成，删除行数 " + deleted).addValue("id", impactId));
        audit.log(null, principal.getName(), "SUPPLEMENTAL_DELETE_EXECUTED",
                  Map.of("impactId", impactId, "assetCode", assetCode, "deleted", deleted));
        return Map.of("impactId", impactId, "deleted", deleted, "status", "DISPOSED");
    }

    private int deleteMisfiledLocation(String assetCode, String location, String wrongSubject) {
        return switch (assetCode) {
            case "AST_EXPORT" -> jdbc.update(
                "DELETE FROM biz.data_export WHERE subject_hash = :sh AND export_path = :loc",
                new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("loc", location));
            case "AST_BACKUP" -> jdbc.update(
                "DELETE FROM biz.backup_archive WHERE subject_hash = :sh AND archive_path = :loc",
                new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("loc", location));
            case "AST_PDF" -> jdbc.update(
                "DELETE FROM biz.resume_pdf WHERE subject_hash = :sh AND object_key = :loc",
                new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("loc", location));
            case "AST_SEARCH" -> jdbc.update(
                "DELETE FROM biz.search_index_doc WHERE subject_hash = :sh AND doc_ref = :loc",
                new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("loc", location));
            default -> 0;
        };
    }

    /** 判定错挂物理行是否仍存在（存在 = 尚未清除，可进补充工单；不存在 = 已物理删除，不可恢复）。 */
    private boolean locationStillPresent(String assetCode, String location, String wrongSubject) {
        Integer c = switch (assetCode) {
            case "AST_EXPORT" -> jdbc.queryForObject(
                "SELECT count(*) FROM biz.data_export WHERE subject_hash = :sh AND export_path = :loc",
                new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("loc", location), Integer.class);
            case "AST_BACKUP" -> jdbc.queryForObject(
                "SELECT count(*) FROM biz.backup_archive WHERE subject_hash = :sh AND archive_path = :loc",
                new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("loc", location), Integer.class);
            case "AST_PDF" -> jdbc.queryForObject(
                "SELECT count(*) FROM biz.resume_pdf WHERE subject_hash = :sh AND object_key = :loc",
                new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("loc", location), Integer.class);
            case "AST_SEARCH" -> jdbc.queryForObject(
                "SELECT count(*) FROM biz.search_index_doc WHERE subject_hash = :sh AND doc_ref = :loc",
                new MapSqlParameterSource().addValue("sh", wrongSubject).addValue("loc", location), Integer.class);
            default -> 0;
        };
        return c != null && c > 0;
    }

    @Transactional
    public Map<String, Object> recordIrrecoverable(long impactId, String note, Principal principal) {
        Map<String, Object> item = requireImpact(impactId);
        jdbc.update("""
            UPDATE compliance.revision_impact_item SET item_status = 'IRRECOVERABLE_RECORDED',
                   handled_by = :by, handled_at = now(), handling_note = COALESCE(handling_note,'') || :note
             WHERE id = :id
            """, new MapSqlParameterSource().addValue("by", principal.getName())
                .addValue("note", " | " + (note == null ? "确认不可恢复" : note)).addValue("id", impactId));
        audit.log(null, principal.getName(), "IRRECOVERABLE_RECORDED", Map.of("impactId", impactId));
        return Map.of("impactId", impactId, "status", "IRRECOVERABLE_RECORDED");
    }

    @Transactional
    public Map<String, Object> resolveImpact(long impactId, String note, Principal principal) {
        requireImpact(impactId);
        jdbc.update("""
            UPDATE compliance.revision_impact_item SET item_status = 'RESOLVED', handled_by = :by,
                   handled_at = now(), handling_note = COALESCE(handling_note,'') || :note WHERE id = :id
            """, new MapSqlParameterSource().addValue("by", principal.getName())
                .addValue("note", " | " + (note == null ? "人工确认办结" : note)).addValue("id", impactId));
        return Map.of("impactId", impactId, "status", "RESOLVED");
    }

    public List<Map<String, Object>> listImpactItems(Long revisionId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (revisionId != null) {
            where = " WHERE revision_id = :rid";
            p.addValue("rid", revisionId);
        }
        return jdbc.queryForList("""
            SELECT * FROM compliance.revision_impact_item %s ORDER BY id
            """.formatted(where), p);
    }

    // ==================================================================
    // 7. 作业取到旧归属后又收到裁定：暂停未清除位置、已处理位置登记事故
    // ==================================================================
    @Transactional
    public Map<String, Object> raiseExecutionIncident(long ticketId, long revisionId, Principal principal) {
        requireTicket(ticketId);
        Map<String, Object> revision = jdbc.query(
            "SELECT * FROM compliance.attribution_revision WHERE id = :id",
            new MapSqlParameterSource("id", revisionId), rs -> rs.next() ? rsRow(rs) : null);
        if (revision == null) {
            throw ApiException.notFound("归属修订不存在: " + revisionId);
        }
        int actualVersion = ((Number) revision.get("evidence_version")).intValue();
        String accountId = (String) revision.get("account_id");

        List<Map<String, Object>> records = jdbc.queryForList(
            "SELECT * FROM compliance.disposition_record WHERE ticket_id = :id ORDER BY id",
            new MapSqlParameterSource("id", ticketId));
        int paused = 0, accidents = 0;
        for (Map<String, Object> d : records) {
            long dispositionId = ((Number) d.get("id")).longValue();
            int expectedVersion = d.get("evidence_version") == null ? 1 : ((Number) d.get("evidence_version")).intValue();
            String st = (String) d.get("status");
            boolean uncleared = "PENDING".equals(st) || "PREVIEWED".equals(st) || "SCHEDULED".equals(st)
                                 || "WAITING_RECEIPT".equals(st) || "FAILED".equals(st);
            if (uncleared) {
                // 未清除：暂停并等待按新版本重新核对，绝不显示成撤回成功
                jdbc.update("""
                    UPDATE compliance.disposition_record SET pause_status = 'PAUSED_VERSION_STALE', paused_at = now()
                     WHERE id = :id AND pause_status IS NULL
                    """, new MapSqlParameterSource("id", dispositionId));
                insertIncident(ticketId, dispositionId, revisionId, accountId, "PAUSED_VERSION_STALE",
                               expectedVersion, actualVersion,
                               "位置尚未清除，已暂停，待按证据版本 v" + actualVersion + " 重新核对");
                paused++;
            } else if ("EXECUTED".equals(st) || "RECEIPT_RECEIVED".equals(st)) {
                // 已处理：生成事故记录
                insertIncident(ticketId, dispositionId, revisionId, accountId, "PROCESSED_ACCIDENT",
                               expectedVersion, actualVersion,
                               "位置已按旧归属处理，无法按撤回处理，登记事故并进入影响项补救");
                accidents++;
            }
        }
        audit.log(null, principal.getName(), "EXECUTION_INCIDENT_RAISED",
                  Map.of("ticketId", ticketId, "revisionId", revisionId, "paused", paused, "accidents", accidents));
        return Map.of("ticketId", ticketId, "paused", paused, "processedAccidents", accidents,
                      "note", "暂停不等于撤回成功；已处理位置走事故与影响项补救");
    }

    private void insertIncident(long ticketId, Long dispositionId, long revisionId, String accountId,
                                String kind, int expected, int actual, String detail) {
        jdbc.update("""
            INSERT INTO compliance.execution_incident
              (ticket_id, disposition_id, revision_id, account_id, incident_kind, expected_version,
               actual_version, incident_status, detail)
            VALUES (:tid, :did, :rid, :acc, :k, :e, :a, 'OPEN', :d)
            """, new MapSqlParameterSource().addValue("tid", ticketId).addValue("did", dispositionId)
                .addValue("rid", revisionId).addValue("acc", accountId).addValue("k", kind)
                .addValue("e", expected).addValue("a", actual).addValue("d", detail));
    }

    /** 暂停位置按最新证据版本重新核对后恢复（核对版本一致才允许）。 */
    @Transactional
    public Map<String, Object> resumePausedPosition(long dispositionId, Principal principal) {
        Map<String, Object> d = jdbc.query(
            "SELECT * FROM compliance.disposition_record WHERE id = :id",
            new MapSqlParameterSource("id", dispositionId), rs -> rs.next() ? rsRow(rs) : null);
        if (d == null) {
            throw ApiException.notFound("处置记录不存在: " + dispositionId);
        }
        if (!"PAUSED_VERSION_STALE".equals(d.get("pause_status"))) {
            throw ApiException.conflict("该位置不处于版本过期暂停状态");
        }
        jdbc.update("""
            UPDATE compliance.disposition_record
               SET pause_status = NULL, paused_at = NULL,
                   evidence_version = COALESCE((
                     SELECT max(aa.evidence_version)
                       FROM compliance.attribution_revision ar
                       JOIN compliance.account_attribution aa
                         ON aa.account_id = ar.account_id AND aa.superseded_by_id = ar.id
                      WHERE ar.id = (SELECT max(revision_id) FROM compliance.execution_incident
                                      WHERE disposition_id = :id)), evidence_version)
             WHERE id = :id
            """, new MapSqlParameterSource("id", dispositionId));
        jdbc.update("""
            UPDATE compliance.execution_incident SET incident_status = 'RESOLVED', resolved_at = now(),
                   resolved_by = :by WHERE disposition_id = :id AND incident_kind = 'PAUSED_VERSION_STALE'
            """, new MapSqlParameterSource().addValue("by", principal.getName()).addValue("id", dispositionId));
        audit.log(null, principal.getName(), "PAUSED_POSITION_RESUMED",
                  Map.of("dispositionId", dispositionId));
        return Map.of("dispositionId", dispositionId, "resumed", true);
    }

    public List<Map<String, Object>> listIncidents(Long ticketId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (ticketId != null) {
            where = " WHERE ticket_id = :tid";
            p.addValue("tid", ticketId);
        }
        return jdbc.queryForList("""
            SELECT * FROM compliance.execution_incident %s ORDER BY id
            """.formatted(where), p);
    }

    // ==================================================================
    // 8. 对外答复生成前置条件：该异议涉及的全部影响项均有明确处理结果
    // ==================================================================
    public boolean allImpactResolved(long disputeId) {
        Long pending = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.revision_impact_item
             WHERE revision_id IN (SELECT id FROM compliance.attribution_revision WHERE dispute_id = :id)
               AND item_status IN ('PENDING','SPLIT_PENDING_DISPOSITION','SUPPLEMENT_TICKETED')
            """, new MapSqlParameterSource("id", disputeId), Long.class);
        return pending == null || pending == 0;
    }

    // ====================== 辅助 ======================
    private Map<String, Object> requireDispute(long disputeId) {
        Map<String, Object> d = jdbc.query("SELECT * FROM compliance.identity_dispute WHERE id = :id",
            new MapSqlParameterSource("id", disputeId), rs -> rs.next() ? rsRow(rs) : null);
        if (d == null) {
            throw ApiException.notFound("异议不存在: " + disputeId);
        }
        return d;
    }

    private Map<String, Object> requireImpact(long impactId) {
        Map<String, Object> item = jdbc.query("SELECT * FROM compliance.revision_impact_item WHERE id = :id",
            new MapSqlParameterSource("id", impactId), rs -> rs.next() ? rsRow(rs) : null);
        if (item == null) {
            throw ApiException.notFound("影响项不存在: " + impactId);
        }
        return item;
    }

    private void requireTicket(long ticketId) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM compliance.work_order WHERE id = :id",
                                        new MapSqlParameterSource("id", ticketId), Integer.class);
        if (c == null || c == 0) {
            throw ApiException.notFound("工单不存在: " + ticketId);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private Map<String, Object> rsRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        int cols = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= cols; i++) {
            String typeName = rs.getMetaData().getColumnTypeName(i);
            if (typeName != null && typeName.toLowerCase().contains("timestamptz")) {
                m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i, OffsetDateTime.class));
            } else if (typeName != null && typeName.toLowerCase().contains("timestamp")) {
                m.put(rs.getMetaData().getColumnLabel(i),
                      rs.getTimestamp(i) == null ? null : rs.getTimestamp(i).toInstant());
            } else {
                m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
            }
        }
        return m;
    }

    /** 供门户服务读取凭证有效期配置。 */
    public int getPortalTokenMinutes() {
        return portalTokenMinutes;
    }

    /** 生成不可预测的限时凭证号。 */
    public String newTokenRef(String subjectHash) {
        byte[] seed = new byte[16];
        random.nextBytes(seed);
        return "QTT-" + HashUtil.sha256(subjectHash + OffsetDateTime.now()
                                        + java.util.HexFormat.of().formatHex(seed));
    }

    /** 门户视图只返回本主体信息，账号关系只露账号与状态，绝不带出其他主体标识值。 */
    public List<Map<String, Object>> relationsForSubject(String subjectHash) {
        List<Map<String, Object>> accounts = jdbc.queryForList(
            "SELECT account_id FROM biz.account WHERE subject_hash = :sh",
            new MapSqlParameterSource("sh", subjectHash));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : accounts) {
            String acc = (String) a.get("account_id");
            out.addAll(jdbc.queryForList("""
                SELECT id, account_id_a, account_id_b, match_type, relation_status, evidence_version
                FROM compliance.account_relation
                 WHERE account_id_a = :acc OR account_id_b = :acc ORDER BY id
                """, new MapSqlParameterSource("acc", acc)));
        }
        return out;
    }
}
