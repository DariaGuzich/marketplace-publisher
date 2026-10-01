# marketplace-publisher

Config publisher: доставляет изменения настроек из outbox marketplace-api в marketplace-serving.

```
marketplace-api ──(одна транзакция)──► settings + outbox (PostgreSQL)
                                                │ раз в 3 с: записи NEW
                                                ▼
                                    marketplace-publisher (этот репозиторий)
                                                │ POST /config
                                                ▼
                                       marketplace-serving
```

## Как работает

Цикл (`Publisher.publishPending`) раз в `POLL_INTERVAL_MS`:

1. Читает из outbox записи со статусом `NEW`, по порядку `account_id, version`.
2. Для каждой записи:
   - **валидирует** конфиг (`ConfigValidator`): `floor_price >= 0`, `currency` — три заглавные латинские буквы,
     домены не пустые. Невалидная запись помечается `FAILED`, publisher переходит к следующей —
     очередь не блокируется;
   - **отправляет** в Serving (`POST /config`), при ошибке **повторяет**: `RETRY_ATTEMPTS` попыток с паузой
     `RETRY_DELAY_MS`;
   - после успешной отправки помечает запись `SENT`.
3. Если все попытки неудачны, цикл **останавливается**: запись и все следующие остаются `NEW`, следующий цикл
   начнёт с неё же. Порядок версий не нарушается, ничего не теряется. Пока Serving недоступен, доставка стоит
   для всех аккаунтов.

### Гарантия доставки: «хотя бы один раз» (at-least-once)

Запись помечается `SENT` **после** отправки. Если процесс упадёт между отправкой и пометкой, после перезапуска
запись уйдёт ещё раз: **дубли возможны**. Это безопасно, потому что Serving игнорирует версии, которые не новее
текущей, и отвечает 200 (`"applied": false`). Publisher пишет в лог `Serving ignored it`.

Обратный порядок («сначала пометить, потом отправить») давал бы «не больше одного раза» (at-most-once):
при падении между шагами версия была бы потеряна навсегда. «Ровно один раз» на практике получают как
at-least-once + идемпотентный получатель, как здесь.

### Падение после отправки (`CRASH_AFTER_SEND=true`)

Сразу после первой успешной отправки процесс завершается через `Runtime.halt` (как `kill -9`), запись
остаётся `NEW`. Запусти publisher снова **без** этой переменной: он отправит ту же версию ещё раз,
а Serving её проигнорирует. С переменной publisher будет падать на каждой записи.

### Откат

`POST /accounts/{account_id}/settings/rollback?to_version=N` в marketplace-api создаёт **новую** версию со
значениями версии N. Для publisher это обычная запись в outbox: отдельной логики отката здесь нет. Serving
применит её, потому что номер версии больше текущей. Если бы откат «возвращал» старый номер версии, Serving
отбросил бы его как устаревший.

## Запуск

Нужны Java 21, Maven, PostgreSQL из marketplace-infra (`docker compose up -d`), запущенные marketplace-api
(создаёт таблицу outbox своими миграциями) и marketplace-serving.

```bash
mvn spring-boot:run
CRASH_AFTER_SEND=true mvn spring-boot:run                     # bash
$env:CRASH_AFTER_SEND="true"; mvn spring-boot:run             # PowerShell
```

| Переменная | По умолчанию | Что это |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | база marketplace-api из docker-compose | откуда читать outbox |
| `SERVING_URL` | `http://localhost:8081` | куда доставлять |
| `POLL_INTERVAL_MS` | `3000` | пауза между циклами |
| `RETRY_ATTEMPTS` / `RETRY_DELAY_MS` | `3` / `1000` | повторы отправки одной записи |
| `CRASH_AFTER_SEND` | `false` | упасть после отправки, до пометки `SENT` |

HTTP-сервера у publisher нет. Что происходит, видно в логе (`Delivered ...`, `Invalid config ...`,
`Attempt 1/3 ... failed`, `Serving unavailable, stopping this cycle`) и в базе:

```sql
SELECT account_id, version, status FROM outbox ORDER BY account_id, version;
```

## Тесты

```bash
mvn verify      # нужен Docker
```

