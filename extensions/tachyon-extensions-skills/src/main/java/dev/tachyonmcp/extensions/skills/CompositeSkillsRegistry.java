/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Combines multiple {@link SkillsRegistry} sources into one, rejecting duplicate skill paths. */
public class CompositeSkillsRegistry implements SkillsRegistry {

    private final List<Skill> skills;
    private final Map<String, SkillsRegistry> registryByUri;

    /**
     * Combines the given registries, merging their skills into one view.
     *
     * @param registries the source registries, consulted in iteration order
     * @throws IllegalArgumentException if two registries serve a skill with the same path
     */
    public CompositeSkillsRegistry(Iterable<SkillsRegistry> registries) {
        var skillsByPath = new LinkedHashMap<String, Skill>();
        var byUri = new HashMap<String, SkillsRegistry>();
        for (var registry : registries) {
            for (var skill : registry.skills()) {
                if (skillsByPath.containsKey(skill.skillPath())) {
                    throw new IllegalArgumentException(
                            "Duplicate skill path '" + skill.skillPath() + "' is served by more than one registry");
                }
                skillsByPath.put(skill.skillPath(), skill);
                for (var file : skill.files()) {
                    byUri.put(file.uri(), registry);
                }
            }
        }
        this.skills = List.copyOf(skillsByPath.values());
        this.registryByUri = Map.copyOf(byUri);
    }

    /**
     * Combines the given registries, merging their skills into one view.
     *
     * @param registries the source registries, consulted in iteration order
     * @throws IllegalArgumentException if two registries serve a skill with the same path
     */
    public CompositeSkillsRegistry(SkillsRegistry... registries) {
        this(Arrays.asList(registries));
    }

    @Override
    public List<Skill> skills() {
        return skills;
    }

    @Override
    public byte @Nullable [] readFile(String fileUri) {
        var registry = registryByUri.get(fileUri);
        return registry != null ? registry.readFile(fileUri) : null;
    }
}
