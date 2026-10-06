.PHONY: all ci ci-lite build test lint package install-server conformance apidocs e2e clean format help mcp-inspector examples examples-snapshot jmh deploy claude-sbx docs-check docs-sync

.DEFAULT_GOAL := help

ifeq ($(CI),true)
SUREFIRE_FORK_COUNT ?= 1
NETTY_ARGS := -Dio.netty.eventLoopThreads=2
else
SUREFIRE_FORK_COUNT ?= 1C
NETTY_ARGS :=
endif

MAVEN_TEST_ARGS := -Dsurefire.forkCount=$(SUREFIRE_FORK_COUNT) $(NETTY_ARGS)
MAVEN_ARGS := --no-transfer-progress # --offline

# Publishing to Maven Central is opt-in: without PUBLISH=true `make deploy` builds, tests
# and signs, but stages nothing. Keeps a stray local `make deploy` harmless.
ifeq ($(PUBLISH),true)
PUBLISH_ARGS :=
else
PUBLISH_ARGS := -DskipPublishing=true
endif

# Plugins that only produce reports or publishable artifacts: pure overhead when the
# build is neither the gated one nor a release.
SKIP_REPORT_ARGS := -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Djacoco.skip=true -Dspotbugs.skip=true -Dspotless.skip=true

help: ## List available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-22s %s\n", $$1, $$2}'

all: clean format lint docs-check revapi examples-snapshot examples ## Full build: clean, format, lint, doc-snippet check, live examples, build+install, SNAPSHOT examples

ci: ## CI pipeline: one reactor for clean + lint + build + revapi
	@echo " 🏗️ 🔍  Building with lint + API compatibility..."
	@./mvnw -version
	@python3 .github/scripts/check-poms.py
	@./mvnw clean verify -Plint -Drevapi.skip=false $(MAVEN_TEST_ARGS) $(MAVEN_ARGS)

ci-lite: ## Same build without the report/analysis plugins (non-primary JDKs)
	@echo " 🏗️  Building (lite)..."
	@./mvnw -version
	@./mvnw clean verify $(MAVEN_TEST_ARGS) $(SKIP_REPORT_ARGS) $(MAVEN_ARGS)

build: ## Compile, test, verify (mvn verify)
	@echo " 🏗️ Building..."
	@./mvnw -version
	@./mvnw verify $(MAVEN_TEST_ARGS) $(MAVEN_ARGS)

test: ## Run unit + e2e tests
	@echo " 🧪 Running tests..."
	@./mvnw test $(MAVEN_TEST_ARGS) $(MAVEN_ARGS)

revapi: ## Check API compatibility against baseline (oldVersion) + write report
	@echo " 🔄  Checking API compatibility..."
	@./mvnw verify -Drevapi.skip=false -pl tachyon-api,tachyon-core,extensions/tachyon-extensions,extensions/tachyon-extensions-skills,tachyon-testkit -DskipTests $(SKIP_REPORT_ARGS) $(MAVEN_ARGS)
	@echo " ✅  Done!"

jmh: ## Run JMH benchmarks (perf regression check)
	@echo " 🏎️   Running JMH benchmarks..."
	@./mvnw -q -pl tachyon-core -am verify -Pjmh -DskipTests $(SKIP_REPORT_ARGS) $(MAVEN_ARGS)
	@echo " ✅  Done!"

install-server: ## Build with tests and install to local Maven repo
	@echo "🔄  Building and installing with tests..."
	@./mvnw install $(MAVEN_TEST_ARGS) $(MAVEN_ARGS)

