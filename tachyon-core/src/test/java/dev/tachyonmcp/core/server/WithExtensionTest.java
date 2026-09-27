/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.extensions.AnnotatedExtension;
import dev.tachyonmcp.core.server.extensions.MisannotatedExtension;
import dev.tachyonmcp.core.server.extensions.StubExtension;
import dev.tachyonmcp.core.server.extensions.UnregisteredExtension;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WithExtensionTest {

    @Test
    void configurersAccumulateOnOneBuilderInCallOrder() {
        try (var server = TachyonServer.builder()
                .withExtension(StubExtension.class, b -> b.option("first"))
                .name("between")
                .withExtension(StubExtension.class, b -> b.option("second").option("third"))
                .build()) {
            assertThat(server.extensions()).hasSize(1);
            assertThat(server.extension(StubExtension.class))
                    .get()
                    .satisfies(extension -> assertThat(extension.options()).containsExactly("first", "second", "third"))
                    .isSameAs(server.extensions().getFirst());
        }
    }

    @Test
    void engineExtensionIsBootstrappedThroughTheEngineOverloadOnly() {
        try (var server = TachyonServer.builder()
                .withExtension(StubExtension.class, b -> {})
                .build()) {
            assertThat(server.extension(StubExtension.class).orElseThrow().bootstraps)
                    .containsExactly("engine");
        }
    }

    @Test
    void extensionWithoutEngineNeedsIsBootstrappedThroughTheContext() {
        var plain = new ServerExtension() {
            int contextBootstraps;

            @Override
            public String extensionId() {
                return "test/plain";
            }

            @Override
            public AdvertiseMode advertiseMode() {
                return AdvertiseMode.ALWAYS;
            }

            @Override
            public void bootstrap(dev.tachyonmcp.api.server.extensions.ExtensionContext context) {
                contextBootstraps++;
            }
        };

        try (var ignored = TachyonServer.builder().withExtensions(plain).build()) {
            assertThat(plain.contextBootstraps).isEqualTo(1);
        }
    }

    @Test
    void everyBuildGetsItsOwnExtensionInstance() {
        var builder = TachyonServer.builder().withExtension(StubExtension.class, b -> b.option("shared"));

        try (var one = builder.build();
                var two = builder.build()) {
            assertThat(one.extension(StubExtension.class).orElseThrow())
                    .isNotSameAs(two.extension(StubExtension.class).orElseThrow());
            assertThat(two.extension(StubExtension.class).orElseThrow().options())
                    .containsExactly("shared");
        }
    }

    @Test
    void providerOnTheClasspathEnablesNothingUnlessRequested() {
        try (var server = TachyonServer.builder().build()) {
            assertThat(server.extensions()).isEmpty();
            assertThat(server.extension(StubExtension.class)).isEmpty();
        }
    }

    @Test
    void lookupByClassFindsOnlyRegisteredTypes() {
        try (var server = TachyonServer.builder()
                .withExtension(StubExtension.class, b -> {})
                .build()) {
            assertThat(server.extension(StubExtension.class)).isPresent();
            assertThat(server.extension(UnregisteredExtension.class)).isEmpty();
            assertThat(server.extension(ServerExtension.class))
                    .as("lookup matches by instanceof, so an interface finds the first implementor")
                    .containsSame(server.extensions().getFirst());
        }
    }

    @Test
    void idClashBetweenInstanceAndTypeFailsTheBuild() {
        var instance = new ServerExtension() {
            @Override
            public String extensionId() {
                return StubExtension.ID;
            }

            @Override
            public AdvertiseMode advertiseMode() {
                return AdvertiseMode.ALWAYS;
            }
        };
        var clash = TachyonServer.builder().withExtensions(instance).withExtension(StubExtension.class, b -> {});
        var reversed = TachyonServer.builder()
                .withExtension(StubExtension.class, b -> {})
                .withExtensions(instance);

        assertThatIllegalArgumentException()
                .isThrownBy(clash::build)
                .withMessage("Duplicate extension ID: " + StubExtension.ID);
        assertThatIllegalArgumentException()
                .isThrownBy(reversed::build)
                .withMessage("Duplicate extension ID: " + StubExtension.ID);
    }

    @Test
    void missingProviderNamesTheServiceFileAndTheShadeHint() {
        var builder = TachyonServer.builder();

        assertThatIllegalStateException()
                .isThrownBy(() -> builder.withExtension(UnregisteredExtension.class, b -> {}))
                .withMessageContaining(UnregisteredExtension.class.getName())
                .withMessageContaining("META-INF/services/dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider")
                .withMessageContaining("@ProvidedBy")
                .withMessageContaining("ServicesResourceTransformer");
    }

    @Test
    void providedByFindsTheProviderWithoutAServiceFileAndItsBindingBootstrapsTheExtension() {
        try (var server = TachyonServer.builder()
                .withExtension(AnnotatedExtension.class, b -> {})
                .build()) {
            var extension = server.extension(AnnotatedExtension.class).orElseThrow();
            assertThat(extension.events)
                    .as("the provider's engine binding replaces the public ExtensionContext bootstrap")
                    .containsExactly("install", "binding");
            assertThat(server.extensions())
                    .extracting(ServerExtension::extensionId)
                    .contains(AnnotatedExtension.ID);
        }
    }

    @Test
    void providedByNamingAProviderForAnotherTypeFailsFast() {
        var builder = TachyonServer.builder();

        assertThatIllegalStateException()
                .isThrownBy(() -> builder.withExtension(MisannotatedExtension.class, b -> {}))
                .withMessageContaining(MisannotatedExtension.class.getName())
                .withMessageContaining("serves " + StubExtension.class.getName());
    }

    @Test
    void configurableExtensionInstanceIsRejectedInFavourOfWithExtension() {
        var instance = new StubExtension.Builder().option("bypass").build();
        var builder = TachyonServer.builder();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.withExtensions(instance))
                .withMessage("Register " + StubExtension.class.getName() + " with withExtension("
                        + StubExtension.class.getSimpleName() + ".class, ...), not as an instance");
    }

    @Test
    void throwingBootstrapFailsTheBuildAndShutsDownExtensionsAlreadyBootstrapped() {
        var events = new ArrayList<String>();
        var failure = new IllegalStateException("boom");
        var builder = TachyonServer.builder()
                .withExtensions(
                        recording("test/first", events, null),
                        recording("test/second", events, null),
                        recording("test/broken", events, failure),
                        recording("test/never", events, null));

        assertThatThrownBy(builder::build).isSameAs(failure);

        assertThat(events)
                .as("the failing and later extensions never start; earlier ones unwind in reverse order")
                .containsExactly(
                        "bootstrap test/first",
                        "bootstrap test/second",
                        "bootstrap test/broken",
                        "shutdown test/second",
                        "shutdown test/first");
    }

    @Test
    void bindingsInstallBeforeAnyExtensionBootstrapsWhateverTheBuilderOrder() {
        var events = new ArrayList<String>();

        try (var ignored = TachyonServer.builder()
                .withExtensions(recording("test/direct", events, null))
                .withExtension(AnnotatedExtension.class, b -> b.events(events))
                .build()) {
            assertThat(events)
                    .as("a direct extension bootstraps first, yet a configured binding has already installed")
                    .containsExactly("install", "bootstrap test/direct", "binding");
        }
    }

    @Test
    void bootstrapFailureShutsDownExtensionsThatOnlyInstalled() {
        var events = new ArrayList<String>();
        var failure = new IllegalStateException("boom");
        var builder = TachyonServer.builder()
                .withExtensions(recording("test/first", events, null), recording("test/broken", events, failure))
                .withExtension(AnnotatedExtension.class, b -> b.events(events));

        assertThatThrownBy(builder::build).isSameAs(failure);

        assertThat(events)
                .as("the installed binding never bootstraps but still unwinds, after the bootstrapped ones")
                .containsExactly(
                        "install", "bootstrap test/first", "bootstrap test/broken", "shutdown test/first", "shutdown");
    }

    @Test
    void shutdownFailureWhileUnwindingIsSuppressedNotMasked() {
        var events = new ArrayList<String>();
        var shutdownFailure = new IllegalStateException("cannot stop");
        var failure = new IllegalStateException("boom");
        var stubborn = recording("test/stubborn", events, null, shutdownFailure);
        var builder = TachyonServer.builder().withExtensions(stubborn, recording("test/broken", events, failure));

        assertThatThrownBy(builder::build).isSameAs(failure).hasSuppressedException(shutdownFailure);
    }

    private static ServerExtension recording(String id, List<String> events, RuntimeException bootstrapFailure) {
        return recording(id, events, bootstrapFailure, null);
    }

    private static ServerExtension recording(
            String id, List<String> events, RuntimeException bootstrapFailure, RuntimeException shutdownFailure) {
        return new ServerExtension() {
            @Override
            public String extensionId() {
                return id;
            }

            @Override
            public AdvertiseMode advertiseMode() {
                return AdvertiseMode.ALWAYS;
            }

            @Override
            public void bootstrap(dev.tachyonmcp.api.server.extensions.ExtensionContext context) {
                events.add("bootstrap " + id);
                if (bootstrapFailure != null) {
                    throw bootstrapFailure;
                }
            }

            @Override
            public void shutdown() {
                events.add("shutdown " + id);
                if (shutdownFailure != null) {
                    throw shutdownFailure;
                }
            }
        };
    }
}
