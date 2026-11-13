package com.example.rtbf.service;

import com.example.rtbf.util.HashUtil;
import com.example.rtbf.util.WorkdayUtil;
import com.example.rtbf.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单编排与删除作业。
 * 关键约束：
 *  - 同一主体多渠道重复提交：subject_hash + channel 幂等键并成一张工单
 *  - 枚举结论执行前冻结为不可变快照；事后补登记只生成增补工单
 *  - 删除作业按 工单 + 存储位置 幂等重放，备份不会被清两遍
 *  - 四类处置分别记账；聚合重算记录 before/after/diff；第三方仅凭回执推进
 */
@Service
public class WorkOrderService {

    private final NamedParameterJdbcTemplate jdbc;
    private final LineageService lineage;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    @Value("${app.cancel-window-minutes:30}")
    private long cancelWindowMinutes;

    @Value("${app.legal-deadline-workdays:15}")
    private int legalDeadlineWorkdays;

    @Value("${app.evidence.dir:./evidence}")
    private String evidenceDir;

    public WorkOrderService(NamedParameterJdbcTemplate jdbc, LineageService lineage,
                            AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.lineage = lineage;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public record CreateTicketRequest(String subjectHash, String channel, String sourceRef) {
    }

    // ------------------------------------------------------------------
    // 1. 创建工单（幂等）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> createTicket(CreateTicketRequest req, Principal principal) {
        Map<String, Object> subject = jdbc.query("""
            SELECT subject_hash, status FROM compliance.subject WHERE subject_hash = :h
            """, new MapSqlParameterSource("h", req.subjectHash()), rs -> rs.next() ? Map.of(
                "subject_hash", rs.getString(1), "status", rs.getString(2)) : null);
        if (subject == null) {
            throw ApiException.notFound("主体不存在，请先执行主体归并扫描");
        }
        if (!"CONFIRMED".equals(subject.get("status"))) {
            throw ApiException.conflict("主体尚未由合规专员确认，不能建单");
        }
        String idemKey = req.subjectHash() + "#" + req.channel();
        Map<String, Object> existed = jdbc.query("""
            SELECT id, ticket_no, status FROM compliance.work_order WHERE idempotency_key = :k
            """, new MapSqlParameterSource("k", idemKey), rs -> rs.next() ? Map.of(
                "id", rs.getLong(1), "ticketNo", rs.getString(2), "status", rs.getString(3)) : null);
        if (existed != null) {
            audit.log(((Number) existed.get("id")).longValue(), principal.getName(),
                      "TICKET_DUPLICATE_MERGED", Map.of("idempotencyKey", idemKey));
            return Map.of("idempotentHit", true, "ticketId", existed.get("id"),
                          "ticketNo", existed.get("ticketNo"), "status", existed.get("status"));
        }
        String ticketNo = "RTBF-" + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
                          + "-" + Integer.toHexString(idemKey.hashCode()).toUpperCase();
        OffsetDateTime withdrawnAt = OffsetDateTime.now();
        LocalDate deadline = WorkdayUtil.plusWorkdays(LocalDate.now(), legalDeadlineWorkdays);
        Long id = jdbc.queryForObject("""
            INSERT INTO compliance.work_order
              (ticket_no, subject_hash, channel, idempotency_key, status, current_node,
               consent_withdrawn_at, legal_deadline, source_ref)
            VALUES (:no, :sh, :ch, :k, 'CREATED', 'ENUMERATION', :w, :d, :ref)
            RETURNING id
            """, new MapSqlParameterSource()
            .addValue("no", ticketNo)
            .addValue("sh", req.subjectHash())
            .addValue("ch", req.channel())
            .addValue("k", idemKey)
            .addValue("w", withdrawnAt)
            .addValue("d", deadline)
            .addValue("ref", req.sourceRef()), Long.class);
        audit.log(id, principal.getName(), "TICKET_CREATED",
                  Map.of("ticketNo", ticketNo, "channel", req.channel(),
                         "consentWithdrawnAt", withdrawnAt, "legalDeadline", deadline));
        return Map.of("idempotentHit", false, "ticketId", id, "ticketNo", ticketNo,
                      "status", "CREATED", "legalDeadline", deadline,
                      "consentWithdrawnAt", withdrawnAt);
    }

