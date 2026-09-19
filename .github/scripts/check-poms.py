#!/usr/bin/env python3
# Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
"""Consistency checks that Maven itself cannot express.

* tachyon-bom has no parent and lists exactly the dev.tachyonmcp artifacts managed by the root pom;
* the BOM's release profile pins the same plugin versions as the root release profile;
* every published module (jar, not install-skipped) declares its own unique automatic.module.name.
"""

import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ROOT = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
GROUP = "dev.tachyonmcp"
DEFAULT_MODULE_NAME = "dev.tachyonmcp.internal"


def load(path):
    return ET.parse(ROOT / path).getroot()


def text(node, path):
    found = node.find(path, NS)
    return found.text.strip() if found is not None and found.text else None


def managed_tachyon_artifacts(pom):
    return {
        text(d, "m:artifactId")
        for d in pom.findall("m:dependencyManagement/m:dependencies/m:dependency", NS)
        if text(d, "m:groupId") == GROUP and text(d, "m:scope") != "import"
    }


def release_plugin_versions(pom):
    versions = {}
    for profile in pom.findall("m:profiles/m:profile", NS):
        if text(profile, "m:id") != "release":
            continue
        for plugin in profile.findall("m:build/m:plugins/m:plugin", NS):
            artifact = text(plugin, "m:artifactId")
            if artifact in ("maven-gpg-plugin", "central-publishing-maven-plugin"):
                versions[artifact] = text(plugin, "m:version")
    return versions


def main():
    errors = []
    root, bom = load("pom.xml"), load("tachyon-bom/pom.xml")

    if bom.find("m:parent", NS) is not None:
        errors.append("tachyon-bom/pom.xml must not have a <parent> (it would leak the build's dependencyManagement)")

    in_root, in_bom = managed_tachyon_artifacts(root), managed_tachyon_artifacts(bom)
    for artifact in sorted(in_root - in_bom):
        errors.append(f"{artifact} is managed by pom.xml but missing from tachyon-bom")
    for artifact in sorted(in_bom - in_root):
        errors.append(f"{artifact} is in tachyon-bom but not managed by pom.xml")

    root_spotless = text(root, "m:properties/m:spotless-maven-plugin.version")
    bom_spotless = next(
        (
            text(p, "m:version")
            for p in bom.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
            if text(p, "m:artifactId") == "spotless-maven-plugin"
        ),
        None,
    )
    if root_spotless != bom_spotless:
        errors.append(f"spotless version differs: pom.xml={root_spotless} tachyon-bom={bom_spotless}")

    if release_plugin_versions(root) != release_plugin_versions(bom):
        errors.append(
            "release profile plugin versions differ: "
            f"pom.xml={release_plugin_versions(root)} tachyon-bom={release_plugin_versions(bom)}"
        )

    seen = {}
    tracked = subprocess.check_output(["git", "ls-files", "*pom.xml"], cwd=ROOT, text=True).split()
    for path in tracked:
        if path.startswith("examples/") or path in ("pom.xml", "tachyon-bom/pom.xml"):
            continue
        pom = load(path)
        if (text(pom, "m:packaging") or "jar") != "jar":
            continue
        properties = pom.find("m:properties", NS)
        skipped = properties is not None and text(properties, "m:maven.install.skip") == "true"
        if skipped:
            continue
        name = properties is not None and text(properties, "m:automatic.module.name")
        if not name or name == DEFAULT_MODULE_NAME:
            errors.append(f"{path}: published module must set its own automatic.module.name")
        elif name in seen:
            errors.append(f"{path}: automatic.module.name {name} already used by {seen[name]}")
        else:
            seen[name] = path

    if errors:
        print("\n".join(f"check-poms: {e}" for e in errors), file=sys.stderr)
        return 1
    print(f"check-poms: ok ({len(in_bom)} BOM entries, {len(seen)} published modules)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
