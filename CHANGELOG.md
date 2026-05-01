# Changelog

All notable changes to clj-ant are tracked here.

This file follows the [Keep a Changelog](https://keepachangelog.com/)
format.

## Unreleased

## 1.0.0-alpha.4 - 2026-05-01

### Added

- Add explicit cljdoc article ordering so README, guides, architecture,
  release notes, and changelog render in a predictable order.
- Add a babashka pod smoke script, wired into CI and release checks, that
  loads the pod with `babashka.pods/load-pod` and exercises generated task
  stubs plus `describe`, `lint`, `files`, and `execute-stream`.

### Changed

- Render generated wrapper attribute docs as Markdown tables for more
  readable cljdoc API pages.
- Use an HTTPS SCM connection in generated POM metadata while keeping the
  maintainer developer connection on SSH.
- Refresh README and release-checklist state for the published alpha.3 and
  live cljdoc documentation.

### Fixed

- Allow Java bridge compilation on JDK 8 while still emitting Java 8
  bytecode from newer JDKs.

### Removed

- Remove obsolete checked-in `safe-target` artifacts and ignore the local
  `safe-target` build directory.

## 1.0.0-alpha.3 - 2026-05-01

### Changed

- Render generated wrapper API docs as Markdown sections with compact
  signatures for cljdoc.

### Fixed

- Ensure build subprocess failures, including lint warnings, fail the
  `tools.build` check pipeline.
- Corrected the generated POM developer name.

## 1.0.0-alpha.2 - 2026-05-01

### Added

- Added strict clj-kondo linting to the local build, CI, and release
  workflows.

### Changed

- Tightened public documentation after the first alpha release.

### Fixed

- Corrected stale manual links and imprecise wrapper-count wording.

## 1.0.0-alpha.1 - 2026-05-01

### Added

- Generated wrapper docstrings and metadata from the bundled Ant manual,
  including task/type descriptions, attribute prose, required markers,
  and manual URLs.
- REPL and tooling introspection with `clj-ant.core/describe`, plus
  `lint` and `explain` for non-executing tree validation and typo
  suggestions.
- Babashka pod introspection helpers for `describe`, `lint`, and
  `explain`.
- `to-xml` for compact XML rendering of normal clj-ant element trees,
  pairing with `from-xml` for build-file migration workflows.
- GitHub CI and release automation for testing, jar building, and
  tag-driven Clojars deployment.

### Changed

- Updated the bundled Ant manual to match the `1.10.17` Ant dependencies.
- Enriched validation with manual metadata while preserving Ant runtime
  introspection as the source of supported attributes and nested tags.
- Cached `describe` introspection results to avoid rebuilding Ant
  project/helper metadata on repeated calls.

### Fixed

- `from-xml` preserves build-file directory context so relative Ant
  paths resolve like Ant's own `build.xml` loader.
- Validation handles context-sensitive nested tags such as `attribute`
  under `macrodef` versus `manifest`.
- Project/session cleanup avoids leaking synthetic references, listeners,
  or transient inline task registrations across repeated builds.
