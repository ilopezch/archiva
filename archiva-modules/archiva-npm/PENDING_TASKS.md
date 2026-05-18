# NPM Registry — Pending Tasks

This document tracks work left to complete for the NPM registry feature.
The current skeleton covers: repository SPI wiring, basic GET (metadata + tarball),
and PUT (publish) via `NpmRegistryServlet` at `/npm/{repoId}/...`.

---

## Repository Layer

- [x] **Implement `toItemSelector(String path)` in `NpmManagedRepositoryContent`**
  Parses all NPM path variants (scoped/unscoped tarballs, metadata files, package
  directories, scope directories) into an `ArchivaItemSelector`. The namespace field
  holds the scope (e.g. `@myorg`), projectId holds the package name, and version is
  extracted by stripping the `{name}-` prefix and `.tgz` suffix from the filename.

- [x] **Implement `newItemStream(ItemSelector, boolean)` in `NpmManagedRepositoryContent`**
  Uses `StorageUtil.newAssetStream()` to walk the filesystem tree from the most
  specific start directory derivable from the selector (namespace+projectId → package
  dir; namespace only → scope dir; otherwise → repo root). Returns only leaf assets
  (files). Applies version filtering when `selector.hasVersion()` is true.

- [x] **Implement `deleteItem()` and `deleteAllItems()` in `NpmManagedRepositoryContent`**
  `deleteItem()` removes a single file (`Files.deleteIfExists`) or entire directory
  tree (`FileUtils.deleteDirectory`), guarded by a path-escape check. `deleteAllItems()`
  delegates to `newItemStream` + `deleteItem`, reporting each result via the consumer.

- [x] **Implement `copyItem()` in `NpmManagedRepositoryContent`**
  Preserves the relative path from the source repository root and resolves it under the
  destination repository root. Files are copied with `StandardCopyOption.REPLACE_EXISTING`;
  directories use `Files.walkFileTree`. `updateMetadata` flag is accepted but deferred
  to the scan consumer.

- [x] **Add `NpmRepositoryContentLayout`**
  New interface `NpmRepositoryContentLayout extends ManagedRepositoryContentLayout` in
  the `content` package with NPM-specific methods: `getPackage`, `getTarball`,
  `getMetadataFile`, `getPackages`, `getTarballs`. `NpmManagedRepositoryContent` now
  implements this layout; `getLayout(NpmRepositoryContentLayout.class)` returns `this`.
  `getSupportedLayouts()` lists `NpmRepositoryContentLayout.class`.

- [x] **NPM-specific `RepositoryFeature`**
  `NpmRegistryFeature implements RepositoryFeature<NpmRegistryFeature>` holds
  `registryUrl` (default `https://registry.npmjs.org`) and optional `authToken`.
  Both `NpmManagedRepository` and `NpmRemoteRepository` declare and return the feature;
  capabilities updated to include `NpmRegistryFeature.class.getName()`.
  `NpmRepositoryProvider.updateRemoteInstance()` calls `syncRegistryUrl()` after
  `setLocation()` so the feature URL stays in sync with the configured registry URL.
  On managed repositories the feature defaults to the public registry and can be
  updated programmatically (config-model persistence deferred to a future task).

---

## Proxy / Remote Fetch

- [x] **Implement `NpmRepositoryProxyHandler`**
  `NpmRepositoryProxyHandler` extends `DefaultRepositoryProxyHandler` and is
  registered as `@Service("repositoryProxyHandler#npm")`. Uses Apache HttpClient
  to fetch packages from the upstream registry; handles 304/404 correctly.
  `NpmRegistryServlet` looks up this bean at init time (optional; silently disabled
  when no proxy connectors are configured) and calls `resolveWithProxy()` for every
  GET before falling back to a 404.

- [x] **Rewrite tarball URLs in proxied metadata**
  `rewriteTarballUrls()` in `NpmRepositoryProxyHandler` parses the metadata JSON,
  iterates `versions[*].dist.tarball`, and replaces the upstream base URL with
  `{archiva.npm.external-url}/npm/{repoId}/`. Rewriting is skipped (with a debug
  log) when the `archiva.npm.external-url` system property is not set.

---

## Servlet / Protocol

- [x] **Scoped-package metadata GET**
  `GET /npm/{repoId}/@{scope}/{name}` is handled by the existing `NpmRequest.parse()`
  logic which detects the `@` prefix and sets the `scope` field. The storage path
  `{scope}/{name}/package.json` is correct. No code changes needed.

- [x] **Version-specific metadata GET**
  `GET /npm/{repoId}/{name}/{version}` (and the scoped equivalent) reads the stored
  `package.json`, walks into `versions.{version}`, and returns only that sub-document.
  Returns 404 when the version key is absent.

