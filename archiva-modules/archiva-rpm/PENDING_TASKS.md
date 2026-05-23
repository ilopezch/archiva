# RPM Repository — Pending Tasks

This document tracks work left to complete for the RPM repository feature.
The current skeleton covers: repository SPI wiring, binary RPM header parsing,
repodata generation (primary.xml.gz, filelists.xml.gz, other.xml.gz, repomd.xml),
GPG signing of repomd.xml, servlet-based GET/PUT/DELETE for yum/dnf clients,
V2 REST CRUD API, and Angular admin UI scaffolding.

---

## Repository Layer

- [x] **Implement `toItemSelector(String path)` in `RpmRepositoryRequestInfo`**
  Parse `RPMS/{arch}/{name}-{version}-{release}.{arch}.rpm` and
  `SRPMS/{name}-{version}-{release}.src.rpm` paths into `ArchivaItemSelector`
  (namespace=arch, projectId=name, version=version-release).

- [x] **Remote/proxy RPM repository support**
  Implement `RpmRemoteRepository`, add proxy handler to fetch and cache
  packages from upstream yum/dnf mirrors (RHEL, CentOS, Fedora, EPEL, etc.).
  Wire the remote repository into `RpmRepositoryProvider`.
  Also added `RpmRemoteRepositoryContent` and wired into `RpmContentProvider`.

- [x] **Repository groups for RPM**
  `RpmRepositoryGroup` exists but the servlet doesn't serve merged repos.
  Add group handling in `RpmRegistryServlet`: merge `repodata/` across group
  members and re-sign with the group's key.
  Added `RepomdGenerator.rebuildMerged()` for multi-root merging.
  Group repodata is rebuilt on-demand when `repodata/repomd.xml` is absent.

---

## Repodata Generation

- [x] **Validate RPM header parser against real packages**
  Added `RpmTestFixtureBuilder` (synthetic RPM binary constructor) and
  `RpmHeaderParserTest` with 12 tests covering: basic fields, epoch-bearing
  packages, epoch=0, source RPMs (arch="src"), noarch packages, requires/
  provides arrays, full file list with directory reconstruction, changelog
  entries, multiline descriptions, and EVR helper edge cases.

- [x] **Full filelists.xml generation**
  `RpmPackageInfo` now carries `dirNames` (TAG_DIRNAMES 1118) and
  `dirIndexes` (TAG_DIRINDEXES 1119). `RpmHeaderParser.extractInfo()` parses
  both tags. `RepomdGenerator.writeFilelists()` reconstructs absolute paths
  as `dirNames[dirIndexes[i]] + basenames[i]` and emits
  `<file type="dir">` for entries with an empty basename. Falls back to
  basename-only output when directory info is absent.

- [x] **other.xml changelog support**
  `RpmPackageInfo` now carries `changelogTimes` (TAG_CHANGELOGTIME 1080),
  `changelogNames` (TAG_CHANGELOGNAME 1081), and `changelogTexts`
  (TAG_CHANGELOGTEXT 1082). `RpmHeaderParser` parses all three via a new
  `ui32Arr()` helper for unsigned 32-bit timestamps. `RepomdGenerator
  .writeOther()` emits `<changelog author="…" date="…">…</changelog>` for
  every entry in the parallel arrays.

- [x] **Incremental repodata rebuild**
  New `RpmRepodataCache` class persists parsed metadata to
  `.repodata/cache.json` (hand-rolled JSON codec — no new runtime deps).
  Cache entries are keyed by repository-relative filename + last-modified
  time (ms). `RpmScanner.scan(Path, RpmRepodataCache)` consults the cache
  before parsing each RPM; hits skip both header parsing and SHA-256
  computation. `RepomdGenerator.rebuildFromRoots()` loads the cache at the
  start and saves it after scanning.

- [x] **Delta RPM metadata (`deltarpm`)**
  `RepomdGenerator.writeDeltaRpm()` scans `drpms/{arch}/` directories in all
  source roots for `.drpm` files. Filenames are parsed using the makedeltarpm
  convention `{name}-{oldVer}-{oldRel}_{newVer}-{newRel}.{arch}.drpm`.
  Generates `prestodelta.xml.gz` grouped by new package and registers it as
  `<data type="deltarpm">` in `repomd.xml`. Returns `null` (no entry emitted)
  when no delta RPMs are found.

