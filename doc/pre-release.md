# Pre-release checklist

What's left between the current state and a public 1.0 on
GitHub + Clojars. Categorized by who owns it.

Legend:
- ⏳ todo
- 🚧 in progress
- ✅ done
- ❓ decision needed (no obvious right answer)


## Blocking — must do before publishing

### Repo + identity

- ⏳ **Move (or mirror) the repo from Bitbucket to GitHub.**
  Current `origin` is `bitbucket.org:mbjarland/clj-ant`. Clojars'
  verified-group policy under `io.github.*` cross-checks the
  GitHub repo exists. Either move outright or set up a mirror.

- ⏳ **Claim the verified group `io.github.mbjarland` on Clojars.**
  https://clojars.org/verified-group — one-time setup, requires
  the repo to live at `github.com/mbjarland/<artifact>`.

- ⏳ **Tag `v1.0.0-alpha.1` (suggest pre-1.0 to start).**
  Lets users know APIs may evolve based on real-world feedback
  during the alpha period. Final 1.0 once external usage settles.

### Build / publish

- ⏳ **Add a `deploy` task to `build.clj`.** ~30 LOC using
  `slipset/deps-deploy`:
  ```clojure
  (defn deploy [_]
    (jar nil)
    (deps-deploy/deploy {:installer :remote
                         :sign-releases? false
                         :artifact jar-file
                         :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
  ```
  Plus the dep in a `:deploy` alias.

- ⏳ **Wire `CLOJARS_USERNAME` / `CLOJARS_PASSWORD` deploy token.**
  Generate via Clojars web UI (deploy-only token, scoped to the
  verified group). Store as a GitHub secret if CI-driven, or as
  an env var for local manual deploy.

### Continuous integration

- ⏳ **`.github/workflows/ci.yml`** running on every push:
  ```yaml
  - clj -T:build javac
  - clj -M:test
  - clj -T:build jar
  ```
  ~30 lines using `DeLaGuardo/setup-clojure`. Without CI, users
  have no confidence in patch releases.


## Strongly recommended — before public announcement

### Code

- ⏳ **Lower `javac --release` from 11 to 8** in `build.clj`.
  Our Java bridge uses nothing JDK 11+; Ant itself supports
  JDK 8. Lowering broadens compatibility for free.

- ⏳ **Bump deps to latest safe versions:**
  - `metosin/malli` 0.16.4 → 0.20.1
  - `nrepl/nrepl` 1.3.0 → 1.7.0 (alias only)
  - `tools.build` v0.10.5 → v0.10.13

  Stay on Clojure 1.11.4 (1.12.0 exists but 1.11 has broader
  user base; nothing in clj-ant requires 1.12). Ant 1.10.17,
  ant-jsch 1.10.17, mwiede/jsch 0.2.25 are already latest.

### Documentation

- ⏳ **README "Status" banner.** Make the alpha state explicit:
  > **Pre-1.0 alpha.** Architecture is stable; surface APIs may
  > shift slightly during the alpha period based on real-world
  > feedback. Once 1.0 lands, semver applies normally.

- ⏳ **Getting Started snag-fix in README.** First-time users
  must run `clj -T:build javac` once. Either document this
  prominently or have `:test` / `:dev` aliases auto-run it.
  Cleanest: add a `:dev` alias that depends on a setup step.

- ❓ **Babashka pod manifest for the registry.**
  https://github.com/babashka/pod-registry — optional. Lets bb
  users do `(pods/load-pod 'mbjarland/clj-ant)` instead of
  spelling out `["clojure" "-M:pod"]`. Worth doing once stable.


## Nice to have — post-launch

- ❓ **CHANGELOG.md** with real entries on each tagged release.
  Currently a placeholder. Switch to `keepachangelog.com` format
  once we cut versions.

- ❓ **cljdoc auto-publish.** Free with Clojars; just needs the
  artifact to land. cljdoc reads `doc/` folder and assembles a
  hosted reference site at cljdoc.org/d/io.github.mbjarland/clj-ant.

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
- 32 tests / 107 assertions / multiple rounds of external review

**Don't** over-promise on:
- `clj-ant.spec` and the `:by-parent` introspection paths — added
  late, no real users yet.
- Pod streaming protocol stability — works but small surface.
- Scale claims beyond verified bounds — `lazy-resources` proven
  to ~10k entries via test, not actual million-file production.


## Suggested order of operations

1. Lower `--release` to 8, bump three deps, verify tests, commit.
2. Add CI workflow + `:deploy` build task. Commit, no push.
3. Migrate repo to GitHub (preserves commits via push).
4. Claim Clojars verified group.
5. Push to GitHub, CI runs, fixes if any.
6. Cut `v1.0.0-alpha.1`, push tag.
7. `clj -T:build deploy`.
8. Wait for cljdoc to pick up; share link in README.
