package com.example.publisher;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Таблица outbox в базе marketplace-api. Схемой владеет API (миграции Flyway там),
 * publisher только читает записи и меняет их статус.
 */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public OutboxRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /** Недоставленные записи по порядку: по аккаунту, внутри аккаунта — по версии. */
    public List<OutboxRecord> findNew() {
        return jdbc.sql("""
                        SELECT id, account_id, version, payload::text AS payload
                        FROM outbox
                        WHERE status = 'NEW'
                        ORDER BY account_id, version
                        """)
                .query((rs, rowNum) -> new OutboxRecord(
                        rs.getLong("id"),
                        rs.getString("account_id"),
                        rs.getLong("version"),
                        jsonMapper.readValue(rs.getString("payload"), ConfigPayload.class)))
                .list();
    }

    public void markSent(long id) {
        setStatus(id, "SENT");
    }

    public void markFailed(long id) {
        setStatus(id, "FAILED");
    }

    private void setStatus(long id, String status) {
        jdbc.sql("UPDATE outbox SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", id)
                .update();
    }
}
