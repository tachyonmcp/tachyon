/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.example.aot;

import dev.tachyonmcp.api.annotations.McpTool;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * An application configuration that happens to live under {@code dev.tachyonmcp.*} — the namespace
 * anything built on Tachyon's own packages would use. Infrastructure is the starter's own
 * auto-configuration, not this namespace, so its interface-typed bean must still be reported.
 */
@Configuration(proxyBeanMethods = false)
public class NamespacedBeanConfig {

    /** The type the {@code @Bean} method advertises. Carries no Tachyon annotation. */
    public interface Farewell {
        String bye(String name);
    }

    /** The type that actually declares the feature, built inside the factory method. */
    public static class FarewellImpl implements Farewell {
        @Override
        @McpTool(description = "Says goodbye by name")
        public String bye(String name) {
            return "Bye, " + name + "!";
        }
    }

    @Bean
    public Farewell farewell() {
        final var factory = new ProxyFactory(new FarewellImpl());
        factory.setProxyTargetClass(true);
        return (Farewell) factory.getProxy();
    }
}
