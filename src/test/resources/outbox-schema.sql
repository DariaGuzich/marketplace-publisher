-- Копия таблицы outbox из marketplace-api (src/main/resources/db/migration/V3__create_outbox.sql).
-- Схемой владеет API. Если там outbox изменится, эту копию нужно обновить руками:
-- общая база — скрытый контракт между сервисами, его не проверяют ни oasdiff, ни типы.
CREATE TABLE outbox (
    id         BIGSERIAL    PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL,
    version    BIGINT       NOT NULL,
    payload    JSONB        NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (account_id, version)
);
