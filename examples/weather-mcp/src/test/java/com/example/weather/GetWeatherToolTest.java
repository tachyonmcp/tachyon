/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather;

import com.example.weather.model.TemperatureUnit;
import com.example.weather.service.WeatherService;
import com.example.weather.spi.CityProvider;
import com.example.weather.spi.WeatherObservation;
import com.example.weather.spi.WeatherProvider;
import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.runtime.ContextNotifications;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.Args;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.features.tools.ToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.json.JacksonPayloadSerde;
import dev.tachyonmcp.core.server.json.NetworkntJsonSchemaValidator;
import dev.tachyonmcp.core.server.session.NoopInteractionContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

class GetWeatherToolTest {

    private final WeatherProvider weatherProvider = new TestWeatherProvider();
    private final CityProvider cityProvider = new TestCityProvider();

    @Test
    void returnsCityNotFoundWhenElicitationIsCancelled() throws Exception {
        var method = new AtomicReference<@Nullable String>();
        var context = context("{\"action\":\"cancel\"}", method);

        var result = invoke(GetWeatherTool.fn(new WeatherService(weatherProvider, cityProvider)), context);

        assertThat(method).hasValue("elicitation/create");
        assertThat(result).isEqualTo(ToolResult.error("City not found"));
    }

    @Test
    void returnsCityNotFoundWhenTheElicitedCityIsUnknown() throws Exception {
        var method = new AtomicReference<@Nullable String>();
        var context = context("{\"action\":\"accept\",\"content\":{\"city\":\"Unknown\"}}", method);

        var result = invoke(GetWeatherTool.fn(new WeatherService(weatherProvider, cityProvider)), context);

        assertThat(method).hasValue("elicitation/create");
        assertThat(result).isEqualTo(ToolResult.error("City not found"));
    }

    @Test
    void returnsFixedErrorWhenProviderFailsWithoutLeakingDetails() throws Exception {
        WeatherProvider failing = new WeatherProvider() {
            @Override
            public WeatherObservation currentWeather(String city) throws Exception {
                throw new IOException("connection refused to internal-host:6443");
            }

            @Override
            public CompletableFuture<WeatherObservation> currentWeatherAsync(String city) {
                return CompletableFuture.failedFuture(new IOException("unused"));
            }
        };

        var result = invoke(GetWeatherTool.fn(
            new WeatherService(failing, cityProvider)), context("unused", new AtomicReference<>()
        ));

        assertThat(result).isEqualTo(ToolResult.error("Could not get weather"));
    }

    @Test
    void fahrenheitAboveSeventyPassesOutputSchema() {
        var validator = new NetworkntJsonSchemaValidator();
        var schema = GetWeatherTool.DESCRIPTOR.outputSchema();

        var response = GetWeatherTool.toResponse(
            "Tallinn", new WeatherObservation("Sunny", 22.0, 40, 5.0), TemperatureUnit.FAHRENHEIT);

        assertThat(response.unit()).isEqualTo(TemperatureUnit.FAHRENHEIT);
        assertThat(response.temperature()).isCloseTo(71.6, within(0.001));
        assertThat(validator.validate(schema, JsonDocument.of(JacksonPayloadSerde.INSTANCE.serialize(response))))
            .isEmpty();

        var extremeHot = GetWeatherTool.toResponse(
            "Death Valley", new WeatherObservation("Sunny", 56.7, 5, 5.0), TemperatureUnit.FAHRENHEIT);
        var extremeCold = GetWeatherTool.toResponse(
            "Vostok", new WeatherObservation("Clear", -89.2, 5, 5.0), TemperatureUnit.FAHRENHEIT);
        assertThat(validator.validate(schema, JsonDocument.of(JacksonPayloadSerde.INSTANCE.serialize(extremeHot))))
            .isEmpty();
        assertThat(validator.validate(schema, JsonDocument.of(JacksonPayloadSerde.INSTANCE.serialize(extremeCold))))
            .isEmpty();
    }

    private static final ContextNotifications NOOP_NOTIFICATIONS = new ContextNotifications() {
        @Override
        public void log(LoggingLevel level, @Nullable String logger, @Nullable Object data) {
            //noop
        }


        @Override
        public void progress(@Nullable ProgressToken progressToken, double progress, double total, String message) {
            //noop
        }

        @Override
        public void comment(@Nullable String message) {
            //noop
        }
    };

    private static InteractionContext context(String response, AtomicReference<@Nullable String> method) {
        return new NoopInteractionContext() {
            @Override
            public CompletableFuture<String> sendRequest(String requestMethod, Object params) {
                method.set(requestMethod);
                return CompletableFuture.completedFuture(response);
            }

            @Override
            public ContextNotifications notifications() {
                return NOOP_NOTIFICATIONS;
            }
        };
    }

    private static ToolResult invoke(ToolFn fn, InteractionContext context) throws Exception {
        var result = new AtomicReference<@Nullable ToolResult>();
        var failure = new AtomicReference<@Nullable Exception>();
        Thread.ofVirtual().start(() -> {
            try {
                result.set(fn.apply(
                        context,
                        ToolRequest.builder()
                                .name("get-weather")
                                .arguments(Args.of(Map.of("city", "Unknown"), JacksonPayloadSerde.INSTANCE))
                                .build()));
            } catch (Exception e) {
                failure.set(e);
            }
        });
        await().atMost(Duration.ofSeconds(1)).until(() -> result.get() != null || failure.get() != null);
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
