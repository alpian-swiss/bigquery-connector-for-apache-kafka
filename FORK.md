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

Based on **`v2.15.0`**; latest release **`v2.15.0-alpian.3`**.

| Change | What it covers |
| --- | --- |
| The feature | A `policyTag` connector config. When a Kafka Connect field schema carries a `PII` parameter, the generated BigQuery field gets that policy tag attached, enabling column-level access control. |
| The infrastructure | The version marker (`<upstream>-alpian.N`, so our builds never collide with an upstream release), `alpian-build.yml`, and this file. |

Only **three upstream files** are modified at all — the two Java files the
feature needs, plus the poms for the version marker. Everything else the fork
adds is a new file, and new files never conflict.

### Keep the patch additive

This is the single most important rule for keeping the fork cheap. The patch is
deliberately written so it *adds* rather than *changes*:

- `BigQuerySchemaConverter` keeps its original `(boolean, boolean)` constructor
  as an overload delegating to the new `(boolean, boolean, String)` with an
  empty policy tag. **Nothing upstream calls the new form**, so no existing call
  site — in particular none of upstream's tests — needs touching.
- The deprecated 4-argument constructor is left completely alone.
- Our tests live in their own file, `BigQuerySchemaConverterPolicyTagTest`,
  rather than inside upstream's `BigQuerySchemaConverterTest`. A new file cannot
  conflict.
- `policyTag` defaults to `""`, and the tagging code returns early when it is
  empty or null, so a converter built without a policy tag behaves exactly as
  upstream's does.

Restructuring the patch this way is what took the `v2.14.0` → `v2.15.0` port
from 10 conflict hunks across 4 files down to 2 hunks across 2 files. Resist any
change that edits an existing upstream signature, test, or call site.

## Always base the patches on a release tag

Our patch sits on the `v2.15.0` tag commit (`276d1380`) exactly — not on some
mid-development commit of upstream `main`. Verify with:

```bash
test "$(git merge-base upstream/main Alpian)" = "$(git rev-parse v2.15.0^{commit})" \
  && echo "based on the v2.15.0 tag"
```

Keep it that way. Basing on a tag means the fork always corresponds to a
shipped, tested upstream release, and `git rebase --onto <next-tag> <this-tag>`
is a single well-defined operation.

## What the v2.14.0 -> v2.15.0 port cost (and why)

Recorded because the same pattern will recur on every upstream release.

The raw `v2.14.0..v2.15.0` diff touches the files our patch cares about by
~2400 lines, which looks alarming. It was not. Upstream added the Spotless
Google-Java-Format plugin (`d58ae257`) and reformatted the codebase, so
**every conflict was a formatting collision, not a semantic one** — the API our
patch hooks into was unchanged. Resolving meant taking upstream's formatting and
re-applying our small addition on top.

The original patch produced 10 conflict hunks across 4 files. Rewriting it in
the additive form described above brought that to 2 hunks across 2 files, and
those 2 were also formatting-only. The two test files dropped out of the patch
entirely.

Expect the same next time: a large-looking diff, a handful of formatting-only
conflicts, and no semantic work — provided the patch stays additive and
Spotless-formatted.

## Cutting a new jar on the same upstream base

Use this when you have changed **our** code (or the pipeline) but upstream has
not released anything new. There is no rebase and no `main` sync — you are only
incrementing the fork's own revision: `2.15.0-alpian.1` → `2.15.0-alpian.2`.

**Just a jar to test with, nothing published.** Two commands, no version bump:

```bash
mvn -ntp -P ci --batch-mode clean install -DskipITs
mvn -ntp -P ci --batch-mode -f kcbq-connector \
  clean package assembly:single@release-artifacts -DskipTests
```

Artifacts land in `kcbq-connector/target/` — the `.zip`/`.tar` for a Connect
worker, `kcbq-connector-<version>.jar` for the bare jar.

**A released, shareable jar.** Bump the suffix, then tag:

