# Contributing

The UseSense Android SDK is proprietary software. External code contributions are not accepted at this time.

## Bug Reports

If you encounter a bug, please report it via:

- **GitHub Issues**: Open an issue in this repository with steps to reproduce, expected behavior, actual behavior, and device/OS details.
- **Email**: support@usesense.ai

## Feature Requests

Feature requests are welcome via GitHub Issues or email. Please describe the use case and expected behavior.

## Security Vulnerabilities

If you discover a security vulnerability, **do not** open a public issue. Instead, follow the process described in [SECURITY.md](SECURITY.md).

---

## Maintainer notes: build system & release process

This section is for internal maintainers. External contributors can ignore it.

### Where the version lives

The SDK version is a single source of truth: `sdkVersion` in `gradle.properties` at the repo root. Every other version string in the build system is derived from it:

- `publish.gradle.kts` reads `val sdkVersion: String by project` and uses it as the Maven publication coordinate (`ai.usesense:sdk:<sdkVersion>`)
- `README.md` install snippets show the same version in copy-paste form; update these by hand when bumping
- `CHANGELOG.md` gets a new top-level `## [X.Y.Z] - YYYY-MM-DD` entry per release

### Releasing a new version

1. Create a release-prep PR that bumps `gradle.properties` → `sdkVersion=X.Y.Z`, updates the README install snippets, and adds the `[X.Y.Z]` CHANGELOG entry
2. Merge the PR
3. Tag the merge commit: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`
4. `release-android.yml` takes over: extracts the version from the tag, `sed`-overrides `gradle.properties` at CI time (so the workflow doesn't need the PR to have updated gradle.properties to work), builds the release AAR + sources JAR + Dokka HTML, runs tests and lint, checks whether the version is already on GitHub Packages, publishes if not, renames the AAR to `usesense-sdk-<version>.aar`, and creates the GitHub Release with the AAR + Dokka zip attached
5. JitPack builds on demand the first time anyone requests `com.github.qudusadeyemi:usesense-android-sdk:vX.Y.Z`; no action needed on our side

If the workflow fails after the publish step, re-running is safe: the `Check if version already published` step will skip the publish and only the release-creation + artifact-upload steps will re-run. Same pattern as the iOS workflow after qudusadeyemi/usesense-ios-sdk#34.

### The CI `sed`-override, explained

`release-android.yml` has a `Set SDK version` step that does:

```bash
sed -i "s/sdkVersion=.*/sdkVersion=${{ steps.version.outputs.version }}/" gradle.properties
```

This means you do **not** have to update `gradle.properties` before tagging — the tag is authoritative. The committed value in `gradle.properties` only affects local `./gradlew` runs (and JitPack builds, which pass `-PsdkVersion=${VERSION#v}` via `jitpack.yml`). Keeping the committed value approximately current (bump it in the release-prep PR) is still recommended so that local builds produce sensibly-named artifacts, but it isn't load-bearing for the release itself.

### Three distribution channels, two of them active

| Channel | Coordinate | Status | Activation path |
|---------|-----------|--------|-----------------|
| **GitHub Packages** | `ai.usesense:sdk:<version>` | Active | `release-android.yml` → `publishReleasePublicationToGitHubPackagesRepository` |
| **JitPack** | `com.github.qudusadeyemi:usesense-android-sdk:v<version>` | Active | Auto-built on first request via `jitpack.yml` at repo root |
| **Maven Central** | `ai.usesense:sdk:<version>` | **Not yet** — needs manual Sonatype setup | See the "Future: Maven Central publishing" section below |

### Future: Maven Central publishing

Activating Maven Central requires coordinated setup between Sonatype, the GitHub repo's Actions secrets, and the Gradle build. Here's the runbook for when we're ready:

**1. Create a Sonatype Central account.**  Sign up at https://central.sonatype.com and claim the `ai.usesense` groupId. You'll need to verify ownership of `usesense.ai` by adding a `TXT` record to DNS (Sonatype will tell you the exact value to use), OR use `io.github.qudusadeyemi` as the groupId if domain verification is too much hassle. **Strongly prefer `ai.usesense`** since that's what's already published on GitHub Packages and switching groupIds mid-flight would break every existing integrator.

**2. Generate a GPG signing key.**

```bash
gpg --full-generate-key          # RSA 4096, no expiry, "UseSense SDK <support@usesense.ai>"
gpg --list-secret-keys --keyid-format=long
gpg --armor --export-secret-keys <KEY_ID> > signing-key.asc
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

**3. Add four secrets to the GitHub repo** at Settings → Secrets and variables → Actions:
- `SONATYPE_USERNAME` — the user token username from central.sonatype.com
- `SONATYPE_PASSWORD` — the user token password from central.sonatype.com
- `SIGNING_KEY` — contents of `signing-key.asc` (the full armored block)
- `SIGNING_PASSWORD` — the passphrase you gave the GPG key in step 2

**4. Swap `publish.gradle.kts` over to the vanniktech maven-publish plugin**, which handles Central + signing + the GitHub Packages repo in one config:

```kotlin
// Add to root build.gradle.kts plugins block:
id("com.vanniktech.maven.publish") version "0.34.0" apply false

// In sdk/build.gradle.kts plugins block:
id("com.vanniktech.maven.publish")

// Replace the contents of publish.gradle.kts with:
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("ai.usesense", "sdk", sdkVersion)

    pom {
        name.set("UseSense Android SDK")
        description.set("Native Android SDK for human presence verification.")
        inceptionYear.set("2026")
        url.set("https://github.com/qudusadeyemi/usesense-android-sdk")
        licenses { license { name.set("Proprietary"); url.set("...") } }
        developers { developer { id.set("usesense"); name.set("UseSense"); email.set("support@usesense.ai") } }
        scm { url.set("https://github.com/qudusadeyemi/usesense-android-sdk") }
    }
}
```

**5. Add a `Publish to Maven Central` step to `release-android.yml`:**

```yaml
- name: Publish to Maven Central
  if: steps.check_published.outputs.already_published != 'true'
  env:
    ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.SONATYPE_USERNAME }}
    ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.SONATYPE_PASSWORD }}
    ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_KEY }}
    ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_PASSWORD }}
  run: ./gradlew :sdk:publishToMavenCentral --no-configuration-cache
