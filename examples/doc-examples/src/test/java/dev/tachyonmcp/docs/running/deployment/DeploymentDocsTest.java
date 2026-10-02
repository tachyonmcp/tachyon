package dev.tachyonmcp.docs.running.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.docs.RawHttp;
import dev.tachyonmcp.core.server.TachyonServer;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DeploymentDocsTest {

    private static String nonLoopbackAddress() throws IOException {
        for (var nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (var address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return "";
    }

    @Test
    void bindingAllInterfacesMakesTheServerReachableFromOutsideLoopback() throws Exception {
        var external = nonLoopbackAddress();
        assumeTrue(!external.isEmpty(), "no non-loopback IPv4 address on this host");

        try (var all = BindAllInterfaces.start()) {
            assertThat(all.host()).isIn("0.0.0.0", "0:0:0:0:0:0:0:0");
            assertThat(RawHttp.post(external, all.port(), "localhost:" + all.port(), null).status()).isEqualTo(200);
        }
        try (var loopbackOnly = TachyonServer.builder().port(0).build()) {
            loopbackOnly.start();
            assertThatThrownBy(() -> RawHttp.post(external, loopbackOnly.port(), "localhost", null))
                    .as("the default 127.0.0.1 bind refuses outside connections")
                    .hasRootCauseInstanceOf(java.net.ConnectException.class);
        }
    }

    @Test
    @Timeout(60)
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

    @Test
    @Timeout(60)
    void anAllowedHostHoldingASchemeIsRejectedWhenTheServerIsBuilt() {
        assertThatThrownBy(() -> ForkedMain.start(
                        "dev.tachyonmcp.docs.running.deployment.PublicHostServer",
                        Map.of("ALLOWED_HOST", "https://mcp.example.com"),
                        8080))
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