```bash
# 1. make your change on Alpian and commit it

# 2. bump the fork revision -- the upstream part stays 2.15.0
mvn versions:set -DnewVersion=2.15.0-alpian.2 -DgenerateBackupPoms=false
git commit -am "Set fork version 2.15.0-alpian.2"

# 3. verify locally (same three checks as the port runbook, step 4)
mvn -ntp -P ci --batch-mode clean install -DskipITs
mvn -ntp -P ci --batch-mode -f kcbq-connector \
  clean package assembly:single@release-artifacts -DskipTests
VER=2.15.0-alpian.2
unzip -l kcbq-connector/target/bigquery-connector-for-apache-kafka-$VER.zip \
  | grep "kcbq-connector-$VER.jar" || echo "BROKEN: connector jar missing"

# 4. push -- a plain fast-forward, no force needed
git push origin Alpian

# 5. tag once CI is green
git tag -a v2.15.0-alpian.2 -m "Alpian fork release 2.15.0-alpian.2"
git push origin v2.15.0-alpian.2
```

Then collect it exactly as in step 8 of the port runbook.

> **You must bump the suffix — never re-tag or re-publish an existing version.**
> `v2.15.0-alpian.1` is already a GitHub Release and already sits in GitHub
> Packages. Maven coordinates are meant to be immutable, the registry rejects
> re-publishing a version that exists, and a worker that already pulled
> `2.15.0-alpian.1` has no way to tell that its contents changed. Increment `N`.

## Runbook: porting to a new upstream release

Worked example: **upstream releases `v2.16.0`, we ship `2.16.0-alpian.1`.**
Substitute the real numbers throughout. We **rebase, never merge**, so the patch
series stays legible and replayable.

Read the whole runbook before starting — step 3 has the only part that needs
judgement.

### 1. Fetch upstream and confirm the tag exists

```bash
cd bigquery-connector-for-apache-kafka
git fetch upstream --tags

# the new release tag must exist locally now
git tag --list 'v2.16.0'

# confirm what we are currently based on (should print the current base tag)
git describe --tags --abbrev=0 $(git merge-base upstream/main Alpian)
```

If `git tag --list` prints nothing, the release is not tagged yet — stop and
wait. Never rebase onto `upstream/main`; only onto a release tag.

### 2. Fast-forward the `main` mirror

`main` is a pristine copy of upstream. It exists only so the diff to `Alpian`
is meaningful. Never commit to it.

```bash
git checkout main
git merge --ff-only upstream/main
git push origin main
```

`--ff-only` is deliberate: if it refuses, someone has committed to `main` and
that needs sorting out before anything else. No workflow fires on this push —
every inherited workflow is disabled (see the next section).

### 3. Rebase `Alpian` onto the new tag

Always take a backup ref first. The rebase rewrites history and is force-pushed;
this is your undo.

```bash
git branch Alpian-v2.15.0-archive Alpian    # name it after the OLD base
git checkout Alpian
git rebase --onto v2.16.0 v2.15.0 Alpian
#                 ^new tag  ^old tag (the base you confirmed in step 1)
```

Expect conflicts in **two Java files** and, on the second commit, the **three
poms**. Nothing else should conflict — our tests are in their own file and the
workflow and this document are new files.

**3a. The two Java files.** Conflicts here are almost always upstream
reformatting colliding with our addition, not real semantic change. Resolve by
taking upstream's side and re-applying our small addition in upstream's
formatting:

| File | What we add |
| --- | --- |
| `BigQuerySchemaConverter.java` | `PolicyTags`/`Collections` imports, `PII_SCHEMA_PARAMETER`, the `policyTag` field, the 3-arg constructor (2-arg delegates to it), the `setPolicyTags` call and method |
| `BigQuerySinkConfig.java` | `POLICY_TAG_CONFIG`/`_DEFAULT`/`_TYPE`/`_IMPORTANCE`/`_DOC` constants, the `.define(POLICY_TAG_…)` block, and `getString(POLICY_TAG_CONFIG)` in `getSchemaConverter()` |

`git checkout --ours <file>` during a rebase gives you the **new upstream**
version of a file — useful when you want to start clean and re-add our bit
by hand.

Then normalise formatting and stage:

```bash
mvn spotless:apply
git add kcbq-connector/src
git rebase --continue
```

`spotless:apply` is not optional — `spotless:check` runs in the build and fails
on anything not in Google Java Format.

**3b. The poms (second commit).** Take upstream's, then re-apply both of our
pom changes — the version marker *and* the `FORK.md` RAT exclusion, which live
in the same commit and are both lost by `--ours`:

