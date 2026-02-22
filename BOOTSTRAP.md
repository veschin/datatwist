# DataTwist Cloud Environment Bootstrap

Instructions for getting DataTwist running in a cloud/sandboxed environment (Claude Code remote, CI containers, fresh VMs). Documents the problems encountered and their solutions.

## Prerequisites

| Tool | Required version | Notes |
|---|---|---|
| JDK | 21+ | OpenJDK works. Cloud images usually have it. |
| Clojure CLI (`clj`) | 1.12+ | **Not always pre-installed** — see below. |
| Maven | 3.x | Only needed as a fallback bootstrap path. |
| Git | 2.x | Standard. |

## The Dependency Problem

DataTwist has a single external dependency: `instaparse/instaparse 1.5.0` (declared in `deps.edn`). The Clojure CLI fetches it from Maven Central on first run. This sounds simple but breaks in restricted environments.

### Problem 1: `clj` not installed

Cloud containers often have Java and Maven but **not** the Clojure CLI. The Clojure CLI is a shell wrapper around `clojure.main` that manages `deps.edn` dependency resolution.

**Solution A — Install from official script (if internet is available):**

```bash
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh  # installs to /usr/local/bin/clj
```

**Solution B — Bootstrap via Maven (if clj install is blocked):**

Create a temporary `pom.xml` that mirrors `deps.edn`:

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>datatwist</groupId>
  <artifactId>datatwist</artifactId>
  <version>0.1.0</version>
  <dependencies>
    <dependency>
      <groupId>instaparse</groupId>
      <artifactId>instaparse</artifactId>
      <version>1.5.0</version>
    </dependency>
    <dependency>
      <groupId>org.clojure</groupId>
      <artifactId>clojure</artifactId>
      <version>1.12.0</version>
    </dependency>
  </dependencies>
</project>
```

Then resolve deps and build a classpath manually:

```bash
mvn dependency:copy-dependencies -DoutputDirectory=lib/
java -cp "src:test:resources:lib/*" clojure.main -m datatwist.test-runner
```

This is a last-resort fallback. Prefer installing `clj` properly.

### Problem 2: Proxy / firewall blocking downloads

Cloud sandbox environments often route traffic through an HTTP proxy. Java needs explicit proxy configuration or dependency downloads will hang/fail silently.

**Symptoms:**
- `clj` hangs on first run (downloading deps)
- Maven fails with connection timeout to `repo1.maven.org`
- `curl` works but Java tools don't

**Solution — Set JVM proxy properties:**

The cloud environment may inject `JAVA_TOOL_OPTIONS` with proxy settings automatically. Check:

```bash
echo $JAVA_TOOL_OPTIONS
```

If it contains `-Dhttp.proxyHost=...` and `-Dhttps.proxyHost=...`, Java tools will use the proxy. If not, set them manually:

```bash
export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=<proxy-host> -Dhttp.proxyPort=<port> -Dhttps.proxyHost=<proxy-host> -Dhttps.proxyPort=<port>"
```

For Maven specifically, you can also configure `~/.m2/settings.xml`:

```xml
<settings>
  <proxies>
    <proxy>
      <active>true</active>
      <protocol>https</protocol>
      <host>proxy-host</host>
      <port>port</port>
    </proxy>
  </proxies>
</settings>
```

**Allowed domains** (must be whitelisted if the firewall is domain-based):
- `repo1.maven.org` — Maven Central (Clojure + Instaparse JARs)
- `github.com` — git clone, Clojure CLI install script
- `api.github.com` — gh CLI operations

### Problem 3: Cached `.cpcache` from a different environment

If you clone a repo that has a `.cpcache/` directory from a different machine, the cached classpath may point to non-existent local paths (e.g. `/home/otheruser/.m2/repository/...`).

**Solution:**

```bash
make clean   # removes .cpcache/ and .lsp/.cache/
clj -P       # re-resolve and download dependencies (prep only, no execution)
```

### Problem 4: Agent/subagent environments

When using Claude Code with multi-agent orchestration (see `CLAUDE.md`), each subagent runs in the same filesystem but may have a different working directory or shell state. Key issues:

- **Working directory**: Always use absolute paths or ensure `cd /home/user/datatwist` before running commands.
- **Environment variables**: `JAVA_TOOL_OPTIONS` must be set in the shell profile, not just the parent process, since subagents spawn fresh shells.
- **Parallel agents writing to the same files**: Use feature branches (`feat/<name>`) to avoid conflicts. The orchestrator merges after review.

## Quick Start (clean environment)

```bash
# 1. Verify prerequisites
java -version          # should be 21+
clj --version          # should be 1.12+

# 2. Clone and enter project
git clone <repo-url> && cd datatwist

# 3. Clean any stale caches
make clean

# 4. Download dependencies (prep only)
clj -P

# 5. Run all tests (760 tests, ~1563 assertions)
make test

# 6. Run demo showcase
make demo

# 7. Run a single test namespace
clj -M -e "(require 'clojure.test 'datatwist.literals-test) (clojure.test/run-tests 'datatwist.literals-test)"
```

## Verify Everything Works

After bootstrap, this should pass cleanly:

```bash
$ make test
=== Running all DataTwist tests ===
...
Ran 760 tests containing 1563 assertions.
0 failures, 0 errors.
```

If you see failures, check:
1. Java version (`java -version` — must be 21+)
2. Dependency resolution (`clj -P` — should complete without errors)
3. Proxy settings (`echo $JAVA_TOOL_OPTIONS`)
4. Stale cache (`make clean && clj -P`)

## Session Recovery

If a Claude Code session runs out of context or gets interrupted:

1. Read `docs/plans/autonomous-session-recovery.md` for task state
2. Read `BACKLOG.md` for current priorities
3. Check `git log --oneline -20` for what was already done
4. Run `make test` to verify the codebase is healthy
5. Continue with the next actionable backlog item
