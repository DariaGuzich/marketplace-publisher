package com.example.publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Цикл доставки: раз в publisher.poll-interval-ms читает записи NEW из outbox и по порядку отправляет их в Serving.
 * <p>
 * Гарантия доставки — «хотя бы один раз» (at-least-once): запись помечается SENT только после успешной отправки.
 * Если процесс упадёт между отправкой и пометкой, после перезапуска запись уйдёт ещё раз.
 * Дубли безопасны, потому что Serving игнорирует версии, которые не новее текущей.
 */
@Component
public class Publisher {

    private static final Logger log = LoggerFactory.getLogger(Publisher.class);

    private final OutboxRepository outbox;
    private final ServingClient serving;
    private final CrashSimulator crashSimulator;
    private final int retryAttempts;
    private final long retryDelayMs;
    private final boolean crashAfterSend;

    public Publisher(OutboxRepository outbox,
                     ServingClient serving,
                     CrashSimulator crashSimulator,
                     @Value("${publisher.retry-attempts}") int retryAttempts,
                     @Value("${publisher.retry-delay-ms}") long retryDelayMs,
                     @Value("${publisher.crash-after-send}") boolean crashAfterSend) {
        this.outbox = outbox;
        this.serving = serving;
        this.crashSimulator = crashSimulator;
        this.retryAttempts = retryAttempts;
        this.retryDelayMs = retryDelayMs;
        this.crashAfterSend = crashAfterSend;
    }

    @Scheduled(fixedDelayString = "${publisher.poll-interval-ms}")
    public void publishPending() {
        for (OutboxRecord record : outbox.findNew()) {
            List<String> errors = ConfigValidator.validate(record.payload());
            if (!errors.isEmpty()) {
                // Невалидный конфиг не блокирует очередь: помечаем и идём дальше
                log.warn("Invalid config, account {} version {} marked as FAILED: {}",
                        record.accountId(), record.version(), errors);
                outbox.markFailed(record.id());
                continue;
            }

            if (!sendWithRetry(record)) {
                // Serving недоступен: запись остаётся NEW, остальные тоже ждут. Следующий цикл начнёт с неё же,
                // поэтому порядок версий не нарушится
                log.error("Serving unavailable, stopping this cycle. Account {} version {} stays NEW",
                        record.accountId(), record.version());
                return;
            }

            if (crashAfterSend) {
                crashSimulator.crash(record);
            }
            outbox.markSent(record.id());
        }
    }

    private boolean sendWithRetry(OutboxRecord record) {
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                ServingClient.ApplyResult result = serving.send(record);
                if (result.applied()) {
                    log.info("Delivered account {} version {}", record.accountId(), record.version());
                } else {
                    log.info("Delivered account {} version {}, but Serving ignored it: current version is {}",
                            record.accountId(), record.version(), result.currentVersion());
                }
                return true;
            } catch (RestClientException e) {
                log.warn("Attempt {}/{} to deliver account {} version {} failed: {}",
                        attempt, retryAttempts, record.accountId(), record.version(), e.getMessage());
                if (attempt < retryAttempts) {
                    sleep(retryDelayMs);
                }
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
