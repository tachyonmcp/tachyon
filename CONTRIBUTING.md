# Contributing to Tachyon MCP

## Setup

- JDK 21+
- Maven 3.9+

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

`lint`/`format` are Maven profiles (`-Plint`/`-Pformat`) kept out of the default
build so `make build`/`make test` stay fast; `make ci`/`make all` wire them back
in explicitly. Prefer the IDE MCP for building/running tests when available.

Coding conventions (TDD, SOLID, nullability, module layout, Kotlin DSL
patterns) live in [AGENTS.md](AGENTS.md), [tachyon-development](.agents/skills/tachyon-development), [guidance.md](docs/architecture/guidance.md) -- read it before opening a PR.

Security issues: see [SECURITY.md](SECURITY.md), not a public issue.
