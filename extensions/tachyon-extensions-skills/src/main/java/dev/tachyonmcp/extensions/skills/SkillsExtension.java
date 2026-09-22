/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.BlobResourceContents;
import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.features.resources.MimeTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * MCP <a href="https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2640">SEP-2640</a>
 * skills extension: serves Agent Skills as {@code skill://} resources and answers
 * {@code skills/list}, {@code skills/get}, and {@code resources/directory/read}.
 * Skill files remain available through the base {@code resources/list} and {@code resources/read}
 * methods when the client has not negotiated this extension. By default
 * ({@link ExtensionNegotiation#OPTIONAL}) the extension methods serve undeclared clients. Build with
 * {@link ExtensionNegotiation#REQUIRED} to reject clients that did not declare the extension.
 *
 * <pre>{@code
 * TachyonServer.builder()
 *         .withExtensions(SkillsExtension.builder()
 *                 .registry(new FilesystemSkillsRegistry(Path.of("skills")))
 *                 .registry(new ClasspathSkillsRegistry("bundled-skills"))
 *                 .build())
 *         .build();
 * }</pre>
 */
public final class SkillsExtension implements ServerExtension {

    /** Extension identifier per SEP-2640. */
    public static final String ID = "io.modelcontextprotocol/skills";

    private static final String SKILL_SCHEME = "skill://";
    private static final String SKILL_MD = "/SKILL.md";
    private static final String DIRECTORY_MIME = "inode/directory";

    private final SkillsRegistry registry;
    private final Map<String, SkillsRegistry.Skill> skillsByPath = new LinkedHashMap<>();
    private final Set<String> fileUris;
    private final long cacheTtlMs;
    private final String cacheScope;
    private final ExtensionNegotiation negotiation;

    private SkillsExtension(
            List<SkillsRegistry> registries, long cacheTtlMs, String cacheScope, ExtensionNegotiation negotiation) {
        this.cacheTtlMs = cacheTtlMs;
        this.cacheScope = cacheScope;
        this.negotiation = negotiation;
        this.registry = new CompositeSkillsRegistry(registries);
        var uris = new HashSet<String>();
        for (var skill : registry.skills()) {
            skillsByPath.put(skill.skillPath(), skill);
            for (var file : skill.files()) {
                uris.add(file.uri());
            }
        }
        this.fileUris = Set.copyOf(uris);
    }

    /** Creates a new {@link SkillsExtension} builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String extensionId() {
        return ID;
    }

    @Override
    public ExtensionNegotiation negotiation() {
        return negotiation;
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    @Override
    public ExtensionSettings serverSettings() {
        return ExtensionSettings.of(Map.of("directoryRead", true));
    }

    /**
     * Registers every skill file as a base MCP resource and the {@code skills/list},
     * {@code skills/get}, and {@code resources/directory/read} methods.
     */
    @Override
    public void bootstrap(ExtensionContext server) {
        for (var skill : skillsByPath.values()) {
            for (var file : skill.files()) {
                var isSkillManifest = file.relativePath().equals("SKILL.md");
                var descriptor = ResourceDescriptor.builder()
                        .name(
                                isSkillManifest
                                        ? String.valueOf(skill.frontmatter().get("name"))
                                        : skill.skillPath() + "/" + file.relativePath())
                        .uri(file.uri())
                        .mimeType(file.mimeType());
                if (isSkillManifest) {
                    descriptor.description(String.valueOf(skill.frontmatter().get("description")));
                }
                server.resources().register(descriptor.build(), (context, request) -> contents(file));
            }
        }
        server.registerHandler("skills/list", this::listSkills);
        server.registerHandler("skills/get", this::getSkill);
        server.registerHandler("resources/directory/read", this::readDirectory);
    }

    private ResourceContents contents(SkillsRegistry.SkillFile file) {
        var bytes = registry.readFile(file.uri());
        if (bytes == null) {
            throw new IllegalStateException("Skill file is no longer available: " + file.uri());
        }
        return MimeTypes.isText(file.mimeType())
                ? TextResourceContents.of(file.uri(), new String(bytes, StandardCharsets.UTF_8), file.mimeType(), null)
                : BlobResourceContents.of(file.uri(), bytes, file.mimeType(), null);
    }

    private Object listSkills(InteractionContext interaction, @Nullable JsonObject params) {
        if (params != null && params.has("cursor")) {
            // ponytail: no pagination — catalogs are bounded; page with a server-side cursor if they grow
            return ServerErrors.invalidParams("skills/list pagination is not supported");
        }
        return Map.of(
                "skills",
                skillsByPath.values().stream().map(SkillsExtension::skillEntry).toList(),
                "resultType",
                "complete",
                "ttlMs",
                cacheTtlMs,
                "cacheScope",
                cacheScope);
    }

    private Object getSkill(InteractionContext interaction, @Nullable JsonObject params) {
        var uri = params != null ? params.stringOpt("uri").orElse(null) : null;
        if (uri == null) {
            return ServerErrors.invalidParams("Missing 'uri' parameter");
        }
        var skill = findSkill(uri);
        if (skill == null) {
            return ServerErrors.invalidParams("Unknown skill: " + uri);
        }
        return Map.of("skill", skillEntry(skill), "resultType", "complete");
    }

    private Object readDirectory(InteractionContext interaction, @Nullable JsonObject params) {
        var uri = params != null ? params.stringOpt("uri").orElse(null) : null;
        if (uri == null) {
            return ServerErrors.invalidParams("Missing 'uri' parameter");
        }
        if (!uri.startsWith(SKILL_SCHEME) || fileUris.contains(uri)) {
            return ServerErrors.invalidParams("Unknown skill directory: " + uri);
        }
        var children = directoryChildren(uri.substring(SKILL_SCHEME.length()));
        if (children == null) {
            return ServerErrors.invalidParams("Unknown skill directory: " + uri);
        }
        return Map.of("resources", toResourceEntries(uri, children), "resultType", "complete");
    }

    /** Returns the immediate children of {@code dirPath}, or {@code null} when it names no known directory. */
    private @Nullable TreeMap<String, String> directoryChildren(String dirPath) {
        var dirPrefix = dirPath.isEmpty() ? "" : dirPath + "/";
        var children = new TreeMap<String, String>();
        var found = false;
        for (var skill : skillsByPath.values()) {
            var skillPath = skill.skillPath();
            if (skillPath.equals(dirPath)) {
                found |= collectChildren(skill, "", children);
            } else if (dirPath.startsWith(skillPath + "/")) {
                found |= collectChildren(skill, dirPath.substring(skillPath.length() + 1) + "/", children);
            } else if (skillPath.startsWith(dirPrefix)) {
                var rest = skillPath.substring(dirPrefix.length());
                var index = rest.indexOf('/');
                found = true;
                children.putIfAbsent(index >= 0 ? rest.substring(0, index) : rest, DIRECTORY_MIME);
            }
        }
        return found ? children : null;
    }

    /** Adds every file of {@code skill} under {@code innerPrefix} as a child; returns whether any matched. */
    private static boolean collectChildren(
            SkillsRegistry.Skill skill, String innerPrefix, Map<String, String> children) {
        var matched = false;
        for (var file : skill.files()) {
            if (!file.relativePath().startsWith(innerPrefix)) {
                continue;
            }
            matched = true;
            var rest = file.relativePath().substring(innerPrefix.length());
            var index = rest.indexOf('/');
            children.putIfAbsent(
                    index >= 0 ? rest.substring(0, index) : rest, index >= 0 ? DIRECTORY_MIME : file.mimeType());
        }
        return matched;
    }

    private static List<Map<String, Object>> toResourceEntries(String parentUri, Map<String, String> children) {
        var prefix = parentUri.endsWith("/") ? parentUri : parentUri + "/";
        return children.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> child = new LinkedHashMap<>();
                    child.put("uri", prefix + entry.getKey());
                    child.put("name", entry.getKey());
                    child.put("mimeType", entry.getValue());
                    return child;
                })
                .toList();
    }

    private static Map<String, Object> skillEntry(SkillsRegistry.Skill skill) {
        var resources = new ArrayList<Map<String, Object>>(skill.files().size());
        for (var file : skill.files()) {
            var resource = new LinkedHashMap<String, Object>();
            resource.put("uri", file.uri());
            resource.put("digest", file.digest());
            resource.put("size", file.size());
            resources.add(resource);
        }
        var entry = new LinkedHashMap<String, Object>();
        entry.put("uri", skill.skillUri());
        entry.put("frontmatter", skill.frontmatter());
        entry.put("resources", resources);
        return entry;
    }

    private SkillsRegistry.@Nullable Skill findSkill(String uri) {
        var skillPath = skillPathOf(uri);
        return skillPath != null ? skillsByPath.get(skillPath) : null;
    }

    private static @Nullable String skillPathOf(String uri) {
        if (!uri.startsWith(SKILL_SCHEME) || !uri.endsWith(SKILL_MD)) {
            return null;
        }
        var path = uri.substring(SKILL_SCHEME.length(), uri.length() - SKILL_MD.length());
        return path.isEmpty() ? null : path;
    }

    /** Configures and builds a {@link SkillsExtension}. */
    public static final class Builder {

        private final List<SkillsRegistry> registries = new ArrayList<>();
        private long cacheTtlMs = 0;
        private String cacheScope = "public";
        private ExtensionNegotiation negotiation = ExtensionNegotiation.OPTIONAL;

        /**
         * Adds a skill registry. Construct {@link FilesystemSkillsRegistry} or
         * {@link ClasspathSkillsRegistry} directly, or supply a custom {@link SkillsRegistry} —
         * the registry itself resolves skills from its source.
         *
         * @param registry the skill registry
         * @return this builder
         */
        public Builder registry(SkillsRegistry registry) {
            registries.add(registry);
            return this;
        }

        /**
         * Sets the {@code ttlMs} cache-freshness hint (SEP-2549) attached to {@code skills/list}
         * results: how long, in milliseconds, a client MAY cache the listing before re-fetching.
         * Defaults to {@code 0} (always stale — the client MAY re-fetch every time).
         *
         * @param cacheTtlMs milliseconds to consider the listing fresh; must be {@code >= 0}
         * @return this builder
         */
        public Builder cacheTtlMs(long cacheTtlMs) {
            if (cacheTtlMs < 0) {
                throw new IllegalArgumentException("cacheTtlMs must be >= 0, was " + cacheTtlMs);
            }
            this.cacheTtlMs = cacheTtlMs;
            return this;
        }

        /**
         * Sets the {@code cacheScope} (SEP-2549) attached to {@code skills/list} results:
         * {@code "public"} when the listing may be cached and shared across authorization
         * contexts, or {@code "private"} when it must be cached only within the same
         * authorization context. Defaults to {@code "public"}.
         *
         * @param cacheScope {@code "public"} or {@code "private"}
         * @return this builder
         */
        public Builder cacheScope(String cacheScope) {
            if (!cacheScope.equals("public") && !cacheScope.equals("private")) {
                throw new IllegalArgumentException(
                        "cacheScope must be 'public' or 'private', was '" + cacheScope + "'");
            }
            this.cacheScope = cacheScope;
            return this;
        }

        /**
         * Sets whether clients must declare {@code io.modelcontextprotocol/skills} before calling
         * {@code skills/list}, {@code skills/get}, and {@code resources/directory/read}. Defaults to
         * {@link ExtensionNegotiation#OPTIONAL}: the Skills extension only requires the server's
         * declaration, so these methods serve any client. Use {@link ExtensionNegotiation#REQUIRED}
         * to reject clients that did not declare the extension.
         *
         * @param negotiation the negotiation policy
         * @return this builder
         */
        public Builder negotiation(ExtensionNegotiation negotiation) {
            this.negotiation = negotiation;
            return this;
        }

        /** Builds the extension. */
        public SkillsExtension build() {
            return new SkillsExtension(registries, cacheTtlMs, cacheScope, negotiation);
        }
    }
}
