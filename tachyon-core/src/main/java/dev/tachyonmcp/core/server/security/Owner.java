/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.security.SecurityContext;
import org.jspecify.annotations.Nullable;

/**
 * Who owns state that outlives a request: a session, a task. A caller is either {@link #anonymous()}
 * or {@link Named} by its principal's name; there is no third, half-known kind.
 *
 * <p>Two questions are asked of an owner, and they differ on purpose:
 *
 * <ul>
 *   <li>{@code equals}: <em>are these the same caller?</em> An anonymous owner equals only
 *       anonymous. A session uses it: an anonymous session serves only anonymous callers.
 *   <li>{@link #admits}: <em>may this caller reach what the owner owns?</em> A named owner admits
 *       only itself; an anonymous owner admits everyone, because state nobody owns is left to the
 *       application's own access control. A task uses it.
 * </ul>
 */
@InternalApi
public sealed interface Owner {

    /** Returns the owner of what an unauthenticated caller, or the server itself, creates. */
    static Owner anonymous() {
        return Anonymous.INSTANCE;
    }

    /**
     * Derives the owner from the security context of one request: its {@link
     * SecurityContext#ownerKey() owner key}, which the context cannot hold blank.
     *
     * @param caller the security context fixed for the request
     * @return the caller's owner
     */
    static Owner of(SecurityContext caller) {
        return fromKey(caller.ownerKey());
    }

    /**
     * Restores an owner from its persisted form.
     *
     * @param key what {@link #key()} returned
     * @return the owner
     * @throws IllegalArgumentException if {@code key} is blank
     */
    static Owner fromKey(@Nullable String key) {
        return key == null ? anonymous() : new Named(key);
    }

    /**
     * Shortens a session or task id for the log line about a caller it refused: a log that holds the
     * whole id would hand it to whoever can read the log.
     *
     * @param id the id
     * @return at most its first 8 characters, and never more than half of it, then an ellipsis
     */
    static String truncated(String id) {
        return id.substring(0, Math.min(8, id.length() / 2)) + "…";
    }

    /** Returns the persisted form: the principal's name, or {@code null} for an anonymous owner. */
    @Nullable
    String key();

    /**
     * Whether this owner lets {@code caller} reach what it owns.
     *
     * @param caller the owner of the request asking
     * @return {@code true} if the caller may go on
     */
    boolean admits(Owner caller);

    /**
     * An owner known by its principal's name.
     *
     * @param name the principal's name, never blank
     */
    record Named(String name) implements Owner {

        /** Rejects a blank name, which could not tell two callers apart. */
        public Named {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("An owner needs a name");
            }
        }

        @Override
        public String key() {
            return name;
        }

        @Override
        public boolean admits(Owner caller) {
            return equals(caller);
        }
    }

    /** The owner of unowned state. */
    enum Anonymous implements Owner {
        /** The only anonymous owner. */
        INSTANCE;

        @Override
        public @Nullable String key() {
            return null;
        }

        @Override
        public boolean admits(Owner caller) {
            return true;
        }
    }
}
