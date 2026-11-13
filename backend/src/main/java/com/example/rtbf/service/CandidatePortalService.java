package com.example.rtbf.service;

import com.example.rtbf.util.HashUtil;
import com.example.rtbf.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 候选人查询门户（免登录，凭限时凭证访问）。
 * 隐私边界：
 *  - 凭证须经“账号 + 标识符知识校验”签发，限时有效；归属修订后旧凭证立即失效。
 *  - 所有视图只返回凭证所属主体的数据，绝不透露其他主体的标识值、账号或资料。
 *  - 隐藏敏感信息（证件号等）只给办理说明，不回显明文。
 */
@Service
public class CandidatePortalService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final AttributionService attribution;

    public CandidatePortalService(NamedParameterJdbcTemplate jdbc, AuditService audit,
                                  AttributionService attribution) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.attribution = attribution;
    }

    public record TokenIssueRequest(String accountId, String idType, String idValue, String purpose) {
    }

    /** 账号 + 标识符知识校验通过后签发限时凭证；不回显任何敏感明文。 */
    @Transactional
    public Map<String, Object> issueToken(TokenIssueRequest req, Principal principal) {
        Map<String, Object> account = jdbc.query("""
            SELECT account_id, subject_hash, phone_hash, email_hash, idcard_hash, name
              FROM biz.account WHERE account_id = :acc
            """, new MapSqlParameterSource("acc", req.accountId()), rs -> rs.next() ? row(rs) : null);
        if (account == null) {
            // 不暴露账号是否存在：统一按校验失败处理
            throw ApiException.badRequest("身份校验失败：账号或标识符不匹配");
        }
        String provided = HashUtil.sha256(req.idValue() == null ? "" : req.idValue().trim());
        String stored = switch (req.idType() == null ? "PHONE" : req.idType()) {
            case "EMAIL" -> (String) account.get("email_hash");
            case "IDCARD" -> (String) account.get("idcard_hash");
            default -> (String) account.get("phone_hash");
        };
        if (stored == null || !stored.equals(provided)) {
            audit.log(null, "applicant", "PORTAL_TOKEN_VERIFY_FAILED",
                      Map.of("accountId", req.accountId(), "idType", req.idType()));
            throw ApiException.badRequest("身份校验失败：账号或标识符不匹配");
        }
        String subjectHash = (String) account.get("subject_hash");
        String purpose = req.purpose() == null ? "PORTAL_INSTRUCTION" : req.purpose();
        OffsetDateTime now = OffsetDateTime.now();
        String tokenRef = attribution.newTokenRef(subjectHash);
        Long id = jdbc.queryForObject("""
            INSERT INTO compliance.query_token
              (token_ref, subject_hash, purpose, status, issued_at, expires_at, created_by)
            VALUES (:ref, :sh, :p, 'ISSUED', :now, :exp, 'applicant')
            RETURNING id
            """, new MapSqlParameterSource().addValue("ref", tokenRef).addValue("sh", subjectHash)
                .addValue("p", purpose).addValue("now", now)
                .addValue("exp", now.plusMinutes(attribution.getPortalTokenMinutes())), Long.class);
        audit.log(null, "applicant", "PORTAL_TOKEN_ISSUED",
                  Map.of("tokenId", id, "accountId", req.accountId(), "purpose", purpose));
        return Map.of("tokenRef", tokenRef, "subjectHash", subjectHash,
                      "issuedAt", now, "expiresAt", now.plusMinutes(attribution.getPortalTokenMinutes()),
                      "expiresInMinutes", attribution.getPortalTokenMinutes());
    }

    /** 校验凭证：存在、未过期、未被撤销/修订失效；惰性标记过期。 */
    private Map<String, Object> requireValidToken(String tokenRef) {
        Map<String, Object> token = jdbc.query("""
            SELECT * FROM compliance.query_token WHERE token_ref = :ref
            """, new MapSqlParameterSource("ref", tokenRef), rs -> rs.next() ? row(rs) : null);
        if (token == null) {
            throw ApiException.notFound("凭证不存在");
        }
        OffsetDateTime expiresAt = (OffsetDateTime) token.get("expires_at");
        String status = (String) token.get("status");
        if ("REVOKED".equals(status)) {
            throw ApiException.conflict("该查询凭证已因归属修订失效，请重新核验身份获取新凭证");
        }
        if (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now())) {
            jdbc.update("UPDATE compliance.query_token SET status = 'EXPIRED' WHERE id = :id AND status = 'ISSUED'",
                        new MapSqlParameterSource("id", token.get("id")));
            throw ApiException.conflict("查询凭证已过期，请重新核验身份获取新凭证");
        }
        if ("EXPIRED".equals(status)) {
            throw ApiException.conflict("查询凭证已过期，请重新核验身份获取新凭证");
        }
        return token;
    }

    /** 限时查看隐藏敏感信息的办理说明（不回显明文，不涉及其他主体）。 */
    @Transactional
    public Map<String, Object> viewInstruction(String tokenRef) {
        Map<String, Object> token = requireValidToken(tokenRef);
        String subjectHash = (String) token.get("subject_hash");
        if (!"USED".equals(token.get("status"))) {
            jdbc.update("UPDATE compliance.query_token SET status = 'USED', used_at = now() WHERE id = :id",
                        new MapSqlParameterSource("id", token.get("id")));
        }
        List<Map<String, Object>> ownAccounts = jdbc.queryForList(
            "SELECT account_id, name FROM biz.account WHERE subject_hash = :sh ORDER BY account_id",
            new MapSqlParameterSource("sh", subjectHash));
        // 主体状态可能尚未经内部扫描生成：门户以业务账号数据为准，主体状态可选关联，不因此报错
        Map<String, Object> subjectRow = jdbc.query("""
            SELECT display_name, status FROM compliance.subject WHERE subject_hash = :sh
            """, new MapSqlParameterSource("sh", subjectHash), rs -> rs.next() ? Map.of(
                "displayName", rs.getString(1), "status", rs.getString(2)) : null);
        String displayName = subjectRow != null ? (String) subjectRow.get("displayName")
            : (ownAccounts.isEmpty() ? "申请人" : (String) ownAccounts.get(0).get("name"));
        String subjectStatus = subjectRow != null ? (String) subjectRow.get("status") : "NOT_SCANNED";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", Map.of("displayName", displayName, "status", subjectStatus));
        out.put("ownAccounts", ownAccounts);
        out.put("instruction", """
            【隐藏敏感信息办理说明】
            1. 证件号等敏感字段在库内仅以哈希/脱敏形式保存，本入口不展示明文，也不会要求您在留言中填写完整证件号。
            2. 如发现另一份简历漏删，或他人资料算到您名下，请在本入口提交“身份异议”，并补交可核验的账号线索（注册手机号、常用邮箱、账号 ID）。
            3. 手机号相同只会生成待核验关系，不会自动合并账号；证件指纹冲突将阻断归并，由客服核验证据后交合规专员裁定。
            4. 纠错会保留每次归属判定与证据版本，旧结论不被改写；您可凭本凭证追踪办理状态与对外答复版本。
            5. 若预留联系方式失效，将转人工身份证明通道；证明不足时案件保持待核验，不会向您透露其他主体的任何信息。""");
        out.put("viewedAt", OffsetDateTime.now());
        out.put("expiresAt", token.get("expires_at"));
        audit.log(null, "applicant", "PORTAL_INSTRUCTION_VIEWED",
                  Map.of("subjectHash", subjectHash, "tokenId", token.get("id")));
        return out;
    }

    /** 申请人凭凭证提交身份异议/线索（重复异议自动合并补证）。 */
    @Transactional
    public Map<String, Object> submitDispute(String tokenRef, String kind, String disputedAccount,
                                             String wrongSubjectHash, String clue) {
        Map<String, Object> token = requireValidToken(tokenRef);
        String tokenSubject = (String) token.get("subject_hash");
        String applicantAccount = jdbc.queryForObject(
            "SELECT min(account_id) FROM biz.account WHERE subject_hash = :sh",
            new MapSqlParameterSource("sh", tokenSubject), String.class);
        // 申请人可声明“资料被错挂到另一主体”；仅接受业务库真实存在的主体哈希，不回显该主体任何信息
        String caseSubject = tokenSubject;
        if (wrongSubjectHash != null && !wrongSubjectHash.isBlank() && !wrongSubjectHash.equals(tokenSubject)) {
            Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM biz.account WHERE subject_hash = :h",
                new MapSqlParameterSource("h", wrongSubjectHash), Integer.class);
            if (exists == null || exists == 0) {
                throw ApiException.badRequest("异议指向的主体不存在");
            }
            caseSubject = wrongSubjectHash;
        }
        return attribution.submitDispute(caseSubject, applicantAccount, disputedAccount, kind, clue, null);
    }

    /** 申请人凭凭证补交线索（证据版本递增）。 */
    @Transactional
    public Map<String, Object> submitClue(String tokenRef, long disputeId, String clueType, String content) {
        Map<String, Object> token = requireValidToken(tokenRef);
        String subjectHash = (String) token.get("subject_hash");
        // 只能补交本主体名下异议，禁止跨主体访问
        Integer own = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.identity_dispute WHERE id = :id AND subject_hash = :sh",
            new MapSqlParameterSource().addValue("id", disputeId).addValue("sh", subjectHash), Integer.class);
        if (own == null || own == 0) {
            throw ApiException.notFound("异议不存在或不属于当前凭证主体");
        }
        String type = clueType == null ? "ACCOUNT_CLUE" : clueType;
        return attribution.addEvidence(disputeId, type, content, null);
    }

    /** 办理追踪：只返回凭证主体的异议、证据版本与对外答复，绝不带出其他主体信息。 */
    @Transactional
    public Map<String, Object> track(String tokenRef) {
        Map<String, Object> token = requireValidToken(tokenRef);
        String subjectHash = (String) token.get("subject_hash");
        List<Map<String, Object>> disputes = jdbc.queryForList("""
            SELECT id, dispute_no, dispute_kind, status, contact_status, current_evidence_version,
                   created_at, updated_at, closed_at
              FROM compliance.identity_dispute
             WHERE subject_hash = :sh
                OR applicant_account IN (SELECT account_id FROM biz.account WHERE subject_hash = :sh)
             ORDER BY id
            """, new MapSqlParameterSource("sh", subjectHash));
        for (Map<String, Object> d : disputes) {
            long disputeId = ((Number) d.get("id")).longValue();
            d.put("evidenceList", jdbc.queryForList("""
                SELECT evidence_version, clue_type, status, submitted_by, created_at
                  FROM compliance.dispute_evidence WHERE dispute_id = :id ORDER BY evidence_version, id
                """, new MapSqlParameterSource("id", disputeId)));
            d.put("replies", jdbc.queryForList("""
                SELECT id, reply_no, reply_version, reply_status, title, issued_at
                  FROM compliance.external_reply WHERE dispute_id = :id ORDER BY reply_version
                """, new MapSqlParameterSource("id", disputeId)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subjectHash", subjectHash);
        out.put("relations", attribution.relationsForSubject(subjectHash));
        out.put("disputes", disputes);
        out.put("trackedAt", OffsetDateTime.now());
        return out;
    }

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
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
}
