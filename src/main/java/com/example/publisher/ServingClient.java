package com.example.publisher;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** HTTP-клиент marketplace-serving: POST /config. */
@Component
public class ServingClient {

    private final RestClient restClient;

    public ServingClient(RestClient.Builder builder, @Value("${publisher.serving-url}") String servingUrl) {
        // Без таймаутов зависший Serving повесил бы publisher навсегда
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = builder.baseUrl(servingUrl).requestFactory(requestFactory).build();
    }

    /**
     * Отправить конфиг. Ответ не 2xx, таймаут или отказ соединения — исключение RestClientException.
     */
    public ApplyResult send(OutboxRecord record) {
        ConfigPayload payload = record.payload();
        ServingConfig body = new ServingConfig(record.accountId(), record.version(),
                payload.floorPrice(), payload.currency(), payload.blockedDomains());
        return restClient.post().uri("/config").body(body).retrieve().body(ApplyResult.class);
    }

    /** Тело POST /config в формате marketplace-serving. */
    record ServingConfig(
            @JsonProperty("account_id") String accountId,
            @JsonProperty("version") long version,
            @JsonProperty("floor_price") BigDecimal floorPrice,
            @JsonProperty("currency") String currency,
            @JsonProperty("blocked_domains") List<String> blockedDomains) {
    }

    /** Ответ Serving: применён ли конфиг (false — версия не новее текущей) и какая версия теперь текущая. */
    public record ApplyResult(
            @JsonProperty("applied") boolean applied,
            @JsonProperty("current_version") long currentVersion) {
    }
}
