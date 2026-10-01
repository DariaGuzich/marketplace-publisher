package com.example.publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Имитация падения процесса (CRASH_AFTER_SEND=true). Runtime.halt завершает JVM сразу,
 * без штатной остановки — как kill -9 или отключение питания.
 * В тестах этот бин подменяется: остановить JVM внутри теста нельзя.
 */
@Component
public class CrashSimulator {

    private static final Logger log = LoggerFactory.getLogger(CrashSimulator.class);

    public void crash(OutboxRecord record) {
        log.error("CRASH_AFTER_SEND: account {} version {} sent, but NOT marked as SENT. Halting the process.",
                record.accountId(), record.version());
        Runtime.getRuntime().halt(1);
    }
}
