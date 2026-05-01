# Changelog

All notable changes to clj-ant are tracked here. The project has not cut
a public versioned release yet; entries under `Unreleased` will become
the first alpha release notes.

This file follows the [Keep a Changelog](https://keepachangelog.com/)
format.

## Unreleased

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
