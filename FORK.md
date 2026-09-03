# Alpian fork of bigquery-connector-for-apache-kafka

This repository is a fork of
[Aiven-Open/bigquery-connector-for-apache-kafka](https://github.com/Aiven-Open/bigquery-connector-for-apache-kafka)
carrying a small set of Alpian-specific patches.

## Layout

| Branch | Purpose |
| --- | --- |
| `main` | Untouched mirror of upstream `main`. **Never commit here.** Used only to pull upstream changes in. |
| `Alpian` | Long-lived patch branch. All Alpian work targets this. |

Keeping `main` pristine means `git diff main..Alpian` and `git log <upstream-tag>..Alpian`
are always an exact, readable statement of what we changed.

`Alpian` should be the repository's default branch, so clones, the PR base
picker and the Actions workflow list all land on it rather than on the upstream
mirror:

```bash
gh repo edit alpian-swiss/bigquery-connector-for-apache-kafka --default-branch Alpian
```

## Remotes

```
origin    git@github.com:alpian-swiss/bigquery-connector-for-apache-kafka.git   (fetch + push)
upstream  https://github.com/Aiven-Open/bigquery-connector-for-apache-kafka.git (fetch only)
```

`upstream`'s push URL is deliberately set to a bogus value so an accidental
`git push upstream` cannot reach the Aiven project. It also only fetches
`main` plus tags, not Aiven's ~16 in-flight development branches.

To reproduce this setup on a fresh clone:

```bash
git clone git@github.com:alpian-swiss/bigquery-connector-for-apache-kafka.git
cd bigquery-connector-for-apache-kafka
git remote add upstream https://github.com/Aiven-Open/bigquery-connector-for-apache-kafka.git
git config remote.upstream.fetch '+refs/heads/main:refs/remotes/upstream/main'
git config remote.upstream.tagOpt '--tags'
git remote set-url --push upstream DISABLED_read_only_upstream
git fetch upstream
```

> **Careful:** because this is a GitHub fork, the *"Create pull request"* button
> defaults the base repository to **Aiven-Open**. Always check the base repo and
> base branch (`alpian-swiss` / `Alpian`) before opening a PR.

## Our patches

| Commit | Change |
| --- | --- |
| `Alpian gcp pii tags` | Adds a `policyTag` connector config. When a Kafka Connect field schema carries a `PII` parameter, the generated BigQuery field gets that BigQuery policy tag attached, enabling column-level access control. |
| version marker | Project version is `<upstream>-alpian.N` so our builds never collide with an upstream release. |

Keep the patch count low and each commit self-contained — every one of them has
to be replayed on each upstream rebase.

## Rebasing onto a new upstream release

We **rebase**, never merge. That keeps the patch series legible and replayable.

```bash
# 1. Refresh the upstream mirror
git fetch upstream
git checkout main
git merge --ff-only upstream/main
git push origin main

# 2. Replay our patches on top of the new upstream release tag
git checkout Alpian
git rebase --onto v2.15.0 <previous-upstream-base> Alpian
#   ...resolve conflicts, then:
mvn -ntp -P ci clean install -DskipITs   # must be green locally

# 3. Re-apply the version marker and publish
mvn versions:set -DnewVersion=2.15.0-alpian.1 -DgenerateBackupPoms=false
git commit -am "Set fork version 2.15.0-alpian.1"
git push --force-with-lease origin Alpian
```

`--force-with-lease` (not `--force`) so you cannot clobber someone else's push.
Because `Alpian` is force-pushed by design, **do not** add a branch protection
rule that forbids force-pushes on it.

The version marker conflicts on every rebase. That is intentional and cheap —
it is three lines of `pom.xml`, and it guarantees a locally built jar has the
same identity as a CI-built one.

## Building and releasing

CI lives in [`.github/workflows/alpian-build.yml`](.github/workflows/alpian-build.yml)
and runs on every push and PR to `Alpian`.

A full local build is exactly two commands:

```bash
# compile, checkstyle, RAT, 398 unit tests, install modules into ~/.m2
mvn -ntp -P ci --batch-mode clean install -DskipITs

# then build the distribution archives
mvn -ntp -P ci --batch-mode -f kcbq-connector \
  clean package assembly:single@release-artifacts -DskipTests
```

Neither command is arbitrary. Three traps, all of which cost time to rediscover:

- **`-DskipITs` is mandatory.** `install` runs *past* the `verify` phase, which
  triggers the failsafe `integration` group. Those tests demand live GCP
  credentials (`KCBQ_TEST_KEYFILE` and friends — see `manual.yml`) and error out
  without them. There is a `skip.unit.tests` property in the pom but no
  integration equivalent, so failsafe's own `-DskipITs` is the switch to use.
- **`assembly:single` is bound to no lifecycle phase**, so a plain `mvn package`
  produces *no archives at all*. You cannot fix this by appending the goal to
  the reactor command either — it then runs against the parent too and fails
  with `No assembly descriptors found`. It has to be a separate,
  connector-scoped invocation.
- **The assembly goal must run in the same invocation that builds the jar.** Run
  on its own it logs `Cannot include project artifact: kcbq-connector` as a mere
  *warning* and cheerfully emits archives with the connector jar missing — a
  plugin that loads but silently does nothing. CI asserts the jar is present for
  exactly this reason; keep that check.

The deliverable for a Kafka Connect plugin is the assembly archive, not the
bare jar:

```
kcbq-connector/target/bigquery-connector-for-apache-kafka-<version>.tar
kcbq-connector/target/bigquery-connector-for-apache-kafka-<version>.zip
```

Unzip either one into the Connect worker's `plugin.path`.

To cut a release, tag the branch. The tag must match `v*-alpian*` to trigger the
release and publish jobs:

```bash
git tag v2.14.0-alpian.1
git push origin v2.14.0-alpian.1
```

That attaches both archives to a GitHub Release and deploys the Maven artifacts
to GitHub Packages under `com.wepay.kcbq`.

## Upstream workflows are disabled on this fork

Aiven's release machinery is inherited by the fork and would act outward under
the `alpian-swiss` name — cutting tags, opening PRs, publishing Pages. These
workflows are disabled at the repository level (Actions → workflow → *Disable*),
which survives rebases without a code change:

| Workflow | State |
| --- | --- |
| `build_site.yml` | disabled |
| `create_release.yml` | disabled |
| `create_release_pr.yml` | disabled |
| `prs_and_commits.yml` | left active — only triggers on `main`, which we never commit to |

Nothing has ever run in Actions on this fork, so none of them has fired yet.

If a rebase or an upstream sync appears to re-enable any of them, disable them
again. `nightly.yml`, `manual.yml` and `release_pr_workflow.yml` are not
registered as separate workflows here and are harmless anyway: forks do not
inherit secrets, so they cannot reach a GCP project.

Note that GitHub registers workflows from the *default* branch, but a workflow
file living on `Alpian` still runs for pushes and PRs targeting `Alpian` — which
is how `alpian-build.yml` works today.

## Contributing a patch back upstream

`policyTag` is generally useful and would be welcome upstream, but the current
implementation changes the signature of the public
`BigQuerySchemaConverter(boolean, boolean)` constructor rather than overloading
it. Upstream would need that as a backwards-compatible overload, and
`POLICY_TAG_DOC` filled in, before it could be accepted. Landing it upstream
would remove it from our rebase burden permanently.
