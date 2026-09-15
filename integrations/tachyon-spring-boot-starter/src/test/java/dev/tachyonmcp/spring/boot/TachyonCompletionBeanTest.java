/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TachyonCompletionBeanTest {
    public interface CompletionService {
        List<String> complete(String prefix, @Nullable String sibling);
    }

    public static class CompletionServiceImpl implements CompletionService {
        private final TachyonServer server;

        CompletionServiceImpl(TachyonServer server) {
            this.server = server;
        }

        @Override
        @McpCompletion(prompt = "trip")
        public List<String> complete(String city, @Nullable String country) {
            assertThat(server.port()).isPositive();
            return List.of(city + ":" + (country == null ? "anywhere" : country));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean
        AtomicInteger intercepted() {
            return new AtomicInteger();
        }

        @Bean
        CompletionService completions(TachyonServer server, AtomicInteger intercepted) {
            final var factory = new ProxyFactory(new CompletionServiceImpl(server));
            factory.addAdvice((MethodInterceptor) invocation -> {
                intercepted.incrementAndGet();
                assertThat(invocation.proceed()).isEqualTo(List.of("Ri:Latvia"));
                return List.of("Riga");
            });
            return (CompletionService) factory.getProxy();
        }
    }

    @Test
    void discoversCompletionOnlyProxyAndBindsTargetParameterNamesThroughAdvice() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TachyonAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .withPropertyValues("tachyon.port=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var server = context.getBean(TachyonServer.class);
                    try (var client = McpTestClients.latest(server.port())) {
                        // language=json
                        assertThat(client.sendRpc("""
                                {"jsonrpc":"2.0","id":1,"method":"completion/complete","params":{
                                  "ref":{"type":"ref/prompt","name":"trip"},
                                  "argument":{"name":"city","value":"Ri"},
                                  "context":{"arguments":{"country":"Latvia"}}}}
                                """)).isSuccess().hasId(1).hasResult("""
                                {"completion":{"values":["Riga"]},"resultType":"complete"}
                                """);
                    }
                    assertThat(context.getBean(AtomicInteger.class)).hasValue(1);
                });
    }
}
