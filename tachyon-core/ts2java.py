#!/usr/bin/env python3
"""
Generate Java records + Jackson Streaming codecs from TypeScript spec.
Output: <BASE>/java/<PACKAGE_PATH>/ (models/, codecs/, protocol/)
"""

#  Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.

import argparse
import hashlib
import json
import os
import re
from collections import OrderedDict
from datetime import datetime, timezone

DEFAULT_TS = "protocol/mcp-2025-11-25.ts"
DEFAULT_BASE = "target/generated-sources/ts2java"
DEFAULT_PKG = "com.example.tsimport"
DEFAULT_PKG_MODELS = f"{DEFAULT_PKG}.models"
DEFAULT_PKG_CODECS = f"{DEFAULT_PKG}.codecs"
DEFAULT_PKG_PROTOCOL = f"{DEFAULT_PKG}.protocol"
VERBOSE = False

# ISO 8601, per the javax.annotation.processing.Generated#date contract; shared by every
# class emitted in this run so one invocation stamps a single, consistent generation time.
GENERATED_DATE = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
GENERATED_ANNOTATION = f'@Generated(value = "ts2java", date = "{GENERATED_DATE}")'

PRIMITIVE_MAP = {
    "string": "String",
    "boolean": "boolean",
    "number": "double",
    "integer": "long",
}

UNKNOWN_TYPE_MAP = {
    "JsonNode": "tools.jackson.databind.JsonNode",
    "TokenBuffer": "tools.jackson.databind.util.TokenBuffer",
    "byte[]": "byte[]",
}

UNKNOWN_SIMPLE_MAP = {
    "tools.jackson.databind.JsonNode": "JsonNode",
    "tools.jackson.databind.util.TokenBuffer": "TokenBuffer",
    "byte[]": "byte[]",
}

# Jackson JSON-tree types (JsonNode and its concrete subtypes, e.g. via the JSONObject/JSONArray/
# JSONValue schema aliases in typeMappings). decodeValue/encodeValue already handle any of these
# generically (readValueAsTree / instanceof JsonNode) — a field resolving to one of these must use
# that generic path, not CodecRegistry.codecFor, which only holds codecs for schema-modeled types.
JSON_TREE_TYPES = (
    "tools.jackson.databind.JsonNode",
    "tools.jackson.databind.node.ObjectNode",
    "tools.jackson.databind.node.ArrayNode",
    "tools.jackson.databind.ValueNode",
)

STRING_NUMBER_UNIONS = {"ProgressToken", "RequestId"}

CACHE_DIR_NAME = "ts2java-cache"


# --- Incremental generation cache ---
#
# Staleness is checked via filesystem attributes (mtime + size) of the TS spec, the config
# file, and this script itself, plus the CLI args that affect output shape. No file content
# is hashed — stat() is cheap and sufficient, and matches what the pom.xml executions need
# (skip re-running python3 + full TS parse when nothing relevant changed).
#
# Cache files live under <target>/ts2java-cache/, a sibling of generated-sources/ — never
# under <base-dir>/java/, which is added as a compiler source root and must contain only
# generated .java files. Each distinct invocation (ts file + output packages) gets its own
# cache file so the three pom.xml executions sharing one base-dir don't clobber each other.


def _stat_fingerprint(path):
    if not path or not os.path.isfile(path):
        return None
    st = os.stat(path)
    return {"path": os.path.abspath(path), "mtime_ns": st.st_mtime_ns, "size": st.st_size}


def _find_target_dir(base_dir):
    """Walk up from base_dir to the nearest ancestor named 'target' (Maven's build dir)."""
    abs_base = os.path.abspath(base_dir)
    parts = abs_base.split(os.sep)
    if "target" in parts:
        idx = parts.index("target")
        return os.sep.join(parts[: idx + 1]) or os.sep
    return abs_base


def _cache_path_for(ts_path, base_dir, pkg_models, pkg_codecs, pkg_protocol):
    stem = re.sub(r"[^A-Za-z0-9_.-]+", "_", os.path.splitext(os.path.basename(ts_path))[0])
    ident = "|".join(
        [
            os.path.abspath(ts_path),
            os.path.abspath(base_dir),
            pkg_models or "",
            pkg_codecs or "",
            pkg_protocol or "",
        ]
    )
    digest = hashlib.sha256(ident.encode("utf-8")).hexdigest()[:12]
    cache_dir = os.path.join(_find_target_dir(base_dir), CACHE_DIR_NAME)
    return os.path.join(cache_dir, f"{stem}-{digest}.json")


def _build_fingerprint(ts_path, config_path, unknown_type):
    return {
        "script": _stat_fingerprint(os.path.abspath(__file__)),
        "ts": _stat_fingerprint(ts_path),
        "config": _stat_fingerprint(config_path) if config_path else None,
        "unknown_type": unknown_type,
    }


def _dir_has_any_file(path):
    if not os.path.isdir(path):
        return False
    for _root, _dirs, files in os.walk(path):
        if files:
            return True
    return False