package: ## Install artifacts to local Maven repo (skip tests)
	@echo "📦 Packaging and installing to local repository..."
	@rm -rf ~/.m2/repository/dev/tachyonmcp/*/*-SNAPSHOT
	@./mvnw install -DskipTests $(SKIP_REPORT_ARGS) $(MAVEN_ARGS)

deploy: ## Build, test and sign release artifacts; publishes to Maven Central only with PUBLISH=true
	@echo " 🚀  Deploying (publish to Central: $(if $(PUBLISH_ARGS),NO — dry run,YES))..."
	@./mvnw -P release,lint clean deploy -Drevapi.skip=false \
		$(MAVEN_TEST_ARGS) $(PUBLISH_ARGS) $(MAVEN_DEPLOY_ARGS) $(MAVEN_ARGS)
	@echo " ✅  Done!"

apidocs:
	@echo "📚  Building API Docs..."
	@rm -rf target/reports/apidocs
	@./mvnw compile javadoc:aggregate \
		-pl tachyon-api,tachyon-core,extensions/tachyon-extensions,extensions/tachyon-extensions-skills,tachyon-testkit -am \
		$(MAVEN_ARGS)
	@echo " ✅  Done!"

examples: ## Build live examples against published artifacts
	@echo "🌤️ 📡  Building LIVE examples..."
	@./mvnw verify -f examples/pom.xml $(MAVEN_ARGS)
	@echo " ✅  Done!"

examples-snapshot: install-server ## Build examples against local SNAPSHOT artifacts
	@echo "🌤️ 🎬 Building SNAPSHOT examples..."
	@./mvnw verify -P snapshot-examples -f examples/pom.xml -Dtachyon.version=1.0.1-SNAPSHOT $(MAVEN_ARGS)
	@echo " ✅  Done!"

conformance: ## Run MCP conformance suite
	@echo " 🔄  Running MCP conformance suite..."
	@rm -rf conformance/target/surefire-reports
	@./mvnw test -am -pl conformance $(MAVEN_TEST_ARGS) $(MAVEN_ARGS)

e2e: package ## Run end-to-end tests
	@echo " 🔗  Running end-to-end tests..."
	@./mvnw test -pl e2e -am $(MAVEN_TEST_ARGS) $(MAVEN_ARGS)

clean: ## Remove all build artifacts
	@echo " 🧹  Cleaning..."
	@rm -rf ~/.m2/repository/dev/tachyonmcp/*/*-SNAPSHOT
	@find . -type d -name target -exec rm -rf {} +
	@echo " ✅  All clean!"

format: ## Auto-format code (Spotless + Detekt)
	@echo " 🎨  Formatting code..."
	@./mvnw compile spotless:apply -Pformat -q $(MAVEN_ARGS)
	@./mvnw spotless:apply -q $(MAVEN_ARGS) -f examples/weather-mcp-spring-boot
	@./mvnw install -pl .,tachyon-api,tachyon-core,tachyon-kotlin -am -DskipTests -Dspotbugs.skip -Dspotless.skip -q $(MAVEN_ARGS)
	@./mvnw exec:java@detekt-format -pl tachyon-kotlin,tachyon-kotlin-kt-schema -Pformat -q $(MAVEN_ARGS)
	@echo " ✅  Done..."

lint: ## Check code style (Spotless + Detekt); SpotBugs runs automatically during build
	@echo " 🔍  Linting code..."
	@python3 .github/scripts/check-poms.py
	@./mvnw spotless:check -pl !reports -Plint $(MAVEN_ARGS)
	@./mvnw process-test-classes -pl tachyon-kotlin-kt-schema -am -Plint $(MAVEN_ARGS)
	@echo " ✅  Done..."

docs-check: ## Check Markdown doc snippets are in sync with source (snips --check); CI-style, non-zero on drift
	@echo " 📝  Checking doc snippets..."
	@snips --check $$(find docs -name '*.md')
	@echo " ✅  Done!"

docs-sync: ## Rewrite Markdown doc snippets from source (snips), then restore java/kotlin/json fence tags
	@echo " 📝  Syncing doc snippets..."
	@snips $$(find docs -name '*.md')
	@python3 .github/scripts/fix-doc-fences.py $$(find docs -name '*.md')
	@echo " ✅  Done!"

mcp-inspector: ## Launch MCP Inspector UI
	@echo "🧐 MCP Inspector"
	@npx -y @modelcontextprotocol/inspector --config mcp-inspector.json

claude-sbx: ## Run an agent in a Docker sandbox from sbxenv.yaml (maven + snips kits); AGENT=codex to switch agent, M2=path for local Maven home
	@echo " 📦  Starting sandbox..."
	@sbx env run --env-arg agent=$(or $(AGENT),claude) --env-arg m2=$(or $(M2),$(HOME)/.m2) ./sbxenv.yaml
