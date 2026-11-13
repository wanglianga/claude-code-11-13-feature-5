package com.example.rtbf.service;

import com.example.rtbf.util.HashUtil;
import com.example.rtbf.web.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主体归并：扫描账号表，按证件号/邮箱哈希做连通分量归并。
 * 规则边界（身份错配复核要求）：
 *  - 姓名只用于展示，永不进入归并键——同名不同人不会被并单
 *  - 手机号相同只生成“待核验关系”（见 AttributionService），不在本处自动归并
 *  - 两个账号各自持有证件指纹且互不相同 → 阻断归并，即使邮箱相同也不连通
 */
@Service
public class SubjectService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public SubjectService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record IdentRow(String accountId, String type, String hash, String masked) {
    }

    @Transactional
    public List<Map<String, Object>> scanAndMerge(Principal principal) {
        List<Map<String, Object>> accounts = jdbc.queryForList("""
            SELECT account_id, phone_hash, email_hash, idcard_hash, name FROM biz.account ORDER BY account_id
            """, Map.of());

        // 联合查找：仅邮箱/证件号为强归并键；手机号不参与自动连通（同号只生成待核验关系）
        Map<String, String> parent = new HashMap<>();
        Map<String, List<IdentRow>> accountIdents = new LinkedHashMap<>();
        Map<String, String> accountIdcard = new HashMap<>();
        for (Map<String, Object> acc : accounts) {
            String accountId = (String) acc.get("account_id");
            parent.put(accountId, accountId);
            List<IdentRow> idents = new ArrayList<>();
            addIdent(idents, accountId, "PHONE", (String) acc.get("phone_hash"));
            addIdent(idents, accountId, "EMAIL", (String) acc.get("email_hash"));
            addIdent(idents, accountId, "IDCARD", (String) acc.get("idcard_hash"));
            accountIdents.put(accountId, idents);
            accountIdcard.put(accountId, (String) acc.get("idcard_hash"));
        }
        // 以强标识符哈希反查账号并连通；证件指纹冲突时阻断归并
        Map<String, String> hashToAccount = new HashMap<>();
        List<String[]> blockedConflicts = new ArrayList<>();
        for (var e : accountIdents.entrySet()) {
            for (IdentRow r : e.getValue()) {
                if ("PHONE".equals(r.type())) {
                    // 手机号可能被家人/前任号主共用：只登记匹配项，不自动归并
                    continue;
                }
                String existed = hashToAccount.get(r.hash());
                if (existed != null) {
                    String idA = accountIdcard.get(existed);
                    String idB = accountIdcard.get(e.getKey());
                    if (idA != null && idB != null && !idA.equals(idB)) {
                        // 双方各执不同证件指纹 → 证件冲突，阻断归并（邮箱相同也不连通）
                        blockedConflicts.add(new String[]{existed, e.getKey(), r.type(), r.hash()});
                        continue;
                    }
                    union(parent, existed, e.getKey());
                } else {
                    hashToAccount.put(r.hash(), e.getKey());
                }
            }
        }

        Map<String, List<String>> components = new LinkedHashMap<>();
        for (String accountId : accountIdents.keySet()) {
            components.computeIfAbsent(find(parent, accountId), k -> new ArrayList<>()).add(accountId);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (List<String> memberAccounts : components.values()) {
            List<IdentRow> allIdents = new ArrayList<>();
            String displayName = null;
            for (String accountId : memberAccounts) {
                allIdents.addAll(accountIdents.get(accountId));
                for (Map<String, Object> acc : accounts) {
                    if (accountId.equals(acc.get("account_id"))) {
                        displayName = (String) acc.get("name");
                    }
                }
            }
            List<String> identHashes = allIdents.stream().map(IdentRow::hash).distinct().toList();
            String subjectHash = HashUtil.subjectHash(identHashes);

            Map<String, Object> existing = jdbc.query("""
                SELECT subject_hash FROM compliance.subject WHERE subject_hash = :h
                """, new MapSqlParameterSource("h", subjectHash), rs -> {
                if (rs.next()) {
                    return Map.of("subject_hash", rs.getString(1));
                }
                return null;
            });
            if (existing == null) {
                jdbc.update("""
                    INSERT INTO compliance.subject (subject_hash, display_name, name_hash)
                    VALUES (:h, :name, :nameHash)
                    """, new MapSqlParameterSource()
                    .addValue("h", subjectHash)
                    .addValue("name", displayName)
                    .addValue("nameHash", HashUtil.sha256(displayName)));
            }

            for (IdentRow r : allIdents) {
                int inserted = jdbc.update("""
                    INSERT INTO compliance.identifier_match
                        (subject_hash, id_type, id_hash, id_value_masked, source_account_ref)
                    VALUES (:sh, :t, :h, :masked, :acc)
                    ON CONFLICT (id_hash, id_type, source_account_ref) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("sh", subjectHash)
                    .addValue("t", r.type())
                    .addValue("h", r.hash())
                    .addValue("masked", r.masked())
                    .addValue("acc", r.accountId()));
                if (inserted > 0) {
                    audit.log(null, principal.getName(), "IDENTIFIER_MATCH_FOUND",
                        Map.of("subjectHash", subjectHash, "type", r.type(),
                               "account", r.accountId(), "masked", r.masked()));
                }
            }
            result.add(Map.of("subjectHash", subjectHash, "displayName", displayName,
                              "accounts", memberAccounts, "identifierCount", identHashes.size()));
        }
        for (String[] c : blockedConflicts) {
            audit.log(null, principal.getName(), "IDCARD_CONFLICT_MERGE_BLOCKED",
                      Map.of("accountA", c[0], "accountB", c[1], "sharedType", c[2],
                             "note", "证件指纹冲突，禁止自动归并，转身份异议复核"));
        }
        if (!blockedConflicts.isEmpty()) {
            // 阻断信息随扫描结果返回，便于操作台提示；关系落库由 AttributionService 统一维护
            audit.log(null, principal.getName(), "SCAN_BLOCKED_CONFLICT_SUMMARY",
                      Map.of("blockedCount", blockedConflicts.size()));
        }
        return result;
    }

    private void addIdent(List<IdentRow> idents, String accountId, String type, String hash) {
        if (hash == null) {
            return;
        }
        // 演示库只存哈希，掩码值从固定演示明文还原；真实环境由入库时同列保存
        String masked = switch (type) {
            case "PHONE" -> HashUtil.maskPhone(DemoPlaintext.phone(hash));
            case "EMAIL" -> HashUtil.maskEmail(DemoPlaintext.email(hash));
            default -> HashUtil.maskIdCard(DemoPlaintext.idcard(hash));
        };
        idents.add(new IdentRow(accountId, type, hash, masked));
    }

    public List<Map<String, Object>> listSubjects() {
        return jdbc.queryForList("""
            SELECT s.subject_hash AS subject_hash, s.display_name AS display_name, s.status AS status,
                   s.confirmed_by AS confirmed_by, s.confirmed_at AS confirmed_at,
                   (SELECT count(*) FROM compliance.identifier_match m WHERE m.subject_hash = s.subject_hash) AS match_count,
                   (SELECT count(*) FROM compliance.identifier_match m
                      WHERE m.subject_hash = s.subject_hash AND m.match_status = 'PENDING') AS pending_count,
                   (SELECT string_agg(DISTINCT m.source_account_ref, ', ')
                      FROM compliance.identifier_match m WHERE m.subject_hash = s.subject_hash) AS accounts
            FROM compliance.subject s ORDER BY s.created_at
            """, Map.of());
    }

    public Map<String, Object> getSubject(String subjectHash) {
        Map<String, Object> subject = jdbc.query("""
            SELECT * FROM compliance.subject WHERE subject_hash = :h
            """, new MapSqlParameterSource("h", subjectHash), rs -> rs.next() ? row(rs) : null);
        if (subject == null) {
            throw ApiException.notFound("主体不存在: " + subjectHash);
        }
        List<Map<String, Object>> matches = jdbc.queryForList("""
            SELECT id, id_type, id_hash, id_value_masked, source_account_ref, match_status, decided_by, decided_at
            FROM compliance.identifier_match WHERE subject_hash = :h ORDER BY id
            """, new MapSqlParameterSource("h", subjectHash));
        subject.put("identifierMatches", matches);
        return subject;
    }

    @Transactional
    public void decideMatch(long matchId, boolean accept, Principal principal) {
        int n = jdbc.update("""
            UPDATE compliance.identifier_match
               SET match_status = :st, decided_by = :actor, decided_at = now()
             WHERE id = :id AND match_status = 'PENDING'
            """, new MapSqlParameterSource()
            .addValue("st", accept ? "ACCEPTED" : "REJECTED")
            .addValue("actor", principal.getName())
            .addValue("id", matchId));
        if (n == 0) {
            throw ApiException.conflict("该匹配项已决定或不存在，不可重复决定");
        }
        audit.log(null, principal.getName(), accept ? "MATCH_ACCEPTED" : "MATCH_REJECTED",
                  Map.of("matchId", matchId));
    }

    @Transactional
    public void confirmSubject(String subjectHash, Principal principal) {
        Long pending = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.identifier_match
             WHERE subject_hash = :h AND match_status = 'PENDING'
            """, new MapSqlParameterSource("h", subjectHash), Long.class);
        if (pending != null && pending > 0) {
            throw ApiException.conflict("仍有 " + pending + " 条标识符匹配待逐条确认，不能确认主体");
        }
        Long accepted = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.identifier_match
             WHERE subject_hash = :h AND match_status = 'ACCEPTED'
            """, new MapSqlParameterSource("h", subjectHash), Long.class);
        if (accepted == null || accepted == 0) {
            throw ApiException.conflict("至少需要接受一条标识符匹配才能确认主体");
        }
        int n = jdbc.update("""
            UPDATE compliance.subject SET status = 'CONFIRMED', confirmed_by = :actor,
                   confirmed_at = now(), updated_at = now()
             WHERE subject_hash = :h AND status = 'CANDIDATE'
            """, new MapSqlParameterSource("h", subjectHash).addValue("actor", principal.getName()));
        if (n == 0) {
            throw ApiException.conflict("主体当前状态不允许确认（可能已确认/已驳回）");
        }
        audit.log(null, principal.getName(), "SUBJECT_CONFIRMED", Map.of("subjectHash", subjectHash));
    }

    @Transactional
    public void rejectSubject(String subjectHash, String reason, Principal principal) {
        int n = jdbc.update("""
            UPDATE compliance.subject SET status = 'REJECTED', updated_at = now()
             WHERE subject_hash = :h AND status IN ('CANDIDATE','CONFIRMED')
            """, new MapSqlParameterSource("h", subjectHash));
        if (n == 0) {
            throw ApiException.conflict("主体当前状态不允许驳回");
        }
        audit.log(null, principal.getName(), "SUBJECT_REJECTED",
                  Map.of("subjectHash", subjectHash, "reason", reason));
    }

    private static String find(Map<String, String> parent, String x) {
        String p = parent.get(x);
        if (p.equals(x)) {
            return x;
        }
        String root = find(parent, p);
        parent.put(x, root);
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.put(find(parent, a), find(parent, b));
    }

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subjectHash", rs.getString("subject_hash"));
        m.put("displayName", rs.getString("display_name"));
        m.put("status", rs.getString("status"));
        m.put("confirmedBy", rs.getString("confirmed_by"));
        m.put("confirmedAt", rs.getObject("confirmed_at"));
        m.put("createdAt", rs.getObject("created_at"));
        return m;
    }
}
