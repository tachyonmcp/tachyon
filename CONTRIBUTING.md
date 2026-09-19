# Contributing to Tachyon MCP

## Setup

- JDK 21+
- Maven 3.9+ (the `./mvnw` wrapper pins the version; `maven-enforcer-plugin` checks it)
- Python 3 on `PATH`: `tachyon-core` generates the MCP protocol classes from `protocol/*.ts` with
  `ts2java.py` at `generate-sources`. Skip it with `-Dts2java.skip=true` when `target/generated-sources` is up to date.

## Build & test

Use the `Makefile` targets (run `make help` for the full list):

```bash
make build   # compile, test, verify (mvn verify)
make test    # unit + e2e tests
make lint    # check style: Spotless + Detekt (SpotBugs runs automatically during build)
make format  # auto-fix style: Spotless + Detekt
make jmh     # JMH benchmarks + throughput regression gate (BenchmarkGate)
make ci      # what CI runs: clean + lint + build + revapi + jmh
make all     # everything: clean + format + lint + full install + examples
```

`make deploy` runs the release build (`-P release,lint`, revapi, GPG signing, aggregate SBOM).
It only uploads to Maven Central with `PUBLISH=true`; otherwise it passes `-DskipPublishing=true`,
which is also how `release.yml`'s `dry_run` input rehearses a release. CI (`CI=true`) caps
`-Dsurefire.forkCount=1` and `-Dio.netty.eventLoopThreads=2` for every target -- runners run out of
threads otherwise.

`e2e`, `conformance` and `reports` are built by the default-active `tests` profile; pass `-DskipTestModules` to leave them out.

`lint`/`format` are Maven profiles (`-Plint`/`-Pformat`) kept out of the default
build so `make build`/`make test` stay fast; `make ci`/`make all` wire them back
in explicitly. Prefer the IDE MCP for building/running tests when available.

Coding conventions (TDD, SOLID, nullability, module layout, Kotlin DSL
patterns) live in [AGENTS.md](AGENTS.md), [tachyon-development](.agents/skills/tachyon-development), [guidance.md](docs/architecture/guidance.md) -- read it before opening a PR.

Security issues: see [SECURITY.md](SECURITY.md), not a public issue.
