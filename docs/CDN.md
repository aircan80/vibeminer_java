# CDN and protocol acquisition plan

Formalized specification. Implementation and validation status are tracked in GitHub Issues.

## Responsibility and boundaries

Discovery obtains official publication metadata. Product adapters interpret protocol structures and capability support. Transport handles bounded HTTP and range semantics. Acquisition resolves source objects, checks verified local mappings, coalesces requests, verifies payloads and publishes through ObjectRepository. Storage owns filesystem/database durability, retention and physical identity. UI receives immutable progress/results and typed failures, never HTTP response streams.

Use typed source identifiers distinguishing build config digest, CDN config digest, encoded key and decoded content key. Validate each according to its actual format specification; local SHA-256 is an additional physical identity, not a replacement for protocol verification. A verified encoded object does not by itself establish successfully verified decoded content.

## First slice: metadata only

Begin deterministic fixtures for version/product/CDN document parsing, preserving extra columns and rejecting malformed required columns. Parse header-declared types carefully, with bounded document sizes, row counts and string lengths. Preserve raw document bytes with source URL, retrieval time, HTTP metadata and local digest. Resolve one exact WoW build snapshot from matching region/product data, retaining build/CDN configuration identities. UI refresh must not mutate an already pinned build snapshot.

Official endpoint candidates from earlier research: `https://us.version.battle.net/v2/summary`, product `/versions` and `/cdns` routes; verify current route shapes and returned formats during the explicit M0 protocol spike. Do not infer product IDs from display names such as Forever; record unverified editions as unresolved capabilities. `/v2/products` was previously observed not to be a valid listing route. Avoid hardcoding an unverified product catalogue as authoritative.

M0 evidence must record exact URL, timestamp, HTTP outcome, document hash, fixture/spec interpretation and chosen reference commit, without publishing game payload. Manual live smoke actions are separate from deterministic CI. A 200 response is not proof of semantically usable metadata.

## Historical builds and availability

Official current/preload pointers plus app-observed snapshots form a locally accumulated catalogue; they are not a complete historical index. Persist old identities when pointers change. Retain metadata resolvers for offline reopening. Known old snapshots can be reacquired only while required configurations/objects remain obtainable. An explicit future import of known config identities can be supported only with validated provenance; do not silently source a community build list.

Availability has separate metadata, resolution and selected-content coverage states. Missing archived object gives partial/unavailable coverage without replacing it with latest or another product. Region CDN routes may differ while content identity remains the same; preserve actual provenance and use only advertised approved hosts. Preload presence does not establish downloadable or decodable contents. Display latest availability check time rather than treating a cached status as current.

## Verified acquisition progression

1. Parse bounded official discovery documents and pin exact product/region/channel/configuration identities.
2. Fetch and verify configuration bytes using source-defined identity rules.
3. Parse supported encoding/root/archive index structures and retain dependency provenance.
4. Resolve requested content/file identity to candidate encodings and loose/archive locations. Unsupported format is explicit.
5. Prefer an existing verified mapping, then a shared in-flight transfer, then a new bounded request.
6. Validate response status, content encoding, byte length, source identity and local SHA-256 before storage publication.
7. Decode supported BLTE modes separately with output/ratio bounds, then verify decoded content identity before interpreted use. Missing encryption keys and unsupported compression are distinct failures.

Range arithmetic is checked and overflow-safe. Require expected 206 Content-Range start/end/total consistency and response length. Request identity HTTP representation to avoid transparent compression altering archive offsets. A 200 full-body response to Range is rejected in the initial implementation; a later bounded full-object fallback requires known size, quota reservation and explicit policy. Treat 416, inconsistent indexes and changed object identity as distinct conditions. Reject arbitrary redirects; validate any allowed redirect destination and scheme under the official source policy. CDN transport/auth assumptions need live validation; never silently downgrade TLS.

Connection/read/whole-operation deadlines, bounded retries with backoff and jitter, Retry-After limits, bounded concurrency and cancellation are acquisition policy, not UI logic. Retry transient network/429/5xx failures within budget; do not retry malformed payloads indefinitely. Hash mismatch can try another advertised host within a small corruption budget. 404 is availability evidence at that time, not proof that every CDN host permanently lacks the object.

## Interface proposals

`DiscoveryPort.refresh(productScope, cancellation)` returns immutable observations and capability/availability distinctions. `BuildResolver.resolve(snapshot)` returns pinned configuration and manifest dependencies. `ContentResolver.locate(request)` returns typed candidates with lengths and verification specification. `AcquisitionService.acquire(sourceObject, lease, cancellation)` returns a verified immutable blob handle, with stage progress and reused-versus-transferred accounting. Domain/application contracts use value types and errors; HTTP, file paths, SQLite and JavaFX remain adapter details.

Progress counts shared object work once, while each consumer reports its own scope completion. Cancellation detaches a subscriber; the underlying request stops only when no subscriber/retention operation needs it. Do not advertise resumable bytes in v1: safely retry at object boundaries first.

## Java feasibility gate

Java is the selected implementation language. Existing C# TACTSharp/DBCD are protocol references, not runtime dependencies or assumed Java ports. CascLib is native and would require JNI/JNA packaging and license review; avoid selecting native bindings without a narrowly demonstrated benefit. Assess maintained pure-Java candidates only against concrete support: online CDN access without game folders, current WoW root/encoding/index variants, BLTE compression coverage, precise verification behavior, bounded parser safety, license, tests and release activity. Do not adopt a library because it can read a local CASC install.

Primary references already recorded in project planning: https://github.com/wowdev/TACTSharp (including VersionServices/Ribbit.cs), https://github.com/ladislav-zezula/CascLib . These were not revalidated during this task; pin source revisions and verify claims in M0. No claim that current Java library coverage is established. Small Java implementations of metadata/configuration parsing can unblock M0 independently of later decoder selection. Binary format and native codec decisions require focused research issues before implementation.

## Tests before implementation

Fixture-first behavior tests: header order/unknown fields, missing required columns, duplicates, unknown product, region mismatch, same label/different configs, changed publication pointers and exact old snapshot reopening. Local scripted HTTP server tests cover truncated response, bad digest, excessive body, wrong Content-Range, 200-on-range, 416, redirects, Retry-After, cancellation and host fallback. Storage integration verifies corrupt bytes never become available, concurrent requests coalesce and offline hits perform zero HTTP. Fuzz/property tests for index counts/offset arithmetic and decompression size bounds follow supported parsers. All fixture bytes are synthetic and checked into normal test resources when implemented, never dependent on ignored local files.
