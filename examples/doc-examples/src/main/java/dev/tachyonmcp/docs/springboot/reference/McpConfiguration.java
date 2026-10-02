package dev.tachyonmcp.docs.springboot.reference;

// snips-start: springboot_customizer
import dev.tachyonmcp.spring.boot.TachyonServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpConfiguration {
    @Bean
    TachyonServerCustomizer mcpSessions(RedisSessionStore store) {
        return builder -> builder.session(session -> session.sessionStore(store));
    }
}
// snips-end: springboot_customizer
