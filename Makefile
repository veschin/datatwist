# DataTwist Makefile

.PHONY: test lint clean demo changelog help uberjar native

# Default target - run all tests
test:
	@echo "=== Running all DataTwist tests ==="
	clj -M -m datatwist.test-runner

# Run language showcase demo
demo:  ## Run language showcase demo
	@clj -M -m datatwist.demo-runner

# Lint code
lint:
	@echo "=== Running linter ==="
	clj-kondo --lint src/

# Build uberjar
uberjar:  ## Build standalone uberjar
	@echo "=== Building uberjar ==="
	clj -T:build uber

# Build native binary (requires GraalVM native-image)
native: uberjar  ## Build native binary via GraalVM
	@echo "=== Building native image ==="
	native-image \
		-jar target/datatwist-0.1.0-standalone.jar \
		--no-fallback \
		--report-unsupported-elements-at-runtime \
		-H:+ReportExceptionStackTraces \
		-o datatwist

# Clean build artifacts
clean:
	@echo "=== Cleaning cache and build artifacts ==="
	rm -rf .cpcache/
	rm -rf .lsp/.cache/
	rm -rf target/

changelog:  ## Generate changelog entry: make changelog TITLE="Feature Name" COMMITS="abc..def"
	@./scripts/changelog-entry.sh "$(TITLE)" $(COMMITS)

# Show help
help:
	@echo "DataTwist Development Commands:"
	@echo ""
	@echo "  make test     - Run all tests"
	@echo "  make lint     - Run linter"
	@echo "  make clean    - Clean cache and build artifacts"
	@echo "  make demo     - Run language showcase demo"
	@echo "  make uberjar  - Build standalone uberjar"
	@echo "  make native   - Build native binary (requires GraalVM)"
	@echo "  make help     - Show this help"