```bash
git checkout --ours pom.xml kcbq-api/pom.xml kcbq-connector/pom.xml

# re-add the RAT exclusion next to the other *.md excludes in the root pom
#   <inputExclude>FORK.md</inputExclude>
$EDITOR pom.xml

# re-apply the version marker across all three poms
mvn versions:set -DnewVersion=2.16.0-alpian.1 -DgenerateBackupPoms=false

git add pom.xml kcbq-api/pom.xml kcbq-connector/pom.xml
git rebase --continue
```

Forgetting the RAT exclusion makes the build fail with a licence-header error on
`FORK.md`. Forgetting the version marker is worse: the build succeeds and
produces artifacts with upstream's exact coordinates.

### 4. Verify locally — before pushing anything

```bash
# 1) compile, spotless, checkstyle, RAT, unit tests, install into ~/.m2
mvn -ntp -P ci --batch-mode clean install -DskipITs

# 2) build the distribution archives
mvn -ntp -P ci --batch-mode -f kcbq-connector \
  clean package assembly:single@release-artifacts -DskipTests

# 3) assert the connector jar is actually inside the archive
VER=2.16.0-alpian.1
unzip -l kcbq-connector/target/bigquery-connector-for-apache-kafka-$VER.zip \
  | grep "kcbq-connector-$VER.jar" \
  || echo "BROKEN: connector jar missing from the archive"
```

All three must pass. Step 3 is not paranoia — see the trap list below.

### 5. Update this document

Genuinely part of the port, and easy to forget:

- **Our patches** → change "Currently based on" to `v2.16.0`
- **Always base the patches on a release tag** → new tag and commit hash
- **Runbook** → bump the example tags for next time

### 6. Push the branch

```bash
git push --force-with-lease origin Alpian
```

`--force-with-lease`, never `--force`: it refuses if someone else has pushed in
the meantime. Because `Alpian` is force-pushed by design, **do not** add a
branch protection rule forbidding force-pushes.

This triggers `alpian-build.yml`, which repeats step 4 in CI and uploads the
archives as build artifacts. Wait for it to go green:

```bash
gh run watch --repo alpian-swiss/bigquery-connector-for-apache-kafka
```

### 7. Tag the release

Do this **last**, and only once step 6 is green. The tag **must** match
`v*-alpian*` or the release and publish jobs do not fire, and it must match the
pom version exactly — the workflow reads the version from the pom, so a
mismatched tag produces a Release whose title disagrees with its contents.

```bash
# check both before tagging
mvn -ntp -q --batch-mode org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate \
  -Dexpression=project.version -DforceStdout   # must print 2.16.0-alpian.1

git tag -a v2.16.0-alpian.1 -m "Alpian fork release 2.16.0-alpian.1"
git push origin v2.16.0-alpian.1
```

That run additionally:

1. creates a **GitHub Release** on `alpian-swiss` with both archives attached,
2. runs the `publish` job, deploying the Maven artifacts to **GitHub Packages**.

Both steps authenticate with the built-in `GITHUB_TOKEN`. **No org secret is
needed to publish** — that token is already scoped to this repository's own
package registry, which is all a publish requires. This mirrors the
`GITHUB_ACTOR` / `GITHUB_TOKEN` credentials block in Alpian's Gradle
`maven-publish` config, and it is the combination that published
`v2.15.0-alpian.1`.

> **Once a release tag is pushed, stop rewriting the commits it points at.**
> Until this point `Alpian` is force-pushed freely. A tag pins a specific commit
> and that commit is now a published artifact: rewriting it strands the tag on
> an unreachable commit, so the Release no longer corresponds to anything on the
> branch. After tagging, add commits on top instead — and if you genuinely must
> rewrite, delete and re-cut the tag as a new `-alpian.N+1` rather than moving
> an existing one.

### 8. Get the jar

**For a Kafka Connect worker — the normal path, and the verified one.** The
deliverable is the assembly archive, not the bare jar: it contains the connector
jar plus every runtime dependency.

```bash
gh release download v2.16.0-alpian.1 \
  --repo alpian-swiss/bigquery-connector-for-apache-kafka \
  --pattern '*.zip'

unzip -d /opt/kafka/plugins/bigquery-connector \
  bigquery-connector-for-apache-kafka-2.16.0-alpian.1.zip
```

Point the worker's `plugin.path` at `/opt/kafka/plugins`, restart it, and
confirm the connector version:

