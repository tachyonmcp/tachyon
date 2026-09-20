/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package example.aot;

import dev.tachyonmcp.api.annotations.McpTool;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * An application configuration that hides an annotated implementation behind an interface — the
 * shape AOT cannot inspect. The processor skips only Spring's and the starter's own configuration
 * classes as infrastructure, so this one is reported.
 */
@Configuration(proxyBeanMethods = false)
public class InterfaceBeanConfig {

    /** The type the {@code @Bean} method advertises. Carries no Tachyon annotation. */
    public interface Greeter {
        String greet(String name);
    }

    /** The type that actually declares the feature, built inside the factory method. */
    public static class GreeterImpl implements Greeter {
        @Override
        @McpTool(description = "Greets by name")
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    /** A plain bean whose concrete type AOT can inspect, so it must never be reported. */
    public static class VisibleGreeter {
        @McpTool(description = "Greets by name")
        public String hail(String name) {
            return "Hail, " + name + "!";
        }
    }

    @Bean
    public Greeter greeter() {
        final var factory = new ProxyFactory(new GreeterImpl());
        factory.setProxyTargetClass(true);
        return (Greeter) factory.getProxy();
    }

    @Bean
    public VisibleGreeter visibleGreeter() {
        return new VisibleGreeter();
    }
}
