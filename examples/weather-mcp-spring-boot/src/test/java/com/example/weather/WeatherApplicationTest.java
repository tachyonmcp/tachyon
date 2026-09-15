/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.weather;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WeatherApplicationTest {
    @Test
    void discoversAnnotationsAndServesWeatherWithoutExternalRequests() {
        new ApplicationContextRunner()
                .withUserConfiguration(WeatherApplication.class)
                .withPropertyValues("tachyon.port=0", "tachyon.host=127.0.0.1")
                .withBean(WeatherProvider.class, () -> new WeatherProvider() {
                    public Weather current(String city) {
                        return new Weather(city, "Clear sky", 20.0, TemperatureUnit.CELSIUS, 60, 10.0);
                    }

                    public List<String> cities(String prefix) {
                        return List.of("Tallinn", "Tartu").stream()
                                .filter(city -> city.startsWith(prefix))
                                .toList();
                    }
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var server = context.getBean(TachyonServer.class);
                    try (final var client = McpTestClients.latest(server.port())) {
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                             "params":{"name":"get-weather","arguments":{"city":"Tallinn","units":"FAHRENHEIT"}}}
                             """)).isSuccess().hasResult("""
                            {"content":[{"type":"text","text":"{\\\"city\\\":\\\"Tallinn\\\",\\\"condition\\\":\\\"Clear sky\\\",\\\"temperature\\\":68.0,\\\"unit\\\":\\\"FAHRENHEIT\\\",\\\"humidity\\\":60,\\\"windSpeed\\\":10.0}"}],
                             "structuredContent":{"city":"Tallinn","condition":"Clear sky","temperature":68.0,"unit":"FAHRENHEIT","humidity":60,"windSpeed":10.0},
                                 "resultType":"complete"}
                            """);
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":2,"method":"prompts/get",
                                 "params":{"name":"rewrite-forecast","arguments":{"forecast":"Sunny","style":"PIRATE"}}}
                                """)).isSuccess().hasResult("""
                                {"description":"Rewrite a forecast in PLAIN, CONCISE, or PIRATE style","messages":[{"role":"user","content":{"type":"text","text":"Rewrite the following weather forecast in pirate style. Preserve factual details:\\n\\nSunny"}}],"resultType":"complete"}
                                """);
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":3,"method":"completion/complete","params":{
                                 "ref":{"type":"ref/prompt","name":"rewrite-forecast"},"argument":{"name":"style","value":"pi"}}}
                                """)).isSuccess().hasResult("""
                                {"completion":{"values":["PIRATE"]},"resultType":"complete"}
                                """);
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":4,"method":"completion/complete","params":{
                                 "ref":{"type":"ref/resource","uri":"weather://current/{city}"},"argument":{"name":"city","value":"Ta"}}}
                                """)).isSuccess().hasResult("""
                                {"completion":{"values":["Tallinn","Tartu"]},"resultType":"complete"}
                                """);
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"weather://current/Tallinn"}}
                                """)).isSuccess().hasResult("""
                            {"cacheScope":"public","ttlMs":0,"contents":[{"uri":"weather://current/Tallinn","mimeType":"application/json","text":"{\\\"city\\\":\\\"Tallinn\\\",\\\"condition\\\":\\\"Clear sky\\\",\\\"temperature\\\":20.0,\\\"unit\\\":\\\"CELSIUS\\\",\\\"humidity\\\":60,\\\"windSpeed\\\":10.0}"}],"resultType":"complete"}
                            """);
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":6,"method":"tools/call",
                                 "params":{"name":"get-weather","arguments":{"city":"Tallinn"}}}
                                """)).isSuccess().hasStructuredContent("""
                            {"city":"Tallinn","condition":"Clear sky","temperature":20.0,"unit":"CELSIUS","humidity":60,"windSpeed":10.0}
                                """);
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":7,"method":"tools/call",
                                 "params":{"name":"get-weather","arguments":{"city":"Tallinn","units":"kelvin"}}}
                                """)).isJsonRpcError().hasErrorCode(-32602);
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":8,"method":"tools/call",
                                 "params":{"name":"get-weather","arguments":{}}}
                                """)).isJsonRpcError().hasErrorCode(-32602);
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":9,"method":"completion/complete","params":{
                                 "ref":{"type":"ref/resource","uri":"weather://current/{city}"},"argument":{"name":"city","value":"T"}}}
                                """)).isSuccess().hasResult("""
                                {"completion":{"values":[]},"resultType":"complete"}
                                """);
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":11,"method":"prompts/get",
                                 "params":{"name":"rewrite-forecast","arguments":{"forecast":"Sunny","style":"pirate"}}}
                                """))
                                .isJsonRpcError()
                                .hasErrorCode(-32602)
                                .hasErrorMessage("invalid argument 'style': must be one of [PLAIN, CONCISE, PIRATE]");
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":10,"method":"completion/complete","params":{
                                 "ref":{"type":"ref/prompt","name":"rewrite-forecast"},"argument":{"name":"forecast","value":"pi"}}}
                                """)).isSuccess().hasResult("""
                                {"completion":{"values":[],"hasMore":false},"resultType":"complete"}
                                """);
                    }
                });
    }
}
