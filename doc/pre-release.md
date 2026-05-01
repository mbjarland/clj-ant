# Release checklist

Release readiness for the current alpha series and the eventual public
1.0. Categorized by who owns each item.

Legend:
- ⏳ todo
- 🚧 in progress
- ✅ done
- ❓ decision needed (no obvious right answer)


## Release foundations

### Repo + identity

- ✅ **Mirror the repo to GitHub.**
  The repo is available at `github.com/mbjarland/clj-ant`, which is
  the location Clojars' `io.github.*` verified-group policy checks.

- ✅ **Claim the verified group `io.github.mbjarland` on Clojars.**
  The group is verified and matches the artifact coordinates in
  `build.clj`.

- ✅ **Tag the alpha releases.**
  `v1.0.0-alpha.1` through `v1.0.0-alpha.4` are published. Final 1.0
  waits for external usage and feedback on the public API.

### Build / publish

- ✅ **Add a `deploy` task to `build.clj`.**
  `build/deploy` uses `slipset/deps-deploy` and the existing
  `:build` alias to publish the jar + generated POM to Clojars.

- ✅ **Wire `CLOJARS_USERNAME` / `CLOJARS_PASSWORD` deploy token.**
  GitHub secrets are configured with a rotated Clojars deploy token.

- ✅ **Confirm Clojars deployment.**
  `io.github.mbjarland/clj-ant` `1.0.0-alpha.4` is available on
  Clojars.

### Continuous integration

- ✅ **`.github/workflows/ci.yml`** running on every push:
   ```yaml
   - clj -T:build javac
   - clj -T:build lint
   - clj -M:test
   - clj -T:build jar
   ```
  The workflow tests JDK 8, 11, 17, and 21.


## Strongly recommended — before public announcement

### Code

- ✅ **Lower `javac --release` from 11 to 8** in `build.clj`.
  Our Java bridge uses nothing JDK 11+; Ant itself supports
  JDK 8. Lowering broadens compatibility for free.

- ✅ **Bump deps to latest safe versions:**
  - `metosin/malli` 0.16.4 → 0.20.1
  - `nrepl/nrepl` 1.3.0 → 1.7.0 (alias only)
  - `tools.build` v0.10.5 → v0.10.13

  Stay on Clojure 1.11.4 (1.12.0 exists but 1.11 has broader
  user base; nothing in clj-ant requires 1.12). Ant 1.10.17,
  ant-jsch 1.10.17, mwiede/jsch 0.2.25 are already latest.

### Documentation

- ✅ **README "Status" banner.** Make the alpha state explicit:
  > **Pre-1.0 alpha.** Architecture is stable; surface APIs may
  > shift slightly during the alpha period based on real-world
  > feedback. Once 1.0 lands, semver applies normally.

- ✅ **Getting Started snag-fix in README.** First-time users
  must run `clj -T:build javac` once when working from source; the
  contributor quickstart documents that step prominently.

- ❓ **Babashka pod manifest for the registry.**
  https://github.com/babashka/pod-registry — optional. Lets bb
  users do `(pods/load-pod 'mbjarland/clj-ant)` instead of
  spelling out `["clojure" "-M:pod"]`. Worth doing once stable.


## Nice to have — post-launch

- ✅ **CHANGELOG.md** with release entries.
  It follows `keepachangelog.com` format and records the first alpha
  release separately from unreleased follow-up work.

- ✅ **cljdoc auto-publish.** Free with Clojars; `1.0.0-alpha.3`
  indexed successfully with API docs, articles, source links, and GitHub
  source metadata.

- ❓ **Migrate `tasks.clj` out of git history?** It's a 16k-line
  generated file. Bloats clones. Could move to an artifact
  resource generated at jar-build time. Open question — the
  REPL ergonomics rely on it being on the classpath.

- ❓ **Squash the development history before 1.0?** 35+ commits
  with detailed messages — useful for archaeology, possibly
  noisy for newcomers. A squash to ~10 commits along
  architectural seams could read better.


## Things to be honest about in advertising

**Do** advertise as battle-tested:
- Data model (`element`, `as-child`, `Element` record)
- AST construction (UnknownElement / RuntimeConfigurable bridge)
- `from-xml` round-trip
- Sessions and prepare/run
- The babashka pod
- SSH bundling (Terrapin-fixed)
- Broad Kaocha test suite / multiple rounds of external review

**Don't** over-promise on:
- `clj-ant.spec` and the `:by-parent` introspection paths — added
  late, no real users yet.
- Pod streaming protocol stability — works but small surface.
- Scale claims beyond verified bounds — `lazy-resources` proven
  to ~10k entries via test, not actual million-file production.


## Suggested order of operations

1. Share the Clojars and cljdoc links with early users.
2. Collect feedback on `clj-ant.spec`, pod streaming, and validation.
3. Cut follow-up alpha tags for documentation or API fixes as needed.
