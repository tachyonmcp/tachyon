/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.security.SecurityContext;

/**
 * Decides which tools, resources, resource templates and prompts a caller may see. One question, one
 * answer for every path: a feature the policy refuses is not listed, and a request that names it
 * (a call, a read, a subscription, a completion) is answered exactly like a request for a feature
 * that does not exist, so the answer reveals nothing about what is hidden.
 *
 * <p>It is role-based access control without a role type: Tachyon knows no roles. Resolve what a caller
 * is during authentication and put it on your own {@link java.security.Principal}, then read it back
 * here:
 *
 * <pre>{@code
 * AccessPolicy adminsOnly = (caller, feature) ->
 *         !feature.name().startsWith("admin-")
 *                 || (caller.principal() instanceof Staff staff && staff.roles().contains("admin"));
 * }</pre>
 *
 * <p>Must be pure and fast: in memory, no I/O, no blocking, no side effects. It is called once for
 * every item of a list ({@code n} calls for {@code n} items; that cost is accepted) and once for each
 * request that names a feature, on the thread serving the request. A policy that throws is not
 * "allowed": the request fails with an internal error. A policy may throw an {@link
 * AuthenticationException} to answer {@code 401}.
 *
 * <p>This is not the place for scopes. A tool's {@code requiredScopes} stay a step-up: the tool is
 * listed and a call without the scopes is answered {@code 403 insufficient_scope}, so a client can ask
 * for a token that covers it. A policy hides.
 *
 * <p>The extension that owns a feature still has to be negotiated for the caller to see it; the policy
 * is asked in addition.
 */
@ExperimentalApi
@FunctionalInterface
public interface AccessPolicy {

    /**
     * Returns whether {@code caller} may see {@code feature}.
     *
     * @param caller the caller of the request being served, anonymous when the server authenticates
     *     nobody
     * @param feature the tool, resource, resource template or prompt
     * @return {@code true} to list it and let the caller use it, {@code false} to hide it
     */
    boolean permits(SecurityContext caller, ServerFeature.Descriptor feature);

    /**
     * Returns the policy that lets every caller see everything. It is the policy of a server that
     * authenticates nobody, and what you choose, on purpose, for a server that does and has nothing to
     * hide.
     *
     * @return the permit-all policy
     */
    static AccessPolicy permitAll() {
        return (caller, feature) -> true;
    }
}
