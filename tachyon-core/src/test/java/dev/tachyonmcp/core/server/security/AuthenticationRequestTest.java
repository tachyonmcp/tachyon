/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The view hands a provider what a credential check needs and nothing it could change. */
class AuthenticationRequestTest {

    private final List<String> stored = new ArrayList<>(List.of("first", "second"));

    private final AuthenticationRequest request = new AuthenticationRequest(
            "POST", "/mcp?client=a", name -> name.equalsIgnoreCase("X-Api-Key") ? stored : null);

    @Test
    void exposesTheMethodAndTheUriAsSent() {
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri()).isEqualTo("/mcp?client=a");
    }

    @Test
    void returnsEveryValueOfAHeaderInArrivalOrderAndNothingForAnAbsentOne() {
        assertThat(request.headers("x-api-key")).containsExactly("first", "second");
        assertThat(request.headers("Authorization")).isEmpty();
    }

    @Test
    void theValuesCannotBeChangedAndTheSourceIsLeftAlone() {
        final var values = request.headers("X-Api-Key");

        assertThatThrownBy(() -> values.add("third")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> values.set(0, "other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(values::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(stored).containsExactly("first", "second");
    }
}