```

**6. Update the README's Installation section** to recommend Maven Central first, JitPack second, GitHub Packages third.

**7. Draft a Sonatype staging release and smoke-test it** before switching the recommended channel. Central enforces a review step; never push a tag until you've verified the staging artifact is consumable.

Steps 1–3 are manual and must happen before steps 4–7 land as a PR. Steps 4–7 are fully implementable in code once the secrets exist.

### Cross-checking with iOS and Web SDK releases

The iOS SDK (`qudusadeyemi/usesense-ios-sdk`) and Web SDK (`qudusadeyemi/usesense-web-sdk`) ship on the same date as the Android SDK when we cut a coordinated release. Version numbers are aligned across all three; a feature that lands in all three platforms gets the same X.Y.Z. Doc-only or infra-only releases on one platform are fine to have independent version numbers (e.g., iOS v4.2.1 was doc-only and had no Android counterpart until this v4.2.0).

### MediaPipe parity workflow

`.github/workflows/mediapipe-parity-check.yml` enforces that the bundled `face_landmarker.task` asset and the `MediaPipeModelInfo.kt` constant stay in lockstep. It runs on any PR that touches either file and fails the check if the SHA-256 mismatches. Do not suppress this workflow when landing a new MediaPipe model version; bump the model bytes and the constant in the same commit.

### Testing a release workflow change locally

`release-android.yml` is too integrated with GitHub Actions context to run end-to-end locally, but the core Gradle tasks are:

```bash
./gradlew :sdk:assembleRelease :sdk:releaseSourcesJar :sdk:dokkaHtml :sdk:test :sdk:lintRelease
```

Any failure in those tasks will also fail the CI workflow. If you're modifying the publish step specifically, `./gradlew :sdk:publishReleasePublicationToMavenLocal` is a safe dry-run (publishes to your local `~/.m2/repository` instead of GitHub Packages).
