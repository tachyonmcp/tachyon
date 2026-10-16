/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The owner key is the principal's name unless the provider says otherwise, and never blank. */
class SecurityContextTest {

    private static final Principal ALICE = () -> "alice";

    @Test
    void theOwnerKeyDefaultsToThePrincipalsName() {
        assertThat(SecurityContext.authenticated(ALICE, List.of()).ownerKey()).isEqualTo("alice");
        assertThat(SecurityContext.authenticated(ALICE, List.of(), Instant.MAX).ownerKey())
                .isEqualTo("alice");
        assertThat(SecurityContext.anonymous().ownerKey()).isNull();
    }

    @Test
    void aProviderStatesItsOwnKeyAndNeverABlankOne() {
        final var tenant = SecurityContext.authenticated(ALICE, List.of("a"), null, "tenant-1|alice");

        assertThat(tenant.ownerKey()).isEqualTo("tenant-1|alice");
        assertThat(tenant.principal().getName()).isEqualTo("alice");
        assertThat(tenant.expiresAt()).isNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SecurityContext.authenticated(ALICE, List.of(), null, " "));
    }
}