- [x] **Unpublish / deprecate (DELETE)**
  `doDelete()` in `NpmRegistryServlet` parses the path (stripping the optional
  `/-rev/{rev}` suffix appended by the npm client) and recursively deletes the
  package directory from storage. Returns `{"ok":true}` on success.

- [x] **`dist-tags` endpoint**
  `GET /npm/{repoId}/-/package/{name}/dist-tags` — returns the `dist-tags` object
  from the stored `package.json`.
  `PUT /npm/{repoId}/-/package/{name}/dist-tags/{tag}` — parses the request body as
  a quoted JSON string (the version), updates `dist-tags.{tag}` in `package.json`,
  and writes it back.

- [x] **Search endpoint**
  `GET /npm/{repoId}/-/v1/search?text=...` — delegates to `repositorySearch#maven`
  (`RepositorySearch`) and maps `SearchResultHit` fields to the npm v1 search JSON
  format (`objects[].package.{name,version}`, `total`, `time`).
  Returns 501 when no search backend is configured (optional bean).

- [x] **Authentication / authorisation**
  `ServletAuthenticator` (`servletAuth`) and `HttpAuthenticator` (`httpAuth#basic`)
  are looked up as optional beans in `init()`. `checkReadAccess()`,
  `checkWriteAccess()`, and `checkDeleteAccess()` call `httpAuth.getAuthenticationResult()`,
  fall back to guest check for reads, and send 401 / 403 on denial. When either bean
  is absent (no security module), access is permitted (permissive mode).

- [x] **Checksum verification on publish**
  For each attachment in the publish body, the tarball bytes are hashed with SHA-1
  and compared against `versions.{version}.dist.shasum`. When the optional
  `dist.integrity` field is present and starts with `sha512-`, the SHA-512 digest
  (standard Base64) is also verified. A mismatch returns 400 Bad Request before
  writing any file to storage.

---

## Configuration

- [x] **Archiva admin UI for NPM repositories**
  Implemented a dedicated V2 REST endpoint at `GET|POST|PUT|DELETE
  /v2/archiva/repositories/npm/managed` via `NpmManagedRepositoryService` (JAX-RS
  interface) and `DefaultNpmManagedRepositoryService` (Spring `@Service`). The service
  is registered in `archiva-rest-services/spring-context.xml` under the `v2.archiva`
  CXF server. On the Angular side: new lazy-loaded `NpmRepositoryModule` at route
  `/admin/npm-repositories` with four components (container, list, add, edit), a
  dedicated `NpmRepositoryService`, model class `NpmManagedRepository`, and all i18n
  keys in `en.json` under `npm-repo.*`. The side menu was updated to link to the new
  route under the Administration section.

- [x] **`archiva.xml` type discrimination**
  `ManagedRepositoryHandler` calls `RepositoryType.valueOf(cfg.getType())` to
  dispatch to the correct `RepositoryProvider`. Since `RepositoryType.NPM` already
  exists in the enum and `NpmRepositoryProvider` is registered via Spring
  `@Service("npmRepositoryProvider")`, NPM repositories survive a configuration
  round-trip with no additional code changes. A repository configured with
  `<type>NPM</type>` in `archiva.xml` will be loaded by `NpmRepositoryProvider`
  on startup.

---

## Metadata / Indexing

- [x] **NPM package consumer (`RepositoryContentConsumer`)**
  `NpmMetadataConsumer` registered as `@Service("knownRepositoryContentConsumer#npm-metadata")`.
  Scans `**/-/*.tgz` globs; extracts `package/package.json` from tarballs using a
  zero-dependency POSIX tar reader (GZIPInputStream + manual 512-byte block parsing).
  Persists `ProjectMetadata`, `ProjectVersionMetadata`, and `ArtifactMetadata` via
  `RepositorySessionFactory`.

- [x] **Browsing support**
  `NpmMetadataResolver` registered as `@Service("metadataResolver#npm")` with
  `supportsRepositoryTypes()` returning `[NPM]`. All `resolve*` methods delegate
  directly to `MetadataRepository` (data written by the consumer above), bypassing
  the Maven-only `RepositoryStorage` layer. Note: `DefaultBrowseService` selects
  resolvers by `supportsRepositoryTypes()`, so NPM repositories are served by this
  resolver automatically once the Spring context loads both beans.

---

## Testing

- [ ] **Unit tests for `NpmRegistryServlet`** — mock `RepositoryRegistry`, test all
  routes (GET metadata, GET tarball, PUT publish, 404 paths).
- [ ] **Unit tests for `NpmManagedRepositoryContent.toPath(ItemSelector)`** — cover
  scoped and unscoped packages.
- [ ] **Integration test** — stand up Archiva with an NPM managed repo and run
  `npm install` / `npm publish` against it.
