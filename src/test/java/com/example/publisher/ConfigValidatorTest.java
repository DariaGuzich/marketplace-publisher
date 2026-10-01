package com.example.publisher;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidatorTest {

    @Test
    void validConfigHasNoErrors() {
        assertThat(ConfigValidator.validate(config("0", "USD", List.of("bad.com")))).isEmpty();
    }

    @Test
    void negativeFloorPriceIsInvalid() {
        assertThat(ConfigValidator.validate(config("-0.01", "USD", List.of())))
                .containsExactly("floor_price must be >= 0, got -0.01");
    }

    @Test
    void currencyMustBeThreeUppercaseLetters() {
        assertThat(ConfigValidator.validate(config("1", "usd", List.of()))).hasSize(1);
        assertThat(ConfigValidator.validate(config("1", "dollars", List.of()))).hasSize(1);
    }

    @Test
    void blankDomainIsInvalid() {
        assertThat(ConfigValidator.validate(config("1", "USD", List.of("bad.com", " ")))).hasSize(1);
    }

    private static ConfigPayload config(String floorPrice, String currency, List<String> domains) {
        return new ConfigPayload(new BigDecimal(floorPrice), currency, domains);
    }
}
