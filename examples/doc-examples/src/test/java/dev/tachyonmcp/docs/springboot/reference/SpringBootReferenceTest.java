package dev.tachyonmcp.docs.springboot.reference;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.spring.boot.TachyonAutoConfiguration;
import dev.tachyonmcp.testkit.McpTestClients;
import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.ClassNameGenerator;
import org.springframework.aot.generate.DefaultGenerationContext;
import org.springframework.aot.generate.InMemoryGeneratedFiles;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.javapoet.ClassName;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBootReferenceTest {

    @Configuration(proxyBeanMethods = false)
    static class Stores {
        @Bean
        RedisSessionStore redisSessionStore() {
            return new RedisSessionStore();
        }
    }

    @Test
    void customizerPlugsAStoreIntoTheServerWhoseSessionsAreAlreadyOnByTtl() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TachyonAutoConfiguration.class))
                .withUserConfiguration(Stores.class, McpConfiguration.class)
                .withPropertyValues("tachyon.port=0", "tachyon.session.session-ttl=45s")
                .run(context -> {
                    var store = context.getBean(RedisSessionStore.class);
                    var server = context.getBean(TachyonServer.class);

                    try (var client = McpTestClients.forVersion(server.port(), "2025-11-25")) {
                        assertThat(client.initialize()).isNotNull();
                    }
                    assertThat(store.created()).as("the customizer's store backs the session").isEqualTo(1);
                });
    }

    @Test
    void reflectionForBindingRegistersHintsForTheNamedTypes() {
        var context = new GenericApplicationContext();
        context.registerBean(NativeHints.class);
        var generationContext = new DefaultGenerationContext(
                new ClassNameGenerator(ClassName.get(SpringBootReferenceTest.class)), new InMemoryGeneratedFiles());

        new ApplicationContextAotGenerator().processAheadOfTime(context, generationContext);

        RuntimeHints hints = generationContext.getRuntimeHints();
        assertThat(RuntimeHintsPredicates.reflection().onType(GreetingRequest.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(GreetingResponse.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(RedisSessionStore.class)).rejects(hints);
    }
}
