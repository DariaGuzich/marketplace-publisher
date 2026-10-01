package com.example.publisher;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

class PublisherTest extends PublisherTestBase {

    private static final String APPLIED = """
            {"applied": true, "current_version": 0}
            """;

    @Test
    void deliversNewRecordsInOrderAndMarksThemSent() {
        serving.stubFor(post(urlEqualTo("/config")).willReturn(okJson(APPLIED)));
        insertOutbox("acc-b", 0, "1.0", "USD");
        insertOutbox("acc-a", 1, "2.0", "USD");
        insertOutbox("acc-a", 0, "1.5", "USD");

        publisher.publishPending();

        assertThat(deliveredBodies())
                .extracting(PublisherTest::accountAndVersion)
                .containsExactly("acc-a v0", "acc-a v1", "acc-b v0");
        assertThat(status("acc-a", 0)).isEqualTo("SENT");
        assertThat(status("acc-a", 1)).isEqualTo("SENT");
        assertThat(status("acc-b", 0)).isEqualTo("SENT");
    }

    @Test
    void invalidRecordIsMarkedFailedAndNextOneIsDelivered() {
        serving.stubFor(post(urlEqualTo("/config")).willReturn(okJson(APPLIED)));
        insertOutbox("acc-a", 0, "-1", "USD");        // отрицательная цена
        insertOutbox("acc-a", 1, "1.5", "dollars");   // валюта не из 3 заглавных букв
        insertOutbox("acc-a", 2, "1.5", "USD");

        publisher.publishPending();

        assertThat(status("acc-a", 0)).isEqualTo("FAILED");
        assertThat(status("acc-a", 1)).isEqualTo("FAILED");
        assertThat(status("acc-a", 2)).isEqualTo("SENT");
        assertThat(deliveredBodies()).extracting(PublisherTest::accountAndVersion).containsExactly("acc-a v2");
    }

    @Test
    void retriesWhenServingFailsAndDeliversOnThirdAttempt() {
        // Сценарий WireMock: первые два ответа 503, третий — успех
        serving.stubFor(post(urlEqualTo("/config")).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("failed once"));
        serving.stubFor(post(urlEqualTo("/config")).inScenario("flaky").whenScenarioStateIs("failed once")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("failed twice"));
        serving.stubFor(post(urlEqualTo("/config")).inScenario("flaky").whenScenarioStateIs("failed twice")
                .willReturn(okJson(APPLIED)));
        insertOutbox("acc-a", 0, "1.5", "USD");

        publisher.publishPending();

        assertThat(deliveredBodies()).hasSize(3);
        assertThat(status("acc-a", 0)).isEqualTo("SENT");
    }

    @Test
    void stopsCycleWhenServingKeepsFailing() {
        serving.stubFor(post(urlEqualTo("/config")).willReturn(aResponse().withStatus(503)));
        insertOutbox("acc-a", 0, "1.5", "USD");
        insertOutbox("acc-b", 0, "1.5", "USD");

        publisher.publishPending();

        // 3 попытки для первой записи, до второй дело не дошло; обе остались NEW до следующего цикла
        assertThat(deliveredBodies()).extracting(PublisherTest::accountAndVersion)
                .containsExactly("acc-a v0", "acc-a v0", "acc-a v0");
        assertThat(status("acc-a", 0)).isEqualTo("NEW");
        assertThat(status("acc-b", 0)).isEqualTo("NEW");
    }

    /** {"account_id":"acc-a","version":1,...} → "acc-a v1" */
    private static String accountAndVersion(String body) {
        String account = body.replaceAll(".*\"account_id\":\"([^\"]+)\".*", "$1");
        String version = body.replaceAll(".*\"version\":(\\d+).*", "$1");
        return account + " v" + version;
    }
}
