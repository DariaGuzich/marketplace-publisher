package com.example.publisher;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Падение после отправки, но до пометки SENT → после «перезапуска» та же версия отправляется ещё раз.
 */
@TestPropertySource(properties = "publisher.crash-after-send=true")
class CrashAfterSendTest extends PublisherTestBase {

    // Настоящий CrashSimulator остановил бы JVM вместе с тестом. Мок при первом вызове бросает исключение
    // (процесс «упал»), при втором ничего не делает (после перезапуска падения уже нет)
    @MockitoBean
    CrashSimulator crashSimulator;

    @Test
    void recordIsDeliveredAgainAfterCrash() {
        doThrow(new IllegalStateException("simulated crash"))
                .doNothing()
                .when(crashSimulator).crash(any());
        serving.stubFor(post(urlEqualTo("/config")).willReturn(okJson("""
                {"applied": true, "current_version": 0}
                """)));
        insertOutbox("acc-a", 0, "1.5", "USD");

        assertThatThrownBy(() -> publisher.publishPending()).hasMessage("simulated crash");
        assertThat(deliveredBodies()).hasSize(1);
        assertThat(status("acc-a", 0)).isEqualTo("NEW");    // отправлено, но не помечено

        publisher.publishPending();                         // «перезапуск»: следующий цикл

        assertThat(deliveredBodies()).hasSize(2);           // Serving получил ту же версию дважды
        assertThat(status("acc-a", 0)).isEqualTo("SENT");
    }
}
