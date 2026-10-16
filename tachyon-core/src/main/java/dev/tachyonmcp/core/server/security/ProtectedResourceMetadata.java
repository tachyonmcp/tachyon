/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.protocol.codec.CodecSupport;
import dev.tachyonmcp.core.server.config.BearerConfig;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * The RFC 9728 Protected Resource Metadata of a bearer-token server: where it is, and what it says.
 * Everything is derived from the configured {@link BearerConfig#resource() resource URI}, never from a
 * request, so a client behind a gateway is told the public address and a spoofed {@code Host} or
 * {@code X-Forwarded-*} header changes nothing. The URL in a {@code 401} challenge and the paths the
 * server answers on both come from {@link #path}, so they cannot drift apart.
 */
@InternalApi
public final class ProtectedResourceMetadata {

    /** The well-known path of RFC 9728 §3. */
    public static final String WELL_KNOWN = "/.well-known/oauth-protected-resource";

    private ProtectedResourceMetadata() {}

    /**
     * Returns the path the metadata of {@code resource} is served at, as RFC 9728 §3.1 inserts the
     * well-known segment between the host and the resource's own path: {@code https://h/mcp} is served
     * at {@code /.well-known/oauth-protected-resource/mcp}, and {@code https://h} at the well-known
     * path itself.
     *
     * @param resource the canonical resource URI
     * @return the path
     */
    public static String path(URI resource) {
        final var resourcePath = resource.getRawPath();
        return resourcePath == null ? WELL_KNOWN : WELL_KNOWN + resourcePath;
    }

    /**
     * Returns the URL of the metadata, which the {@code resource_metadata} parameter of a challenge
     * carries (RFC 9728 §5.1).
     *
     * @param resource the canonical resource URI
     * @return the URL
     */
    public static URI url(URI resource) {
        return URI.create(resource.getScheme() + "://" + resource.getRawAuthority() + path(resource));
    }

    /**
     * Returns every path the metadata is served at: the path-inserted one and the root well-known path
     * (they are the same for a resource with no path).
     *
     * @param resource the canonical resource URI
     * @return the paths
     */
    public static Set<String> paths(URI resource) {
        return Set.copyOf(List.of(path(resource), WELL_KNOWN));
    }

    /**
     * Renders the metadata document (RFC 9728 §2) once: the resource, its authorization servers, the
     * scopes it supports when there are any, and that tokens are sent in the header only.
     *
     * @param bearer the bearer-token configuration
     * @return the UTF-8 JSON
     */
    public static byte[] json(BearerConfig bearer) {
        return CodecSupport.writeToBytes(gen -> {
            gen.writeStartObject();
            gen.writeStringProperty("resource", bearer.resource().toString());
            gen.writeName("authorization_servers");
            gen.writeStartArray();
            for (final var server : bearer.authorizationServers()) {
                gen.writeString(server.toString());
            }
            gen.writeEndArray();
            if (!bearer.scopesSupported().isEmpty()) {
                gen.writeName("scopes_supported");
                gen.writeStartArray();
                for (final var scope : bearer.scopesSupported()) {
                    gen.writeString(scope);
                }
                gen.writeEndArray();
            }
            gen.writeName("bearer_methods_supported");
            gen.writeStartArray();
            gen.writeString("header");
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }
}
