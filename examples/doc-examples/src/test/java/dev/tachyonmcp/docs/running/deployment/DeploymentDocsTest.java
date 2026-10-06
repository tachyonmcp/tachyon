package dev.tachyonmcp.docs.running.deployment;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.docs.RawHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentDocsTest {

    @Test
    @Timeout(30)
    void bindingAllInterfacesListensOnTheWildcardAddress() {
        try (var all = BindAllInterfaces.start()) {
            assertThat(all.host()).isIn("0.0.0.0", "0:0:0:0:0:0:0:0");
            assertThat(RawHttp.postStatus(all.port(), "localhost:" + all.port())).isEqualTo(200);
        }
    }

    @Test
    @Timeout(30)
    void defaultBindIsLoopbackOnly() {
        try (var server = TachyonServer.builder().port(0).build()) {
            server.start();
            assertThat(server.host()).isEqualTo("127.0.0.1");
        }
    }

    @Test
    @Timeout(60)
    @ResourceLock("localhost:18091")
    @ResourceLock("localhost:8080")
    void portComesFromTheEnvironmentAndDefaultsTo8080() throws Exception {
        try (var ignored = ForkedMain.start(
            "dev.tachyonmcp.docs.running.deployment.PortFromEnvServer", Map.of("PORT", "18091"), 18091)) {
            assertThat(RawHttp.postStatus(18091, "localhost:18091")).isEqualTo(200);
        }
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.running.deployment.PortFromEnvServer")) {
            assertThat(RawHttp.postStatus(8080, "localhost:8080")).isEqualTo(200);
        }
    }

    @Test
    @Timeout(60)
    @ResourceLock("localhost:8080")
    void allowedHostFromTheEnvironmentAdmitsThePublicHostnameButNotItsTrailingDotForm() throws Exception {
        try (var ignored = ForkedMain.start(
            "dev.tachyonmcp.docs.running.deployment.PublicHostServer",
            Map.of("ALLOWED_HOST", "mcp.example.com"),
            8080)) {
            assertThat(RawHttp.postStatus(8080, "mcp.example.com")).isEqualTo(200);
            assertThat(RawHttp.postStatus(8080, "mcp.example.com.")).as("trailing dot probe").isEqualTo(403);
            assertThat(RawHttp.postStatus(8080, "localhost:8080")).isEqualTo(200);
        }
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.running.deployment.PublicHostServer")) {
            assertThat(RawHttp.postStatus(8080, "mcp.example.com")).as("unset allowlist").isEqualTo(403);
        }
        try (var ignored = ForkedMain.start(
            "dev.tachyonmcp.docs.running.deployment.PublicHostServer", Map.of("ALLOWED_HOST", "  "), 8080)) {
            assertThat(RawHttp.postStatus(8080, "mcp.example.com")).as("blank value is ignored").isEqualTo(403);
        }
    }

    @SuppressWarnings("EmptyTryBlock")
    @Test
    @Timeout(60)
    @ResourceLock("localhost:8080")
    void anAllowedHostHoldingASchemeIsRejectedWhenTheServerIsBuilt() {
        assertThatThrownBy(() -> {
            try (var ignore = ForkedMain.start(
                "dev.tachyonmcp.docs.running.deployment.PublicHostServer",
                Map.of("ALLOWED_HOST", "https://mcp.example.com"),
                8080)) {
            }
        })
            .hasMessageContaining("exited");
    }

    @Test
    void browserOnAnotherOriginNeedsBothAllowedHostsAndAllowedOrigins() {
        try (var server = BrowserServer.start()) {
            var host = "mcp.example.com:" + server.port();

            var allowed = RawHttp.post("127.0.0.1", server.port(), host, "https://app.example.com");
            assertThat(allowed.status()).isEqualTo(200);
            assertThat(allowed.headers()).containsEntry("access-control-allow-origin", "https://app.example.com");

            assertThat(RawHttp.post("127.0.0.1", server.port(), host, "https://evil.example.com").status())
                .isEqualTo(403);
            assertThat(RawHttp.post("127.0.0.1", server.port(), host, null).status())
                .as("clients that send no Origin are unaffected")
                .isEqualTo(200);
        }
    }
}
