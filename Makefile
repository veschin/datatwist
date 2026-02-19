# DataTwist Makefile

.PHONY: test lint clean demo changelog help

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

# Clean cache
clean:
	@echo "=== Cleaning cache ==="
	rm -rf .cpcache/
	rm -rf .lsp/.cache/

changelog:  ## Generate changelog entry: make changelog TITLE="Feature Name" COMMITS="abc..def"
	@./scripts/changelog-entry.sh "$(TITLE)" $(COMMITS)

# Show help
help:
	@echo "DataTwist Development Commands:"
	@echo ""
	@echo "  make test  - Run all tests"
	@echo "  make lint  - Run linter"
	@echo "  make clean - Clean cache"
	@echo "  make demo  - Run language showcase demo"
	@echo "  make help  - Show this help"