```bash
curl -s localhost:8083/connector-plugins \
  | grep -o '"version":"[^"]*"' | sort -u
```

It should report `2.16.0-alpian.1` — that version string is the whole point of
the marker, and it is how you prove a worker is running our build and not
upstream's.

**As a Maven dependency (GitHub Packages).** Only needed if something compiles
against the connector.

> **Authentication is always required, even though this repo is public.**
> Unlike most registries, `maven.pkg.github.com` has no anonymous read. Verified:
> an unauthenticated fetch of a published `.pom` returns **401**, the same fetch
> with a token returns **200**. Every consumer — CI job or laptop — needs a
> credential. There is no "it's public, just add the repository" path.

Note the asymmetry, because it is easy to get backwards:

| Direction | Credential | Why |
| --- | --- | --- |
| **Publishing** (this repo → its own registry) | built-in `GITHUB_TOKEN` | already scoped to this repository's packages; no secret to manage |
| **Consuming** (another repo → this registry) | org service account | a repo's own `GITHUB_TOKEN` cannot read *another* repository's packages |

So for consumers use the **org service account** that Alpian's other pipelines
already carry — the `PACKAGES_TOKEN` in those workflows — rather than a personal
PAT:

```yaml
env:
  MAVEN_USERNAME: ALP-ACC-SVC-Github
  MAVEN_PASSWORD: ${{ secrets.ALP_ACC_SVC_CI_TOKEN }}
```

with `setup-java` pointed at those variable *names* — it writes a
`settings.xml` holding `${env.MAVEN_USERNAME}` / `${env.MAVEN_PASSWORD}`
placeholders that Maven interpolates at resolve time:

```yaml
- uses: actions/setup-java@v6
  with:
    distribution: temurin
    java-version: 17
    server-id: github-alpian
    server-username: MAVEN_USERNAME
    server-password: MAVEN_PASSWORD
```

Then declare the repository and the dependency:

```xml
<repository>
  <id>github-alpian</id>
  <url>https://maven.pkg.github.com/alpian-swiss/bigquery-connector-for-apache-kafka</url>
</repository>

<dependency>
  <groupId>com.wepay.kcbq</groupId>
  <artifactId>kcbq-connector</artifactId>
  <version>2.16.0-alpian.1</version>
</dependency>
```

The `<server>` id must equal the `<repository>` id, or Maven resolves anonymously
and gets the 401.

For a **developer machine**, put the same server block in `~/.m2/settings.xml`
using the service-account credential or a personal PAT with `read:packages` —
never in a `settings.xml` inside a repository, where it would get committed:

```xml
<servers>
  <server>
    <id>github-alpian</id>
    <username>ALP-ACC-SVC-Github</username>
    <password>${env.PACKAGES_TOKEN}</password>
  </server>
</servers>
```

`${env.PACKAGES_TOKEN}` keeps the token out of the file itself — export it from
your shell or a secret manager. Prefer the service account over a personal PAT so
access does not follow one individual's account.

**Verified working** as of `v2.15.0-alpian.1`. There was a documented concern
that GitHub Packages requires the groupId to correspond to the repository owner,
which would have rejected upstream's `com.wepay.kcbq`. It does not — the deploy
succeeded and all three modules resolve:

| Artifact | `.pom` | `.jar` |
| --- | --- | --- |
| `com.wepay.kcbq:kcbq-connector` | 200 | 200 |
| `com.wepay.kcbq:kcbq-api` | 200 | 200 |
| `com.wepay.kcbq:kcbq-parent` | 200 | n/a — `<packaging>pom</packaging>` |

The published jar stamps `Implementation-Version: 2.15.0-alpian.1` in its
manifest and contains both `BigQuerySchemaConverter` constructors, so the
groupId does **not** need relocating. No pom patch required.

To sanity-check the registry directly without a full Maven resolve — useful
because a first transitive resolve pulls well over 150 MB of Google Cloud
dependencies and is slow:

```bash
TOK=$(gh auth token)
curl -sL -o /dev/null -w '%{http_code}\n' -u "x-access-token:$TOK" \
  https://maven.pkg.github.com/alpian-swiss/bigquery-connector-for-apache-kafka/com/wepay/kcbq/kcbq-connector/2.16.0-alpian.1/kcbq-connector-2.16.0-alpian.1.jar
```

