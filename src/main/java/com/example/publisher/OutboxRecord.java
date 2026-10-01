package com.example.publisher;

/** Строка outbox со статусом NEW: версия конфига аккаунта, которую нужно доставить. */
public record OutboxRecord(long id, String accountId, long version, ConfigPayload payload) {
}