    public List<Map<String, Object>> listTickets(String status) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (status != null && !status.isBlank()) {
            where = " WHERE status = :st";
            p.addValue("st", status);
        }
        return jdbc.queryForList("""
            SELECT id, ticket_no, subject_hash, channel, status, current_node,
                   consent_withdrawn_at, legal_deadline, cancel_before, return_reason,
                   snapshot_id, closed_at, created_at
            FROM compliance.work_order %s ORDER BY id DESC
            """.formatted(where), p);
    }

    public Map<String, Object> getTicket(long ticketId) {
        Map<String, Object> ticket = jdbc.query("""
            SELECT * FROM compliance.work_order WHERE id = :id
            """, new MapSqlParameterSource("id", ticketId), rs -> rs.next() ? ticketRow(rs) : null);
        if (ticket == null) {
            throw ApiException.notFound("工单不存在: " + ticketId);
        }
        ticket.put("snapshot", jdbc.queryForList("""
            SELECT * FROM compliance.enum_snapshot WHERE ticket_id = :id
            """, new MapSqlParameterSource("id", ticketId)));
        ticket.put("items", jdbc.queryForList("""
            SELECT si.*, a.asset_name FROM compliance.snapshot_item si
            JOIN compliance.data_asset a ON a.asset_code = si.asset_code
            WHERE si.snapshot_id = (SELECT snapshot_id FROM compliance.work_order WHERE id = :id)
            ORDER BY si.id
            """, new MapSqlParameterSource("id", ticketId)));
        ticket.put("dispositions", jdbc.queryForList("""
            SELECT * FROM compliance.disposition_record WHERE ticket_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", ticketId)));
        ticket.put("issues", jdbc.queryForList("""
            SELECT * FROM compliance.reconciliation_issue WHERE ticket_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", ticketId)));
        ticket.put("modelCards", jdbc.queryForList("""
            SELECT * FROM compliance.model_card WHERE ticket_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", ticketId)));
        ticket.put("notifications", jdbc.queryForList("""
            SELECT * FROM compliance.notification_outbox WHERE ticket_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", ticketId)));
        ticket.put("supplements", jdbc.queryForList("""
            SELECT * FROM compliance.supplement_ticket WHERE original_ticket_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", ticketId)));
        ticket.put("auditLogs", jdbc.queryForList("""
            SELECT id, actor, action, detail_json, created_at
            FROM compliance.audit_log WHERE ticket_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", ticketId)));
        return ticket;
    }

    // ------------------------------------------------------------------
    // 2. 沿血缘实时遍历并冻结不可变快照
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> freezeEnumeration(long ticketId, Principal principal) {
        Map<String, Object> ticket = requireTicket(ticketId);
        if (!"CREATED".equals(ticket.get("status")) && !"RETURNED".equals(ticket.get("status"))) {
            throw ApiException.conflict("当前状态 " + ticket.get("status") + " 不允许冻结枚举");
        }
        String subjectHash = (String) ticket.get("subjectHash");

        List<LineageService.TraversedItem> traversed = lineage.traverse();
        List<Map<String, Object>> assets = jdbc.queryForList(
            "SELECT * FROM compliance.data_asset", Map.of());
        Map<String, Map<String, Object>> assetMap = new LinkedHashMap<>();
        assets.forEach(a -> assetMap.put((String) a.get("asset_code"), a));

        long rootCount = assets.stream()
            .filter(a -> "TABLE".equals(a.get("storage_type")) && !"AST_FUNNEL".equals(a.get("asset_code")))
            .count();

        OffsetDateTime frozenAt = OffsetDateTime.now();
        Long snapshotId = jdbc.queryForObject("""
            INSERT INTO compliance.enum_snapshot
              (ticket_id, subject_hash, frozen_at, frozen_by, root_asset_count, item_count, manifest_hash, note)
            VALUES (:tid, :sh, :at, :by, :rc, 0, :mh, :note)
            RETURNING id
            """, new MapSqlParameterSource()
            .addValue("tid", ticketId)
            .addValue("sh", subjectHash)
            .addValue("at", frozenAt)
            .addValue("by", principal.getName())
            .addValue("rc", (int) rootCount)
            .addValue("mh", "PENDING")
            .addValue("note", "沿血缘实时全图遍历后冻结；断链项待人工确认；冻结后补登记资产只生成增补工单"),
            Long.class);

        List<String> manifestLines = new ArrayList<>();
        int itemCount = 0;
        for (LineageService.TraversedItem t : traversed) {
            Map<String, Object> asset = assetMap.get(t.assetCode());
            String category = (String) asset.get("disposition_default");
            int rows = countSubjectRows(t.assetCode(), subjectHash);
            String itemStatus = t.brokenGap() ? "PENDING_HUMAN" : "IN_SCOPE";
            LocalDate retentionUntil = computeRetentionUntil(t.assetCode(), subjectHash);

            Long itemId = jdbc.queryForObject("""
                INSERT INTO compliance.snapshot_item
                  (snapshot_id, asset_code, storage_type, target_ref, lineage_path,
                   item_status, disposition_category, retention_until, gap_id, row_count, frozen_detail)
                VALUES (:sid, :code, :st, :ref, :path, :ist, :cat, :ru, :gap, :rows,
                        CAST(:detail AS jsonb))
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("sid", snapshotId)
                .addValue("code", t.assetCode())
                .addValue("st", t.storageType())
                .addValue("ref", t.targetRef())
                .addValue("path", t.lineagePath())
                .addValue("ist", itemStatus)
                .addValue("cat", category)
                .addValue("ru", retentionUntil)
                .addValue("gap", t.gapId())
                .addValue("rows", rows)
                .addValue("detail", toJson(frozenDetail(asset, frozenAt))), Long.class);
            itemCount++;
            manifestLines.add(itemId + "|" + t.assetCode() + "|" + t.lineagePath() + "|"
                              + itemStatus + "|" + category + "|" + rows);
        }
        String manifestHash = HashUtil.sha256(String.join("\n", manifestLines));
        jdbc.update("UPDATE compliance.enum_snapshot SET item_count = :c, manifest_hash = :h WHERE id = :id",
                    new MapSqlParameterSource().addValue("c", itemCount).addValue("h", manifestHash)
                        .addValue("id", snapshotId));
        jdbc.update("""
            UPDATE compliance.work_order SET status = 'ENUM_FROZEN', current_node = 'ENUMERATION',
                   snapshot_id = :sid, updated_at = now() WHERE id = :tid
            """, new MapSqlParameterSource().addValue("sid", snapshotId).addValue("tid", ticketId));
        audit.log(ticketId, principal.getName(), "ENUMERATION_FROZEN",
                  Map.of("snapshotId", snapshotId, "itemCount", itemCount,
                         "manifestHash", manifestHash, "pendingHuman",
                         manifestLines.stream().filter(l -> l.contains("PENDING_HUMAN")).count()));
        return Map.of("snapshotId", snapshotId, "itemCount", itemCount,
                      "manifestHash", manifestHash, "frozenAt", frozenAt);
    }

    @Transactional
    public void confirmGapItem(long ticketId, long gapId, boolean inScope, Principal principal) {
        // 全局断链点若仍 OPEN 则首次确认；已被其他工单确认时不报错——
        // 每个工单在自己快照项上的纳入/排除结论是独立的
        Integer open = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.lineage_gap WHERE id = :id AND status = 'OPEN'",
            new MapSqlParameterSource("id", gapId), Integer.class);
        if (open != null && open > 0) {
            lineage.confirmGap(gapId, inScope, ticketId, principal);
        }
        // 只允许改写快照项的人工确认状态，不新增/删除条目，枚举清单本身不变。
        // 按断链点对应的资产编码定位本工单快照项（兼容历史快照 gap_id 为空的情况）
        int n = jdbc.update("""
            UPDATE compliance.snapshot_item
               SET item_status = :st
             WHERE snapshot_id = (SELECT snapshot_id FROM compliance.work_order WHERE id = :tid)
               AND asset_code = (SELECT asset_code FROM compliance.lineage_gap WHERE id = :gap)
            """, new MapSqlParameterSource()
            .addValue("st", inScope ? "IN_SCOPE" : "OUT_OF_SCOPE")
            .addValue("gap", gapId).addValue("tid", ticketId));
        if (n == 0) {
            throw ApiException.notFound("快照中未找到该断链项");
        }
        audit.log(ticketId, principal.getName(), "GAP_SCOPE_DECIDED",
                  Map.of("gapId", gapId, "inScope", inScope));
    }

    // ------------------------------------------------------------------
    // 3. 执行前影响面预览（进入可撤回延迟窗口）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> preview(long ticketId, Principal principal) {
        Map<String, Object> ticket = requireTicket(ticketId);
        if (!"ENUM_FROZEN".equals(ticket.get("status")) && !"RETURNED".equals(ticket.get("status"))) {
            throw ApiException.conflict("仅枚举已冻结的工单可做影响面预览");
        }
        Long pendingHuman = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.snapshot_item
             WHERE snapshot_id = :sid AND item_status = 'PENDING_HUMAN'
            """, new MapSqlParameterSource("sid", ticket.get("snapshotId")), Long.class);
        if (pendingHuman != null && pendingHuman > 0) {
            throw ApiException.conflict("仍有 " + pendingHuman + " 个血缘断链点待人工确认");
        }
        Long snapshotId = (Long) ticket.get("snapshotId");
        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT * FROM compliance.snapshot_item WHERE snapshot_id = :sid AND item_status = 'IN_SCOPE'
            """, new MapSqlParameterSource("sid", snapshotId));

        Map<String, Integer> categoryCount = new LinkedHashMap<>();
        List<Map<String, Object>> impact = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String cat = (String) item.get("disposition_category");
            categoryCount.merge(cat, 1, Integer::sum);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("assetCode", item.get("asset_code"));
            line.put("category", cat);
            line.put("rowCount", item.get("row_count"));
            line.put("retentionUntil", item.get("retention_until"));
            if ("RECOMPUTE_AGGREGATE".equals(cat)) {
                line.put("beforeValue", funnelBeforeValue());
            }
            impact.add(line);

            // 为每个位置预建处置记录（工单+存储位置幂等键），状态 PREVIEWED
            String idem = ticketId + ":" + item.get("asset_code") + ":" + item.get("target_ref");
            jdbc.update("""
                INSERT INTO compliance.disposition_record
                  (ticket_id, snapshot_item_id, asset_code, location_ref, category, status,
                   idempotency_key, retention_until, detail)
                VALUES (:tid, :siid, :code, :loc, :cat, 'PREVIEWED', :k, :ru, CAST(:d AS jsonb))
                ON CONFLICT (idempotency_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("tid", ticketId)
                .addValue("siid", item.get("id"))
                .addValue("code", item.get("asset_code"))
                .addValue("loc", item.get("target_ref"))
                .addValue("cat", cat)
                .addValue("k", idem)
                .addValue("ru", item.get("retention_until"))
                .addValue("d", "{\"preview\":true}"));
        }
        OffsetDateTime cancelBefore = OffsetDateTime.now().plusMinutes(cancelWindowMinutes);
        jdbc.update("""
            UPDATE compliance.work_order SET status = 'PENDING_EXECUTION', current_node = 'PREVIEW',
                   cancel_before = :cb, updated_at = now() WHERE id = :tid
            """, new MapSqlParameterSource().addValue("cb", cancelBefore).addValue("tid", ticketId));
        audit.log(ticketId, principal.getName(), "IMPACT_PREVIEWED",
                  Map.of("categoryCount", categoryCount, "cancelBefore", cancelBefore));
        return Map.of("impact", impact, "categoryCount", categoryCount,
                      "cancelBefore", cancelBefore, "cancelWindowMinutes", cancelWindowMinutes);
    }

    /** 候选人撤回注销 / 合规在延迟窗口内主动撤回：工单作废，数据不动。 */
    @Transactional
    public void cancel(long ticketId, String reason, Principal principal) {
        Map<String, Object> ticket = requireTicket(ticketId);
        String status = (String) ticket.get("status");
        if (!"PENDING_EXECUTION".equals(status) && !"RETURNED".equals(status)) {
            throw ApiException.conflict("当前状态 " + status + " 不允许撤回（仅预览后的延迟窗口可撤回）");
        }
        OffsetDateTime cancelBefore = (OffsetDateTime) ticket.get("cancelBefore");
        if (cancelWindowMinutes > 0 && cancelBefore != null && OffsetDateTime.now().isAfter(cancelBefore)) {
            throw ApiException.conflict("可撤回延迟窗口已过（" + cancelBefore + "），不能撤回");
        }
        jdbc.update("UPDATE compliance.work_order SET status = 'CANCELLED', closed_at = now(), " +
                    "return_reason = :r, updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("r", reason).addValue("id", ticketId));
        audit.log(ticketId, principal.getName(), "TICKET_CANCELLED", Map.of("reason", reason));
    }

    /** 退回当前处置节点：保留已确认主体与已冻结枚举结论，接手人可直接续办。 */
    @Transactional
    public void returnTicket(long ticketId, String reason, Principal principal) {
        Map<String, Object> ticket = requireTicket(ticketId);
        if ("COMPLETED".equals(ticket.get("status")) || "CANCELLED".equals(ticket.get("status"))) {
            throw ApiException.conflict("终态工单不能退回");
        }
        jdbc.update("UPDATE compliance.work_order SET status = 'RETURNED', return_reason = :r, " +
                    "updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("r", reason).addValue("id", ticketId));
        audit.log(ticketId, principal.getName(), "TICKET_RETURNED",
                  Map.of("reason", reason, "node", ticket.get("currentNode")));
    }

    @Transactional
    public void resume(long ticketId, Principal principal) {
        Map<String, Object> ticket = requireTicket(ticketId);
        if (!"RETURNED".equals(ticket.get("status"))) {
            throw ApiException.conflict("仅退回状态的工单可续办");
        }
        String node = (String) ticket.get("currentNode");
        String status = switch (node) {
            case "ENUMERATION" -> "ENUM_FROZEN";
            case "PREVIEW" -> "PENDING_EXECUTION";
            case "EXECUTION", "RECEIPT", "RECONCILE" -> "EXECUTING";
            default -> "PENDING_EXECUTION";
        };
        jdbc.update("UPDATE compliance.work_order SET status = :st, return_reason = NULL, updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("st", status).addValue("id", ticketId));
        audit.log(ticketId, principal.getName(), "TICKET_RESUMED", Map.of("restoredStatus", status));
    }

    // ------------------------------------------------------------------
    // 4. 执行删除作业（按位置幂等重放）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> execute(long ticketId, Principal principal) {
        Map<String, Object> ticket = requireTicket(ticketId);
        String status = (String) ticket.get("status");
        if (!"PENDING_EXECUTION".equals(status) && !"RETURNED".equals(status)
            && !"EXECUTING".equals(status) && !"WAITING_RECEIPT".equals(status)) {
            throw ApiException.conflict("当前状态 " + status + " 不允许执行删除作业");
        }
        String subjectHash = (String) ticket.get("subjectHash");
        jdbc.update("UPDATE compliance.work_order SET status = 'EXECUTING', current_node = 'EXECUTION', " +
                    "updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", ticketId));

        List<Map<String, Object>> dispositions = jdbc.queryForList("""
            SELECT * FROM compliance.disposition_record WHERE ticket_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", ticketId));

        int executed = 0, scheduled = 0, waiting = 0, skipped = 0;
        for (Map<String, Object> d : dispositions) {
            String dStatus = (String) d.get("status");
            if ("EXECUTED".equals(dStatus) || "RECEIPT_RECEIVED".equals(dStatus)
                || "SCHEDULED".equals(dStatus) || "SKIPPED".equals(dStatus)
                || "WAITING_RECEIPT".equals(dStatus)) {
                // 幂等重放：已完成/等待回执的位置直接跳过，备份不会被清两遍、通知不会重发
                continue;
            }
            String assetCode = (String) d.get("asset_code");
            String category = (String) d.get("category");
            long dispId = ((Number) d.get("id")).longValue();

            switch (category) {
                case "PHYSICAL_DELETE" -> {
                    int deleted = physicalDelete(assetCode, subjectHash);
                    String recheck = recheckHash(assetCode, subjectHash);
                    updateExecuted(dispId, deleted, recheck, principal);
                    if ("AST_SAMPLE".equals(assetCode)) {
                        ensureModelCard(ticketId, deleted);
                    }
                    executed++;
                }
                case "RECOMPUTE_AGGREGATE" -> {
                    // 投递记录已在同事务删除，重算漏斗并留 before/after/diff
                    Map<String, Object> before = funnelBeforeValue();
                    recomputeFunnel();
                    Map<String, Object> after = funnelAfterValue();
                    jdbc.update("""
                        UPDATE compliance.disposition_record
                           SET status = 'EXECUTED', before_value = :b, after_value = :a,
                               diff_value = :diff, recheck_hash = :rh, attempt_count = attempt_count + 1,
                               executed_at = now(), updated_at = now(),
                               detail = CAST(:d AS jsonb)
                         WHERE id = :id
                        """, new MapSqlParameterSource()
                        .addValue("b", before.toString())
                        .addValue("a", after.toString())
                        .addValue("diff", diff(before, after))
                        .addValue("rh", HashUtil.sha256("funnel:" + after))
                        .addValue("d", toJson(
                            Map.of("recomputedAt", OffsetDateTime.now().toString(),
                                   "note", "报表不允许自行跳变，差额由作业留痕")))
                        .addValue("id", dispId));
                    executed++;
                }
                case "WAIT_RETENTION" -> {
                    java.sql.Date untilDate = (java.sql.Date) d.get("retention_until");
                    if (untilDate == null) {
                        // 该位置没有本主体数据（如未导出过、未备份过），无需处置
                        jdbc.update("""
                            UPDATE compliance.disposition_record SET status = 'SKIPPED',
                                   attempt_count = attempt_count + 1, updated_at = now() WHERE id = :id
                            """, new MapSqlParameterSource("id", dispId));
                        skipped++;
                    } else {
                        LocalDate until = untilDate.toLocalDate();
                        if (!until.isAfter(LocalDate.now())) {
                            int deleted = physicalDelete(assetCode, subjectHash);
                            updateExecuted(dispId, deleted, recheckHash(assetCode, subjectHash), principal);
                            executed++;
                        } else {
                            jdbc.update("""
                                UPDATE compliance.disposition_record SET status = 'SCHEDULED',
                                       attempt_count = attempt_count + 1, updated_at = now() WHERE id = :id
                                """, new MapSqlParameterSource("id", dispId));
                            scheduled++;
                        }
                    }
                }
                case "EXTERNAL_NOTIFY" -> {
                    int beforeOutbox = jdbc.queryForObject(
                        "SELECT count(*) FROM compliance.notification_outbox WHERE ticket_id = :id",
                        new MapSqlParameterSource("id", ticketId), Integer.class);
                    sendExternalNotification(ticketId, assetCode, subjectHash);
                    int afterOutbox = jdbc.queryForObject(
                        "SELECT count(*) FROM compliance.notification_outbox WHERE ticket_id = :id",
                        new MapSqlParameterSource("id", ticketId), Integer.class);
                    if (afterOutbox > beforeOutbox) {
                        jdbc.update("""
                            UPDATE compliance.disposition_record SET status = 'WAITING_RECEIPT',
                                   attempt_count = attempt_count + 1, updated_at = now() WHERE id = :id
                            """, new MapSqlParameterSource("id", dispId));
                        waiting++;
                    } else {
                        // 本主体没有流出给任何第三方，无通知可发
                        jdbc.update("""
                            UPDATE compliance.disposition_record SET status = 'SKIPPED',
                                   attempt_count = attempt_count + 1, updated_at = now() WHERE id = :id
                            """, new MapSqlParameterSource("id", dispId));
                        skipped++;
                    }
                }
                default -> skipped++;
            }
        }

        // OUT_OF_SCOPE 的快照项不产生处置记录；这里补记 SKIPPED 留痕
        List<Map<String, Object>> outItems = jdbc.queryForList("""
            SELECT si.id, si.asset_code, si.target_ref FROM compliance.snapshot_item si
            WHERE si.snapshot_id = :sid AND si.item_status = 'OUT_OF_SCOPE'
            """, new MapSqlParameterSource("sid", ticket.get("snapshotId")));
        for (Map<String, Object> item : outItems) {
            String idem = ticketId + ":" + item.get("asset_code") + ":OUT_OF_SCOPE";
            int n = jdbc.update("""
                INSERT INTO compliance.disposition_record
                  (ticket_id, snapshot_item_id, asset_code, location_ref, category, status, idempotency_key, detail)
                VALUES (:tid, :siid, :code, :loc, 'PHYSICAL_DELETE', 'SKIPPED', :k, CAST(:d AS jsonb))
                ON CONFLICT (idempotency_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("tid", ticketId).addValue("siid", item.get("id"))
                .addValue("code", item.get("asset_code")).addValue("loc", item.get("target_ref"))
                .addValue("k", idem).addValue("d", "{\"outOfScope\":true}"));
            if (n > 0) {
                skipped++;
            }
        }

        boolean stillWaiting = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM compliance.disposition_record
                           WHERE ticket_id = :id AND status = 'WAITING_RECEIPT')
            """, new MapSqlParameterSource("id", ticketId), Boolean.class);
        boolean stillScheduled = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM compliance.disposition_record
                           WHERE ticket_id = :id AND status = 'SCHEDULED')
            """, new MapSqlParameterSource("id", ticketId), Boolean.class);
        String nextStatus = Boolean.TRUE.equals(stillWaiting) ? "WAITING_RECEIPT"
                            : Boolean.TRUE.equals(stillScheduled) ? "EXECUTING" : "RECONCILIATION";
        String nextNode = Boolean.TRUE.equals(stillWaiting) ? "RECEIPT"
                          : Boolean.TRUE.equals(stillScheduled) ? "EXECUTION" : "RECONCILE";
        jdbc.update("UPDATE compliance.work_order SET status = :st, current_node = :n, updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("st", nextStatus).addValue("n", nextNode)
                        .addValue("id", ticketId));
        audit.log(ticketId, principal.getName(), "DELETE_JOB_EXECUTED",
                  Map.of("executed", executed, "scheduled", scheduled,
                         "waitingReceipt", waiting, "skipped", skipped, "nextStatus", nextStatus));
        return Map.of("executed", executed, "scheduled", scheduled,
                      "waitingReceipt", waiting, "skipped", skipped, "nextStatus", nextStatus);
    }

    /** 保留期到期后的定时重放入口（幂等：只处理 SCHEDULED 且到期的位置）。 */
    @Transactional
    public Map<String, Object> retryExpiredRetention(long ticketId, Principal principal) {
        Map<String, Object> ticket = requireTicket(ticketId);
        String subjectHash = (String) ticket.get("subjectHash");
        List<Map<String, Object>> due = jdbc.queryForList("""
            SELECT * FROM compliance.disposition_record
             WHERE ticket_id = :id AND status = 'SCHEDULED'
               AND retention_until <= CURRENT_DATE
            """, new MapSqlParameterSource("id", ticketId));
        int executed = 0;
        for (Map<String, Object> d : due) {
            long dispId = ((Number) d.get("id")).longValue();
            int deleted = physicalDelete((String) d.get("asset_code"), subjectHash);
            updateExecuted(dispId, deleted, recheckHash((String) d.get("asset_code"), subjectHash), principal);
            executed++;
        }
        boolean stillScheduled = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM compliance.disposition_record
                           WHERE ticket_id = :id AND status = 'SCHEDULED')
            """, new MapSqlParameterSource("id", ticketId), Boolean.class);
        if (!Boolean.TRUE.equals(stillScheduled)) {
            boolean waiting = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM compliance.disposition_record
                               WHERE ticket_id = :id AND status = 'WAITING_RECEIPT')
                """, new MapSqlParameterSource("id", ticketId), Boolean.class);
            jdbc.update("UPDATE compliance.work_order SET status = :st, current_node = :n, updated_at = now() WHERE id = :id",
                        new MapSqlParameterSource()
                            .addValue("st", Boolean.TRUE.equals(waiting) ? "WAITING_RECEIPT" : "RECONCILIATION")
                            .addValue("n", Boolean.TRUE.equals(waiting) ? "RECEIPT" : "RECONCILE")
                            .addValue("id", ticketId));
        }
        audit.log(ticketId, principal.getName(), "RETENTION_RETRY", Map.of("executed", executed));
        return Map.of("executed", executed);
    }

    // ------------------------------------------------------------------
    // 5. 第三方回执（本地事务边界外，只能凭回执状态推进）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> registerReceipt(long ticketId, String noticeRef, String receiptRef,
                                               Principal principal) {
        int n = jdbc.update("""
            UPDATE compliance.notification_outbox
               SET notice_status = 'RECEIPT_RECEIVED', receipt_ref = :rr, receipt_at = now()
             WHERE ticket_id = :tid AND notice_ref = :nr AND notice_status = 'SENT'
            """, new MapSqlParameterSource().addValue("rr", receiptRef)
                .addValue("tid", ticketId).addValue("nr", noticeRef));
        if (n == 0) {
            throw ApiException.conflict("通知不存在或非 SENT 状态，无法登记回执: " + noticeRef);
        }
        jdbc.update("""
            UPDATE compliance.disposition_record
               SET status = 'RECEIPT_RECEIVED', receipt_ref = :rr, receipt_at = now(), updated_at = now()
             WHERE ticket_id = :tid AND category = 'EXTERNAL_NOTIFY'
               AND asset_code = (SELECT asset_code FROM compliance.notification_outbox
                                  WHERE ticket_id = :tid AND notice_ref = :nr LIMIT 1)
            """, new MapSqlParameterSource().addValue("rr", receiptRef)
                .addValue("tid", ticketId).addValue("nr", noticeRef));
        boolean stillWaiting = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM compliance.disposition_record
                           WHERE ticket_id = :id AND status = 'WAITING_RECEIPT')
            """, new MapSqlParameterSource("id", ticketId), Boolean.class);
        if (!Boolean.TRUE.equals(stillWaiting)) {
            jdbc.update("""
                UPDATE compliance.work_order SET status = 'RECONCILIATION', current_node = 'RECONCILE',
                       updated_at = now() WHERE id = :id
                """, new MapSqlParameterSource("id", ticketId));
        }
        audit.log(ticketId, principal.getName(), "EXTERNAL_RECEIPT_REGISTERED",
                  Map.of("noticeRef", noticeRef, "receiptRef", receiptRef));
        return Map.of("noticeRef", noticeRef, "receiptRef", receiptRef, "stillWaiting", stillWaiting);
    }

    // ------------------------------------------------------------------
    // 6. 对账：逐位置核对物理清除/重算差额/抽样反查
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> reconcile(long ticketId, Principal principal) {
        Map<String, Object> ticket = requireTicket(ticketId);
        if (!"RECONCILIATION".equals(ticket.get("status")) && !"RETURNED".equals(ticket.get("status"))) {
            throw ApiException.conflict("仅对账节点可执行对账");
        }
        String subjectHash = (String) ticket.get("subjectHash");
        List<Map<String, Object>> records = jdbc.queryForList("""
            SELECT * FROM compliance.disposition_record
             WHERE ticket_id = :id AND status IN ('EXECUTED','RECEIPT_RECEIVED')
            """, new MapSqlParameterSource("id", ticketId));

        int issues = 0;
        for (Map<String, Object> d : records) {
            long dispId = ((Number) d.get("id")).longValue();
            String assetCode = (String) d.get("asset_code");
            String category = (String) d.get("category");
            if ("PHYSICAL_DELETE".equals(category)) {
                String expected = (String) d.get("recheck_hash");
                String actual = recheckHash(assetCode, subjectHash);
                if (expected != null && !expected.equals(actual)) {
                    raiseIssue(ticketId, dispId, "RECHECK_HASH_MISMATCH", expected, actual);
                    issues++;
                }
                int remaining = countSubjectRows(assetCode, subjectHash);
                if (remaining > 0 && !"SKIPPED".equals(d.get("status"))) {
                    raiseIssue(ticketId, dispId, "PHYSICAL_MISMATCH", "0", String.valueOf(remaining));
                    issues++;
                }
            } else if ("RECOMPUTE_AGGREGATE".equals(category)) {
                String expectedHash = (String) d.get("recheck_hash");
                String actualHash = HashUtil.sha256("funnel:" + funnelAfterValue());
                if (!actualHash.equals(expectedHash)) {
                    raiseIssue(ticketId, dispId, "RECOMPUTE_MISMATCH", expectedHash, actualHash);
                    issues++;
                }
            }
        }
        // 回执超期检查：SENT 超过 7 天未收回执
        int overdue = jdbc.update("""
            INSERT INTO compliance.reconciliation_issue
              (ticket_id, disposition_id, issue_type, expected_value, actual_value, status)
            SELECT :tid, d.id, 'RECEIPT_OVERDUE', 'RECEIPT_WITHIN_7D', 'OVERDUE', 'OPEN'
            FROM compliance.disposition_record d
            WHERE d.ticket_id = :tid AND d.status = 'WAITING_RECEIPT'
              AND d.id NOT IN (SELECT disposition_id FROM compliance.reconciliation_issue
                                WHERE ticket_id = :tid AND issue_type = 'RECEIPT_OVERDUE')
            """, new MapSqlParameterSource("tid", ticketId));
        issues += overdue;

        Long openIssues = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.reconciliation_issue WHERE ticket_id = :id AND status = 'OPEN'
            """, new MapSqlParameterSource("id", ticketId), Long.class);
        audit.log(ticketId, principal.getName(), "RECONCILED",
                  Map.of("newIssues", issues, "openIssues", openIssues));
        if (openIssues != null && openIssues > 0) {
            return Map.of("passed", false, "openIssues", openIssues,
                          "note", "存在对不上的位置，已单列交合规复核，工单不得关单");
        }
        jdbc.update("""
            UPDATE compliance.work_order SET status = 'COMPLETED', current_node = 'DONE',
                   closed_at = now(), updated_at = now() WHERE id = :id
            """, new MapSqlParameterSource("id", ticketId));
        audit.log(ticketId, principal.getName(), "TICKET_COMPLETED", Map.of());
        return Map.of("passed", true, "openIssues", 0);
    }

    @Transactional
    public void resolveIssue(long issueId, String note, Principal principal) {
        int n = jdbc.update("""
            UPDATE compliance.reconciliation_issue
               SET status = 'RESOLVED', resolved_by = :actor, resolution_note = :note, resolved_at = now()
             WHERE id = :id AND status = 'OPEN'
            """, new MapSqlParameterSource().addValue("actor", principal.getName())
                .addValue("note", note).addValue("id", issueId));
        if (n == 0) {
            throw ApiException.conflict("对账问题不存在或已解决");
        }
        audit.log(null, principal.getName(), "ISSUE_RESOLVED", Map.of("issueId", issueId, "note", note));
    }

    // ------------------------------------------------------------------
    // 7. 模型卡：样本进过模型，参数不可回退，重训决策单独陈列
    // ------------------------------------------------------------------
    @Transactional
    public void decideRetrain(long modelCardId, String decision, String performanceBefore,
                              String performanceAfter, String justification, Principal principal) {
        int n = jdbc.update("""
            UPDATE compliance.model_card
               SET retrain_decision = :dec, performance_before = :pb, performance_after = :pa,
                   justification = :j, decided_by = :actor, decided_at = now()
             WHERE id = :id AND retrain_decision = 'PENDING'
            """, new MapSqlParameterSource()
            .addValue("dec", decision).addValue("pb", performanceBefore)
            .addValue("pa", performanceAfter).addValue("j", justification)
            .addValue("actor", principal.getName()).addValue("id", modelCardId));
        if (n == 0) {
            throw ApiException.conflict("模型卡不存在或已作出重训决策");
        }
        Long ticketId = jdbc.queryForObject(
            "SELECT ticket_id FROM compliance.model_card WHERE id = :id",
            new MapSqlParameterSource("id", modelCardId), Long.class);
        audit.log(ticketId, principal.getName(), "MODEL_RETRAIN_DECIDED",
                  Map.of("modelCardId", modelCardId, "decision", decision));
    }

    // ------------------------------------------------------------------
    // 8. 事后补登记 → 增补工单（不得追溯改写已冻结快照/已出具说明）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> createSupplement(long originalTicketId, String assetCode,
                                                String reason, Principal principal) {
        requireTicket(originalTicketId);
        Integer assetExists = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.data_asset WHERE asset_code = :c",
            new MapSqlParameterSource("c", assetCode), Integer.class);
        if (assetExists == null || assetExists == 0) {
            throw ApiException.notFound("资产未登记: " + assetCode);
        }
        Long inSnapshot = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.snapshot_item si
            JOIN compliance.work_order w ON w.snapshot_id = si.snapshot_id
            WHERE w.id = :tid AND si.asset_code = :c
            """, new MapSqlParameterSource().addValue("tid", originalTicketId).addValue("c", assetCode),
            Long.class);
        if (inSnapshot != null && inSnapshot > 0) {
            throw ApiException.conflict("该资产已在冻结快照内，不属于增补范围");
        }
        Long id = jdbc.queryForObject("""
            INSERT INTO compliance.supplement_ticket (original_ticket_id, asset_code, reason, status)
            VALUES (:tid, :code, :reason, 'PENDING') RETURNING id
            """, new MapSqlParameterSource().addValue("tid", originalTicketId)
                .addValue("code", assetCode).addValue("reason", reason), Long.class);
        audit.log(originalTicketId, principal.getName(), "SUPPLEMENT_TICKET_CREATED",
                  Map.of("supplementId", id, "assetCode", assetCode,
                         "note", "原快照不可变，增补处置与原说明分开陈列"));
        return Map.of("supplementId", id, "status", "PENDING", "immutableSnapshotKept", true);
    }

    @Transactional
    public Map<String, Object> executeSupplement(long supplementId, Principal principal) {
        Map<String, Object> sup = jdbc.query("""
            SELECT * FROM compliance.supplement_ticket WHERE id = :id
            """, new MapSqlParameterSource("id", supplementId), rs -> rs.next() ? rsRow(rs) : null);
        if (sup == null) {
            throw ApiException.notFound("增补工单不存在");
        }
        if (!"PENDING".equals(sup.get("status"))) {
            throw ApiException.conflict("增补工单状态为 " + sup.get("status") + "，不可重复执行");
        }
        long originalTicketId = ((Number) sup.get("original_ticket_id")).longValue();
        String assetCode = (String) sup.get("asset_code");
        Map<String, Object> ticket = requireTicket(originalTicketId);
        String subjectHash = (String) ticket.get("subjectHash");
        int rows = countSubjectRows(assetCode, subjectHash);
        int deleted = rows > 0 ? physicalDelete(assetCode, subjectHash) : 0;
        String idem = originalTicketId + ":" + assetCode + ":SUPPLEMENT";
        jdbc.update("""
            INSERT INTO compliance.disposition_record
              (ticket_id, snapshot_item_id, asset_code, location_ref, category, status,
               idempotency_key, before_value, after_value, recheck_hash, detail, executed_at)
            VALUES (:tid, 0, :code, :loc, 'PHYSICAL_DELETE', 'EXECUTED', :k, :b, '0', :rh,
                    CAST(:d AS jsonb), now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("tid", originalTicketId).addValue("code", assetCode)
            .addValue("loc", "SUPPLEMENT:" + assetCode).addValue("k", idem)
            .addValue("b", String.valueOf(rows)).addValue("rh", recheckHash(assetCode, subjectHash))
            .addValue("d", "{\"supplement\":true,\"supplementId\":" + supplementId + "}"));
        jdbc.update("UPDATE compliance.supplement_ticket SET status = 'EXECUTED', executed_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", supplementId));
        audit.log(originalTicketId, principal.getName(), "SUPPLEMENT_EXECUTED",
                  Map.of("supplementId", supplementId, "assetCode", assetCode, "deleted", deleted));
        return Map.of("supplementId", supplementId, "deleted", deleted);
    }

    // ------------------------------------------------------------------
    // 9. 证据包：快照清单 + 处置留痕 + 重算差额 + 模型卡 + 对账问题
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> buildEvidencePackage(long ticketId, Principal principal) {
        requireTicket(ticketId);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("ticketId", ticketId);
        manifest.put("generatedAt", OffsetDateTime.now().toString());
        manifest.put("snapshot", jdbc.queryForList("""
            SELECT id, frozen_at, frozen_by, root_asset_count, item_count, manifest_hash
            FROM compliance.enum_snapshot WHERE ticket_id = :id
            """, new MapSqlParameterSource("id", ticketId)));
        manifest.put("snapshotItems", jdbc.queryForList("""
            SELECT asset_code, storage_type, lineage_path, item_status, disposition_category,
                   retention_until, row_count FROM compliance.snapshot_item
            WHERE snapshot_id = (SELECT snapshot_id FROM compliance.work_order WHERE id = :id) ORDER BY id
            """, new MapSqlParameterSource("id", ticketId)));
        manifest.put("dispositions", jdbc.queryForList("""
            SELECT asset_code, location_ref, category, status, before_value, after_value,
                   diff_value, recheck_hash, receipt_ref, executed_at
            FROM compliance.disposition_record WHERE ticket_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", ticketId)));
        manifest.put("modelCards", jdbc.queryForList(
            "SELECT * FROM compliance.model_card WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId)));
        manifest.put("reconciliationIssues", jdbc.queryForList(
            "SELECT * FROM compliance.reconciliation_issue WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId)));
        manifest.put("supplements", jdbc.queryForList(
            "SELECT * FROM compliance.supplement_ticket WHERE original_ticket_id = :id",
            new MapSqlParameterSource("id", ticketId)));

        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
            String packageHash = HashUtil.sha256(json);
            Path dir = Path.of(evidenceDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("evidence-ticket-" + ticketId + "-" + packageHash.substring(0, 12) + ".json");
            Files.writeString(file, json);
            int itemCount = ((List<?>) manifest.get("snapshotItems")).size()
                            + ((List<?>) manifest.get("dispositions")).size();
            Long id = jdbc.queryForObject("""
                INSERT INTO compliance.evidence_package
                  (ticket_id, package_hash, item_count, manifest_json, file_path)
                VALUES (:tid, :h, :c, CAST(:m AS jsonb), :fp) RETURNING id
                """, new MapSqlParameterSource().addValue("tid", ticketId).addValue("h", packageHash)
                    .addValue("c", itemCount).addValue("m", json).addValue("fp", file.toString()), Long.class);
            audit.log(ticketId, principal.getName(), "EVIDENCE_PACKAGE_BUILT",
                      Map.of("evidenceId", id, "packageHash", packageHash, "file", file.toString()));
            return Map.of("evidenceId", id, "packageHash", packageHash, "filePath", file.toString());
        } catch (Exception e) {
            throw new IllegalStateException("证据包生成失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> listEvidence(long ticketId) {
        return jdbc.queryForList("""
            SELECT id, ticket_id, package_hash, item_count, file_path, created_at
            FROM compliance.evidence_package WHERE ticket_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", ticketId));
    }

    // ====================== 内部辅助 ======================

    private Map<String, Object> frozenDetail(Map<String, Object> asset, OffsetDateTime frozenAt) {
        // 不能用 Map.of：external_party 对内部资产为 null
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("assetName", asset.get("asset_name"));
        d.put("externalParty", asset.get("external_party"));
        d.put("frozenAt", frozenAt.toString());
        return d;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private Map<String, Object> requireTicket(long ticketId) {
        Map<String, Object> ticket = jdbc.query("SELECT * FROM compliance.work_order WHERE id = :id",
            new MapSqlParameterSource("id", ticketId), rs -> rs.next() ? ticketRow(rs) : null);
        if (ticket == null) {
            throw ApiException.notFound("工单不存在: " + ticketId);
        }
        return ticket;
    }

    private void updateExecuted(long dispId, int deleted, String recheck, Principal principal) {
        jdbc.update("""
            UPDATE compliance.disposition_record
               SET status = 'EXECUTED', before_value = :b, after_value = '0',
                   diff_value = :diff, recheck_hash = :rh, attempt_count = attempt_count + 1,
                   executed_at = now(), updated_at = now(),
                   detail = CAST(:d AS jsonb)
             WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("b", String.valueOf(deleted))
            .addValue("diff", "-" + deleted)
            .addValue("rh", recheck)
            .addValue("d", "{\"executedBy\":\"" + principal.getName() + "\"}")
            .addValue("id", dispId));
    }

    private void ensureModelCard(long ticketId, int sampleCount) {
        Long exists = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.model_card WHERE ticket_id = :id AND model_name = :m",
            new MapSqlParameterSource().addValue("id", ticketId).addValue("m", "job-match-embedding"),
            Long.class);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("""
            INSERT INTO compliance.model_card
              (ticket_id, model_name, affected_sample_count, trained_params_note, retrain_decision)
            VALUES (:tid, 'job-match-embedding', :c,
                    '该主体样本已参与历史训练，已训练模型参数无法逐条回退；物理删除样本与模型参数处置分开陈列',
                    'PENDING')
            """, new MapSqlParameterSource().addValue("tid", ticketId).addValue("c", sampleCount));
    }

    private void sendExternalNotification(long ticketId, String assetCode, String subjectHash) {
        // external_sharing 记录的是被下载的源资产（如 AST_PDF），而处置资产是 AST_EXTERNAL，
        // 因此按主体枚举其全部外发接收方，而不是按处置资产编码关联
        List<Map<String, Object>> shares = jdbc.queryForList("""
            SELECT DISTINCT party, asset_code AS source_asset FROM biz.external_sharing
             WHERE subject_hash = :sh
            """, new MapSqlParameterSource("sh", subjectHash));
        for (Map<String, Object> s : shares) {
            String party = (String) s.get("party");
            String noticeRef = "NTC-" + ticketId + "-" + assetCode + "-" + s.get("source_asset");
            int n = jdbc.update("""
                INSERT INTO compliance.notification_outbox
                  (ticket_id, party, asset_code, notice_ref, notice_status, sent_at)
                VALUES (:tid, :party, :code, :ref, 'SENT', now())
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource().addValue("tid", ticketId).addValue("party", party)
                    .addValue("code", assetCode).addValue("ref", noticeRef));
            if (n > 0) {
                audit.log(ticketId, "system", "EXTERNAL_NOTICE_SENT",
                          Map.of("party", party, "noticeRef", noticeRef));
            }
        }
    }

    private void raiseIssue(long ticketId, long dispId, String type, String expected, String actual) {
        Integer exists = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.reconciliation_issue
             WHERE ticket_id = :tid AND disposition_id = :did AND issue_type = :t AND status = 'OPEN'
            """, new MapSqlParameterSource().addValue("tid", ticketId).addValue("did", dispId)
                .addValue("t", type), Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("""
            INSERT INTO compliance.reconciliation_issue
              (ticket_id, disposition_id, issue_type, expected_value, actual_value, status)
            VALUES (:tid, :did, :t, :e, :a, 'OPEN')
            """, new MapSqlParameterSource().addValue("tid", ticketId).addValue("did", dispId)
                .addValue("t", type).addValue("e", expected).addValue("a", actual));
    }

    private int physicalDelete(String assetCode, String subjectHash) {
        return switch (assetCode) {
            case "AST_ACCOUNT" -> delete("biz.account", subjectHash);
            case "AST_RESUME" -> delete("biz.resume", subjectHash);
            case "AST_PDF" -> delete("biz.resume_pdf", subjectHash);
            case "AST_SEARCH" -> delete("biz.search_index_doc", subjectHash);
            case "AST_FEATURE" -> delete("biz.recommend_feature", subjectHash);
            case "AST_SAMPLE" -> delete("biz.training_sample", subjectHash);
            case "AST_APPLICATION" -> delete("biz.application", subjectHash);
            case "AST_EXPORT" -> delete("biz.data_export", subjectHash);
            case "AST_BACKUP" -> delete("biz.backup_archive", subjectHash);
            case "AST_LEGACY" -> delete("biz.legacy_archive", subjectHash);
            case "AST_INTERVIEW_VIDEO" -> delete("biz.interview_video", subjectHash);
            default -> 0;
        };
    }

    private int delete(String table, String subjectHash) {
        return jdbc.update("DELETE FROM " + table + " WHERE subject_hash = :sh",
                           new MapSqlParameterSource("sh", subjectHash));
    }

    private int countSubjectRows(String assetCode, String subjectHash) {
        String table = switch (assetCode) {
            case "AST_ACCOUNT" -> "biz.account";
            case "AST_RESUME" -> "biz.resume";
            case "AST_PDF" -> "biz.resume_pdf";
            case "AST_SEARCH" -> "biz.search_index_doc";
            case "AST_FEATURE" -> "biz.recommend_feature";
            case "AST_SAMPLE" -> "biz.training_sample";
            case "AST_APPLICATION" -> "biz.application";
            case "AST_EXPORT" -> "biz.data_export";
            case "AST_BACKUP" -> "biz.backup_archive";
            case "AST_LEGACY" -> "biz.legacy_archive";
            case "AST_INTERVIEW_VIDEO" -> "biz.interview_video";
            case "AST_EXTERNAL" -> "biz.external_sharing";
            default -> null;
        };
        if (table == null) {
            return 0;
        }
        Integer c = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE subject_hash = :sh",
                                        new MapSqlParameterSource("sh", subjectHash), Integer.class);
        return c == null ? 0 : c;
    }

    private LocalDate computeRetentionUntil(String assetCode, String subjectHash) {
        return switch (assetCode) {
            case "AST_EXPORT" -> jdbc.query("""
                SELECT min(retention_until) FROM biz.data_export WHERE subject_hash = :sh
                """, new MapSqlParameterSource("sh", subjectHash),
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null);
            case "AST_BACKUP" -> jdbc.query("""
                SELECT min(retention_until) FROM biz.backup_archive WHERE subject_hash = :sh
                """, new MapSqlParameterSource("sh", subjectHash),
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null);
            default -> null;
        };
    }

    private String recheckHash(String assetCode, String subjectHash) {
        int remaining = countSubjectRows(assetCode, subjectHash);
        return HashUtil.sha256(assetCode + ":" + subjectHash + ":remaining=" + remaining);
    }

    private void recomputeFunnel() {
        jdbc.update("""
            INSERT INTO biz.funnel_daily (job_id, apply_count, updated_at)
            SELECT job_id, count(*), now() FROM biz.application GROUP BY job_id
            ON CONFLICT (job_id) DO UPDATE SET apply_count = EXCLUDED.apply_count, updated_at = now()
            """, Map.of());
        // 已无任何投递的职位：汇总行归零而不是删除，保留重算差额痕迹
        jdbc.update("""
            UPDATE biz.funnel_daily f SET apply_count = 0, updated_at = now()
             WHERE NOT EXISTS (SELECT 1 FROM biz.application a WHERE a.job_id = f.job_id)
            """, Map.of());
    }

    private Map<String, Object> funnelBeforeValue() {
        Map<String, Object> m = new LinkedHashMap<>();
        jdbc.queryForList("SELECT job_id, apply_count FROM biz.funnel_daily ORDER BY job_id", Map.of())
            .forEach(r -> m.put((String) r.get("job_id"), r.get("apply_count")));
        return m;
    }

    private Map<String, Object> funnelAfterValue() {
        return funnelBeforeValue();
    }

    private String diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> d = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            int b = ((Number) before.getOrDefault(k, 0)).intValue();
            int a = ((Number) after.getOrDefault(k, 0)).intValue();
            d.put(k, a - b);
        }
        return d.toString();
    }

    private Map<String, Object> ticketRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("ticketNo", rs.getString("ticket_no"));
        m.put("subjectHash", rs.getString("subject_hash"));
        m.put("channel", rs.getString("channel"));
        m.put("idempotencyKey", rs.getString("idempotency_key"));
        m.put("status", rs.getString("status"));
        m.put("currentNode", rs.getString("current_node"));
        m.put("consentWithdrawnAt", rs.getObject("consent_withdrawn_at", OffsetDateTime.class));
        m.put("legalDeadline", rs.getObject("legal_deadline"));
        m.put("cancelBefore", rs.getObject("cancel_before", OffsetDateTime.class));
        m.put("sourceRef", rs.getString("source_ref"));
        m.put("returnReason", rs.getString("return_reason"));
        m.put("snapshotId", rs.getObject("snapshot_id"));
        m.put("closedAt", rs.getObject("closed_at", OffsetDateTime.class));
        m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
        return m;
    }

    private Map<String, Object> rsRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        int cols = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= cols; i++) {
            m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        return m;
    }
}
