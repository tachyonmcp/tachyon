/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.weather;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Spring Boot entry point; Tachyon's starter owns the MCP server lifecycle. */
@SpringBootApplication(proxyBeanMethods = false)
public class WeatherApplication {
    /** Creates the application configuration. */
    public WeatherApplication() {}

    /**
     * Starts the application.
     * @param args Spring Boot command-line properties
     */
    public static void main(String[] args) {
        SpringApplication.run(WeatherApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    HttpClient weatherHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean
    @ConditionalOnMissingBean(WeatherProvider.class)
    WeatherProvider weatherProvider(HttpClient weatherHttpClient) {
        return new OpenMeteoProvider(weatherHttpClient);
    }
}