- [x] **Module metadata (`modules.yaml.gz`)**
  `RepomdGenerator.writeModules()` walks each source root collecting
  `*.modulemd.yaml` / `*.modulemd.yml` files, concatenates them as a
  multi-document YAML stream (prepends `---` if absent), compresses to
  `modules.yaml.gz`, and registers as `<data type="modules">` in `repomd.xml`.
  Returns `null` when no modulemd files are found.

---

## GPG Key Management

- [x] **Expose GPG key fingerprint via REST API**
  Added `GET /v2/archiva/repositories/rpm/managed/{id}/gpgkey` returning
  `RpmGpgKeyInfo` (fingerprint, userId, algorithm, bitStrength, created,
  expires, armoredPublicKey). `POST …/gpgkey/rotate` forces key regeneration
  and rebuilds repodata. Both endpoints implemented in
  `DefaultRpmManagedRepositoryService` and declared in
  `RpmManagedRepositoryService`.

- [x] **Allow operator-supplied GPG key**
  Added `gpgKeyPath` / `gpgUserId` fields to REST DTO `RpmManagedRepository`,
  domain `RpmManagedRepository`, and `ManagedRepositoryConfiguration`.
  `RpmRepositoryProvider` reads/writes both fields during
  `updateManagedInstance` / `getManagedConfiguration`.
  `RepomdGenerator.getOrCreateSigningKey()` now loads the external key when
  `gpgKeyPath` is set and the file exists; falls back to auto-generated key.

- [x] **GPG key expiry and rotation**
  `getOrCreateSigningKey()` checks `validSeconds > 0` and
  `creationTime + validSeconds < now`; expired keys are deleted and
  regenerated automatically. `RepomdGenerator.rotateKey(Path, String)`
  provides explicit rotation. The `POST …/gpgkey/rotate` REST endpoint
  rotates the key and immediately rebuilds repodata so repomd.xml is
  re-signed with the new key.

---

## Servlet / Protocol

- [x] **Authentication and authorisation in `RpmRegistryServlet`**
  Wired `HttpAuthenticator` (bean `httpAuthenticator#basic`) and
  `ServletAuthenticator` (bean `servletAuthenticator`) as optional beans
  loaded in `init()`. Added `checkReadAccess()`, `checkWriteAccess()`,
  `checkDeleteAccess()`, and `checkAccess()` helpers (mirrors the npm
  servlet pattern). `doGet`, `doPut`, and `doDelete` all gate on the
  appropriate operation constant from `ArchivaRoleConstants`. When no
  auth beans are configured the servlet remains open (permissive mode).

- [x] **ETag / conditional GET support**
  `serveFile()` now computes and sets an `ETag` response header before
  streaming. Metadata files ≤10 MB receive a strong SHA-256 ETag;
  larger files (RPMs) use a `"size-mtime"` form to avoid re-hashing on
  every request. A matching `If-None-Match` request header results in a
  304 Not Modified response with no body.

- [x] **Content-Range / resume upload**
  `doPut()` now inspects the `Content-Range` header and routes to
  `handleRangeUpload()` when present. Each chunk is written at the
  specified byte offset via a `FileChannel`. Partial chunks return 202
  Accepted; the final chunk (detected when `end + 1 >= total`) triggers
  checksum verification (if requested) and a repodata rebuild before
  returning 201 Created. A new inner class `ContentRange` handles header
  parsing and validation.

- [x] **Checksum verification on upload**
  `handleFullUpload()` wraps the request input stream in a
  `DigestInputStream` and writes to a temp file. If an
  `X-Checksum-SHA256` header is present, the computed hex digest is
  compared before committing the file via `Files.move()`; a mismatch
  returns 400 Bad Request and deletes the temp file. For range uploads,
  `handleRangeUpload()` recomputes the SHA-256 of the complete on-disk
  file after the final chunk and likewise rejects on mismatch.

