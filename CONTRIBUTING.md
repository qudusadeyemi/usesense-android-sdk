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

### Three active distribution channels

| Channel | Coordinate | Auth | Publish path |
|---------|-----------|------|--------------|
| **Maven Central** | `ai.usesense:sdk:<version>` | None (recommended) | `release-android.yml` → `publishAndReleaseToMavenCentral` (vanniktech plugin, Sonatype Central Portal, in-memory GPG signing) |
| **JitPack** | `com.github.qudusadeyemi:usesense-android-sdk:v<version>` | None | Auto-built on first request via `jitpack.yml` at repo root |
| **GitHub Packages** | `ai.usesense:sdk:<version>` | GitHub PAT with `read:packages` | `release-android.yml` → `publishMavenPublicationToGitHubPackagesRepository` |

All three target the same `release` Maven publication built by
vanniktech's `AndroidSingleVariantLibrary` configuration in
`sdk/build.gradle.kts`. Maven Central publish is gated by a
`Check Sonatype credentials` step in the workflow: if the
`SONATYPE_USERNAME` secret is absent, the Maven Central step is
skipped and GitHub Packages + JitPack still publish normally.

### Maven Central: how to rotate the signing key

The GPG key used to sign Maven Central artifacts is stored as a GitHub
Actions secret (`SIGNING_KEY`). To rotate it:

```bash
# 1. Generate a new key. The batch config is checked in at
#    scripts/maven-central-gpg-batch.conf for reproducibility.
gpg --batch --generate-key scripts/maven-central-gpg-batch.conf

# 2. Find the new key ID.
gpg --list-secret-keys --keyid-format=long support@usesense.ai

# 3. Export the armored secret key.
gpg --armor --export-secret-keys <NEW_KEY_ID> > signing-key-new.asc

# 4. Publish the public key so Sonatype can verify signatures.
gpg --keyserver keyserver.ubuntu.com --send-keys <NEW_KEY_ID>

# 5. Update the GitHub secrets:
#    - SIGNING_KEY     = contents of signing-key-new.asc (full armored block)
#    - SIGNING_PASSWORD = the passphrase you set in step 1
```

The old key can be revoked once a release has been successfully
signed with the new one.

### Maven Central: rotating Sonatype credentials

The `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` secrets are **user tokens**
generated at https://central.sonatype.com/account, not the portal
login. User tokens can be regenerated without losing the account; just
paste the new value over the secret in the GitHub repo settings.

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
