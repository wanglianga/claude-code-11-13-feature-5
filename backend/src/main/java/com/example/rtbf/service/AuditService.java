package com.example.rtbf.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuditService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void log(Long ticketId, String actor, String action, Object detail) {
        String json;
        try {
            json = detail == null ? null : objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            json = String.valueOf(detail);
        }
        jdbc.update("""
            INSERT INTO compliance.audit_log (ticket_id, actor, action, detail_json)
            VALUES (:ticketId, :actor, :action, CAST(:detail AS jsonb))
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("actor", actor)
                .addValue("action", action)
                .addValue("detail", json));
    }

    public Map<String, Object> unwrapJson(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) {
            return Map.of();
        }
        return objectMapper.convertValue(v, Map.class);
    }
}
