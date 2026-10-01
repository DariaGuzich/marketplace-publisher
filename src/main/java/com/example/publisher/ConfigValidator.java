package com.example.publisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Проверка конфига перед отправкой в Serving. Пустой список — конфиг валиден. */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    public static List<String> validate(ConfigPayload config) {
        List<String> errors = new ArrayList<>();
        if (config.floorPrice() == null || config.floorPrice().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("floor_price must be >= 0, got " + config.floorPrice());
        }
        if (config.currency() == null || !config.currency().matches("[A-Z]{3}")) {
            errors.add("currency must be 3 uppercase letters, got " + config.currency());
        }
        if (config.blockedDomains() == null || config.blockedDomains().stream().anyMatch(d -> d == null || d.isBlank())) {
            errors.add("blocked_domains must not contain empty values");
        }
        return errors;
    }
}
