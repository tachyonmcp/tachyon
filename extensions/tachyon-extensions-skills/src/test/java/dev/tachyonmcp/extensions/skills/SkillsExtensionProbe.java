/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import static dev.tachyonmcp.extensions.skills.SkillTestFixtures.filesystemSkillsDir;

import dev.tachyonmcp.core.server.TachyonServer;
import java.util.concurrent.CountDownLatch;

class SkillsExtensionProbe {

    private static final SkillsRegistry classpathSkillsRegistry = new ClasspathSkillsRegistry("skills");
    private static final SkillsRegistry filesystemSkillsRegistry = new FilesystemSkillsRegistry(filesystemSkillsDir);

    private static final SkillsRegistry combinedRegistry =
            new CompositeSkillsRegistry(filesystemSkillsRegistry, classpathSkillsRegistry);

    // quickstart example
    public static void main(String... args) throws InterruptedException {
        try (var server = TachyonServer.builder()
                .port(8080)
                .withExtensions(
                        SkillsExtension.builder().registry(combinedRegistry).build())
                .build()) {
            server.start();
            new CountDownLatch(1).await();
        }
    }
}
