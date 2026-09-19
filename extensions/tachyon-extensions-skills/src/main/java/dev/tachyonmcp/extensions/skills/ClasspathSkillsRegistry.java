/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link SkillsRegistry} serving skills from classpath resources (a directory inside a
 * JAR or on the classpath). Pass an instance to {@link SkillsExtension.Builder#registry(SkillsRegistry)}.
 */
public class ClasspathSkillsRegistry extends BaseSkillsRegistry {

    /**
     * Creates a registry from a classpath directory of skills: every subdirectory containing a
     * {@code SKILL.md} becomes a skill named after its directory.
     *
     * @param skillsResource the classpath directory containing the skill directories
     */
    public ClasspathSkillsRegistry(String skillsResource) {
        scan(contextClassLoader(), skillsResource, null);
    }

    /**
     * Creates a registry for a single classpath skill directory.
     *
     * @param skillResource the classpath path of the skill directory
     * @param skillPath the skill path; its final segment must equal the frontmatter {@code name}
     */
    public ClasspathSkillsRegistry(String skillResource, String skillPath) {
        scan(contextClassLoader(), skillResource, skillPath);
    }

    ClasspathSkillsRegistry(ClassLoader classLoader, String resource, @Nullable String skillPath) {
        scan(classLoader, resource, skillPath);
    }

    private static ClassLoader contextClassLoader() {
        var loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : ClasspathSkillsRegistry.class.getClassLoader();
    }

    private void scan(ClassLoader classLoader, String resource, @Nullable String skillPath) {
        var url = classLoader.getResource(resource);
        if (url == null) {
            throw new IllegalArgumentException("Classpath resource not found: " + resource);
        }
        if ("file".equals(url.getProtocol())) {
            var root = toPath(url);
            if (skillPath != null) {
                addSkill(root, skillPath);
            } else {
                addSkillDir(root);
            }
            return;
        }
        if ("jar".equals(url.getProtocol())) {
            scanJar(url, skillPath);
            return;
        }
        throw new IllegalArgumentException("Unsupported classpath protocol: " + url.getProtocol());
    }

    private void scanJar(URL url, @Nullable String skillPath) {
        try {
            final var connection = (JarURLConnection) url.openConnection();
            connection.setUseCaches(false);
            var base = connection.getEntryName();
            if (base == null) {
                throw new IllegalArgumentException("Jar resource has no entry: " + url);
            }
            base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            var prefix = base + "/";
            var groups = new LinkedHashMap<String, Map<String, byte[]>>();
            try (var jar = connection.getJarFile()) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                        continue;
                    }
                    var relative = entry.getName().substring(prefix.length());
                    byte[] bytes;
                    try (var in = jar.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    var group = skillPath;
                    var file = relative;
                    if (skillPath == null) {
                        var index = relative.indexOf('/');
                        if (index <= 0) {
                            // no separator (file sits directly under the base) or an empty group name
                            continue;
                        }
                        group = relative.substring(0, index);
                        file = relative.substring(index + 1);
                    }
                    groups.computeIfAbsent(group, key -> new HashMap<>()).put(file, bytes);
                }
            }
            for (var entry : groups.entrySet()) {
                if (skillPath != null) {
                    addFilesStrict(entry.getKey(), entry.getValue());
                } else {
                    addFilesLenient(entry.getKey(), entry.getValue());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path toPath(URL url) {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid classpath resource URL: " + url, e);
        }
    }
}