---

## Configuration

- [x] **Persist GPG key settings in `archiva.xml`**
  Added `gpgKeyPath` and `gpgUserId` fields to `ManagedRepositoryConfiguration`
  (both the generated Java class and `configuration.mdo`). The fields are
  serialized via the existing Modello/XPP3 codec so they persist across
  Archiva restarts.

- [x] **Configuration UI for GPG**
  Updated `manage-rpm-repo-edit.component.ts` and its HTML template:
  the edit form now includes `gpg_key_path` and `gpg_user_id` inputs.
  A GPG key status panel below the form shows the fingerprint, user-ID,
  algorithm/bit-strength, creation date, and expiry; it provides a
  "Download public key" button and a "Rotate key" button with spinner.
  `RpmRepositoryService` has `getGpgKey(id)` and `rotateGpgKey(id)`
  methods wired to the new REST endpoints.

---

## Consumer / Indexing

- [x] **`RpmMetadataConsumer` (`RepositoryContentConsumer`)**
  New class `org.apache.archiva.rpm.repository.consumer.RpmMetadataConsumer`.
  Registered as `@Service("knownRepositoryContentConsumer#rpm-metadata")` with
  `@Scope("prototype")`. Extends `AbstractMonitoredConsumer`, implements
  `KnownRepositoryContentConsumer`. Includes glob patterns `RPMS/**/*.rpm`
  and `SRPMS/**/*.rpm`. `processFile()` opens each RPM, calls
  `RpmHeaderParser.parseHeader()`, then opens a `RepositorySession` and
  persists `updateNamespace` + `updateArtifact` + `updateProjectVersion` +
  `updateProject` in a single session per file (session saved per-file,
  rolled back on error).
  Field mapping: namespace = arch, project = name, version = version-release.
  Added `metadata-model` and `metadata-repository-api` dependencies to
  `archiva-rpm-repository/pom.xml`.

- [x] **`RpmMetadataResolver`**
  New class `org.apache.archiva.rpm.repository.RpmMetadataResolver`.
  Registered as `@Service("metadataResolver#rpm")`.
  `supportsRepositoryTypes()` returns `[RepositoryType.RPM]`.
  All seven `resolve*` methods delegate directly to
  `session.getRepository()` — metadata is read from the store populated by
  the consumer (same pattern as `NpmMetadataResolver`).

- [x] **Trigger repodata rebuild from scan consumer**
  `RpmMetadataConsumer.completeScan()` calls `RepomdGenerator.rebuild(repoDir)`
  once at the end of every scan pass. By placing the rebuild in
  `completeScan()` rather than `processFile()`, exactly one rebuild fires
  per scan regardless of how many packages were processed. Individual PUT
  uploads continue to trigger their own immediate rebuild in
  `RpmRegistryServlet`, so there is no double-rebuild for single-package
  uploads that bypass the scan infrastructure.

---

## Testing

- [x] **Unit tests for `RpmHeaderParser`** — fixture RPMs covering epoch,
  requires/provides arrays, source RPMs, and noarch packages.
  Implemented in `RpmHeaderParserTest` (12 tests) using `RpmTestFixtureBuilder`
  to construct synthetic RPM binaries without real packages.

- [x] **Unit tests for `PrimaryXmlBuilder`** — verify XML output against
  createrepo-c output for the same package.
  Implemented in `PrimaryXmlBuilderTest`.

- [x] **Unit tests for `RpmManagedRepositoryContent.toPath/toItemSelector`**
  Implemented in `RpmManagedRepositoryContentTest` (22 tests) covering binary RPMs,
  noarch, source RPMs, hyphenated names, error cases, and round-trip validation.

- [ ] **Integration test** — stand up Archiva with an RPM managed repo,
  upload a package via PUT, then run `dnf install` pointing at it.
  (Requires external dnf tooling; deferred to CI environment.)

- [ ] **Angular E2E tests** — create/edit/delete an RPM repo via the UI.
  (Requires running Archiva instance and browser driver; deferred to E2E CI job.)
