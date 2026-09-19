.PHONY: all ci build test lint package install-server conformance apidocs e2e clean format help mcp-inspector examples examples-snapshot jmh

.DEFAULT_GOAL := help

ifeq ($(CI),true)
SUREFIRE_FORK_COUNT ?= 1
NETTY_ARGS := -Dio.netty.eventLoopThreads=2
else
SUREFIRE_FORK_COUNT ?= 1C
NETTY_ARGS :=
endif

MAVEN_TEST_ARGS := -Dsurefire.forkCount=$(SUREFIRE_FORK_COUNT) $(NETTY_ARGS)

help: ## List available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-22s %s\n", $$1, $$2}'

all: clean format lint install-server revapi examples-snapshot examples ## Full build: clean, format, lint, live examples, build+install, SNAPSHOT examples

ci: clean lint build revapi jmh ## CI pipeline: clean + lint + build + jmh regression check

build: ## Compile, test, verify (mvn verify)
	@echo " 🏗️ Building..."
	@./mvnw -version
	@./mvnw verify $(MAVEN_TEST_ARGS) --no-transfer-progress

test: ## Run unit + e2e tests
	@echo " 🧪 Running tests..."
	@./mvnw test $(MAVEN_TEST_ARGS) --no-transfer-progress

revapi: ## Check API compatibility against baseline (oldVersion) + write report
	@echo " 🔄  Checking API compatibility..."
	@./mvnw verify -Drevapi.skip=false -pl tachyon-api,tachyon-core,extensions/tachyon-extensions,extensions/tachyon-extensions-skills,tachyon-testkit -DskipTests -Dspotbugs.skip -Dmaven.javadoc.skip=true -Djacoco.skip=true --no-transfer-progress
	@echo " ✅  Done!"

jmh: ## Run JMH benchmarks (perf regression check)
	@echo " 🏎️   Running JMH benchmarks..."
	@./mvnw -q -pl tachyon-core -am verify -Pjmh -DskipTests --no-transfer-progress
	@echo " ✅  Done!"

install-server: ## Build with tests and install to local Maven repo
	@echo "🔄  Building and installing with tests..."
	@./mvnw install $(MAVEN_TEST_ARGS) --no-transfer-progress

package: ## Install artifacts to local Maven repo (skip tests)
	@echo "📦 Packaging and installing to local repository..."
	@rm -rf ~/.m2/repository/dev/tachyonmcp/
	@./mvnw install -DskipTests -Dspotbugs.skip -Dspotless.skip

apidocs:
	@echo "📚  Building API Docs..."
	@rm -rf target/reports/apidocs
	@./mvnw compile javadoc:aggregate \
		-pl tachyon-api,tachyon-core,extensions/tachyon-extensions,extensions/tachyon-extensions-skills,tachyon-testkit -am \
		--no-transfer-progress
	@echo " ✅  Done!"

examples: ## Build live examples against published artifacts
	@echo "🌤️ 📡  Building LIVE examples..."
	@./mvnw verify -f examples/pom.xml --no-transfer-progress
	@echo " ✅  Done!"

examples-snapshot: install-server ## Build examples against local SNAPSHOT artifacts
	@echo "🌤️ 🎬 Building SNAPSHOT examples..."
	@./mvnw verify -P snapshot-examples -f examples/pom.xml -Dtachyon.version=1.0.0-SNAPSHOT --no-transfer-progress
	@echo " ✅  Done!"

conformance: ## Run MCP conformance suite
	@echo " 🔄  Running MCP conformance suite..."
	@rm -rf conformance/target/surefire-reports
	@./mvnw test -am -pl conformance $(MAVEN_TEST_ARGS)

e2e: package ## Run end-to-end tests
	@echo " 🔗  Running end-to-end tests..."
	@./mvnw test -pl e2e -am $(MAVEN_TEST_ARGS)

clean: ## Remove all build artifacts
	@echo " 🧹  Cleaning..."
	@rm -rf ~/.m2/repository/dev/tachyonmcp
	@find . -type d -name target -exec rm -rf {} +
	@echo " ✅  All clean!"

format: ## Auto-format code (Spotless + Detekt)
	@echo " 🎨  Formatting code..."
	@./mvnw spotless:apply -Pformat -q
	@./mvnw install -pl tachyon-api,tachyon-core,tachyon-kotlin -DskipTests -Dspotbugs.skip -Dspotless.skip -q
	@./mvnw exec:java@detekt-format -pl tachyon-kotlin,tachyon-kotlin-kt-schema -Pformat -q
	@echo " ✅  Done..."

lint: ## Check code style (Spotless + Detekt); SpotBugs runs automatically during build
	@echo " 🔍  Linting code..."
	@python3 .github/scripts/check-poms.py
	@./mvnw spotless:check -pl !reports -Plint
	@./mvnw process-test-classes -pl tachyon-kotlin-kt-schema -am -Plint
	@echo " ✅  Done..."

mcp-inspector: ## Launch MCP Inspector UI
	@echo "🧐 MCP Inspector"
	@npx -y @modelcontextprotocol/inspector --config mcp-inspector.json
