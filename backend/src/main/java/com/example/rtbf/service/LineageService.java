package com.example.rtbf.service;

import com.example.rtbf.web.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据资产接入登记与血缘注册绑定为一次事务；枚举沿血缘图实时遍历（全图 BFS），
 * 断链边不自动跨越，转为人工确认点。
 */
@Service
public class LineageService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public LineageService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record AssetRegisterRequest(String assetCode, String assetName, String storageType,
                                       String targetRef, String externalParty, Integer retentionDays,
                                       String dispositionDefault, List<String> parentAssetCodes) {
    }

    @Transactional
    public Map<String, Object> registerAsset(AssetRegisterRequest req, Principal principal) {
        Integer exists = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.data_asset WHERE asset_code = :c",
            new MapSqlParameterSource("c", req.assetCode()), Integer.class);
        if (exists != null && exists > 0) {
            throw ApiException.conflict("资产编码已登记: " + req.assetCode());
        }
        // 同一事务内先登记资产、再注册血缘边；父资产缺失则整体回滚——接入与血缘注册是一次操作
        jdbc.update("""
            INSERT INTO compliance.data_asset
              (asset_code, asset_name, storage_type, target_ref, external_party, retention_days, disposition_default)
            VALUES (:code, :name, :st, :ref, :party, :ret, :disp)
            """, new MapSqlParameterSource()
            .addValue("code", req.assetCode())
            .addValue("name", req.assetName())
            .addValue("st", req.storageType())
            .addValue("ref", req.targetRef())
            .addValue("party", req.externalParty())
            .addValue("ret", req.retentionDays())
            .addValue("disp", req.dispositionDefault()));

        List<String> parents = req.parentAssetCodes() == null ? List.of() : req.parentAssetCodes();
        for (String parent : parents) {
            Integer parentExists = jdbc.queryForObject(
                "SELECT count(*) FROM compliance.data_asset WHERE asset_code = :c",
                new MapSqlParameterSource("c", parent), Integer.class);
            if (parentExists == null || parentExists == 0) {
                throw ApiException.badRequest("父资产未登记，血缘注册失败，本次接入整体回滚: " + parent);
            }
            jdbc.update("""
                INSERT INTO compliance.lineage_edge (from_asset_code, to_asset_code)
                VALUES (:from, :to)
                """, new MapSqlParameterSource().addValue("from", parent).addValue("to", req.assetCode()));
        }
        audit.log(null, principal.getName(), "ASSET_REGISTERED_WITH_LINEAGE",
                  Map.of("assetCode", req.assetCode(), "parents", parents));
        return Map.of("assetCode", req.assetCode(), "registeredEdges", parents.size(),
                      "boundInOneTransaction", true);
    }

    public List<Map<String, Object>> listAssets() {
        return jdbc.queryForList("""
            SELECT asset_code AS asset_code, asset_name, storage_type, target_ref, external_party,
                   retention_days, disposition_default, registered_at
            FROM compliance.data_asset ORDER BY asset_code
            """, Map.of());
    }

    public List<Map<String, Object>> listEdges() {
        return jdbc.queryForList("""
            SELECT from_asset_code AS from_asset_code, to_asset_code AS to_asset_code, edge_type, broken
            FROM compliance.lineage_edge ORDER BY id
            """, Map.of());
    }

    public List<Map<String, Object>> listGaps() {
        return jdbc.queryForList("""
            SELECT g.id, g.asset_code, a.asset_name, g.description, g.status,
                   g.ticket_id, g.confirmed_by, g.confirmed_at
            FROM compliance.lineage_gap g
            JOIN compliance.data_asset a ON a.asset_code = g.asset_code
            ORDER BY g.id
            """, Map.of());
    }

    public record TraversedItem(String assetCode, String storageType, String targetRef,
                                String lineagePath, boolean brokenGap, Long gapId) {
    }

    /**
     * 实时血缘遍历：从无入边的根资产出发 BFS；broken 边不跨越，目标资产作为断链点挂起。
     */
    public List<TraversedItem> traverse() {
        List<Map<String, Object>> assets = listAssets();
        Map<String, Map<String, Object>> assetByCode = new LinkedHashMap<>();
        assets.forEach(a -> assetByCode.put((String) a.get("asset_code"), a));

        Set<String> hasIncoming = new HashSet<>();
        List<Map<String, Object>> edges = listEdges();
        Map<String, List<Map<String, Object>>> outgoing = new LinkedHashMap<>();
        for (Map<String, Object> e : edges) {
            hasIncoming.add((String) e.get("to_asset_code"));
            outgoing.computeIfAbsent((String) e.get("from_asset_code"), k -> new ArrayList<>()).add(e);
        }

        List<TraversedItem> out = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        // 队列元素：资产编码 + 到达路径
        List<String[]> queue = new ArrayList<>();
        for (String code : assetByCode.keySet()) {
            if (!hasIncoming.contains(code)) {
                queue.add(new String[]{code, code});
            }
        }
        int head = 0;
        while (head < queue.size()) {
            String[] cur = queue.get(head++);
            String code = cur[0];
            String path = cur[1];
            if (!visited.add(code)) {
                continue;
            }
            Map<String, Object> asset = assetByCode.get(code);
            Long gapId = findAnyGap(code);
            out.add(new TraversedItem(code, (String) asset.get("storage_type"),
                                      (String) asset.get("target_ref"), path, gapId != null, gapId));
            for (Map<String, Object> e : outgoing.getOrDefault(code, List.of())) {
                String to = (String) e.get("to_asset_code");
                boolean broken = Boolean.TRUE.equals(e.get("broken"));
                if (broken) {
                    // 断链：不跨越，目标资产作为挂起项输出一次
                    if (!visited.contains(to)) {
                        Map<String, Object> target = assetByCode.get(to);
                        Long g = findAnyGap(to);
                        out.add(new TraversedItem(to, (String) target.get("storage_type"),
                                                  (String) target.get("target_ref"),
                                                  path + " -X[断链]-> " + to, true, g));
                        visited.add(to);
                    }
                    continue;
                }
                if (!visited.contains(to)) {
                    queue.add(new String[]{to, path + " -> " + to});
                }
            }
        }
        return out;
    }

    private Long findOpenGap(String assetCode) {
        List<Long> ids = jdbc.queryForList("""
            SELECT id FROM compliance.lineage_gap WHERE asset_code = :c AND status = 'OPEN'
            """, new MapSqlParameterSource("c", assetCode), Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 断链是血缘图上的客观事实：即使全局断链点已被其他工单确认，遍历仍需返回其 id，
     *  由每个工单在自己的快照项里独立做纳入/排除确认。 */
    private Long findAnyGap(String assetCode) {
        List<Long> ids = jdbc.queryForList("""
            SELECT id FROM compliance.lineage_gap WHERE asset_code = :c ORDER BY id LIMIT 1
            """, new MapSqlParameterSource("c", assetCode), Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Transactional
    public void confirmGap(long gapId, boolean inScope, Long ticketId, Principal principal) {
        int n = jdbc.update("""
            UPDATE compliance.lineage_gap
               SET status = :st, confirmed_by = :actor, confirmed_at = now(), ticket_id = :tid
             WHERE id = :id AND status = 'OPEN'
            """, new MapSqlParameterSource()
            .addValue("st", inScope ? "CONFIRMED_IN_SCOPE" : "CONFIRMED_OUT_SCOPE")
            .addValue("actor", principal.getName())
            .addValue("tid", ticketId)
            .addValue("id", gapId));
        if (n == 0) {
            throw ApiException.conflict("断链点不存在或已确认");
        }
        audit.log(ticketId, principal.getName(), "LINEAGE_GAP_CONFIRMED",
                  Map.of("gapId", gapId, "inScope", inScope));
    }
}
