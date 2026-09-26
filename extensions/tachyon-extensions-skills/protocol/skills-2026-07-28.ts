/**
 * A file belonging to a skill, with the digest and size of its content.
 */
interface SkillResource {
    /** Resource URI of the file. */
    uri: string;

    /**
     * SHA-256 digest of the file's raw bytes, formatted as `sha256:{hex}`
     * where {hex} is 64 lowercase hexadecimal characters.
     */
    digest: string;

    /**
     * Length in bytes of the file's raw content (the same bytes that `digest` covers).
     */
    size: number;
}

/**
 * The entry for a single skill.
 */
interface Skill {
    /** Resource URI of the skill's SKILL.md, readable via resources/read. */
    uri: string;

    /**
     * The skill's SKILL.md YAML frontmatter, rendered verbatim as a JSON object.
     * `name` and `description` are always present; every other field the author
     * wrote passes through unchanged.
     */
    frontmatter: {
        name: string;
        description: string;
        [key: string]: unknown;
    };

    /**
     * The skill's files: a complete enumeration of SKILL.md and every supporting
     * file, or the string "dynamic" when the skill's content is generated such
     * that stable digests cannot be published.
     */
    resources: SkillResource[] | "dynamic";
}