def _load_cache(cache_path):
    try:
        with open(cache_path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _is_cache_fresh(cache_data, fingerprint):
    if not cache_data or cache_data.get("fingerprint") != fingerprint:
        return False
    output_dirs = cache_data.get("output_dirs") or []
    return all(_dir_has_any_file(d) for d in output_dirs)


def _save_cache(cache_path, fingerprint, output_dirs):
    os.makedirs(os.path.dirname(cache_path), exist_ok=True)
    with open(cache_path, "w") as f:
        json.dump({"fingerprint": fingerprint, "output_dirs": output_dirs}, f, indent=2)
        f.write("\n")


# --- TS Parser ---


class TsParser:
    """Parse TypeScript spec into structured exports."""

    @staticmethod
    def parse_ts(filepath):
        with open(filepath) as f:
            text = f.read()
        text = re.sub(r"//[^\n]*", "", text)
        text = re.sub(r"/\*[^*].*?\*/", "", text, flags=re.DOTALL)
        exports = []
        i = 0
        jsdoc = ""
        while i < len(text):
            m = re.match(r"/\*\*[\s\S]*?\*/", text[i:])
            if m:
                jsdoc = m.group(0)
                i += m.end()
                continue
            m = re.match(
                r"(?:export\s+)?interface\s+(\w+)(?:\s+extends\s+([^{]+))?\s*\{",
                text[i:],
            )
            if m:
                name = m.group(1)
                raw_extends = m.group(2)
                parts = []
                if raw_extends:
                    raw_extends = raw_extends.strip()
                    depth = 0
                    part = ""
                    for ch in raw_extends:
                        if ch in "<(":
                            depth += 1
                            part += ch
                        elif ch in ">)":
                            depth -= 1
                            part += ch
                        elif ch == "," and depth == 0:
                            parts.append(part.strip())
                            part = ""
                        else:
                            part += ch
                    if part.strip():
                        parts.append(part.strip())
                    resolved = []
                    for p in parts:
                        p = p.rstrip(",").strip()
                        if p.startswith("Omit<"):
                            inner = p[5:-1]
                            idx = inner.rfind(",")
                            ot = inner[:idx].strip()
                            ok = inner[idx + 1 :].strip()
                            resolved.append(("omit", ot, ok))
                        else:
                            resolved.append(("extends", p))
                    parts = resolved
                brace_start = i + m.end()
                body, end_idx = TsParser.extract_balanced(text, brace_start)
                fields = TsParser.parse_fields(body)
                exports.append(
                    {
                        "kind": "interface",
                        "name": name,
                        "extends": parts,
                        "fields": fields,
                        "jsdoc": jsdoc,
                    }
                )
                i = end_idx + 1
                jsdoc = ""
                continue
            m = re.match(r"export\s+type\s+(\w+)\s*=\s*", text[i:])
            if m:
                name = m.group(1)
                rhs_start = i + m.end()
                rhs_end = TsParser.find_statement_end(text, rhs_start)
                rhs = re.sub(r"//.*", "", text[rhs_start:rhs_end]).strip()
                exports.append(
                    {
                        "kind": "type_alias",
                        "name": name,
                        "rhs": rhs,
                        "jsdoc": jsdoc,
                    }
                )
                i = rhs_end + 1
                jsdoc = ""
                continue
            m = re.match(r"export\s+(const|function|class|enum)\s", text[i:])
            if m:
                end = text.find(";", i)
                i = (end + 1) if end != -1 else len(text)
                jsdoc = ""
                continue
            i += 1
        return exports

    @staticmethod
    def _bracket_depth(text, i, depth):
        """Nesting depth after `text[i]`; the `>` of an arrow (`=>`) closes nothing."""
        ch = text[i]
        if ch in "{(<[":
            return depth + 1
        if ch in "})]" or (ch == ">" and (i == 0 or text[i - 1] != "=")):
            return max(depth - 1, 0)
        return depth

    @staticmethod
    def find_statement_end(text, start):
        """Index of the `;` ending a type alias, skipping `;` nested in `{}`, `()`, `<>` or `[]`."""
        depth = 0
        for i in range(start, len(text)):
            if text[i] == ";" and depth == 0:
                return i
            depth = TsParser._bracket_depth(text, i, depth)
        return len(text)

    @staticmethod
    def split_top_level(text, sep):
        """Splits on `sep` outside `{}`, `()`, `<>` and `[]`."""
        parts = []
        depth = 0
        part_start = 0
        for i, ch in enumerate(text):
            if ch == sep and depth == 0:
                parts.append(text[part_start:i].strip())
                part_start = i + 1
            depth = TsParser._bracket_depth(text, i, depth)
        parts.append(text[part_start:].strip())
        return [p for p in parts if p]

    @staticmethod
    def extract_balanced(text, start):
        depth = 1
        i = start
        while i < len(text) and depth > 0:
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
            i += 1
        return text[start : i - 1], i - 1

    @staticmethod
    def _unescape_ts_string(raw):
        """Decodes backslash escapes in a quoted TS string literal to its semantic value."""
        simple = {
            "n": "\n",
            "t": "\t",
            "r": "\r",
            "b": "\b",
            "f": "\f",
            "\\": "\\",
            "'": "'",
            '"': '"',
            "/": "/",
        }

        def repl(m):
            esc = m.group(1)
            if esc.startswith("u") and len(esc) == 5:
                return chr(int(esc[1:], 16))
            return simple.get(esc, esc)

        return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", repl, raw)

    @staticmethod
    def parse_fields(body):
        fields = []
        i = 0
        jsdoc = ""
        while i < len(body):
            if body[i] in " \t\n\r":
                i += 1
                continue
            m = re.match(r"/\*\*[\s\S]*?\*/", body[i:])
            if m:
                jsdoc = m.group(0)
                i += m.end()
                continue
            m = re.match(r"//[^\n]*", body[i:])
            if m:
                i += m.end()
                continue
            m = re.match(r"/\*[^*].*?\*/", body[i:], re.DOTALL)
            if m:
                i += m.end()
                continue
            if body[i] == "}":
                break
            m = re.match(r"\[\s*(\w+)\s*:\s*(\w+)\s*\]\s*:\s*(.+?);", body[i:])
            if m:
                fields.append(
                    {
                        "kind": "index",
                        "key_name": m.group(1),
                        "key_type": m.group(2),
                        "value_type": m.group(3).strip(),
                        "jsdoc": jsdoc,
                    }
                )
                i += m.end()
                jsdoc = ""
                continue
            m = re.match(r"(\w+)(\??)\s*:\s*", body[i:])
            if m:
                name = m.group(1)
                optional = m.group(2) == "?"
                after_colon = i + m.end()
                type_str, end_idx = TsParser.parse_type(body, after_colon)
                semicolon = body.find(";", end_idx)
                if semicolon == -1:
                    break
                raw_type = body[after_colon:semicolon].strip()
                fields.append(
                    {
                        "kind": "field",
                        "name": name,
                        "optional": optional,
                        "type_str": type_str,
                        "raw_type": raw_type,
                        "jsdoc": jsdoc,
                    }
                )
                i = semicolon + 1
                jsdoc = ""
                continue
            # Quoted string-literal keys (e.g. reserved `_meta` namespaced keys like
            # "io.modelcontextprotocol/serverInfo") aren't valid Java identifiers as-is.
            # Derive a Java field name from the segment after the last '/', keep the
            # original string as the wire-format JSON property name.
            m = re.match(r"""(['"])(.+?)\1(\??)\s*:\s*""", body[i:])
            if m:
                json_name = TsParser._unescape_ts_string(m.group(2))
                name = json_name.rsplit("/", 1)[-1]
                name = re.sub(r"[^A-Za-z0-9_]", "_", name)
                if not name or name[0].isdigit():
                    name = "_" + name
                existing_names = {f["name"] for f in fields if f["kind"] == "field"}
                if name in existing_names:
                    suffix = 2
                    while f"{name}_{suffix}" in existing_names:
                        suffix += 1
                    name = f"{name}_{suffix}"
                optional = m.group(3) == "?"
                after_colon = i + m.end()
                type_str, end_idx = TsParser.parse_type(body, after_colon)
                semicolon = body.find(";", end_idx)
                if semicolon == -1:
                    break
                raw_type = body[after_colon:semicolon].strip()
                fields.append(
                    {
                        "kind": "field",
                        "name": name,
                        "json_name": json_name,
                        "optional": optional,
                        "type_str": type_str,
                        "raw_type": raw_type,
                        "jsdoc": jsdoc,
                    }
                )
                i = semicolon + 1
                jsdoc = ""
                continue
            i += 1
        return fields

    @staticmethod
    def parse_type(text, start):
        i = start
        depth = 0
        depth_paren = 0
        depth_angle = 0
        while i < len(text):
            ch = text[i]
            if ch == "(" and depth == 0:
                depth_paren += 1
            elif ch == ")" and depth == 0:
                depth_paren -= 1
            elif ch == "<":
                depth_angle += 1
            elif ch == ">":
                depth_angle -= 1
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
            if depth < 0:
                break
            if depth == 0 and depth_paren == 0 and depth_angle == 0:
                if ch in ";,:":
                    if ch in ";," or ch == ":":
                        break
            i += 1
        return text[start:i].strip(), i


# --- Javadoc Formatting ---


class JavadocFormatter:
    """Format JSDoc strings to JavaDoc blocks."""

    @staticmethod
    def format_jsdoc(jsdoc_raw):
        if not jsdoc_raw:
            return ""
        lines = jsdoc_raw.split("\n")
        desc_lines = []
        for line in lines:
            s = line.strip()
            if s.startswith("/**"):
                s = s[3:].strip()
            if s.endswith("*/"):
                s = s[:-2].strip()
            if s.startswith("*"):
                s = s[1:].strip()
            if s.startswith("@") or s == "":
                continue
            desc_lines.append(s)
        text = " ".join(desc_lines).strip()
        text = re.sub(r'\{@includeCode\s+[^}]*\}', "", text)

        def _clean_link(m):
            inner = m.group(1).strip()
            if "|" in inner:
                return inner.split("|", 1)[1].strip()
            return inner

        text = re.sub(r"\{@link\s+([^}]*)\}", _clean_link, text)
        # TS prose like "Result & Task" is a bad HTML entity to doclint; keep real entities intact.
        text = re.sub(r"&(?!#?\w+;)", "&amp;", text)
        return text

    @staticmethod
    def _param_desc(pname, pdoc):
        if pdoc:
            return pdoc
        return " ".join(w for w in pname.replace("_", " ").split() if w)

    @staticmethod
    def make_javadoc(desc, param_docs=None, indent=""):
        if not desc and not param_docs:
            return ""
        lines = [f"{indent}/**\n"]
        if desc:
            lines.append(f"{indent} * {desc}\n")
        if param_docs:
            if desc:
                lines.append(f"{indent} *\n")
            for pname, pdoc in param_docs:
                lines.append(f"{indent} * @param {pname} {JavadocFormatter._param_desc(pname, pdoc)}\n")
        lines.append(f"{indent} */\n")
        return "".join(lines)




# --- Generator ---


class Generator:
    def __init__(
        self,
        ts_path=None,
        base_dir=None,
        pkg_models=None,
        pkg_codecs=None,
        pkg_protocol=None,
        unknown_type="JsonNode",
        type_mappings=None,
        additional_properties=None,
        ignore_properties=None,
        base_package=None,
    ):
        self.ts_path = ts_path or DEFAULT_TS
        self.base_dir = base_dir or DEFAULT_BASE
        self.pkg_models = pkg_models or DEFAULT_PKG_MODELS
        self.pkg_codecs = pkg_codecs
        self.pkg_protocol = pkg_protocol
        self.skip_codecs = not self.pkg_codecs
        self.skip_protocol = not self.pkg_protocol
        self.unknown_type = unknown_type
        self.unknown_java_type = UNKNOWN_TYPE_MAP[unknown_type]
        self.unknown_simple = UNKNOWN_SIMPLE_MAP.get(
            self.unknown_java_type, unknown_type
        )
        raw_mappings = type_mappings or {}
        self.type_mappings = {k: v for k, v in raw_mappings.items() if "." not in k}
        self.field_type_mappings = {k: v for k, v in raw_mappings.items() if "." in k}
        self.additional_props = additional_properties or {}
        self.ignore_props = ignore_properties or {}
        # Extension mode: types from the base package (e.g. core MCP models) are referenced via
        # typeMappings, and its Codec/CodecSupport/CodecRegistry are imported instead of generated.
        self.base_package = base_package
        # model name -> (discriminated union, component name) for intersections that inline one
        # union's properties (GetTaskResult = Result & DetailedTask & { resultType: "complete" }).
        self.flattened_unions = {}
        # class_name -> set of json field names that are required-but-nullable (`x: T | null`,
        # no `?`). Their key MUST be written on encode, emitting JSON null when the value is null.
        self.nullable_required = {}
        self.dir_models = f"{self.base_dir}/java/{self.pkg_models.replace('.', '/')}"
        self.dir_codecs = (
            f"{self.base_dir}/java/{self.pkg_codecs.replace('.', '/')}"
            if self.pkg_codecs
            else None
        )
        self.dir_protocol = (
            f"{self.base_dir}/java/{self.pkg_protocol.replace('.', '/')}"
            if self.pkg_protocol
            else None
        )

        # Per-instance primitive map (don't mutate module-level PRIMITIVE_MAP)
        self.primitive_map = dict(PRIMITIVE_MAP)
        self.primitive_map["unknown"] = self.unknown_java_type
        self.primitive_map["object"] = self.unknown_java_type

        self.ts_text = open(self.ts_path).read()
        self.exports = TsParser.parse_ts(self.ts_path)
        self.all_interfaces = {}
        self.all_type_aliases = {}
        self.anon_registry = OrderedDict()
        self.known_type_names = set()
        self.inner_models = set()
        self.inner_types = set()
        self.inner_model_owners = {}
        self.processed_inner = set()
        self.request_names = set()
        self.result_names = set()
        self.notif_names = set()
        self.discriminated_unions = OrderedDict()
        self.variant_names = set()
        self.structural_unions = OrderedDict()
        self.interface_extends = OrderedDict()
        self.method_map = OrderedDict()
        self.notifications = []

        for exp in self.exports:
            self.known_type_names.add(exp["name"])
            if exp["kind"] == "interface":
                self.all_interfaces[exp["name"]] = exp
            elif exp["kind"] == "type_alias":
                self.all_type_aliases[exp["name"]] = exp

        # Storage for generated outputs
        self.model_files = OrderedDict()
        self.model_components = (
            OrderedDict()
        )  # name -> [(type, field, optional, jsdoc), ...]
        self.codec_files = OrderedDict()
        self.protocol_files = OrderedDict()

        self.init_from_ts()

    def is_enum_type(self, name):
        exp = self.all_type_aliases.get(name)
        if not exp:
            return False
        rhs = exp["rhs"]
        parts = [p.strip() for p in rhs.split("|") if p.strip()]
        non_null = [p for p in parts if p != "null"]
        return len(non_null) > 0 and all(
            p.startswith('"') and p.endswith('"') for p in non_null
        )

    def init_from_ts(self):
        self.request_names.clear()
        self.result_names.clear()
        self.notif_names.clear()
        self.discriminated_unions.clear()
        self.variant_names.clear()
        self.structural_unions.clear()
        self.interface_extends.clear()
        self.method_map.clear()
        self.notifications[:] = []

        text = self.ts_text

        def extract_body(text, idx):
            brace = text.find("{", idx)
            if brace < 0:
                return ""
            depth = 1
            i = brace + 1
            while i < len(text) and depth > 0:
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                i += 1
            return text[brace + 1 : i - 1]

        def parse_union(name):
            m = re.search(r"export type " + name + r"\s*=\s*(.*?);", text, re.DOTALL)
            if not m:
                return set()
            rhs = m.group(1).strip()
            parts = [p.strip() for p in rhs.split("|") if p.strip()]
            return {
                p
                for p in parts
                if re.match(r"^[A-Z]", p) and '"' not in p and p != "never"
            }

        # 1. Message hierarchy from type union aliases
        for uname in [
            "ClientRequest",
            "ServerRequest",
            "ClientResult",
            "ServerResult",
            "ClientNotification",
            "ServerNotification",
        ]:
            parts = parse_union(uname)
            if uname.endswith("Request"):
                self.request_names.update(parts)
            elif uname.endswith("Result"):
                self.result_names.update(parts)
            elif uname.endswith("Notification"):
                self.notif_names.update(parts)

        # 2. Detect discriminated unions
        seen_unions = {
            "ClientRequest",
            "ServerRequest",
            "ClientResult",
            "ServerResult",
            "ClientNotification",
            "ServerNotification",
        }
        for m in re.finditer(r"export type (\w+)\s*=\s*(.*?);", text, re.DOTALL):
            name = m.group(1)
            if name in seen_unions:
                continue
            rhs = m.group(2).strip()
            parts = [p.strip() for p in rhs.split("|") if p.strip()]
            variants = [p for p in parts if re.match(r"^[A-Z]", p) and '"' not in p]
            if len(variants) < 2:
                continue

            fbv = {}
            for v in variants:
                idx = text.find(f"export interface {v}")
                if idx < 0:
                    idx = text.find(f"export type {v}")
                if idx < 0:
                    break
                body = extract_body(text, idx)
                if not body:
                    break
                fnames = re.findall(r"(\w+)\??\s*:", body)
                fbv[v] = (fnames, body)
            else:
                if not fbv:
                    continue
                common = set.intersection(
                    *[set(fnames) for (fnames, _) in fbv.values()]
                )
                disc = None
                for cf in sorted(common):
                    ok = True
                    vals = set()
                    for v, (_, body) in fbv.items():
                        tm = re.search(rf'{re.escape(cf)}\??:\s*"([^"]+)"', body)
                        if not tm:
                            ok = False
                            break
                        vals.add(tm.group(1))
                    if ok and len(vals) == len(variants):
                        disc = cf
                        break
                if disc:
                    items = []
                    for v in variants:
                        sub = re.search(
                            rf'{re.escape(disc)}\??:\s*"([^"]+)"', fbv[v][1]
                        )
                        if sub:
                            items.append((v, sub.group(1)))
                    self.discriminated_unions[name] = (disc, items)
                    self.variant_names.update(variants)

        # 3. Structural unions
        seen_unions |= set(self.discriminated_unions.keys())
        for m in re.finditer(r"export type (\w+)\s*=\s*(.*?);", text, re.DOTALL):
            name = m.group(1)
            if name in seen_unions:
                continue
            if "|" not in m.group(2):
                continue
            rhs = m.group(2).strip()
            parts = [p.strip() for p in rhs.split("|") if p.strip()]
            struct = [
                p
                for p in parts
                if re.match(r"^[A-Z]", p) and '"' not in p and p != "never"
            ]
            if len(struct) >= 2:
                self.structural_unions[name] = struct

        # 4. Build method_map
        meth_map = {}
        for m in re.finditer(
            r'export interface (\w+)\b[^}]*?method:\s*"([^"]+)"', text, re.DOTALL
        ):
            iface = m.group(1)
            meth = m.group(2)
            meth_map[iface] = meth

        for req in sorted(self.request_names):
            if req not in meth_map:
                continue
            meth = meth_map[req]
            base = req[: -len("Request")] if req.endswith("Request") else req
            res = base + "Result"
            if res not in self.result_names:
                res = "EmptyResult"
            self.method_map[meth] = (req, res)
            self.result_names.add(res)

        # 5. Build notifications
        for notif in sorted(self.notif_names):
            if notif not in meth_map:
                continue
            meth = meth_map[notif]
            idx = text.find(f"export interface {notif}")
            body = ""
            if idx >= 0:
                body = extract_body(text, idx)
            pm = re.search(r"params:\s*(\w+)", body) if body else None
            pt = pm.group(1) if pm else None
            self.notifications.append((meth, notif, pt))

        # 6. Interface extends hierarchy (child -> parent for vertical sealed interfaces)
        parent_children = OrderedDict()
        for exp in self.exports:
            if exp["kind"] != "interface":
                continue
            name = exp["name"]
            for kind, *args in exp.get("extends", []):
                if kind == "extends":
                    parent_name = args[0].strip().rstrip(",").strip()
                    if parent_name not in parent_children:
                        parent_children[parent_name] = []
                    parent_children[parent_name].append(name)
        for parent_name, children in parent_children.items():
            if len(children) >= 2:
                self.interface_extends[parent_name] = children

    def resolve_type(
        self,
        type_str,
        owning_type=None,
        field_name=None,
        type_mappings=None,
    ):
        type_str = type_str.strip()
        if type_str.startswith("typeof "):
            return "String"
        if type_str.startswith('"') and type_str.endswith('"'):
            return "String"
        if type_str == "null":
            return "Void"
        if type_str in self.primitive_map:
            return self.primitive_map[type_str]
        if type_mappings and type_str in type_mappings:
            return type_mappings[type_str]
        if self.all_type_aliases and type_str in self.all_type_aliases:
            alias_rhs = self.all_type_aliases[type_str]["rhs"]
            alias_parts = [p.strip() for p in alias_rhs.split("|") if p.strip()]
            alias_non_null = [p for p in alias_parts if p != "null"]
            alias_non_literals = [
                p for p in alias_non_null
                if not (p.startswith('"') and p.endswith('"'))
            ]
            alias_literals = [
                p for p in alias_non_null
                if p.startswith('"') and p.endswith('"')
            ]
            if alias_literals and not alias_non_literals:
                return type_str
            if len(alias_non_literals) == 1:
                only = alias_non_literals[0]
                if only in self.primitive_map:
                    return self.primitive_map[only]
                if only.startswith("{"):
                    return self.resolve_inline_object(only, owning_type, field_name)
                arr_match = re.match(r"(.+?)\s*\[\]$", only)
                if arr_match and "|" not in arr_match.group(1):
                    inner = self.resolve_type(
                        arr_match.group(1), owning_type, field_name, type_mappings
                    )
                    return "java.util.List<" + inner + ">"
                rec_match = re.match(r"Record<(\w+),\s*(\w+)>", only)
                if rec_match:
                    key_t = self.primitive_map.get(rec_match.group(1), rec_match.group(1))
                    val_t = rec_match.group(2)
                    val_t = self.primitive_map.get(val_t, val_t)
                    if val_t == "unknown":
                        val_t = UNKNOWN_TYPE_MAP.get("unknown", "tools.jackson.databind.JsonNode")
                    jk = "String" if key_t == "String" else key_t
                    return f"java.util.Map<{jk}, {val_t}>"
                if only in self.known_type_names:
                    return self.resolve_type(
                        only, owning_type, field_name, type_mappings
                    )
            if alias_literals and "string" in alias_non_literals:
                return "String"
            if len(alias_non_literals) > 1:
                if type_str in self.discriminated_unions or type_str in self.structural_unions:
                    return type_str
                return self.resolve_union_type(
                    alias_rhs, owning_type, field_name, type_mappings
                )
        if "&" in type_str:
            parts = [p.strip() for p in type_str.split("&")]
            named = [p for p in parts if not p.startswith("{") and p in self.known_type_names]
            if named:
                return named[0]
            inline = [p for p in parts if p.startswith("{")]
            if inline:
                return self.resolve_inline_object(
                    " & ".join(inline), owning_type, field_name
                )
            return "Object"
        if type_str.startswith("{"):
            return self.resolve_inline_object(type_str, owning_type, field_name)
        arr_match = re.match(r"\((.+)\)\s*\[\]", type_str)
        if arr_match:
            inner = self.resolve_union_type(
                arr_match.group(1), owning_type, field_name, type_mappings
            )
            return "java.util.List<" + inner + ">"
        arr_match = re.match(r"(.+?)\s*\[\]", type_str)
        if arr_match and "|" not in arr_match.group(1):
            inner = self.resolve_type(
                arr_match.group(1), owning_type, field_name, type_mappings
            )
            return "java.util.List<" + inner + ">"
        if "|" in type_str:
            return self.resolve_union_type(
                type_str, owning_type, field_name, type_mappings
            )
        arr_gen = re.match(r"Array<(.+)>", type_str, re.DOTALL)
        if arr_gen:
            inner = arr_gen.group(1).strip()
            if inner.startswith("{"):
                return "java.util.List<tools.jackson.databind.JsonNode>"
            return "java.util.List<" + inner + ">"
        if type_str in STRING_NUMBER_UNIONS:
            return "Object"
        if type_str in self.known_type_names:
            return type_str
        return type_str

    def resolve_union_type(
        self,
        type_str,
        owning_type=None,
        field_name=None,
        type_mappings=None,
    ):
        parts = [p.strip() for p in type_str.split("|")]
        non_null = [p for p in parts if p != "null"]
        if "string" in non_null and "number" in non_null:
            return "Object"
        if "string" in non_null and "integer" in non_null:
            return "Object"
        if "string" in non_null and all(
            p == "string" or (p.startswith('"') and p.endswith('"'))
            for p in non_null
        ):
            return "String"
        all_literals = all(p.startswith('"') and p.endswith('"') for p in non_null)
        if all_literals:
            return "String"
        if len(non_null) == 1:
            return self.resolve_type(
                non_null[0], owning_type, field_name, type_mappings
            )
        if len(non_null) >= 2:
            for union_name, variants in self.structural_unions.items():
                if set(non_null) == set(variants):
                    return union_name
            for parent, children in self.interface_extends.items():
                if set(non_null).issubset(set(children)):
                    return parent
        for p in non_null:
            if p in self.known_type_names:
                return p
        return "Object"

    def resolve_inline_object(self, type_str, owning_type=None, field_name=None):
        if re.search(r"\[\s*\w+\s*:\s*\w+\s*\]\s*:", type_str):
            return "java.util.Map<String, tools.jackson.databind.JsonNode>"
        body_match = re.match(r"\{(.+)\}", type_str, re.DOTALL)
        if not body_match:
            return "java.util.Map<String, tools.jackson.databind.JsonNode>"
        body = body_match.group(1).strip()
        for key, val in self.anon_registry.items():
            if isinstance(val, tuple) and val[0] == body:
                _, existing_owner = val
                if owning_type and existing_owner == owning_type:
                    return key[1]
                if not owning_type:
                    return key[1]
            elif isinstance(val, str) and val == body:
                return key[1] if isinstance(key, tuple) else key
        if field_name:
            name = field_name[0].upper() + field_name[1:] if field_name else "Anon"
        else:
            name = "Anon_" + str(len(self.anon_registry) + 1)
        base_name = name
        counter = 1
        while any(
            k[1] == name
            for k, v in self.anon_registry.items()
            if isinstance(k, tuple) and v[1] == owning_type
        ):
            counter += 1
            name = f"{base_name}{counter}"
        key = (owning_type, name) if owning_type else name
        self.anon_registry[key] = (body, owning_type)
        return name

    def cap(self, s):
        return s[0].upper() + s[1:] if s else ""

    def pkg(self, pkg):
        return f"package {pkg};\n"

    @staticmethod
    def escape_name(name):
        JAVA_KEYWORDS = {
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null",
            "var",
            "record",
            "sealed",
            "permits",
            "yield",
        }
        if name in JAVA_KEYWORDS:
            return name + "_"
        return name

    @staticmethod
    def resolve_array_ts(type_str):
        """Handle Array<T> syntax in addition to T[]."""
        m = re.match(r"Array<(.+)>", type_str)
        if m:
            inner = m.group(1).strip()
            # If inner type is an inline object, fall back to JsonNode
            if inner.startswith("{"):
                return "java.util.List<tools.jackson.databind.JsonNode>"
            return "java.util.List<" + inner + ">"
        return None

    def write_file(self, path, content):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(content)

    def get_parent_properties(self, exp, visited=None):
        if visited is None:
            visited = set()
        props = {}
        jsdocs = {}
        for kind, *args in exp.get("extends", []):
            if kind == "extends":
                parent_name = args[0].strip().rstrip(",").strip()
                if parent_name in visited or parent_name not in self.all_interfaces:
                    continue
                visited.add(parent_name)
                parent = self.all_interfaces[parent_name]
                pp, pj = self.get_parent_properties(parent, visited)
                props.update(pp)
                jsdocs.update(pj)
                for f in parent.get("fields", []):
                    if f["kind"] == "field" and f["name"] not in props:
                        props[f["name"]] = f
                        jsdocs[f["name"]] = f.get("jsdoc", "")
                    elif f["kind"] == "index":
                        props["__index__"] = f
            elif kind == "omit":
                omit_type = args[0].strip()
                omit_keys_raw = args[1].strip()
                omit_keys = set(re.findall(r'"([^"]+)"', omit_keys_raw))
                if omit_type in visited or omit_type not in self.all_interfaces:
                    continue
                visited.add(omit_type)
                parent = self.all_interfaces[omit_type]
                pp, pj = self.get_parent_properties(parent, visited)
                for k, v in pp.items():
                    if k == "__index__" or k not in omit_keys:
                        if k not in props:
                            props[k] = v
                            jsdocs[k] = pj.get(k, "")
        return props, jsdocs

    def collect_all_fields(self, exp):
        collected = list(exp.get("fields", []))
        parent_props, parent_jsdocs = self.get_parent_properties(exp)
        has_index = "__index__" in parent_props
        for key in list(parent_props.keys()):
            if key == "__index__":
                continue
            if not any(ef["name"] == key for ef in collected if ef["kind"] == "field"):
                f_copy = dict(parent_props[key])
                collected.append(f_copy)
        # If any parent has an index signature, propagate it
        if has_index and not any(f["kind"] == "index" for f in collected):
            collected.append({"kind": "index"})
        return collected, has_index

    def _parse_extra_field(self, field_str, class_name):
        m = re.match(r"(\w+)(\??)\s*:\s*(.+);\s*$", field_str.strip())
        if not m:
            return None
        name = m.group(1)
        optional = bool(m.group(2))
        raw_type = m.group(3).strip()
        java_type = self.resolve_type(
            raw_type,
            owning_type=class_name,
            field_name=name,
            type_mappings=self.type_mappings,
        )
        if optional and java_type in ("boolean", "long", "double"):
            java_type = (
                java_type.replace("boolean", "Boolean")
                .replace("long", "Long")
                .replace("double", "Double")
            )
        return (java_type, name, optional, "", name)

    def _is_ignored(self, field_name, class_name):
        ignored = self.ignore_props.get(class_name, [])
        return field_name in ignored

    def get_components(self, fields, class_name, has_index_inherited=False):
        components = []
        has_additional = has_index_inherited
        for f in fields:
            if f["kind"] == "index":
                has_additional = True
                continue
            ftm_key = f"{class_name}.{f['name']}"
            if ftm_key in self.field_type_mappings:
                field_type = self.field_type_mappings[ftm_key]
            else:
                field_type = self.resolve_type(
                    f["type_str"],
                    owning_type=class_name,
                    field_name=f["name"],
                    type_mappings=self.type_mappings,
                )
            optional = f.get("optional", False)
            # A required-but-nullable field (`x: T | null`, no `?`) is nullable like an optional
            # field for typing/decoding/boxing, but on encode its key MUST still be written (as a
            # JSON null literal), not omitted. Treat it as optional here and record it so the
            # encoder can emit the else-null branch.
            union_members = [p.strip() for p in f.get("raw_type", "").split("|")]
            if not optional and "null" in union_members:
                optional = True
                self.nullable_required.setdefault(class_name, set()).add(f.get("json_name", f["name"]))
            # Box optional/nullable primitives
            if optional and field_type in ("boolean", "long", "double"):
                field_type = (
                    field_type.replace("boolean", "Boolean")
                    .replace("long", "Long")
                    .replace("double", "Double")
                )
            if self._is_ignored(f["name"], class_name):
                continue
            fname = self.escape_name(f["name"])
            json_name = f.get("json_name", f["name"])
            components.append(
                (
                    field_type,
                    fname,
                    optional,
                    JavadocFormatter.format_jsdoc(f.get("jsdoc", "")),
                    json_name,
                )
            )
        extra_fields = self.additional_props.get(class_name, [])
        for field_str in extra_fields:
            comp = self._parse_extra_field(field_str, class_name)
            if comp and not any(c[1] == comp[1] for c in components):
                components.append(comp)
        if has_additional:
            components.append(
                (
                    "java.util.Map<String, tools.jackson.databind.JsonNode>",
                    "additionalProperties",
                    True,
                    "",
                    "additionalProperties",
                )
            )
        return components, has_additional

    def simplify_type(self, typ):
        return re.sub(r'(?<![A-Za-z])(?:[a-z_][a-z0-9_]*\.)+([A-Z]\w+)', r'\1', typ)

    def _collect_imports(self, components, imports):
        for c in components:
            t = c[0]
            for m in re.finditer(r'((?:[a-z_][a-z0-9_]*\.)+[A-Z]\w+)', t):
                imports.add(m.group(1))
            if c[2]:
                imports.add("org.jspecify.annotations.Nullable")

    def _collect_inner_imports(self, parent_name, imports):
        children = [
            (k, v)
            for k, v in self.anon_registry.items()
            if isinstance(k, tuple)
            and isinstance(v, tuple)
            and v[1] == parent_name
            and k not in self.processed_inner
        ]
        for key, (body, _) in children:
            anon_name = key[1]
            qualified_name = f"{parent_name}.{anon_name}"
            fields = TsParser.parse_fields("{" + body + "}")
            comps, _ = self.get_components(fields, qualified_name)
            self._collect_imports(comps, imports)
            self._collect_inner_imports(qualified_name, imports)

    def add_model(self, name, components, interfaces=None, jsdoc=None):
        if name in self.model_files:
            return
        self.model_components[name] = components

        imports = set()
        imports.add("javax.annotation.processing.Generated")
        imports.add("com.fasterxml.jackson.annotation.JsonIgnoreProperties")
        imports.add("com.fasterxml.jackson.annotation.JsonProperty")
        if any(c[4] is None for c in components):
            imports.add("com.fasterxml.jackson.annotation.JsonUnwrapped")
        self._collect_imports(components, imports)
        # Also collect imports from inner record components
        self._collect_inner_imports(name, imports)

        imp_str = "\n".join(f"import {i};" for i in sorted(imports))
        if imp_str:
            imp_str += "\n"

        iface_str = ""
        if interfaces:
            iface_str = " implements " + ", ".join(interfaces)

        params = []
        for c in components:
            typ, fname, optional, _, json_name = c
            nullable = "@Nullable " if optional else ""
            simple_typ = self.simplify_type(typ)
            annotation = "@JsonUnwrapped" if json_name is None else f'@JsonProperty("{json_name}")'
            params.append(f"    {annotation} {nullable}{simple_typ} {fname}")

        class_desc = JavadocFormatter.format_jsdoc(jsdoc) if jsdoc else ""
        if not class_desc:
            class_desc = "A " + " ".join(w for w in re.sub(r"([A-Z])", r" \1", name).split()).lower() + "."
        param_docs = [(fname, jd or "") for _, fname, _, jd, _ in components]

        out = []
        out.append(self.pkg(self.pkg_models))
        out.append("\n")
        out.append(imp_str)
        out.append("\n")
        jd_block = JavadocFormatter.make_javadoc(class_desc, param_docs or None)
        if jd_block:
            out.append(jd_block)
        out.append('@JsonIgnoreProperties(ignoreUnknown = true)\n')
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append(f"public record {name}(\n")
        out.append(",\n".join(params))
        out.append(f"\n) {iface_str.strip()}{{\n")
        self.append_inner_classes(name, out)
        self.append_factory_methods(name, out, components)
        self.append_builder(name, out, components, depth=0)
        out.append("}\n")
        self.model_files[name] = "".join(out)

    def append_inner_classes(self, parent_name, out, depth=1):
        children = [
            (k, v)
            for k, v in self.anon_registry.items()
            if isinstance(k, tuple)
            and isinstance(v, tuple)
            and v[1] == parent_name
            and k not in self.processed_inner
        ]
        indent = "    " * depth
        for key, (body, _) in children:
            anon_name = key[1]
            qualified_name = f"{parent_name}.{anon_name}"
            self.processed_inner.add(key)
            self.inner_types.add(qualified_name)
            self.inner_model_owners[qualified_name] = parent_name
            self.model_files[qualified_name] = ""
            fields = TsParser.parse_fields("{" + body + "}")
            comps, _ = self.get_components(fields, qualified_name)
            self.model_components[qualified_name] = comps
            out.append(f'{indent}@JsonIgnoreProperties(ignoreUnknown = true)\n')
            out.append(f'{indent}{GENERATED_ANNOTATION}\n')
            params = []
            for c in comps:
                typ, fname, optional, _, json_name = c
                nullable = "@Nullable " if optional else ""
                simple_typ = self.simplify_type(typ)
                params.append(
                    f'{indent}    @JsonProperty("{json_name}") {nullable}{simple_typ} {fname}'
                )
            class_desc = " ".join(w for w in anon_name.replace("_", " ").split() if w)
            out.append(f'{indent}/** Parameters for {{@link {parent_name}}}. */\n')
            out.append(f"{indent}public record {anon_name}(\n")
            out.append(",\n".join(params))
            out.append(f"\n{indent}) {{\n")
            self.append_inner_classes(qualified_name, out, depth + 1)
            self.append_builder(qualified_name, out, comps, depth)
            out.append(f"{indent}}}\n\n")

    def add_interface(self, name, permits=None, super_iface=None, jsdoc=None):
        if name in self.model_files:
            return
        out = []
        out.append(self.pkg(self.pkg_models))
        out.append("\n")
        out.append("import javax.annotation.processing.Generated;\n")
        out.append("\n")
        class_desc = JavadocFormatter.format_jsdoc(jsdoc) if jsdoc else ""
        jd_block = JavadocFormatter.make_javadoc(class_desc)
        if jd_block:
            out.append(jd_block)
        ext = f" extends {super_iface}" if super_iface else ""
        perm = f" permits {', '.join(permits)}" if permits else ""
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append(f"public sealed interface {name}{ext}{perm} {{\n")
        out.append("}\n")
        self.model_files[name] = "".join(out)

    def add_discriminated_interface(self, name, discriminator, variants, jsdoc=None):
        if name in self.model_files:
            return
        out = []
        out.append(self.pkg(self.pkg_models))
        out.append("\n")
        out.append("import com.fasterxml.jackson.annotation.JsonSubTypes;\n")
        out.append("import com.fasterxml.jackson.annotation.JsonTypeInfo;\n")
        out.append("import javax.annotation.processing.Generated;\n")
        out.append("\n")
        class_desc = JavadocFormatter.format_jsdoc(jsdoc) if jsdoc else ""
        jd_block = JavadocFormatter.make_javadoc(class_desc)
        if jd_block:
            out.append(jd_block)
        entries = [
            f'        @JsonSubTypes.Type(value = {vn}.class, name = "{lit}")'
            for vn, lit in variants
        ]
        out.append("@JsonTypeInfo(\n")
        out.append("    use = JsonTypeInfo.Id.NAME,\n")
        out.append("    include = JsonTypeInfo.As.EXISTING_PROPERTY,\n")
        out.append(f'    property = "{discriminator}"\n')
        out.append(")\n")
        out.append("@JsonSubTypes({\n")
        out.append(",\n".join(entries))
        out.append("\n})\n")
        permit_list = ", ".join(v[0] for v in variants)
        out.append(f"public sealed interface {name} permits {permit_list} {{\n")
        out.append("}\n")
        self.model_files[name] = "".join(out)

    def add_sealed_interface_from_extends(self, name, children, jsdoc=None):
        if name in self.model_files:
            return
        exp = self.all_interfaces.get(name)
        comps = []
        if exp:
            fields, _ = self.collect_all_fields(exp)
            comps, _ = self.get_components(fields, name)

        imports = set()
        imports.add("javax.annotation.processing.Generated")
        imports.add("com.fasterxml.jackson.annotation.JsonSubTypes")
        imports.add("com.fasterxml.jackson.annotation.JsonTypeInfo")
        self._collect_imports(comps, imports)
        imp_str = "\n".join(f"import {i};" for i in sorted(imports))
        if imp_str:
            imp_str += "\n"

        out = []
        out.append(self.pkg(self.pkg_models))
        out.append("\n")
        out.append(imp_str)
        out.append("\n")
        class_desc = JavadocFormatter.format_jsdoc(jsdoc) if jsdoc else ""
        jd_block = JavadocFormatter.make_javadoc(class_desc)
        if jd_block:
            out.append(jd_block)
        entries = [
            f"        @JsonSubTypes.Type(value = {v}.class)"
            for v in children
            if v in self.all_interfaces
        ]
        out.append("@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)\n")
        out.append("@JsonSubTypes({\n")
        out.append(",\n".join(entries))
        out.append("\n})\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        # Determine extends clause — if this sealed interface is also a child of another
        parent_ext = ""
        for pn, pc in self.interface_extends.items():
            if (
                name in pc
                and pn not in self.discriminated_unions
                and pn not in self.structural_unions
            ):
                parent_ext = f" extends {pn}"
                break
        out.append(
            f"public sealed interface {name}{parent_ext} permits {', '.join(children)} {{\n"
        )
        for typ, fname, optional, _, _ in comps:
            simple_typ = self.simplify_type(typ)
            nul = "@Nullable " if optional else ""
            desc = " ".join(w for w in fname.replace("_", " ").split() if w)
            out.append(f"    /**\n")
            out.append(f"     * Returns {desc}.\n")
            out.append(f"     *\n")
            out.append(f"     * @return {desc}\n")
            out.append(f"     */\n")
            out.append(f"    {nul}{simple_typ} {fname}();\n")
        out.append("}\n")
        self.model_files[name] = "".join(out)

    def add_structural_union(self, name, variants, super_iface=None, jsdoc=None):
        if name in self.model_files:
            return
        out = []
        out.append(self.pkg(self.pkg_models))
        out.append("\n")
        out.append("import com.fasterxml.jackson.annotation.JsonSubTypes;\n")
        out.append("import com.fasterxml.jackson.annotation.JsonTypeInfo;\n")
        out.append("import javax.annotation.processing.Generated;\n")
        out.append("\n")
        class_desc = JavadocFormatter.format_jsdoc(jsdoc) if jsdoc else ""
        jd_block = JavadocFormatter.make_javadoc(class_desc)
        if jd_block:
            out.append(jd_block)
        entries = [
            f"        @JsonSubTypes.Type(value = {v}.class)"
            for v in variants
            if v in self.all_interfaces
        ]
        out.append("@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)\n")
        out.append("@JsonSubTypes({\n")
        out.append(",\n".join(entries))
        out.append("\n})\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        ext = f" extends {super_iface}" if super_iface else ""
        out.append(
            f"public sealed interface {name}{ext} permits {', '.join(variants)} {{\n"
        )
        out.append("}\n")
        self.model_files[name] = "".join(out)

    def add_enum(self, name, values, jsdoc=None):
        if name in self.model_files:
            return
        out = []
        out.append(self.pkg(self.pkg_models))
        out.append("\nimport com.fasterxml.jackson.annotation.JsonValue;\n")
        out.append("import javax.annotation.processing.Generated;\n\n")
        class_desc = JavadocFormatter.format_jsdoc(jsdoc) if jsdoc else ""
        jd_block = JavadocFormatter.make_javadoc(class_desc)
        if jd_block:
            out.append(jd_block)
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append(f"public enum {name} {{\n")
        lines = []
        for v in values:
            cn = v.upper().replace("-", "_").replace(" ", "_").replace("/", "_")
            if cn[0].isdigit():
                cn = "_" + cn
            lines.append(f'    {cn}("{v}")')
        out.append(",\n".join(lines))
        out.append(";\n\n")
        out.append("    private final String value;\n\n")
        out.append(f"    {name}(String value) {{ this.value = value; }}\n\n")
        out.append("    @JsonValue\n")
        out.append("    public String getValue() { return value; }\n\n")
        out.append("    public static " + name + " fromValue(String value) {\n")
        out.append("        for (" + name + " v : values()) {\n")
        out.append("            if (v.value.equals(value)) return v;\n")
        out.append("        }\n")
        out.append('        throw new IllegalArgumentException("Unexpected value: " + value);\n')
        out.append("    }\n")
        out.append("}\n")
        self.model_files[name] = "".join(out)

    def type_ref(self, name, context=None):
        if context:
            candidate = f"{context}.{name}"
            if candidate in self.inner_types:
                return candidate
            parts = context.split(".")
            for i in range(len(parts) - 1, 0, -1):
                parent = ".".join(parts[:i])
                candidate = f"{parent}.{name}"
                if candidate in self.inner_types:
                    return candidate
        if name in self.inner_types:
            return name
        return name

    def ref_for_type(self, typ, context=None):
        if "<" in typ:
            base = typ.split("<")[0]
            inner = typ.split("<")[1].rsplit(">", 1)[0]
            inner_ref = self.ref_for_type(inner, context)
            if inner_ref != inner:
                return f"{base}<{inner_ref}>"
            return typ
        q = self.type_ref(typ, context)
        return q

    def append_factory_methods(self, name, out, components, depth=0):
        indent = "    " * depth
        factory_methods = {
            "TextContent": [
                ("of", ["String text"], ['"text"', "text"]),
            ],
            "CallToolResult": [
                ("ofText", ["String text"], ["List.of(TextContent.of(text))"]),
                ("ofError", ["String message"], ["List.of(TextContent.of(message))", None, "true"]),
                ("of", ["ContentBlock... content"], ["List.of(content)"]),
            ],
            "PromptMessage": [
                ("of", ["Role role", "ContentBlock content"], ["role", "content"]),
                ("user", ["ContentBlock content"], ["Role.USER", "content"]),
                ("user", ["String text"], ["Role.USER", "TextContent.of(text)"]),
            ],
            "ImageContent": [
                ("of", ["byte[] data", "String mimeType"], ['"image"', "data", "mimeType"]),
            ],
            "AudioContent": [
                ("of", ["byte[] data", "String mimeType"], ['"audio"', "data", "mimeType"]),
            ],
            "TextResourceContents": [
                ("of", ["String text", "String uri", "String mimeType"], ["text", "uri", "mimeType"]),
            ],
            "EmbeddedResource": [
                ("of", ["ResourceContents resource"], ['"resource"', "resource"]),
            ],
        }
        if name not in factory_methods:
            return
        num = len(components)
        out.append("\n")
        for method_name, params, explicit in factory_methods[name]:
            args = [str(e) if e is not None else "null" for e in explicit]
            while len(args) < num:
                args.append("null")
            body = f"return new {name}(" + ", ".join(args) + ");"
            param_names = [p.split()[-1] for p in params]
            out.append(f"{indent}    /**\n")
            out.append(f"{indent}     * Creates a {name}.\n")
            for pn in param_names:
                out.append(f"{indent}     * @param {pn} the value\n")
            out.append(f"{indent}     * @return the new instance\n")
            out.append(f"{indent}     */\n")
            out.append(f"{indent}    public static {name} {method_name}({', '.join(params)}) {{\n")
            out.append(f"{indent}        {body}\n")
            out.append(f"{indent}    }}\n\n")

    def append_builder(self, name, out, components, depth=0):
        indent = "    " * depth

        out.append(f"{indent}    /**\n")
        out.append(f"{indent}     * Creates a new builder.\n")
        out.append(f"{indent}     *\n")
        out.append(f"{indent}     * @return a new builder\n")
        out.append(f"{indent}     */\n")
        out.append(f"{indent}    public static Builder builder() {{\n")
        out.append(f"{indent}        return new Builder();\n")
        out.append(f"{indent}    }}\n\n")

        out.append(indent + "    /** Builder for {@link " + name + "}. */\n")
        out.append(f"{indent}    public static final class Builder {{\n")
        out.append(f"{indent}        private Builder() {{}}\n\n")

        for typ, fname, optional, _, _ in components:
            simple_typ = self.simplify_type(typ)
            out.append(f"{indent}        private {simple_typ} {fname};\n")
        out.append("\n")

        for typ, fname, optional, _, _ in components:
            simple_typ = self.simplify_type(typ)
            desc = " ".join(w for w in fname.replace("_", " ").split() if w)
            out.append(f"{indent}    /**\n")
            out.append(f"{indent}     * Sets {desc}.\n")
            out.append(f"{indent}     *\n")
            out.append(f"{indent}     * @param {fname} the {desc}\n")
            out.append(f"{indent}     * @return this builder\n")
            out.append(f"{indent}     */\n")
            out.append(
                f"{indent}    public Builder {fname}({simple_typ} {fname}) {{\n"
            )
            out.append(f"{indent}        this.{fname} = {fname};\n")
            out.append(f"{indent}        return this;\n")
            out.append(f"{indent}    }}\n")
        out.append("\n")

        args = [c[1] for c in components]
        out.append(f"{indent}    /**\n")
        out.append(indent + "     * Builds the {@link " + name + "}.\n")
        out.append(f"{indent}     *\n")
        out.append(f"{indent}     * @return the built instance\n")
        out.append(f"{indent}     */\n")
        out.append(f"{indent}    public {name} build() {{\n")
        out.append(f"{indent}        return new {name}(\n")
        for i, a in enumerate(args):
            out.append(
                f"{indent}            {a}{',' if i < len(args) - 1 else ''}\n"
            )
        out.append(f"{indent}        );\n")
        out.append(f"{indent}    }}\n")

        out.append(f"{indent}    }}\n")

    def top_owner(self, qualified_name):
        while qualified_name in self.inner_model_owners:
            qualified_name = self.inner_model_owners[qualified_name]
        return qualified_name

    def codec_class_name(self, qualified_name):
        if qualified_name in self.inner_types:
            return qualified_name.replace(".", "_") + "Codec"
        return f"{qualified_name}Codec"

    def codec_ref(self, typ, context_owner):
        base = typ.split("<")[0].split(".")[-1]
        q = self.type_ref(base, context_owner)
        if q != base:
            return q.replace(".", "_") + "Codec"
        return f"{base}Codec"

    def registry_ref(self, typ, context_owner):
        base = typ.split("<")[0].split(".")[-1]
        q = self.type_ref(base, context_owner)
        return q

    # --- Codec generation ---

    def add_codec(self, model_name):
        codec_name = self.codec_class_name(model_name)
        if codec_name in self.codec_files:
            return
        if model_name not in self.model_components:
            return

        is_inner = model_name in self.inner_types
        qname = self.type_ref(model_name, model_name)

        components = self.model_components[model_name]
        has_additional = any(c[1] == "additionalProperties" for c in components)
        flattened = self.flattened_unions.get(model_name)
        regular = [
            c for c in components
            if c[1] != "additionalProperties" and (not flattened or c[1] != flattened[1])
        ]

        out = []
        out.append(self.pkg(self.pkg_codecs))
        out.append("\n")

        imported = set()

        def emit_import(fqcn):
            imported.add(fqcn)

        emit_import("tools.jackson.core.JsonGenerator")
        emit_import("tools.jackson.core.JsonParser")
        emit_import("tools.jackson.core.JsonToken")
        if self.unknown_type == "TokenBuffer":
            emit_import("tools.jackson.databind.util.TokenBuffer")
        if any("java.util.List" in c[0] for c in components):
            emit_import("java.util.ArrayList")
            emit_import("java.util.List")
        if has_additional or any("java.util.Map" in c[0] for c in components):
            emit_import("java.util.Map")
            emit_import("tools.jackson.databind.JsonNode")
        if has_additional:
            emit_import("java.util.LinkedHashMap")
        if any(c[0] in JSON_TREE_TYPES or "java.util.List<tools.jackson.databind" in c[0] for c in components):
            emit_import("tools.jackson.databind.JsonNode")
        emit_import("javax.annotation.processing.Generated")
        if is_inner:
            owner = self.top_owner(model_name)
            emit_import(f"{self.pkg_models}.{owner}")
        else:
            owner = model_name
            emit_import(f"{self.pkg_models}.{model_name}")

        # Imports for referenced model types and FQN from type mappings
        refs = set()
        for typ, _, _, _, _ in regular:
            parts = typ.replace("<", " ").replace(">", " ").replace(",", " ").split()
            for p in parts:
                if "." in p and not p[0].isupper():
                    refs.add(p)
                    continue
                base = p.split(".")[-1]
                if base in (
                    "ArrayList",
                    "List",
                    "LinkedHashMap",
                    "Map",
                    "String",
                    "Boolean",
                    "Long",
                    "Double",
                    "Integer",
                    "JsonNode",
                    "Object",
                    self.unknown_simple,
                ):
                    continue
                if base == model_name:
                    continue
                q = self.type_ref(base, model_name)
                if q != base:
                    refs.add(self.top_owner(q))
                elif base in self.model_files:
                    refs.add(base)
        if flattened:
            union, _ = flattened
            refs.add(union)
            emit_import("tools.jackson.databind.util.TokenBuffer")
            if has_additional:
                emit_import("java.util.Set")
                refs.update(vn for vn, _ in self.discriminated_unions[union][1])
        refs.discard(owner)
        refs.discard(self.pkg_models + "." + owner)
        for r in sorted(refs):
            if "." in r:
                emit_import(r)
            else:
                emit_import(f"{self.pkg_models}.{r}")

        for i in sorted(imported):
            out.append(f"import {i};\n")

        out.append("\n")
        out.append("/** Codec for {@link " + qname + "}. */\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append(f"public class {codec_name} implements Codec<{qname}> {{\n")
        out.append(f"    /** Default constructor. */\n")
        out.append(f"    public {codec_name}() {{}}\n\n")

        # --- DECODE ---
        out.append("    @Override\n")
        if flattened:
            union, field = flattened
            out.append(f"    public {qname} decode(JsonParser source) {{\n")
            out.append("        var buffer = TokenBuffer.forBuffering(source, source.objectReadContext());\n")
            out.append("        buffer.copyCurrentStructure(source);\n")
            out.append(f"        {union} {field};\n")
            out.append("        try (JsonParser parser = buffer.asParser()) {\n")
            out.append("            parser.nextToken();\n")
            out.append(f"            {field} = CodecRegistry.<{union}>codecFor({union}.class).decode(parser);\n")
            out.append("        }\n")
            out.append("        try (JsonParser parser = buffer.asParser()) {\n")
            out.append("            parser.nextToken();\n")
            out.append(f"            return decodeProperties(parser, {field});\n")
            out.append("        }\n")
            out.append("    }\n\n")
            out.append(f"    private {qname} decodeProperties(JsonParser parser, {union} {field}) {{\n")
        else:
            out.append(f"    public {qname} decode(JsonParser parser) {{\n")
        for typ, fname, optional, _, _ in regular:
            if "java.util.List" in typ:
                item = typ.split("<")[1][:-1]
                item_ref = self.ref_for_type(item, model_name)
                out.append(f"        List<{item_ref}> {fname}List = null;\n")
            elif "java.util.Map" in typ:
                out.append(f"        Map<String, JsonNode> {fname}Map = null;\n")
            else:
                default = "null"
                if typ == "boolean":
                    default = "false"
                elif typ == "long":
                    default = "0L"
                elif typ == "double":
                    default = "0.0"
                var_typ = self.ref_for_type(typ, model_name)
                out.append(f"        {var_typ} {fname} = {default};\n")
        if has_additional:
            out.append("        Map<String, JsonNode> additionalProperties = null;\n")

        out.append("\n        while (parser.nextToken() != JsonToken.END_OBJECT) {\n")
        out.append("            String fieldName = parser.currentName();\n")
        out.append("            parser.nextToken();\n")
        out.append("            switch (fieldName) {\n")

        for typ, fname, optional, _, json_name in regular:
            out.append(f'                case "{json_name}" -> {{\n')
            is_nullable_required = json_name in self.nullable_required.get(model_name, set())
            is_raw_json_str = (
                typ == "String"
                and self.field_type_mappings.get(f"{model_name}.{json_name}") == "String"
            )
            if is_nullable_required and typ in ("String", "Boolean", "Long", "Double") and not is_raw_json_str:
                # Required-but-nullable scalar: decodeValue returns null for a JSON null.
                out.append(
                    f"                    {fname} = CodecSupport.decodeValue(parser, {self.ref_for_type(typ, model_name)}.class);\n"
                )
                out.append("                }\n")
                continue
            if is_nullable_required:
                # Non-scalar required-but-nullable: an explicit JSON null leaves the field at its null default.
                out.append(
                    "                    if (parser.currentToken() != JsonToken.VALUE_NULL) {\n"
                )
            if is_raw_json_str:
                out.append(
                    f"                    {fname} = CodecSupport.readRawJson(parser);\n"
                )
            elif typ == "String":
                out.append(f"                    {fname} = CodecSupport.decodeString(parser);\n")
            elif typ == "boolean":
                out.append(f"                    {fname} = CodecSupport.decodeBoolean(parser, false);\n")
            elif typ == "Boolean":
                out.append(f"                    {fname} = CodecSupport.decodeBoolean(parser);\n")
            elif typ == "long":
                out.append(f"                    {fname} = CodecSupport.decodeLong(parser, 0L);\n")
            elif typ == "Long":
                out.append(f"                    {fname} = CodecSupport.decodeLong(parser);\n")
            elif typ == "double":
                out.append(f"                    {fname} = CodecSupport.decodeDouble(parser, 0.0);\n")
            elif typ == "Double":
                out.append(f"                    {fname} = CodecSupport.decodeDouble(parser);\n")
            elif typ == "byte[]":
                out.append(f"                    {fname} = CodecSupport.decodeBinary(parser);\n")
            elif typ == "java.time.Instant":
                out.append(
                    f"                    {fname} = parser.currentToken() == JsonToken.VALUE_NULL\n"
                    f"                            ? null\n"
                    f"                            : java.time.Instant.parse(parser.getString());\n"
                )
            elif typ == "java.time.Duration":
                out.append(
                    f"                    {fname} = parser.currentToken() == JsonToken.VALUE_NULL\n"
                    f"                            ? null\n"
                    f"                            : java.time.Duration.ofMillis(parser.getLongValue());\n"
                )
            elif "java.util.List" in typ:
                item = typ.split("<")[1][:-1]
                item_ref = self.ref_for_type(item, model_name)
                is_scalar_item = item_ref in (
                    "String", "boolean", "Boolean", "long", "Long", "double", "Double",
                    *JSON_TREE_TYPES)
                is_enum_item = self.is_enum_type(item)
                out.append(
                    f"                    if (parser.currentToken() == JsonToken.START_ARRAY) {{\n"
                )
                out.append(
                    f"                        var list = new ArrayList<{item_ref}>();\n"
                )
                if not is_scalar_item and not is_enum_item:
                    out.append(
                        f"                        var {fname}Codec = CodecRegistry.<{item_ref}>codecFor({item_ref}.class);\n"
                    )
                out.append(
                    f"                        while (parser.nextToken() != JsonToken.END_ARRAY) {{\n"
                )
                if is_scalar_item:
                    out.append(
                        f"                            list.add(CodecSupport.decodeValue(parser, {item_ref}.class));\n"
                    )
                elif is_enum_item:
                    out.append(
                        f"                            var {fname}Value = CodecSupport.decodeString(parser);\n"
                        f"                            if ({fname}Value != null) list.add({item_ref}.fromValue({fname}Value));\n"
                    )
                else:
                    out.append(
                        f"                            list.add({fname}Codec.decode(parser));\n"
                    )
                out.append(f"                        }}\n")
                out.append(f"                        {fname}List = list;\n")
                out.append(f"                    }} else if (parser.currentToken() != JsonToken.VALUE_NULL) {{\n")
                out.append(
                    f'                        throw CodecSupport.wrongType(parser, "{json_name}", "an array");\n'
                )
                out.append(f"                    }}\n")
            elif typ in JSON_TREE_TYPES:
                out.append(
                    f"                    {fname} = CodecSupport.decodeValue(parser, {typ}.class);\n"
                )
            elif "java.util.Map" in typ:
                out.append(
                    f'                    {fname}Map = CodecSupport.readTreeMap(parser, "{json_name}");\n'
                )
            else:
                base_type = self.registry_ref(typ, model_name)
                if self.is_enum_type(typ):
                    out.append(
                        f"                    var raw{fname} = CodecSupport.decodeString(parser);\n"
                        f"                    {fname} = raw{fname} == null ? null : {base_type}.fromValue(raw{fname});\n"
                    )
                else:
                    out.append(
                        f"                    if (parser.currentToken() == JsonToken.START_OBJECT) {{\n"
                    )
                    out.append(
                        f"                        {fname} = CodecRegistry.<{base_type}>codecFor({base_type}.class).decode(parser);\n"
                    )
                    out.append(f"                    }} else if (parser.currentToken() != JsonToken.VALUE_NULL) {{\n")
                    out.append(
                        f'                        throw CodecSupport.wrongType(parser, "{json_name}", "an object");\n'
                    )
                    out.append(f"                    }}\n")
            if is_nullable_required:
                out.append("                    }\n")
            out.append("                }\n")

        if has_additional and flattened:
            out.append("                default -> {\n")
            out.append(f"                    if (variantProperties({flattened[1]}).contains(fieldName)) {{\n")
            out.append("                        parser.skipChildren();\n")
            out.append("                        continue;\n")
            out.append("                    }\n")
            out.append(
                "                    if (additionalProperties == null) additionalProperties = new LinkedHashMap<>();\n"
            )
            out.append(
                "                    additionalProperties.put(fieldName, CodecSupport.readTree(parser));\n"
            )
            out.append("                }\n")
        elif has_additional:
            out.append("                default -> {\n")
            out.append(
                "                    if (additionalProperties == null) additionalProperties = new LinkedHashMap<>();\n"
            )
            out.append(
                "                    additionalProperties.put(fieldName, CodecSupport.readTree(parser));\n"
            )
            out.append("                }\n")
        else:
            out.append("                default -> parser.skipChildren();\n")
        out.append("            }\n")
        out.append("        }\n\n")

        args = [flattened[1]] if flattened else []
        for typ, fname, optional, _, _ in regular:
            if "java.util.List" in typ:
                args.append(f"{fname}List")
            elif "java.util.Map" in typ:
                args.append(f"{fname}Map")
            else:
                args.append(fname)
        if has_additional:
            args.append("additionalProperties")

        out.append(f"        return new {qname}(\n")
        for i, a in enumerate(args):
            out.append(f"            {a}{',' if i < len(args) - 1 else ''}\n")
        out.append("        );\n")
        out.append("    }\n\n")

        # --- ENCODE ---
        out.append("    @Override\n")
        out.append(f"    public void encode(JsonGenerator gen, {qname} value) {{\n")
        out.append("        gen.writeStartObject();\n")
        if flattened:
            union, field = flattened
            out.append(f"        var variant = CodecRegistry.<{union}>codecFor({union}.class).encodeToBytes(value.{field}());\n")
            out.append("        try (JsonParser parser = CodecSupport.createParser(variant)) {\n")
            out.append("            parser.nextToken();\n")
            out.append(f'            CodecSupport.writeTreeEntries(gen, CodecSupport.readTreeMap(parser, "{field}"));\n')
            out.append("        }\n")
        for typ, fname, optional, _, json_name in regular:
            acc = f"value.{fname}()"
            is_primitive = typ in ("boolean", "long", "double")
            is_raw_json_str = (
                typ == "String"
                and self.field_type_mappings.get(f"{model_name}.{json_name}") == "String"
            )
            if (
                json_name in self.nullable_required.get(model_name, set())
                and typ in ("String", "Boolean", "Long", "Double")
                and not is_raw_json_str
            ):
                # Required-but-nullable scalar: encodeValue writes the value, or a JSON null literal.
                out.append(f'        gen.writeName("{json_name}");\n')
                out.append(f"        CodecSupport.encodeValue(gen, {acc});\n")
                continue
            if (
                json_name in self.nullable_required.get(model_name, set())
                and typ == "java.time.Duration"
            ):
                # Required-but-nullable Duration: no generic encodeValue overload knows the
                # millis wire representation, so null-check explicitly instead.
                out.append(f'        gen.writeName("{json_name}");\n')
                out.append(f"        if ({acc} != null) {{\n")
                out.append(f"            gen.writeNumber({acc}.toMillis());\n")
                out.append("        } else {\n")
                out.append("            gen.writeNull();\n")
                out.append("        }\n")
                continue
            if optional and not is_primitive:
                out.append(f"        if ({acc} != null) {{\n")
                ind = "            "
            elif optional:
                out.append(f"        if ({acc} != null) {{\n")
                ind = "            "
            else:
                ind = "        "
            if typ == "String" and self.field_type_mappings.get(f"{model_name}.{json_name}") == "String":
                out.append(f'{ind}gen.writeName("{json_name}");\n')
                out.append(f"{ind}if ({acc} != null) {{\n")
                out.append(f"{ind}    gen.writeRawValue({acc});\n")
                out.append(f"{ind}}} else {{\n")
                out.append(f"{ind}    gen.writeNull();\n")
                out.append(f"{ind}}}\n")
            elif typ == "String":
                out.append(f'{ind}gen.writeStringProperty("{json_name}", {acc});\n')
            elif typ in ("boolean", "Boolean"):
                out.append(f'{ind}gen.writeBooleanProperty("{json_name}", {acc});\n')
            elif typ in ("long", "Long"):
                out.append(f'{ind}gen.writeNumberProperty("{json_name}", {acc});\n')
            elif typ in ("double", "Double"):
                out.append(f'{ind}gen.writeNumberProperty("{json_name}", {acc});\n')
            elif typ == "byte[]":
                out.append(f'{ind}gen.writeBinaryProperty("{json_name}", {acc});\n')
            elif typ == "java.time.Instant":
                out.append(f'{ind}gen.writeStringProperty("{json_name}", {acc}.toString());\n')
            elif typ == "java.time.Duration":
                out.append(f'{ind}gen.writeNumberProperty("{json_name}", {acc}.toMillis());\n')
            elif "java.util.List" in typ:
                item = typ.split("<")[1][:-1]
                item_ref = self.ref_for_type(item, model_name)
                is_scalar_item = item_ref in (
                    "String", "boolean", "Boolean", "long", "Long", "double", "Double",
                    *JSON_TREE_TYPES)
                is_enum_item = self.is_enum_type(item)
                if not is_scalar_item and not is_enum_item:
                    out.append(
                        f"{ind}var {fname}Codec = CodecRegistry.<{item_ref}>codecFor({item_ref}.class);\n"
                    )
                out.append(f'{ind}gen.writeArrayPropertyStart("{json_name}");\n')
                out.append(f"{ind}for (var item : {acc}) {{\n")
                if is_scalar_item:
                    out.append(f"{ind}    CodecSupport.encodeValue(gen, item);\n")
                elif is_enum_item:
                    out.append(f"{ind}    gen.writeString(item.getValue());\n")
                else:
                    out.append(f"{ind}    {fname}Codec.encode(gen, item);\n")
                out.append(f"{ind}}}\n")
                out.append(f"{ind}gen.writeEndArray();\n")
            elif typ in JSON_TREE_TYPES:
                out.append(f'{ind}gen.writeName("{json_name}");\n')
                out.append(f"{ind}CodecSupport.encodeValue(gen, {acc});\n")
            elif "java.util.Map" in typ:
                out.append(f'{ind}gen.writeObjectPropertyStart("{json_name}");\n')
                out.append(f"{ind}CodecSupport.writeTreeEntries(gen, {acc});\n")
                out.append(f"{ind}gen.writeEndObject();\n")
            else:
                base_type = self.registry_ref(typ, model_name)
                if self.is_enum_type(typ):
                    out.append(
                        f'{ind}gen.writeStringProperty("{json_name}", {acc}.getValue());\n'
                    )
                else:
                    out.append(
                        f'{ind}gen.writeName("{json_name}");\n'
                    )
                    out.append(
                        f"{ind}CodecRegistry.<{base_type}>codecFor({base_type}.class).encode(gen, {acc});\n"
                    )  # noqa: E501
            if optional:
                if json_name in self.nullable_required.get(model_name, set()):
                    # Required-but-nullable: write the key as JSON null instead of omitting it.
                    out.append("        } else {\n")
                    out.append(f'            gen.writeName("{json_name}");\n')
                    out.append("            gen.writeNull();\n")
                    out.append("        }\n")
                else:
                    out.append("        }\n")

        if has_additional:
            out.append("        if (value.additionalProperties() != null) {\n")
            out.append("            CodecSupport.writeTreeEntries(gen, value.additionalProperties());\n")
            out.append("        }\n")

        out.append("        gen.writeEndObject();\n")
        out.append("    }\n")

        if flattened and has_additional:
            union, field = flattened
            out.append(f"\n    private static Set<String> variantProperties({union} {field}) {{\n")
            out.append(f"        return switch ({field}) {{\n")
            for vn, _ in self.discriminated_unions[union][1]:
                names = ", ".join(f'"{c[4]}"' for c in self.model_components[vn] if c[4])
                out.append(f"            case {vn} ignored -> Set.of({names});\n")
            out.append("        };\n")
            out.append("    }\n")

        out.append("}\n")
        self.codec_files[codec_name] = "".join(out)

    # --- Protocol registries ---

    def add_protocol_version(self):
        name = "McpProtocolVersion"
        if name in self.protocol_files:
            return
        out = [self.pkg(self.pkg_protocol), "\n"]
        out.append("import javax.annotation.processing.Generated;\n\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append(f"public final class {name} {{\n")
        out.append('    public static final String VERSION = "2025-11-25";\n')
        out.append("    private McpProtocolVersion() {}\n")
        out.append("}\n")
        self.protocol_files[name] = "".join(out)

    def add_method_registry(self):
        name = "McpMethodRegistry"
        if name in self.protocol_files:
            return
        out = [self.pkg(self.pkg_protocol), "\n"]
        out.append("import javax.annotation.processing.Generated;\n\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append(f"public final class {name} {{\n")
        for method, _ in self.method_map.items():
            cn = method.upper().replace("/", "_")
            out.append(f'    public static final String {cn} = "{method}";\n')
        out.append(f"    private {name}() {{}}\n")
        out.append("}\n")
        self.protocol_files[name] = "".join(out)

    def add_descriptors(self):
        if "MethodDescriptor" not in self.protocol_files:
            out = [self.pkg(self.pkg_protocol), "\n"]
            out.append("import javax.annotation.processing.Generated;\n\n")
            out.append(f'{GENERATED_ANNOTATION}\n')
            out.append("public record MethodDescriptor(\n")
            out.append("    String method,\n")
            out.append("    Class<?> requestType,\n")
            out.append("    Class<?> resultType\n")
            out.append(") {}\n")
            self.protocol_files["MethodDescriptor"] = "".join(out)

        name = "McpMethodDispatch"
        if name in self.protocol_files:
            return
        out = [self.pkg(self.pkg_protocol), "\n"]
        model_refs = set()
        for _, (req, res) in self.method_map.items():
            model_refs.add(req)
            model_refs.add(res)
        model_refs.add("UnknownRequest")
        for r in sorted(model_refs):
            out.append(f"import {self.pkg_models}.{r};\n")
        out.append("import java.util.Collections;\n")
        out.append("import java.util.LinkedHashMap;\n")
        out.append("import java.util.Map;\n")
        out.append("import java.util.Set;\n\n")
        out.append(f"public final class {name} {{\n")
        out.append(
            "    private static final Map<String, MethodDescriptor> METHODS = buildMethods();\n\n"
        )
        out.append(
            "    private static Map<String, MethodDescriptor> buildMethods() {\n"
        )
        out.append("        var map = new LinkedHashMap<String, MethodDescriptor>();\n")
        for method, (req, res) in self.method_map.items():
            out.append(
                f'        map.put("{method}", new MethodDescriptor("{method}", {req}.class, {res}.class));\n'
            )
        out.append("        return Collections.unmodifiableMap(map);\n")
        out.append("    }\n\n")
        out.append(
            "    public static MethodDescriptor get(String method) { return METHODS.get(method); }\n"
        )
        out.append(
            "    public static Set<String> knownMethods() { return METHODS.keySet(); }\n"
        )
        out.append("    public static Class<?> requestType(String method) {\n")
        out.append("        var d = METHODS.get(method);\n")
        out.append(
            "        return d != null ? d.requestType() : UnknownRequest.class;\n"
        )
        out.append("    }\n")
        out.append("    public static Class<?> resultType(String method) {\n")
        out.append("        var d = METHODS.get(method);\n")
        out.append("        return d != null ? d.resultType() : null;\n")
        out.append("    }\n")
        out.append("}\n")
        self.protocol_files[name] = "".join(out)

    def add_discriminated_codec(self, union_name, discriminator, variants):
        codec_name = self.codec_class_name(union_name)
        if codec_name in self.codec_files:
            return
        out = [self.pkg(self.pkg_codecs), "\n"]
        imported = {
            "tools.jackson.core.JsonGenerator",
            "tools.jackson.core.JsonParser",
            "tools.jackson.core.JsonToken",
            "tools.jackson.databind.util.TokenBuffer",
            "javax.annotation.processing.Generated",
            f"{self.pkg_models}.{union_name}",
        }
        for vn, _ in variants:
            imported.add(f"{self.pkg_models}.{vn}")
        for i in sorted(imported):
            out.append(f"import {i};\n")
        out.append("\n")
        out.append("/** Codec for {@link " + union_name + "}. */\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append(f"public class {codec_name} implements Codec<{union_name}> {{\n\n")
        out.append(f"    /** Default constructor. */\n")
        out.append(f"    public {codec_name}() {{}}\n\n")

        # --- DECODE ---
        out.append("    @Override\n")
        out.append(f"    public {union_name} decode(JsonParser parser) {{\n")
        out.append("        var tb = TokenBuffer.forBuffering(parser, parser.objectReadContext());\n")
        out.append("        tb.copyCurrentStructure(parser);\n\n")
        out.append("        String type = null;\n")
        out.append("        try (JsonParser tp = tb.asParser()) {\n")
        out.append("            tp.nextToken();\n")
        out.append("            while (tp.nextToken() != JsonToken.END_OBJECT) {\n")
        out.append("                String fn = tp.currentName();\n")
        out.append("                tp.nextToken();\n")
        out.append(f'                if ("{discriminator}".equals(fn)) {{\n')
        out.append("                    type = tp.getString();\n")
        out.append("                    break;\n")
        out.append("                }\n")
        out.append("                tp.skipChildren();\n")
        out.append("            }\n")
        out.append("        }\n")
        out.append("        if (type == null) {\n")
        out.append(f'            throw new IllegalArgumentException("Missing {discriminator} discriminator");\n')
        out.append("        }\n")
        out.append("        try (JsonParser dp = tb.asParser()) {\n")
        out.append("            dp.nextToken();\n")
        out.append("            return switch (type) {\n")
        for vn, lit in variants:
            out.append(f'                case "{lit}" -> CodecRegistry.<{vn}>codecFor({vn}.class).decode(dp);\n')
        out.append('                default -> throw new IllegalArgumentException("Unknown ' + union_name + ' type: " + type);\n')
        out.append("            };\n")
        out.append("        }\n")
        out.append("    }\n\n")

        # --- ENCODE ---
        out.append("    @Override\n")
        out.append(f"    public void encode(JsonGenerator gen, {union_name} value) {{\n")
        first = True
        for vn, _ in variants:
            prefix = "if" if first else "} else if"
            out.append(f"        {prefix} (value instanceof {vn} v) {{\n")
            out.append(f"            CodecRegistry.<{vn}>codecFor({vn}.class).encode(gen, v);\n")
            first = False
        out.append("        }\n")
        out.append("    }\n")
        out.append("}\n")
        self.codec_files[codec_name] = "".join(out)

    def add_deduction_codec(self, union_name, variant_field_map):
        codec_name = self.codec_class_name(union_name)
        if codec_name in self.codec_files:
            return
        out = [self.pkg(self.pkg_codecs), "\n"]
        imported = {
            "tools.jackson.core.JsonGenerator",
            "tools.jackson.core.JsonParser",
            "tools.jackson.core.JsonToken",
            "tools.jackson.databind.util.TokenBuffer",
            "javax.annotation.processing.Generated",
            f"{self.pkg_models}.{union_name}",
        }
        for vn in variant_field_map:
            imported.add(f"{self.pkg_models}.{vn}")
        for i in sorted(imported):
            out.append(f"import {i};\n")
        out.append("/** Codec for {@link " + union_name + "}. */\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append(f"public class {codec_name} implements Codec<{union_name}> {{\n\n")
        out.append(f"    /** Default constructor. */\n")
        out.append(f"    public {codec_name}() {{}}\n\n")

        out.append("    @Override\n")
        out.append(f"    public {union_name} decode(JsonParser parser) {{\n")
        out.append("        var tb = TokenBuffer.forBuffering(parser, parser.objectReadContext());\n")
        out.append("        tb.copyCurrentStructure(parser);\n\n")
        for vn, field in variant_field_map.items():
            out.append(f"        boolean has{field} = false;\n")
        out.append("        try (JsonParser tp = tb.asParser()) {\n")
        out.append("            tp.nextToken();\n")
        out.append("            while (tp.nextToken() != JsonToken.END_OBJECT) {\n")
        out.append("                String fn = tp.currentName();\n")
        out.append("                tp.nextToken();\n")
        for vn, field in variant_field_map.items():
            out.append(f'                if ("{field}".equals(fn)) {{ has{field} = true; }}\n')
        out.append("                tp.skipChildren();\n")
        out.append("            }\n")
        out.append("        }\n")
        out.append("        try (JsonParser dp = tb.asParser()) {\n")
        out.append("            dp.nextToken();\n")
        items = list(variant_field_map.items())
        for i, (vn, field) in enumerate(items):
            if i == 0:
                out.append(f"            if (has{field}) {{\n")
            else:
                out.append(f"            }} else if (has{field}) {{\n")
            out.append(f"                return CodecRegistry.<{vn}>codecFor({vn}.class).decode(dp);\n")
        out.append("            } else {\n")
        out.append(f'                throw new IllegalArgumentException("Cannot determine {union_name} variant");\n')
        out.append("            }\n")
        out.append("        }\n")
        out.append("    }\n\n")

        out.append("    @Override\n")
        out.append(f"    public void encode(JsonGenerator gen, {union_name} value) {{\n")
        first = True
        for vn in variant_field_map:
            prefix = "if" if first else "} else if"
            out.append(f"        {prefix} (value instanceof {vn} v) {{\n")
            out.append(f"            CodecRegistry.<{vn}>codecFor({vn}.class).encode(gen, v);\n")
            first = False
        out.append("        }\n")
        out.append("    }\n")
        out.append("}\n")
        self.codec_files[codec_name] = "".join(out)

    def add_codec_interface(self):
        name = "Codec"
        if name in self.codec_files:
            return
        out = [self.pkg(self.pkg_codecs), "\n"]
        for i in sorted(
            [
                "tools.jackson.core.JacksonException",
                "tools.jackson.core.JsonGenerator",
                "tools.jackson.core.JsonParser",
                "tools.jackson.core.JsonToken",
                "tools.jackson.core.json.JsonFactory",
                "javax.annotation.processing.Generated",
            ]
        ):
            out.append(f"import {i};\n")
        out.append("\n")
        out.append("/** Codec for serializing and deserializing model types. */\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append("public interface Codec<T> {\n")
        out.append("    /** Shared streaming factory; prefer {@link CodecSupport} for tree-aware parsing. */\n")
        out.append("    JsonFactory FACTORY = CodecSupport.FACTORY;\n\n")
        out.append("    /**\n")
        out.append("     * Deserializes a value from JSON.\n")
        out.append("     *\n")
        out.append("     * @param parser the JSON parser positioned at a value\n")
        out.append("     * @return the decoded value\n")
        out.append("     * @throws JacksonException on parse failure\n")
        out.append("     */\n")
        out.append("    T decode(JsonParser parser);\n\n")
        out.append("    /**\n")
        out.append("     * Serializes a value to JSON.\n")
        out.append("     *\n")
        out.append("     * @param gen   the JSON generator to write to\n")
        out.append("     * @param value the value to serialize\n")
        out.append("     * @throws JacksonException on write failure\n")
        out.append("     */\n")
        out.append("    void encode(JsonGenerator gen, T value);\n\n")
        out.append("    /**\n")
        out.append("     * Serializes a value to a UTF-8 JSON byte array.\n")
        out.append("     *\n")
        out.append("     * @param value the value to serialize\n")
        out.append("     * @return the UTF-8 encoded JSON\n")
        out.append("     * @throws JacksonException on write failure\n")
        out.append("     */\n")
        out.append("    default byte[] encodeToBytes(T value) {\n")
        out.append("        return CodecSupport.writeToBytes(gen -> encode(gen, value));\n")
        out.append("    }\n\n")
        out.append("    /**\n")
        out.append("     * Deserializes a value from a UTF-8 JSON byte array.\n")
        out.append("     *\n")
        out.append("     * @param data the UTF-8 encoded JSON object\n")
        out.append("     * @return the decoded value\n")
        out.append("     * @throws JacksonException on parse failure\n")
        out.append("     */\n")
        out.append("    default T decodeFromBytes(byte[] data) {\n")
        out.append("        try (var parser = CodecSupport.createParser(data)) {\n")
        out.append("            if (parser.nextToken() != JsonToken.START_OBJECT) {\n")
        out.append('                throw new IllegalArgumentException("Expected JSON object");\n')
        out.append("            }\n")
        out.append("            return decode(parser);\n")
        out.append("        }\n")
        out.append("    }\n")
        out.append("}\n")
        self.codec_files[name] = "".join(out)

    def add_codec_support(self):
        name = "CodecSupport"
        if name in self.codec_files:
            return
        out = [self.pkg(self.pkg_codecs), "\n"]
        for i in sorted(
            [
                "java.io.ByteArrayOutputStream",
                "java.io.StringWriter",
                "java.util.LinkedHashMap",
                "java.util.Map",
                "javax.annotation.processing.Generated",
                "org.jspecify.annotations.Nullable",
                "tools.jackson.core.JacksonException",
                "tools.jackson.core.JsonGenerator",
                "tools.jackson.core.JsonParser",
                "tools.jackson.core.JsonToken",
                "tools.jackson.core.ObjectReadContext",
                "tools.jackson.core.json.JsonFactory",
                "tools.jackson.databind.DeserializationFeature",
                "tools.jackson.databind.exc.MismatchedInputException",
                "tools.jackson.databind.JsonNode",
                "tools.jackson.databind.json.JsonMapper",
            ]
        ):
            out.append(f"import {i};\n")
        out.append("\n")
        out.append("""/**
 * Streaming helpers shared by every generated {@link Codec}.
 *
 * <p>All reads tolerate an explicit JSON {@code null} in place of a value: the wire allows it for
 * any optional property, and the streaming accessors ({@code getString}, {@code getBooleanValue},
 * &hellip;) either coerce it to nonsense or throw. All tree reads and writes go through a
 * databind-backed context, so they work no matter which {@code ObjectReadContext} produced the
 * parser.
 */""")
        out.append("\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append("public final class CodecSupport {\n\n")
        out.append(
            "    /**\n"
            "     * Reads and writes sub-trees of a larger stream, so the trailing-token check that guards a\n"
            "     * whole-document bind must be off.\n"
            "     */\n"
        )
        out.append(
            "    private static final JsonMapper MAPPER = JsonMapper.builder()\n"
            "            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)\n"
            "            .build();\n\n"
        )
        out.append("    /** Streaming factory shared by the generated codecs. */\n")
        out.append("    public static final JsonFactory FACTORY = new JsonFactory();\n\n")
        out.append("    private CodecSupport() {}\n\n")
        out.append("""    /** Writes JSON into a generator. */
    @FunctionalInterface
    public interface JsonWriter {
        /**
         * Writes one JSON value.
         *
         * @param gen the generator to write to
         */
        void write(JsonGenerator gen);
    }

    /**
     * Creates a tree-capable parser over UTF-8 JSON bytes.
     *
     * @param data the UTF-8 encoded JSON
     * @return a parser whose {@code readValueAsTree} is backed by databind
     */
    public static JsonParser createParser(byte[] data) {
        return MAPPER.createParser(data);
    }

    /**
     * Creates a tree-capable parser over a JSON string.
     *
     * @param json the JSON text
     * @return a parser whose {@code readValueAsTree} is backed by databind
     */
    public static JsonParser createParser(String json) {
        return MAPPER.createParser(json);
    }

    /**
     * Serializes JSON written by {@code writer} to a UTF-8 byte array.
     *
     * @param writer writes the JSON value
     * @return the UTF-8 encoded JSON
     */
    public static byte[] writeToBytes(JsonWriter writer) {
        var out = new ByteArrayOutputStream(256);
        try (var gen = MAPPER.createGenerator(out)) {
            writer.write(gen);
        }
        return out.toByteArray();
    }

    /**
     * Reads the value at the parser's current token as a tree, regardless of the parser's own
     * {@code ObjectReadContext}.
     *
     * @param parser the parser positioned at a value
     * @return the parsed tree
     */
    public static JsonNode readTree(JsonParser parser) {
        return MAPPER.readTree(parser);
    }

    /**
     * Writes a tree to the generator without an intermediate string.
     *
     * @param gen  the generator to write to
     * @param node the tree to write
     */
    public static void writeTree(JsonGenerator gen, @Nullable JsonNode node) {
        if (node == null) {
            gen.writeNull();
            return;
        }
        try (JsonParser p = node.traverse(ObjectReadContext.empty())) {
            p.nextToken();
            gen.copyCurrentStructure(p);
        }
    }

    /**
     * Writes each entry of a tree map as a property of the current object.
     *
     * @param gen     the generator, positioned inside an object
     * @param entries the properties to write
     */
    public static void writeTreeEntries(JsonGenerator gen, Map<String, JsonNode> entries) {
        for (var entry : entries.entrySet()) {
            gen.writeName(entry.getKey());
            writeTree(gen, entry.getValue());
        }
    }

    /**
     * Reads a JSON object into a map of trees. An explicit JSON null yields {@code null}; any other
     * non-object token is a client error.
     *
     * @param parser the parser positioned at the value
     * @param field  the property being read, for the error message
     * @return the property map, or {@code null}
     */
    public static @Nullable Map<String, JsonNode> readTreeMap(JsonParser parser, String field) {
        if (parser.currentToken() == JsonToken.VALUE_NULL) return null;
        if (parser.currentToken() != JsonToken.START_OBJECT) throw wrongType(parser, field, "an object");
        var map = new LinkedHashMap<String, JsonNode>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String key = parser.currentName();
            parser.nextToken();
            map.put(key, readTree(parser));
        }
        return map;
    }

    /**
     * Reports a property whose JSON type does not match the schema.
     *
     * <p>The generated codecs skip properties they do not model, but a property they <em>do</em>
     * model arriving with the wrong type is a client error, not something to drop silently.
     *
     * @param parser   the parser positioned at the offending value
     * @param field    the property name
     * @param expected a description of the expected shape, e.g. {@code "an object"}
     * @return the exception to throw
     */
    public static MismatchedInputException wrongType(JsonParser parser, String field, String expected) {
        return MismatchedInputException.from(
                parser, Object.class, "Property '" + field + "' expects " + expected);
    }

    /**
     * Copies the value at the current token out as raw JSON text, without building a tree.
     *
     * @param parser the parser positioned at a value
     * @return the raw JSON, or {@code null} for an explicit JSON null
     */
    public static @Nullable String readRawJson(JsonParser parser) {
        if (parser.currentToken() == JsonToken.VALUE_NULL) return null;
        var writer = new StringWriter();
        try (JsonGenerator gen = FACTORY.createGenerator(tools.jackson.core.ObjectWriteContext.empty(), writer)) {
            gen.copyCurrentStructure(parser);
        }
        return writer.toString();
    }

    /**
     * Reads a string, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the string, or {@code null}
     */
    public static @Nullable String decodeString(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getString();
    }

    /**
     * Reads a boolean, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the boolean, or {@code null}
     */
    public static @Nullable Boolean decodeBoolean(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getBooleanValue();
    }

    /**
     * Reads a long, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the long, or {@code null}
     */
    public static @Nullable Long decodeLong(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getLongValue();
    }

    /**
     * Reads a double, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the double, or {@code null}
     */
    public static @Nullable Double decodeDouble(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getDoubleValue();
    }

    /**
     * Reads a primitive boolean, substituting {@code fallback} for an explicit JSON null.
     *
     * @param parser   the parser positioned at a value
     * @param fallback the value to use for a JSON null
     * @return the boolean
     */
    public static boolean decodeBoolean(JsonParser parser, boolean fallback) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? fallback : parser.getBooleanValue();
    }

    /**
     * Reads a primitive long, substituting {@code fallback} for an explicit JSON null.
     *
     * @param parser   the parser positioned at a value
     * @param fallback the value to use for a JSON null
     * @return the long
     */
    public static long decodeLong(JsonParser parser, long fallback) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? fallback : parser.getLongValue();
    }

    /**
     * Reads a primitive double, substituting {@code fallback} for an explicit JSON null.
     *
     * @param parser   the parser positioned at a value
     * @param fallback the value to use for a JSON null
     * @return the double
     */
    public static double decodeDouble(JsonParser parser, double fallback) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? fallback : parser.getDoubleValue();
    }

    /**
     * Reads base64 binary, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the decoded bytes, or {@code null}
     */
    public static byte @Nullable [] decodeBinary(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getBinaryValue();
    }

    /**
     * Reads a value of one of the scalar or tree types the generated codecs use.
     *
     * @param <T>    the value type
     * @param parser the parser positioned at a value
     * @param type   the expected type
     * @return the decoded value, or {@code null} for an explicit JSON null
     */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable T decodeValue(JsonParser parser, Class<T> type) {
        if (parser.currentToken() == JsonToken.VALUE_NULL) return null;
        if (type == String.class) return (T) parser.getString();
        if (type == Boolean.class || type == boolean.class) return (T) Boolean.valueOf(parser.getBooleanValue());
        if (type == Long.class || type == long.class) return (T) Long.valueOf(parser.getLongValue());
        if (type == Double.class || type == double.class) return (T) Double.valueOf(parser.getDoubleValue());
%%DECODE_UNKNOWN%%        return (T) readTree(parser);
    }

    /**
     * Writes a scalar, tree, or {@code null} value to the generator.
     *
     * @param gen   the generator to write to
     * @param value the value to write
     */
    public static void encodeValue(JsonGenerator gen, @Nullable Object value) {
        switch (value) {
            case null -> gen.writeNull();
            case String s -> gen.writeString(s);
            case Boolean b -> gen.writeBoolean(b);
            case Long l -> gen.writeNumber(l);
            case Double d -> gen.writeNumber(d);
            case JsonNode n -> writeTree(gen, n);
%%ENCODE_UNKNOWN%%            default -> gen.writeString(value.toString());
        }
    }
}
""")
        text = "".join(out)
        decode_unknown = ""
        encode_unknown = ""
        extra_imports = ""
        if self.unknown_type == "TokenBuffer":
            decode_unknown = (
                "        if (type == TokenBuffer.class) {\n"
                "            TokenBuffer buf = TokenBuffer.forBuffering(parser, parser.objectReadContext());\n"
                "            buf.copyCurrentStructure(parser);\n"
                "            return (T) buf;\n"
                "        }\n"
            )
            encode_unknown = "            case TokenBuffer tb -> tb.serialize(gen);\n"
            extra_imports = "import tools.jackson.databind.util.TokenBuffer;\n"
        elif self.unknown_type == "byte[]":
            decode_unknown = (
                "        if (type == byte[].class) {\n"
                "            return (T) writeToBytes(gen -> {\n"
                "                gen.copyCurrentStructure(parser);\n"
                "            });\n"
                "        }\n"
            )
            encode_unknown = (
                "            case byte[] b -> {\n"
                "                try (JsonParser sub = createParser(b)) {\n"
                "                    sub.nextToken();\n"
                "                    gen.copyCurrentStructure(sub);\n"
                "                }\n"
                "            }\n"
            )
        text = text.replace("%%DECODE_UNKNOWN%%", decode_unknown)
        text = text.replace("%%ENCODE_UNKNOWN%%", encode_unknown)
        if extra_imports:
            text = text.replace(
                "import tools.jackson.databind.json.JsonMapper;\n",
                "import tools.jackson.databind.json.JsonMapper;\n" + extra_imports,
                1,
            )
        self.codec_files[name] = text

    def add_codec_registry(self):
        name = "CodecRegistry"
        if name in self.codec_files:
            return
        out = [self.pkg(self.pkg_codecs), "\n"]
        # Collect model class references for imports
        model_imports = set()
        for codec_name in self.codec_files:
            if codec_name in ("Codec", "CodecRegistry", "CodecSupport"):
                continue
            model_part = codec_name[:-5]
            if "_" in model_part:
                owner = model_part.split("_")[0]
                model_imports.add(owner)
            else:
                model_imports.add(model_part)
        for req, _ in self.method_map.values():
            model_imports.add(req)
        for _, notif, _ in self.notifications:
            model_imports.add(notif)
        for ref in sorted(model_imports):
            out.append(f"import {self.pkg_models}.{ref};\n")
        if not self.base_package:
            out.append("import java.util.Collections;\n")
        out.append("import java.util.LinkedHashMap;\n")
        out.append("import java.util.Map;\n")
        out.append("import java.util.concurrent.ConcurrentHashMap;\n")
        out.append("import javax.annotation.processing.Generated;\n\n")
        out.append(f'{GENERATED_ANNOTATION}\n')
        out.append(f"public class {name} {{\n")
        # Static codec map
        out.append(
            "    private static final Map<Class<?>, Codec<?>> CODECS = new LinkedHashMap<>();\n\n"
        )
        out.append(
            "    /** Runtime overrides; concurrent because {@code registerOverride} runs after publication. */\n"
        )
        out.append(
            "    private static final Map<Class<?>, Codec<?>> OVERRIDES = new ConcurrentHashMap<>();\n\n"
        )
        out.append("    static {\n")
        for codec_name in self.codec_files:
            if codec_name in ("Codec", "CodecRegistry", "CodecSupport"):
                continue
            model_part = codec_name[:-5]
            model_class = model_part.replace("_", ".")
            out.append(
                f"        CODECS.put({model_class}.class, new {codec_name}());\n"
            )
        out.append("    }\n\n")
        out.append("    /**\n")
        out.append("     * Looks up the codec registered for a model type.\n")
        out.append("     *\n")
        out.append("     * @param <T>        the model type\n")
        out.append("     * @param modelClass the model type\n")
        if self.base_package:
            out.append("     * @return the codec, falling back to the base registry for base-package types,\n")
            out.append("     *     or {@code null} if neither registers one\n")
        else:
            out.append("     * @return the codec, or {@code null} if none is registered\n")
        out.append("     */\n")
        out.append('    @SuppressWarnings("unchecked")\n')
        out.append("    public static <T> Codec<T> codecFor(Class<T> modelClass) {\n")
        out.append("        if (!OVERRIDES.isEmpty()) {\n")
        out.append("            var override = (Codec<T>) OVERRIDES.get(modelClass);\n")
        out.append("            if (override != null) return override;\n")
        out.append("        }\n")
        if self.base_package:
            out.append("        var codec = (Codec<T>) CODECS.get(modelClass);\n")
            out.append(
                f"        return codec != null ? codec : {self.base_package}.codecs.CodecRegistry.codecFor(modelClass);\n"
            )
        else:
            out.append("        return (Codec<T>) CODECS.get(modelClass);\n")
        out.append("    }\n\n")
        out.append("    /**\n")
        out.append("     * Replaces the codec used for a model type.\n")
        out.append("     *\n")
        out.append("     * @param <T>        the model type\n")
        out.append("     * @param modelClass the model type\n")
        out.append("     * @param codec      the codec to use instead of the generated one\n")
        out.append("     */\n")
        out.append("    public static <T> void registerOverride(Class<T> modelClass, Codec<T> codec) {\n")
        out.append("        OVERRIDES.put(modelClass, codec);\n")
        out.append("    }\n")
        if self.base_package:
            # Extensions add no MCP methods of their own to the base dispatcher.
            out.append("}\n")
            self.codec_files[name] = "".join(out)
            return
        out.append("\n")
        # Instance-based method lookup (for dispatcher)
        out.append("    private final Map<String, Object> codecs;\n\n")
        out.append(f"    private {name}(Builder b) {{\n")
        out.append("        this.codecs = Collections.unmodifiableMap(b.codecs);\n")
        out.append("    }\n\n")
        out.append('    @SuppressWarnings("unchecked")\n')
        out.append(
            '    public <T> T requestCodec(String method) { return (T) codecs.get("request:" + method); }\n'
        )
        out.append('    @SuppressWarnings("unchecked")\n')
        out.append(
            '    public <T> T notificationCodec(String method) { return (T) codecs.get("notification:" + method); }\n'
        )
        out.append('    @SuppressWarnings("unchecked")\n')
        out.append(
            '    public <T> T resultCodec(Class<?> c) { return (T) codecs.get("result:" + c.getSimpleName()); }\n\n'
        )
        out.append("    /**\n")
        out.append("     * Creates a new builder.\n")
        out.append("     *\n")
        out.append("     * @return a new builder\n")
        out.append("     */\n")
        out.append("    public static Builder builder() { return new Builder(); }\n\n")
        out.append("    /** Builder for {@link CodecRegistry}. */\n")
        out.append("    public static class Builder {\n")
        out.append(
            "        private final Map<String, Object> codecs = new LinkedHashMap<>();\n\n"
        )
        out.append("        /** Default constructor. */\n")
        out.append("        public Builder() {}\n\n")
        for method, (req, res) in self.method_map.items():
            desc = method.replace("_", " ").title()
            out.append("        /**\n")
            out.append(f"         * Registers codecs for {desc}.\n")
            out.append("         *\n")
            out.append("         * @return this builder\n")
            out.append("         */\n")
            out.append(f"        public Builder register{req}() {{\n")
            out.append(
                f'            codecs.put("request:{method}", new {req}Codec());\n'
            )
            out.append(f'            codecs.put("result:{res}", new {res}Codec());\n')
            out.append("            return this;\n")
            out.append("        }\n")
        for method, notif, _ in self.notifications:
            desc = method.replace("_", " ").title()
            out.append("        /**\n")
            out.append(f"         * Registers codec for {desc}.\n")
            out.append("         *\n")
            out.append("         * @return this builder\n")
            out.append("         */\n")
            out.append(f"        public Builder register{notif}() {{\n")
            out.append(
                f'            codecs.put("notification:{method}", new {notif}Codec());\n'
            )
            out.append("            return this;\n")
            out.append("        }\n")
        out.append("        /**\n")
        out.append(f"         * Builds the {{@link CodecRegistry}}.\n")
        out.append("         *\n")
        out.append("         * @return the built registry\n")
        out.append("         */\n")
        out.append(
            f"        public CodecRegistry build() {{ return new CodecRegistry(this); }}\n"
        )
        out.append("    }\n")
        out.append("}\n")
        self.codec_files[name] = "".join(out)

    # --- Main generation ---

    def generate(self):
        # 1. Enums from pure string literal unions
        for exp in self.exports:
            if exp["kind"] != "type_alias":
                continue
            name = exp["name"]
            if name in self.type_mappings:
                continue
            if self.is_enum_type(name):
                rhs = exp["rhs"]
                parts = [p.strip() for p in rhs.split("|") if p.strip()]
                values = [p[1:-1] for p in parts if p.startswith('"')]
                if values:
                    self.add_enum(name, values, jsdoc=exp.get("jsdoc"))
                continue

        # 2. Discriminated union interfaces + variants
        variant_interfaces = {}
        for union_name, (disc, variants) in self.discriminated_unions.items():
            for vn, _ in variants:
                if vn not in variant_interfaces:
                    variant_interfaces[vn] = []
                variant_interfaces[vn].append(union_name)
        processed_union_variants = set()
        for union_name, (disc, variants) in self.discriminated_unions.items():
            for vn, _ in variants:
                if vn in processed_union_variants:
                    continue
                processed_union_variants.add(vn)
                if vn in self.all_interfaces:
                    exp = self.all_interfaces[vn]
                    fields, has_idx = self.collect_all_fields(exp)
                    comps, _ = self.get_components(fields, vn, has_idx)
                    self.add_model(vn, comps, variant_interfaces.get(vn), jsdoc=exp.get("jsdoc"))
            union_exp = self.all_type_aliases.get(union_name)
            self.add_discriminated_interface(union_name, disc, variants,
                                             jsdoc=union_exp.get("jsdoc") if union_exp else None)

        # 3. Structural unions — collect variant->parent mapping first
        structural_variant_parents = {}
        for un, variants in self.structural_unions.items():
            for v in variants:
                if v not in structural_variant_parents:
                    structural_variant_parents[v] = []
                structural_variant_parents[v].append(un)
        for un, variants in self.structural_unions.items():
            if un in self.type_mappings:
                continue
            parents = structural_variant_parents.get(un)
            super_iface = parents[0] if parents else None
            union_exp = self.all_type_aliases.get(un)
            self.add_structural_union(un, variants, super_iface,
                                      jsdoc=union_exp.get("jsdoc") if union_exp else None)

        # 4. Sealed interface parents (pure extends-based hierarchies)
        # Only ResourceContents needs a sealed interface for polymorphic
        # deserialization — other extends-parents have field type conflicts
        # with their grandparent types when implemented as interfaces.
        if "ResourceContents" in self.interface_extends:
            rc_children = self.interface_extends["ResourceContents"]
            rc_exp = self.all_interfaces.get("ResourceContents")
            self.add_sealed_interface_from_extends("ResourceContents", rc_children,
                                                   jsdoc=rc_exp.get("jsdoc") if rc_exp else None)

        # 4.5. All other interfaces as records
        for exp in self.exports:
            if exp["kind"] != "interface":
                continue
            name = exp["name"]
            if name in self.model_files:
                continue
            if name in self.all_type_aliases:
                continue
            if name in self.variant_names:
                continue
            if name in self.structural_unions:
                continue
            if name in self.discriminated_unions:
                continue

            fields, has_idx = self.collect_all_fields(exp)
            comps, _ = self.get_components(fields, name, has_idx)

            ifaces = []
            if name in self.request_names:
                ifaces.append("McpRequest")
            if name in self.notif_names:
                ifaces.append("McpNotification")
            if name in self.result_names:
                ifaces.append("McpResponse")
            if name in structural_variant_parents:
                ifaces.extend(structural_variant_parents[name])
            # Add extends parents for child interfaces (only when parent
            # is a sealed interface; flat records don't support implements)
            for pn, children in self.interface_extends.items():
                if name in children and pn in self.model_files:
                    fname = self.model_files[pn]
                    if "sealed interface" in fname:
                        ifaces.append(pn)
            self.add_model(name, comps, ifaces if ifaces else None, jsdoc=exp.get("jsdoc"))

        # 4.5. Type aliases that need concrete model files
        for exp in self.exports:
            if exp["kind"] != "type_alias":
                continue
            name = exp["name"]
            if name in self.model_files:
                continue
            if name in self.type_mappings:
                continue
            rhs = exp["rhs"]

            # Skip enum types (already generated in step 1)
            if self.is_enum_type(name):
                continue

            # Analyze alias structure
            parts = [p.strip() for p in rhs.split("|") if p.strip()]
            non_null = [p for p in parts if p != "null"]
            non_literals = [
                p for p in non_null
                if not (p.startswith('"') and p.endswith('"'))
            ]
            literals = [p for p in non_null if p.startswith('"') and p.endswith('"')]

            if len(non_null) == 1 and len(non_literals) == 1:
                only = non_literals[0]
                # Simple primitive alias (Cursor = string) → no model needed
                if only in self.primitive_map:
                    continue
                # Alias to another model (EmptyResult = Result, ClientResult = EmptyResult)
                comps_raw = None
                if only in self.model_components:
                    comps_raw = self.model_components[only]
                elif only in self.all_interfaces:
                    fields, _ = self.collect_all_fields(self.all_interfaces[only])
                    comps_raw, _ = self.get_components(fields, only)
                if comps_raw is not None:
                    comps = []
                    for c in comps_raw:
                        typ, fname, optional, field_jsdoc, json_name = c
                        if typ in self.inner_models:
                            typ = self.type_ref(typ)
                        comps.append((typ, fname, optional, field_jsdoc, json_name))
                    if name in self.result_names:
                        self.add_model(name, comps, ["McpResponse"], jsdoc=exp.get("jsdoc"))
                    else:
                        self.add_model(name, comps, jsdoc=exp.get("jsdoc"))
                    continue

            # String literals + | string → no model needed (resolve_type returns String)
            if literals and "string" in non_literals:
                continue

            # Intersection types (CreateTaskResult = Result & Task & { resultType: "task" })
            if "&" in rhs:
                parts = TsParser.split_top_level(rhs, "&")
                union_parts = [
                    p for p in parts
                    if p in self.discriminated_unions or p in self.structural_unions
                ]
                flattened = None
                if union_parts:
                    if len(union_parts) > 1 or union_parts[0] not in self.discriminated_unions:
                        print(
                            f"ts2java: skipping {name}: intersection with union {union_parts[0]} "
                            f"can't be flattened into a record; map it in typeMappings or hand-write it"
                        )
                        continue
                    flattened = union_parts[0]
                all_fields = {}
                has_idx = False
                for p in parts:
                    if p == flattened:
                        continue
                    if p in self.all_interfaces:
                        fields, idx = self.collect_all_fields(self.all_interfaces[p])
                    elif p.startswith("{"):
                        fields = TsParser.parse_fields(p[1:])
                        # Same test as resolve_inline_object: a trailing `;` is optional in TS.
                        idx = bool(re.search(r"\[\s*\w+\s*:\s*\w+\s*\]\s*:", p))
                    else:
                        # Base-package types (typeMappings) contribute no fields here; declare the
                        # inherited ones in the config's additionalProperties.
                        continue
                    has_idx = has_idx or idx
                    for f in fields:
                        if f["kind"] == "field":
                            # Later parts narrow earlier ones (e.g. a literal discriminator).
                            all_fields[f["name"]] = f
                if all_fields or self.additional_props.get(name) or flattened:
                    merged = list(all_fields.values())
                    comps, _ = self.get_components(merged, name, has_idx)
                    if flattened:
                        field = flattened[0].lower() + flattened[1:]
                        doc = f"the {flattened} variant whose properties are inlined into this object"
                        comps.insert(0, (flattened, field, False, doc, None))
                        self.flattened_unions[name] = (flattened, field)
                    ifaces = []
                    if name in self.request_names:
                        ifaces.append("McpRequest")
                    if name in self.notif_names:
                        ifaces.append("McpNotification")
                    if name in self.result_names:
                        ifaces.append("McpResponse")
                    self.add_model(name, comps, ifaces if ifaces else None, jsdoc=exp.get("jsdoc"))

        # 5. Anonymous types (handled as inner classes by append_inner_classes during add_model)
        # Safety net: handle any orphaned anon types (without owning_type)
        while True:
            pending = [
                (k, v)
                for k, v in self.anon_registry.items()
                if not isinstance(k, tuple) and k not in self.model_files
            ]
            if not pending:
                break
            for anon_name, val in pending:
                if isinstance(val, tuple):
                    body, owning_type = val
                    if owning_type is None:
                        fields = TsParser.parse_fields("{" + body + "}")
                        comps, _ = self.get_components(fields, anon_name)
                        self.add_model(anon_name, comps)
                else:
                    fields = TsParser.parse_fields("{" + val + "}")
                    comps, _ = self.get_components(fields, anon_name)
                    self.add_model(anon_name, comps)

        # 6-7. Message hierarchy lives in the base package (sealed there), not in extensions
        if not self.base_package:
            self.add_message_hierarchy()

        # 8. Codec interface + codecs for all models
        if not self.skip_codecs:
            if not self.base_package:
                self.add_codec_support()
                self.add_codec_interface()
            self.add_model_codecs()

        # 9. Protocol registries
        if self.base_package:
            if not self.skip_codecs:
                self.add_codec_registry()
                self.import_base_codecs()
        elif not self.skip_protocol:
            self.add_protocol_version()
            self.add_method_registry()
            self.add_descriptors()
            self.add_codec_registry()

        self.write_outputs()

    def add_message_hierarchy(self):
        # 6. UnknownRequest
        self.add_model(
            "UnknownRequest",
            [
                (
                    self.field_type_mappings.get("UnknownRequest.id", self.unknown_java_type),
                    "id",
                    True,
                    "",
                    "id",
                ),
                ("String", "method", False, "", "method"),
                (
                    self.field_type_mappings.get("UnknownRequest.params", self.unknown_java_type),
                    "params",
                    True,
                    "",
                    "params",
                ),
            ],
            ["McpRequest"],
        )

        # 7. Message hierarchy interfaces
        hierarchy = [
            ("McpMessage", None, ["McpRequest", "McpNotification", "McpResponse"]),
            ("McpRequest", "McpMessage", sorted(self.request_names) + ["UnknownRequest"]),
            ("McpNotification", "McpMessage", sorted(self.notif_names)),
            ("McpResponse", "McpMessage", sorted(self.result_names)),
        ]
        for cls, super_iface, permits in hierarchy:
            self.add_interface(cls, permits if permits else None, super_iface)

    def add_model_codecs(self):
        for model_name in list(self.model_files.keys()):
            if model_name in self.type_mappings:
                continue
            if model_name in self.structural_unions:
                continue
            if model_name in self.discriminated_unions:
                disc, variants = self.discriminated_unions[model_name]
                self.add_discriminated_codec(model_name, disc, variants)
                continue
            if model_name in self.interface_extends:
                # Polymorphic extends parents referenced as field types get a deduction codec
                # (e.g. ResourceContents). The rest are plain records that codecs still
                # reference by their declared type (NotificationParams, RequestParams, Result,
                # Error), so they fall through to a plain codec below — without one,
                # CodecRegistry.codecFor returns null and the caller NPEs.
                if model_name == "ResourceContents":
                    children = self.interface_extends[model_name]
                    variant_field_map = OrderedDict()
                    parent_exp = self.all_interfaces.get(model_name)
                    parent_field_set = set()
                    if parent_exp:
                        for f in parent_exp.get("fields", []):
                            if f["kind"] == "field":
                                parent_field_set.add(f["name"])
                    for child_name in children:
                        child_exp = self.all_interfaces.get(child_name)
                        if child_exp:
                            for f in child_exp.get("fields", []):
                                if f["kind"] == "field" and f["name"] not in parent_field_set:
                                    variant_field_map[child_name] = f["name"]
                                    break
                    if variant_field_map:
                        self.add_deduction_codec(model_name, variant_field_map)
                    continue
            if model_name in (
                "McpMessage",
                "McpRequest",
                "McpNotification",
                "McpResponse",
            ):
                continue
            if model_name in self.codec_files:
                continue
            self.add_codec(model_name)

    def import_base_codecs(self):
        """Adds imports of the base package's Codec/CodecSupport to every generated codec."""
        base_codecs = f"{self.base_package}.codecs"
        for name, content in self.codec_files.items():
            needed = set()
            if re.search(r"\bCodec<", content):
                needed.add(f"import {base_codecs}.Codec;")
            if "CodecSupport." in content:
                needed.add(f"import {base_codecs}.CodecSupport;")
            lines = content.split("\n")
            import_idx = [i for i, line in enumerate(lines) if line.startswith("import ")]
            first, last = import_idx[0], import_idx[-1]
            imports = sorted(set(lines[first : last + 1]) | needed)
            self.codec_files[name] = "\n".join(lines[:first] + imports + lines[last + 1 :])

    def write_outputs(self):
        # 10. Write files
        for name, content in self.model_files.items():
            if not content:
                continue
            path = os.path.join(self.dir_models, f"{name}.java")
            self.write_file(path, content)
            if VERBOSE:
                print(f"  MODEL  {name}.java")
        for name, content in self.codec_files.items():
            path = os.path.join(self.dir_codecs, f"{name}.java")
            self.write_file(path, content)
            if VERBOSE:
                print(f"  CODEC  {name}.java")
        for name, content in self.protocol_files.items():
            path = os.path.join(self.dir_protocol, f"{name}.java")
            self.write_file(path, content)
            if VERBOSE:
                print(f"  PROTO  {name}.java")
        print(
            f"\nGenerated {len(self.model_files)} models, {len(self.codec_files)} codecs, {len(self.protocol_files)} protocol files"
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Generate Java models + codecs from TS spec"
    )
    parser.add_argument("--ts", default=DEFAULT_TS, help="Path to TypeScript spec file")
    parser.add_argument(
        "--base-dir",
        default=DEFAULT_BASE,
        help=f"Base output directory (default: {DEFAULT_BASE})",
    )
    parser.add_argument(
        "--pkg-default",
        default=DEFAULT_PKG,
        help="Default package (appended with .models/.codecs/.protocol)",
    )
    parser.add_argument(
        "--pkg-models",
        default=None,
        help="Package for generated models (overrides --pkg-default)",
    )
    parser.add_argument(
        "--pkg-codecs",
        default=None,
        help="Package for generated codecs (empty to skip)",
    )
    parser.add_argument(
        "--pkg-protocol",
        default=None,
        help="Package for generated protocol files (empty to skip)",
    )
    parser.add_argument(
        "--unknown-type",
        default="JsonNode",
        choices=["JsonNode", "TokenBuffer", "byte[]"],
        help="Java type for TS 'unknown' / 'object' (default: JsonNode)",
    )
    parser.add_argument(
        "--config",
        default=None,
        help="Path to JSON config file with typeMappings, additionalProperties, ignoreProperties "
        "and basePackage (extension mode: reuse that package's models and codecs)",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Ignore the incremental-generation cache and regenerate unconditionally",
    )
    args = parser.parse_args()

    pkg = args.pkg_default
    pkg_models = args.pkg_models or f"{pkg}.models"
    pkg_codecs = args.pkg_codecs if args.pkg_codecs is not None else f"{pkg}.codecs"
    pkg_protocol = (
        args.pkg_protocol if args.pkg_protocol is not None else f"{pkg}.protocol"
    )

    cache_path = _cache_path_for(
        args.ts, args.base_dir, pkg_models, pkg_codecs, pkg_protocol
    )
    fingerprint = _build_fingerprint(args.ts, args.config, args.unknown_type)

    if not args.force and _is_cache_fresh(_load_cache(cache_path), fingerprint):
        print(f"ts2java: up to date, skipping generation for {args.ts}")
    else:
        config = {}
        if args.config:
            with open(args.config) as f:
                config = json.load(f)

        type_mappings = config.get("typeMappings")
        additional_properties = config.get("additionalProperties", {})
        ignore_properties = config.get("ignoreProperties", {})
        base_package = config.get("basePackage")

        gen = Generator(
            ts_path=args.ts,
            base_dir=args.base_dir,
            pkg_models=pkg_models,
            pkg_codecs=pkg_codecs,
            pkg_protocol=pkg_protocol,
            unknown_type=args.unknown_type,
            type_mappings=type_mappings,
            additional_properties=additional_properties,
            ignore_properties=ignore_properties,
            base_package=base_package,
        )
        gen.generate()

        output_dirs = [d for d in (gen.dir_models, gen.dir_codecs, gen.dir_protocol) if d]
        _save_cache(cache_path, fingerprint, output_dirs)