`x-access-token` works as the username with a `gh` token; a GitHub username with
a `read:packages` PAT works too. Never commit either into a repo `settings.xml`.

### 9. Tidy up

```bash
# once you trust the new base, drop the previous backup ref
git branch -D Alpian-v2.14.0-archive
```

Keep the most recent archive branch; delete older ones.

---

## Build traps worth knowing

The two build commands in step 4 look redundant. They are not. Each guards a
real failure mode that cost time to find once already:

- **`-DskipITs` is mandatory.** `install` runs *past* the `verify` phase, which
  triggers the failsafe `integration` group. Those tests demand live GCP
  credentials (`KCBQ_TEST_KEYFILE` and friends) and error out without them.
  There is a `skip.unit.tests` property in the pom but no integration
  equivalent, so failsafe's own `-DskipITs` is the switch to use.
- **`assembly:single` is bound to no lifecycle phase**, so a plain `mvn package`
  produces *no archives at all*. You cannot fix that by appending the goal to
  the reactor command either — it then also runs against the parent module and
  fails with `No assembly descriptors found`. It has to be a separate,
  connector-scoped invocation.
- **The assembly goal must run in the same invocation that builds the jar.** Run
  standalone it logs `Cannot include project artifact: kcbq-connector` as a mere
  *warning* and cheerfully emits archives with the connector jar missing — a
  plugin that loads and silently does nothing. This is why step 4 asserts the
  jar is present, and why CI does too. Keep both checks.

## Upstream workflows are disabled on this fork

Aiven's release machinery is inherited by the fork and would act outward under
the `alpian-swiss` name — cutting tags, opening PRs, publishing Pages. These
workflows are disabled at the repository level (Actions → workflow → *Disable*),
which survives rebases without a code change:

| Workflow | Triggers | State |
| --- | --- | --- |
| `alpian-build.yml` | push/PR `Alpian`, `v*-alpian*` tags | **active — ours** |
| `build_site.yml` | push `main`, dispatch | disabled |
| `create_release.yml` | dispatch | disabled |
| `create_release_pr.yml` | dispatch | disabled |
| `prs_and_commits.yml` | push/PR `main`, dispatch | disabled |

`alpian-build.yml` is the only workflow that runs on this fork. Nothing else has
ever run here.

Why this matters rather than being mere tidiness: forks do not inherit secrets,
so none of Aiven's workflows can reach a GCP project — but they *do* get a
`GITHUB_TOKEN` scoped to **our** repository. `create_release.yml` could
therefore cut tags and Releases under `alpian-swiss`, and `build_site.yml`
requests `pages: write` and runs `actions/deploy-pages`, which would publish
Aiven's docs as an Alpian GitHub Pages site on every push to `main`.

`prs_and_commits.yml` is disabled not because it is dangerous — it only builds
upstream's own code — but because it fires on the `git push origin main` in
step 2 of the runbook and tells us nothing: we never modify `main`.

Two mechanics to keep in mind:

- The disable is a **repository-level** setting keyed by the workflow's file
  path. It survives rebases and force-pushes, which is exactly why we use it
  instead of deleting the files — deleting them would conflict on every single
  upstream rebase, forever. If an upstream sync appears to re-enable one,
  disable it again rather than removing the file.
- GitHub registers workflows from the *default* branch, but `push` and
  `pull_request` workflows run from the file as it exists on the event's own
  ref. That is why `alpian-build.yml` works from the `Alpian` branch even while
  `main` is the default branch. Only `schedule`/`cron` requires the default
  branch — and as of the `v2.15.0` base no workflow here has one, so nothing fires
  on a timer.

## Contributing a patch back upstream

`policyTag` is generic and would plausibly be welcome upstream, and **the patch
is now in a shape upstream could accept**: the constructor change is a
backwards-compatible overload, `POLICY_TAG_DOC` is filled in, the tests are
self-contained, and nothing existing is modified. The two blockers that
previously ruled this out are gone.

Worth opening as a PR against `Aiven-Open/bigquery-connector-for-apache-kafka`.
If it lands, the fork's Java patch disappears entirely and only the version
marker and build pipeline remain — at which point the fork costs nothing to
maintain.

The one thing to tidy first: `PII` as the schema-parameter name is ours by
convention and is not namespaced. Upstream would likely want it configurable, or
at least prefixed, rather than a bare `PII` key.
