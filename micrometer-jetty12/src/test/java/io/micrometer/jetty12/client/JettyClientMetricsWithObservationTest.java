/*
 * Copyright 2024 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.jetty12.client;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class JettyClientMetricsWithObservationTest extends JettyClientMetricsTest {

    private final ObservationRegistry observationRegistry = TestObservationRegistry.create();

    @BeforeEach
    @Override
    void beforeEach() throws Exception {
        observationRegistry.observationConfig().observationHandler(new DefaultMeterObservationHandler(registry));
        super.beforeEach();
    }

    @Override
    protected void addInstrumentingListener() {
        this.httpClient.getRequestListeners()
            .addListener(JettyClientMetrics.builder(registry, (request, result) -> request.getURI().getPath())
                .observationRegistry(observationRegistry)
                .build());
    }

    @Test
    void activeTimer(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get("/ok").willReturn(ok().withFixedDelay(100)));

        Thread thread = new Thread(() -> {
            try {
                httpClient.GET("http://localhost:" + wmRuntimeInfo.getHttpPort() + "/ok");
            }
            catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        thread.start();

        await().atMost(Duration.ofMillis(100))
            .pollDelay(Duration.ofMillis(10))
            .pollInterval(Duration.ofMillis(10))
            .untilAsserted(() -> {
                LongTaskTimer longTaskTimer = registry.find("jetty.client.requests.active")
                    .tags("uri", "/ok", "method", "GET")
                    .longTaskTimer();
                assertThat(longTaskTimer).isNotNull();
                assertThat(longTaskTimer.activeTasks()).isOne();
            });

        thread.join();

        httpClient.stop();

        assertThat(singleRequestLatch.await(10, SECONDS)).isTrue();
        assertThat(registry.get("jetty.client.requests")
            .tag("outcome", "SUCCESS")
            .tag("status", "200")
            .tag("uri", "/ok")
            .timer()
            .count()).isEqualTo(1);
    }

}
