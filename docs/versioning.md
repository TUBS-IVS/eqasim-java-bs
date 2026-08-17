# How to make a new version

Releases in this fork are automated by
[release-please](https://github.com/googleapis/release-please). There is no manual
`mvn versions:set` step and no artifact publishing. The moving parts are:

- `.github/workflows/release-please.yml` — runs on every push to `main`
- `release-please-config.json` — which files carry the version
- `.release-please-manifest.json` — the current version, as release-please sees it
- `version.txt` — the same version as plain text

## Which version number to choose?

You do not choose it. Release-please derives it from the commit messages since the
last release, following `semver` (`MAJOR.MINOR.PATCH`):

- `fix:` raises `PATCH`
- `feat:` raises `MINOR`
- `feat!:` or a `BREAKING CHANGE:` footer raises `MAJOR`
- `ci:`, `build:`, `docs:`, `chore:`, `refactor:`, `test:` do not raise the version
  and stay out of the changelog

This only works if commits follow
[Conventional Commits](https://www.conventionalcommits.org/). The
`conventional-commits` workflow enforces it on pull request titles, but note what
release-please actually reads: the **individual commits** carried onto `main`. Merge
commits are subject `Merge pull request #N from ...` and cannot be parsed —
release-please logs `commit could not be parsed` for each and skips it, which is
harmless. So the commit messages inside your branch are what determine the next
version and the changelog; a conventional PR title alone changes nothing.

## Step by step

1. Merge your work into `main` through a pull request with a conventional title.
2. Release-please opens or updates a pull request titled
   `chore(main): release X.Y.Z`. It keeps that PR in sync with `main` on every push,
   so there is nothing to do while work continues.
3. Review that release pull request. It should bump `version.txt`,
   `.release-please-manifest.json`, `README.md`, the parent `pom.xml` and **every**
   module `pom.xml`, and add the new `CHANGELOG.md` section.
4. Merge it. Release-please then creates the tag `vX.Y.Z` and the GitHub release.

That is the whole release. No tag is pushed by hand, and no `mvn deploy` runs.

## Adding a Maven module

Any new module must be added to `extra-files` in `release-please-config.json`.
A module pom that is not listed keeps the previous version while the parent pom
moves on, and Maven then refuses the build: it rejects `../pom.xml` because the
declared parent version no longer matches, and cannot resolve the old
`org.eqasim:eqasim:pom:<old>` from the configured `osgeo` and `matsim`
repositories. The result is a `FATAL Non-resolvable parent POM` that aborts the
reactor.

This is easy to miss locally, because a `~/.m2` that still holds the previous
version resolves the stale parent and the build succeeds. CI runs `mvn test`, which
never installs project artifacts, so it always sees the real failure. To reproduce
it the way CI does, build with an empty local repository:

```bash
mvn -B -Dmaven.repo.local=/tmp/empty-repo validate
```

The `braunschweig` module was missing from that list from the moment it was renamed
out of upstream's `bavaria`, which is what motivated writing this section down.

## Why this fork publishes nothing

Upstream deploys `org.eqasim:eqasim` to
[packagecloud.io/eth-ivt/eqasim](https://packagecloud.io/eth-ivt/eqasim). This fork
inherited both that deploy target (`distributionManagement` in `pom.xml`) and the
upstream `org.eqasim` coordinates, so publishing from here would push fork artifacts
into a registry we do not own, under the coordinates upstream needs for its own
releases. The build and deploy steps were therefore removed from the release
workflow, and no `PACKAGECLOUD_TOKEN` is configured.

Consumers build this fork from source instead — the eqasim-bs pipeline points
`eqasim_source_path` at a local checkout and builds the `braunschweig` module with
Maven directly.

`.github/workflows/packagecloud.yml` still exists and still targets the upstream
registry. It runs only on manual `workflow_dispatch`, so it cannot fire by accident.
Do not dispatch it.

## Downstream effect of a release

A release changes the built artifact name, because the module version drives it:
`braunschweig-X.Y.Z.jar`. The eqasim-bs pipeline resolves that jar by version
(`eqasim_version`, see `matsim/runtime/eqasim.py`) and raises a `RuntimeError` when
it is absent, so `eqasim_version` there has to move together with a release here.
