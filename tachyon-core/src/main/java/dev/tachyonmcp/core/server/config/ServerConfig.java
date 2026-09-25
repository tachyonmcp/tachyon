/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import dev.tachyonmcp.api.server.config.RuntimeConfig;
import dev.tachyonmcp.api.server.config.ServerIdentity;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Aggregated server configuration grouping identity, capabilities, session, network, runtime,
 * observability, and security settings.
 */
public final class ServerConfig {

    private final ServerIdentity identity;
    private final CapabilitiesConfig capabilities;
    private final SessionConfig session;
    private final NetworkConfig network;
    private final RuntimeConfig runtime;
    private final ObservabilityConfig observability;
    private final SecurityConfig security;

    private ServerConfig(Builder builder) {
        this.identity = Objects.requireNonNull(builder.identity, "identity cannot be null");
        this.capabilities = Objects.requireNonNull(builder.capabilities, "capabilities cannot be null");
        this.session = Objects.requireNonNull(builder.session, "session cannot be null");
        this.network = Objects.requireNonNull(builder.network, "network cannot be null");
        this.runtime = Objects.requireNonNull(builder.runtime, "runtime cannot be null");
        this.observability = Objects.requireNonNull(builder.observability, "observability cannot be null");
        this.security = Objects.requireNonNull(builder.security, "security cannot be null");
    }

    /** Returns a builder for an immutable server configuration. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns server identity metadata. */
    public ServerIdentity identity() {
        return identity;
    }

    /** Returns the enabled MCP capabilities. */
    public CapabilitiesConfig capabilities() {
        return capabilities;
    }

    /** Returns session lifecycle and persistence settings. */
    public SessionConfig session() {
        return session;
    }

    /** Returns transport-level settings. */
    public NetworkConfig network() {
        return network;
    }

    /** Returns handler-execution runtime settings. */
    public RuntimeConfig runtime() {
        return runtime;
    }

    /** Returns passive observation-lifecycle settings. */
    public ObservabilityConfig observability() {
        return observability;
    }

    /** Returns request-authentication settings. */
    public SecurityConfig security() {
        return security;
    }

    /** Builder for {@link ServerConfig}. */
    public static final class Builder {

        @Nullable
        private ServerIdentity identity;

        @Nullable
        private CapabilitiesConfig capabilities;

        @Nullable
        private SessionConfig session;

        @Nullable
        private NetworkConfig network;

        @Nullable
        private RuntimeConfig runtime;

        @Nullable
        private ObservabilityConfig observability;

        private SecurityConfig security = SecurityConfig.disabled();

        private Builder() {}

        /** Sets server identity metadata. */
        public Builder identity(ServerIdentity identity) {
            this.identity = identity;
            return this;
        }

        /** Sets enabled MCP capabilities. */
        public Builder capabilities(CapabilitiesConfig capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        /** Sets session lifecycle and persistence settings. */
        public Builder session(SessionConfig session) {
            this.session = session;
            return this;
        }

        /** Sets transport-level settings. */
        public Builder network(NetworkConfig network) {
            this.network = network;
            return this;
        }

        /** Sets handler-execution runtime settings. */
        public Builder runtime(RuntimeConfig runtime) {
            this.runtime = runtime;
            return this;
        }

        /** Sets passive observation-lifecycle settings. */
        public Builder observability(ObservabilityConfig observability) {
            this.observability = observability;
            return this;
        }

        /** Sets request-authentication settings; defaults to {@link SecurityConfig#disabled()}. */
        public Builder security(SecurityConfig security) {
            this.security = security;
            return this;
        }

        /** Builds the immutable server configuration. */
        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
