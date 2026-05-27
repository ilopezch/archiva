# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Full build
mvn clean install

# Skip tests (faster build)
mvn clean install -DskipTests

# Run all tests
mvn clean test

# Run a single test class
mvn clean test -Dtest=ClassName

# Run a single test method
mvn clean test -Dtest=ClassName#methodName

# Run tests in a specific module
mvn clean test -pl archiva-modules/archiva-base/archiva-repository-api

# Checkstyle validation
mvn checkstyle:check

# Run the webapp locally (port 9091)
cd archiva-jetty && sh jetty.sh
# With debug port 8000:
cd archiva-jetty && sh jetty-debug.sh
```

Set this before building to avoid OOM errors:
```bash
export MAVEN_OPTS="-Xmx768m -Xms768m"
```

### Useful Maven Profiles

- `-Pcassandra` — use Cassandra as metadata storage backend
- `-Pjacoco-coverage` — enable JaCoCo code coverage
- `-Prelease` — include source distribution assembly

## Architecture

Apache Archiva is a Maven repository manager. The codebase is organized as a multi-module Maven project:

```
archiva-modules/
  archiva-base/         # Core foundation
    archiva-event-api/  # Event system contracts
    archiva-configuration/  # Config model + management
    archiva-repository-api/ # Repository abstraction layer
    archiva-consumers/  # Artifact consumer framework
    archiva-proxy/      # Remote repository proxying
    archiva-storage-api/ # Storage abstraction
  archiva-maven/        # Maven-specific implementations
    archiva-maven-indexer/     # Maven indexer integration
    archiva-maven-metadata/    # Maven POM/metadata parsing
    archiva-maven-repository/  # Maven repo implementation
    archiva-maven-proxy/       # Maven proxy logic
  archiva-web/          # Web tier
    archiva-rest-services/     # JAX-RS REST endpoints (CXF)
    archiva-webdav/            # WebDAV access to repos
    archiva-webapp/            # WAR artifact
  metadata/             # Metadata subsystem
    metadata-model/     # Metadata domain model
    metadata-repository-api/   # Storage-agnostic metadata API
    metadata-store-file/       # File-based metadata storage
    metadata-store-cassandra/  # Cassandra storage backend
archiva-cli/            # CLI entrypoint
archiva-jetty/          # Standalone Jetty distribution
```

### Key Architectural Patterns

**Storage abstraction**: `RepositoryStorage` and `MetadataRepository` interfaces decouple business logic from storage backends. File-based storage is the default; Cassandra is an optional alternative.

**Consumer framework**: Artifact processors implement `RepositoryContentConsumer`. The `FileLockManager` and consumer chain handle scanning and indexing.

**Event system**: `EventManager` / `Event` classes in `archiva-event-api` provide a typed pub/sub model used across modules to decouple components.

**REST API layer**: CXF JAX-RS services in `archiva-rest-services` expose repository management, search, and administration endpoints. Jackson handles JSON/XML serialization. Swagger annotations document the API.

**Spring DI**: All wiring is done via Spring. Configuration is in `applicationContext.xml` files per module; tests use `spring-test` with `@ContextConfiguration`.

## Web UI — Two Separate Frontends

This project ships **two completely separate Web UIs**. Always clarify which one is in scope before doing any frontend work.

### Old UI (PRIMARY — what is actually shipped)
- **Technology**: Knockout.js + jQuery + RequireJS + jQuery.tmpl
- **Location**: `archiva-modules/archiva-web/archiva-webapp/src/main/webapp/`
- **URL**: `http://localhost:8080/archiva/#hashroutes`
- **JS modules**: `src/main/webapp/js/archiva/admin/repository/{npm,rpm,maven2}/`
- **HTML templates**: `src/main/webapp/js/templates/archiva/repositories.html` (jQuery.tmpl `${...}` syntax)
- **i18n**: `archiva-modules/archiva-web/archiva-web-common/src/main/resources/org/apache/archiva/i18n/default.properties`
- **Plugin loading**: `DefaultPluginsServices` auto-discovers `main.js` files under `archiva/admin/repository/*/` and `archiva/admin/features/*/`
- **Routing**: Sammy.js hash routing; `#:folder` generic route calls `func()` on the matching menu item

### New UI (IN PROGRESS — not shipped, not integrated into Maven build)
- **Technology**: Angular 11
- **Location**: `archiva-modules/archiva-web/archiva-webapp/src/main/archiva-web/`
- **Dev server**: `npm install && ng serve` → `http://localhost:4200`
- **Backend expected at**: `http://localhost:8080` (hardcoded in `environment.ts`) — no CORS proxy configured
- **Status**: Functionally incomplete for production use; not built by Maven; dist output not packaged into the WAR
- **Do NOT implement UI features here** unless explicitly asked — the old UI is what users see

### Key Technologies

- **Spring 5.3** — DI, transactions, web MVC
- **Apache CXF 3.3** — JAX-RS REST services
- **OpenJPA 3.1** + Derby — default ORM/embedded DB
- **Maven Indexer 6.2** — Lucene-based artifact search
- **Jackrabbit Oak 1.40** — JCR content repository (optional metadata backend)
- **Quartz** — background scheduling (indexing, proxy sync)
- **EHCache 3.9** — caching layer
- **Jetty 9.4** — embedded servlet container for distribution

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **archiva** (30825 symbols, 98647 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/archiva/context` | Codebase overview, check index freshness |
| `gitnexus://repo/archiva/clusters` | All functional areas |
| `gitnexus://repo/archiva/processes` | All execution flows |
| `gitnexus://repo/archiva/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
