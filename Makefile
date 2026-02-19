# DataTwist Makefile

.PHONY: test lint clean help

# Default target - run all tests
test:
	@echo "=== Running all DataTwist tests ==="
	clj -M -m datatwist.test-runner

# Lint code
lint:
	@echo "=== Running linter ==="
	clj-kondo --lint src/ test/ 2>/dev/null || clj-kondo --lint *.clj

# Clean cache
clean:
	@echo "=== Cleaning cache ==="
	rm -rf .cpcache/
	rm -rf .lsp/.cache/

# Show help
help:
	@echo "DataTwist Development Commands:"
	@echo ""
	@echo "  make test  - Run all tests (grammar + DTW files)"
	@echo "  make lint  - Run linter"
	@echo "  make clean - Clean cache"
	@echo "  make help  - Show this help"