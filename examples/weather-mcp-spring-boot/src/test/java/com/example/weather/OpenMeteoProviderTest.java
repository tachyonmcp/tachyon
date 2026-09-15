/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class OpenMeteoProviderTest {
    private static final String TALLINN = """
            {"results":[{"name":"Tallinn","latitude":59.437,"longitude":24.7535}]}
            """;
    private static final String CURRENT = """
            {"current":{"temperature_2m":4.5,"relative_humidity_2m":81,"weather_code":3,"wind_speed_10m":12.2}}
            """;

    private final AtomicReference<String> geocodingBody = new AtomicReference<>(TALLINN);
    private final AtomicReference<String> forecastBody = new AtomicReference<>(CURRENT);
    private final AtomicReference<String> forecastQuery = new AtomicReference<>();
    private final AtomicInteger forecastRequests = new AtomicInteger();
    private HttpServer server;
    private HttpClient client;
    private OpenMeteoProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/geocoding", exchange -> respond(exchange, geocodingBody.get()));
        server.createContext("/forecast", exchange -> {
            forecastRequests.incrementAndGet();
            forecastQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, forecastBody.get());
        });
        server.start();
        final var base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
        provider = new OpenMeteoProvider(client, URI.create(base + "/geocoding"), URI.create(base + "/forecast"));
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop(0);
    }

    @Test
    void mapsValidResponses() throws Exception {
        assertThat(provider.current("Tallinn"))
                .isEqualTo(new Weather("Tallinn", "Overcast", 4.5, TemperatureUnit.CELSIUS, 81, 12.2));
        assertThat(forecastRequests).hasValue(1);
        assertThat(forecastQuery.get())
                .startsWith("latitude=59.437&longitude=24.7535&current=")
                .endsWith("&wind_speed_unit=kmh");

        geocodingBody.set("""
                {"results":[{"name":"Tallinn"},{"name":"Tartu"},{"name":"Tallinn"},{"name":" "},{}]}
                """);
        assertThat(provider.cities("Ta")).containsExactly("Tallinn", "Tartu");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"results\":[]}"})
    void rejectsUnknownCityWithoutForecastRequest(String body) {
        geocodingBody.set(body);

        assertThatThrownBy(() -> provider.current("Atlantis"))
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessage("not found");
        assertThat(forecastRequests).hasValue(0);
    }

    static Stream<Arguments> partialGeocoding() {
        return Stream.of(
                Arguments.of("{\"name\":\"Tallinn\",\"longitude\":24.75}", "'latitude' must be a number"),
                Arguments.of("{\"latitude\":null,\"longitude\":24.75}", "'latitude' must be a number"),
                Arguments.of("{\"latitude\":\"59.4\",\"longitude\":24.75}", "'latitude' must be a number"),
                Arguments.of("{\"latitude\":91,\"longitude\":24.75}", "'latitude' is out of range: 91.0"),
                Arguments.of("{\"latitude\":59.4}", "'longitude' must be a number"),
                Arguments.of("{\"latitude\":59.4,\"longitude\":true}", "'longitude' must be a number"),
                Arguments.of("{\"latitude\":59.4,\"longitude\":-181}", "'longitude' is out of range: -181.0"));
    }

    @ParameterizedTest
    @MethodSource("partialGeocoding")
    void rejectsPartialGeocodingWithoutForecastRequest(String result, String message) {
        geocodingBody.set("{\"results\":[" + result + "]}");

        assertThatThrownBy(() -> provider.current("Tallinn"))
                .isInstanceOf(IOException.class)
                .hasMessage("Open-Meteo field " + message);
        assertThat(forecastRequests).hasValue(0);
    }

    static Stream<Arguments> partialCurrentWeather() {
        return Stream.of(
                Arguments.of(
                        "{\"relative_humidity_2m\":81,\"weather_code\":3,\"wind_speed_10m\":12.2}",
                        "'temperature_2m' must be a number"),
                Arguments.of(
                        "{\"temperature_2m\":null,\"relative_humidity_2m\":81,\"weather_code\":3,\"wind_speed_10m\":12.2}",
                        "'temperature_2m' must be a number"),
                Arguments.of(
                        "{\"temperature_2m\":\"4.5\",\"relative_humidity_2m\":81,\"weather_code\":3,\"wind_speed_10m\":12.2}",
                        "'temperature_2m' must be a number"),
                Arguments.of(
                        "{\"temperature_2m\":4.5,\"weather_code\":3,\"wind_speed_10m\":12.2}",
                        "'relative_humidity_2m' must be an integer"),
                Arguments.of(
                        "{\"temperature_2m\":4.5,\"relative_humidity_2m\":81.5,\"weather_code\":3,\"wind_speed_10m\":12.2}",
                        "'relative_humidity_2m' must be an integer"),
                Arguments.of(
                        "{\"temperature_2m\":4.5,\"relative_humidity_2m\":101,\"weather_code\":3,\"wind_speed_10m\":12.2}",
                        "'relative_humidity_2m' is out of range: 101"),
                Arguments.of(
                        "{\"temperature_2m\":4.5,\"relative_humidity_2m\":81,\"wind_speed_10m\":12.2}",
                        "'weather_code' must be an integer"),
                Arguments.of(
                        "{\"temperature_2m\":4.5,\"relative_humidity_2m\":81,\"weather_code\":\"3\",\"wind_speed_10m\":12.2}",
                        "'weather_code' must be an integer"),
                Arguments.of(
                        "{\"temperature_2m\":4.5,\"relative_humidity_2m\":81,\"weather_code\":3}",
                        "'wind_speed_10m' must be a number"),
                Arguments.of(
                        "{\"temperature_2m\":4.5,\"relative_humidity_2m\":81,\"weather_code\":3,\"wind_speed_10m\":-1}",
                        "'wind_speed_10m' is out of range: -1.0"));
    }

    @ParameterizedTest
    @MethodSource("partialCurrentWeather")
    void rejectsPartialCurrentWeather(String current, String message) {
        forecastBody.set("{\"current\":" + current + "}");

        assertThatThrownBy(() -> provider.current("Tallinn"))
                .isInstanceOf(IOException.class)
                .hasMessage("Open-Meteo field " + message);
        assertThat(forecastRequests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"current\":null}", "{\"current\":[]}"})
    void rejectsMissingCurrentWeather(String body) {
        forecastBody.set(body);

        assertThatThrownBy(() -> provider.current("Tallinn"))
                .isInstanceOf(IOException.class)
                .hasMessage("Missing current weather");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (final var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
