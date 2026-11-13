package com.example.rtbf.service;

import com.example.rtbf.util.HashUtil;
import com.example.rtbf.web.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 对外答复独立维护状态与版本：
 *  - 只有异议裁定涉及的全部影响项均取得明确处理结果，才允许生成新的对外说明。
 *  - 新答复生成后，旧答复标记 SUPERSEDED 但物理保留，原答复可按日期查回。
 */
@Service
public class ExternalReplyService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final AttributionService attribution;

    public ExternalReplyService(NamedParameterJdbcTemplate jdbc, AuditService audit,
                                AttributionService attribution) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.attribution = attribution;
    }

    @Transactional
    public Map<String, Object> generateReply(long disputeId, String title, String bodyText, Principal principal) {
        Map<String, Object> dispute = jdbc.query("""
            SELECT id, subject_hash, status FROM compliance.identity_dispute WHERE id = :id
            """, new MapSqlParameterSource("id", disputeId), rs -> rs.next() ? Map.of(
                "id", rs.getLong("id"), "subjectHash", rs.getString("subject_hash"),
                "status", rs.getString("status")) : null);
        if (dispute == null) {
            throw ApiException.notFound("异议不存在: " + disputeId);
        }
        if (!"ADJUDICATED".equals(dispute.get("status"))) {
            throw ApiException.conflict("异议尚未裁定，不能生成对外说明");
        }
        if (!attribution.allImpactResolved(disputeId)) {
            long pending = countPendingImpacts(disputeId);
            throw ApiException.conflict("仍有 " + pending + " 个影响项未取得明确处理结果，不能生成新的对外说明");
        }

        Integer maxVersion = jdbc.queryForObject(
            "SELECT max(reply_version) FROM compliance.external_reply WHERE dispute_id = :id",
            new MapSqlParameterSource("id", disputeId), Integer.class);
        int version = (maxVersion == null ? 0 : maxVersion) + 1;
        String subjectHash = (String) dispute.get("subjectHash");
        String replyNo = "RPL-" + disputeId + "-V" + version + "-"
                         + HashUtil.sha256(subjectHash + version).substring(0, 10).toUpperCase();
        String safeTitle = title == null || title.isBlank()
            ? "身份错配复核处理说明（第 " + version + " 版）" : title;
        String safeBody = bodyText == null || bodyText.isBlank()
            ? defaultBody(disputeId, version) : bodyText;

        Long replyId = jdbc.queryForObject("""
            INSERT INTO compliance.external_reply
              (dispute_id, subject_hash, reply_version, reply_no, reply_status, title, body_text, issued_at)
            VALUES (:did, :sh, :v, :no, 'ISSUED', :t, :b, now())
            RETURNING id
            """, new MapSqlParameterSource().addValue("did", disputeId).addValue("sh", subjectHash)
                .addValue("v", version).addValue("no", replyNo).addValue("t", safeTitle)
                .addValue("b", safeBody), Long.class);

        // 旧答复整体被新版本取代，但行保留可按日期查回
        if (version > 1) {
            jdbc.update("""
                UPDATE compliance.external_reply SET reply_status = 'SUPERSEDED', superseded_by_id = :newId
                 WHERE dispute_id = :did AND id <> :newId
                """, new MapSqlParameterSource().addValue("newId", replyId).addValue("did", disputeId));
        }
        audit.log(null, principal.getName(), "EXTERNAL_REPLY_ISSUED",
                  Map.of("disputeId", disputeId, "replyId", replyId, "version", version, "replyNo", replyNo));
        return Map.of("replyId", replyId, "replyNo", replyNo, "replyVersion", version,
                      "status", "ISSUED", "supersededOlder", version - 1);
    }

    public List<Map<String, Object>> listReplies(Long disputeId, String subjectHash) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = " WHERE 1=1";
        if (disputeId != null) {
            where += " AND dispute_id = :did";
            p.addValue("did", disputeId);
        }
        if (subjectHash != null && !subjectHash.isBlank()) {
            where += " AND subject_hash = :sh";
            p.addValue("sh", subjectHash);
        }
        return jdbc.queryForList("""
            SELECT * FROM compliance.external_reply %s ORDER BY reply_version
            """.formatted(where), p);
    }

    /** 原答复按日期查回：返回该主体在指定日期当天（或之前最近）出具的答复，含已被取代的历史版本。 */
    public Map<String, Object> getReplyByDate(String subjectHash, LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        Map<String, Object> reply = jdbc.query("""
            SELECT * FROM compliance.external_reply
             WHERE subject_hash = :sh AND (issued_at AT TIME ZONE 'Asia/Shanghai')::date <= :d
             ORDER BY (issued_at AT TIME ZONE 'Asia/Shanghai')::date DESC, reply_version DESC
             LIMIT 1
            """, new MapSqlParameterSource().addValue("sh", subjectHash).addValue("d", date),
            rs -> rs.next() ? rsRow(rs) : null);
        if (reply == null) {
            throw ApiException.notFound("该主体在 " + date + " 当天或之前没有可查回的对外答复");
        }
        return reply;
    }

    private long countPendingImpacts(long disputeId) {
        Long pending = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.revision_impact_item
             WHERE revision_id IN (SELECT id FROM compliance.attribution_revision WHERE dispute_id = :id)
               AND item_status IN ('PENDING','SPLIT_PENDING_DISPOSITION','SUPPLEMENT_TICKETED')
            """, new MapSqlParameterSource("id", disputeId), Long.class);
        return pending == null ? 0 : pending;
    }

    private String defaultBody(long disputeId, int version) {
        return "您于身份异议（编号见办理追踪）中反映的归属问题已经合规裁定。本次为第 " + version
               + " 版对外说明：相关账号归属已按最新证据版本更正，历史归属判定与证据包保留不改写；"
               + "涉及的删除范围、共享资料与不可恢复位置均已逐项处理并留痕，可在办理追踪中查看明细。";
    }

    private Map<String, Object> rsRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        int cols = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= cols; i++) {
            m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        return m;
    }
}
