/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.security.SecurityContext;
import java.security.Principal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OwnerTest {

    private static final Principal ALICE = () -> "alice";

    /** K13: ownership follows the owner key, never the name alone. */
    @Test
    void twoCallersWithOneNameAndDifferentKeysAreTwoOwners() {
        final var tenantOne = SecurityContext.authenticated(ALICE, Set.of(), null, "tenant-1|alice");
        final var tenantTwo = SecurityContext.authenticated(ALICE, Set.of(), null, "tenant-2|alice");
        final var byName = SecurityContext.authenticated(ALICE, Set.of());

        assertThat(Owner.of(tenantOne)).isNotEqualTo(Owner.of(tenantTwo)).isNotEqualTo(Owner.of(byName));
        assertThat(Owner.of(tenantOne).key()).isEqualTo("tenant-1|alice");
        assertThat(Owner.of(byName)).isEqualTo(new Owner.Named("alice"));
        assertThat(Owner.of(SecurityContext.anonymous())).isEqualTo(Owner.anonymous());
        assertThat(Owner.of(tenantOne).admits(Owner.of(tenantTwo))).isFalse();
    }

    @Test
    void aLoggedIdIsNeverWholeAndNeverLongerThanEightCharacters() {
        assertThat(Owner.truncated("0123456789abcdef0123456789abcdef")).isEqualTo("01234567…");
        assertThat(Owner.truncated("abcd")).isEqualTo("ab…");
        assertThat(Owner.truncated("a")).isEqualTo("…");
        assertThat(Owner.truncated("")).isEqualTo("…");
    }
}
