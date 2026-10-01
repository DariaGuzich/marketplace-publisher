package com.example.publisher;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Общая обвязка тестов publisher:
 * - PostgreSQL в Docker с таблицей outbox (копия схемы API, см. outbox-schema.sql);
 * - WireMock вместо marketplace-serving: отвечает так, как настроит тест, и запоминает полученные запросы;
 * - расписание выключено, цикл доставки тест вызывает сам: publisher.publishPending().
 */
@SpringBootTest(properties = {
        "publisher.scheduling-enabled=false",
        "publisher.retry-delay-ms=10"
})
@Testcontainers
abstract class PublisherTestBase {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("outbox-schema.sql");

    @RegisterExtension
    static WireMockExtension serving = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void servingUrl(DynamicPropertyRegistry registry) {
        registry.add("publisher.serving-url", serving::baseUrl);
    }

    @Autowired
    Publisher publisher;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanOutbox() {
        jdbc.sql("DELETE FROM outbox").update();
    }

    void insertOutbox(String accountId, long version, String floorPrice, String currency) {
        jdbc.sql("INSERT INTO outbox (account_id, version, payload) VALUES (:account, :version, :payload::jsonb)")
                .param("account", accountId)
                .param("version", version)
                .param("payload", """
                        {"floor_price": %s, "currency": "%s", "blocked_domains": ["bad.com"]}
                        """.formatted(floorPrice, currency))
                .update();
    }

    String status(String accountId, long version) {
        return jdbc.sql("SELECT status FROM outbox WHERE account_id = :account AND version = :version")
                .param("account", accountId)
                .param("version", version)
                .query(String.class)
                .single();
    }

    /** Тела запросов, которые получил Serving, в порядке получения. */
    List<String> deliveredBodies() {
        return serving.findAll(postRequestedFor(urlEqualTo("/config"))).stream()
                .map(request -> request.getBodyAsString())
                .toList();
    }
}
