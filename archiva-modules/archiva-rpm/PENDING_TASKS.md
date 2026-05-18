# RPM Repository — Pending Tasks

This document tracks work left to complete for the RPM repository feature.
The current skeleton covers: repository SPI wiring, binary RPM header parsing,
repodata generation (primary.xml.gz, filelists.xml.gz, other.xml.gz, repomd.xml),
GPG signing of repomd.xml, servlet-based GET/PUT/DELETE for yum/dnf clients,
V2 REST CRUD API, and Angular admin UI scaffolding.

---

## Repository Layer

- [ ] **Implement `toItemSelector(String path)` in `RpmRepositoryRequestInfo`**
  Parse `RPMS/{arch}/{name}-{version}-{release}.{arch}.rpm` and
  `SRPMS/{name}-{version}-{release}.src.rpm` paths into `ArchivaItemSelector`
  (namespace=arch, projectId=name, version=version-release).

- [ ] **Remote/proxy RPM repository support**
  Implement `RpmRemoteRepository`, add proxy handler to fetch and cache
  packages from upstream yum/dnf mirrors (RHEL, CentOS, Fedora, EPEL, etc.).
  Wire the remote repository into `RpmRepositoryProvider`.

- [ ] **Repository groups for RPM**
  `RpmRepositoryGroup` exists but the servlet doesn't serve merged repos.
  Add group handling in `RpmRegistryServlet`: merge `repodata/` across group
  members and re-sign with the group's key.

---

## Repodata Generation

- [ ] **Validate RPM header parser against real packages**
  `RpmHeaderParser` covers the common case but needs testing against
  epoch-bearing packages, packages with POSIX-style extended headers, and
  source RPMs. Add JUnit tests with fixture RPMs.

- [ ] **Full filelists.xml generation**
  `RepomdGenerator.writeFilelists()` currently uses only `TAG_BASENAMES`.
  Full implementation requires reading `TAG_DIRNAMES` + `TAG_DIRINDEXES` to
  reconstruct absolute paths, and emitting `<file type="dir">` entries.

- [ ] **other.xml changelog support**
  Parse `TAG_CHANGELOGNAME`, `TAG_CHANGELOGTIME`, `TAG_CHANGELOGTEXT` from
  the RPM header and emit `<changelog>` entries in `other.xml`.

- [ ] **Incremental repodata rebuild**
  `RepomdGenerator.rebuild()` currently performs a full scan on every upload.
  Cache parsed `RpmPackageInfo` (e.g. in a `.repodata/cache.json`) keyed by
  filename + mtime and only re-parse changed files.

- [ ] **Delta RPM metadata (`deltarpm`)**
  Generate `prestodelta.xml.gz` referencing delta RPMs when present under
  `drpms/{arch}/`. Register as an additional `<data type="deltarpm">` entry
  in `repomd.xml`.

- [ ] **Module metadata (`modules.yaml.gz`)**
  Generate or forward `modules.yaml.gz` for RHEL 8+ / Fedora AppStream
  support. Parse `.modulemd.yaml` files placed by operators alongside RPMs.

---

## GPG Key Management

- [ ] **Expose GPG key fingerprint via REST API**
  Add `GET /v2/archiva/repositories/rpm/managed/{id}/gpgkey` returning the
  armored public key and its fingerprint so clients can import it via the UI.

- [ ] **Allow operator-supplied GPG key**
  Add `gpgKeyId` / `gpgKeyPath` fields to `RpmManagedRepository` REST DTO
  and provider configuration so an existing GPG key (e.g. from a corporate
  key store) can be used instead of the auto-generated one.

- [ ] **GPG key expiry and rotation**
  Detect key expiry, auto-rotate, and re-sign all existing repomd.xml files.
  Expose a manual rotation endpoint.

---

## Servlet / Protocol

- [ ] **Authentication and authorisation in `RpmRegistryServlet`**
  Wire `HttpAuthenticator` (`httpAuth#basic`) and `ServletAuthenticator`
  for read/write/delete access checks, mirroring the npm servlet.

- [ ] **ETag / conditional GET support**
  Return `ETag` headers based on the repomd.xml SHA-256. Respond with
  `304 Not Modified` when `If-None-Match` matches. Speeds up dnf metadata
  refresh for unchanged repos.

- [ ] **Content-Range / resume upload**
  Support `Content-Range` on `PUT` for large RPM uploads that may be
  interrupted and resumed.

- [ ] **Checksum verification on upload**
  Compute SHA-256 of uploaded RPM bytes and compare against an optional
  `X-Checksum-SHA256` request header before writing to storage.

---

## Configuration

- [ ] **Persist GPG key settings in `archiva.xml`**
  Add `<gpgKeyPath>` and `<gpgUserId>` child elements to the managed
  repository config model so settings survive Archiva restarts.

- [ ] **Configuration UI for GPG**
  Add GPG key management panel to the Angular edit page: display fingerprint,
  download public key, trigger key rotation.

---

## Consumer / Indexing

- [ ] **`RpmMetadataConsumer` (`RepositoryContentConsumer`)**
  Register as `@Service("knownRepositoryContentConsumer#rpm-metadata")`.
  Triggered by the existing scan infrastructure on `.rpm` files. Persists
  `ProjectMetadata`, `ProjectVersionMetadata`, and `ArtifactMetadata` via
  `RepositorySessionFactory`.

- [ ] **`RpmMetadataResolver`**
  Register as `@Service("metadataResolver#rpm")` with
  `supportsRepositoryTypes()` returning `[RPM]`. Delegates `resolve*`
  methods to `MetadataRepository` so browse/search work for RPM repos.

- [ ] **Trigger repodata rebuild from scan consumer**
  After the metadata consumer finishes a scan pass, call
  `RepomdGenerator.rebuild()` to regenerate repodata. Wire via the
  `RepositoryEvent` system so individual PUT uploads do not duplicate work.

---

## Testing

- [ ] **Unit tests for `RpmHeaderParser`** — fixture RPMs covering epoch,
  requires/provides arrays, source RPMs, and noarch packages.
- [ ] **Unit tests for `PrimaryXmlBuilder`** — verify XML output against
  createrepo-c output for the same package.
- [ ] **Unit tests for `RpmManagedRepositoryContent.toPath/toItemSelector`**
- [ ] **Integration test** — stand up Archiva with an RPM managed repo,
  upload a package via PUT, then run `dnf install` pointing at it.
- [ ] **Angular E2E tests** — create/edit/delete an RPM repo via the UI.
