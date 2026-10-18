# Repository working instructions

## Product and architecture

Read README.md, BUILDING.md and docs/README.md before changes. The selected implementation is Java/JavaFX with the Atlas design. The formal specifications in docs/ describe the intended architecture, workflows and quality gates; GitHub Issues records implemented scope and remaining work.

Keep domain, application use cases, infrastructure adapters, JavaFX presentation and composition separate. Domain/application must not depend on JavaFX, HTTP/JDBC implementations or filesystem mining. UI code must not perform downloads, persistence or binary parsing. Enforce module boundaries with automated checks. Introduce new abstractions/modules only for real responsibilities.

VibeMiner only accesses official CDN sources and app-owned storage. Never discover/read game installations, Battle.net directories or game-generated caches. Preserve exact edition/build/locale identity and source provenance. Reuse identical physical content across build/cache/archive memberships. Unsupported, encrypted, unnamed, partial and unavailable data are distinct states.

## Work and verification

- Open or identify a VibeMiner GitHub issue before enhancements, fixes or substantive maintenance. Use small logical steps with testable acceptance criteria, dependencies and explicit scope.
- Use TDD where practical. Add meaningful regression tests for testable defects. Use deterministic synthetic fixtures in normal CI; explicit live checks are separate.
- Every change includes relevant checks. Unit tests do not replace required integration, UI, accessibility or native-package evidence. Record actual results and failed/unrun checks before issue closure.
- Follow Atlas closely. Compare real JavaFX screenshots against the design at matching sizes for substantive screen/layout changes. Review typography, spacing, palette, hierarchy, focus, scaling and loading/empty/error/offline states. Document deliberate usability corrections.
- Agree module/file ownership before parallel edits. Coordinate shared contracts and have changes reviewed independently. Do not overwrite another contributor's work.
- Formalized decisions/specifications belong in versioned docs/ so another computer can resume work. Informal drafts, private logs and team composition stay in ignored local storage; never copy them into commits, issues or packages. Builds/tests must work without them.
- Never commit downloaded game content, caches, credentials or local diagnostic paths. Package only declared runtime/application inputs.
- Use GitHub noreply identity for both author and committer: `aircan80 <329744803+aircan80@users.noreply.github.com>`. Never use a personal email.

## Commands

Run `python3 -m unittest discover -s tests -v` for build-automation helpers and `./gradlew check` for Java checks. See BUILDING.md for full JDK setup, desktop launch, native packaging, UI capture and release procedures.