| Тест | Что проверяет |
|---|---|
| `PublisherTest.deliversNewRecordsInOrderAndMarksThemSent` | порядок `account_id, version`, статус `SENT` |
| `PublisherTest.invalidRecordIsMarkedFailedAndNextOneIsDelivered` | невалидные → `FAILED`, следующая валидная доставлена |
| `PublisherTest.retriesWhenServingFailsAndDeliversOnThirdAttempt` | Serving дважды отвечает 503, третья попытка успешна |
| `PublisherTest.stopsCycleWhenServingKeepsFailing` | 3 неудачи → цикл остановлен, записи остаются `NEW` |
| `CrashAfterSendTest` | «падение» после отправки → запись `NEW` → следующий цикл отправляет её ещё раз |
| `ConfigValidatorTest` | правила валидации |

Как устроены тесты (`PublisherTestBase`):
- **Testcontainers** — настоящий PostgreSQL. Таблица outbox создаётся скриптом `outbox-schema.sql`, копией
  миграции API (см. «Общая база» ниже);
- **WireMock** вместо Serving. Тест задаёт ответы (`200`, `503`, «сначала 503, потом 200» через сценарии) и
  проверяет, сколько и каких запросов пришло;
- **расписание выключено** (`publisher.scheduling-enabled=false`), тест сам вызывает `publishPending()`.
  Ждать срабатывания таймера в тесте — медленно и ненадёжно. Проверяется сам цикл, а то, что Spring вызывает
  его по расписанию, — забота Spring;
- настоящий `CrashSimulator` остановил бы JVM вместе с тестами, поэтому в `CrashAfterSendTest` он заменён
  моком, который в первый раз бросает исключение.

## CI (`.github/workflows/ci.yml`)

`mvn verify` на push в main, PR и вручную.

## Сознательные упрощения

### Publisher читает outbox прямо из базы API

Здесь два сервиса работают с одной таблицей. Это **общая база** — скрытый контракт: если API поменяет
структуру outbox, publisher сломается, и ни oasdiff, ни типы этого не заметят. Тесты publisher тоже не
заметят: в них своя копия схемы (`outbox-schema.sql`).

Как это обычно делают в реальных системах:
- **outbox читает и публикует сам владелец базы**. Небольшой процесс внутри API (или рядом с ним) читает
  outbox и публикует события в **брокер сообщений** (Kafka, RabbitMQ). Другие сервисы подписываются на
  брокер и в базу API не ходят;
- **CDC (Change Data Capture)**, например Debezium: читает журнал транзакций PostgreSQL (WAL) и сам
  превращает каждую новую строку outbox в сообщение в Kafka. Сервис не опрашивает таблицу по таймеру;
- у брокера контракт — **схема сообщения** (JSON Schema, Avro, AsyncAPI), и её проверяют так же, как
  OpenAPI: на совместимость в CI.

### Остальное

- **Один экземпляр publisher.** Два экземпляра прочитали бы одни и те же записи и отправили каждую дважды
  (не опасно для Serving, но лишняя работа). Для нескольких экземпляров записи разбирают с блокировкой:
  `SELECT ... FOR UPDATE SKIP LOCKED`.
- **Ошибка Serving останавливает доставку для всех аккаунтов.** В реальной системе очереди обычно разделены
  (например, по аккаунту), чтобы проблема одного не задерживала остальных.
- **Любая ошибка HTTP повторяется**, включая 4xx. Если Serving отвергнет конфиг как неправильный (400),
  publisher будет повторять отправку бесконечно. Настоящий publisher различает временные ошибки
  (таймаут, 503 — повторить) и постоянные (400 — пометить `FAILED`).
- Пауза между повторами постоянная. Обычно её увеличивают с каждой попыткой (exponential backoff) и добавляют
  случайный разброс (jitter), чтобы много клиентов не повторяли запросы одновременно.
- Записи `SENT` никогда не удаляются (см. README marketplace-api).
- Серверу Serving после перезапуска никто не досылает текущий конфиг: publisher отправляет только новые версии.

## Инструменты

- **Spring Boot** (без веб-сервера) — расписание (`@Scheduled`), конфигурация, `JdbcClient`, `RestClient`.
- **JdbcClient** — SQL-запросы руками. Таблицей владеет API, поэтому publisher не дублирует JPA-сущности,
  а два его запроса (`SELECT ... WHERE status = 'NEW'`, `UPDATE ... SET status`) видны целиком.
- **RestClient** с таймаутами (2 с): без них зависший Serving повесил бы цикл навсегда.
- **Testcontainers** — настоящий PostgreSQL в тестах. **WireMock** — заглушка HTTP-сервиса с управляемыми
  ответами и подсчётом запросов.

## Эксперименты

См. [EXPERIMENTS.md](https://github.com/DariaGuzich/marketplace-infra/blob/main/EXPERIMENTS.md) в marketplace-infra.
