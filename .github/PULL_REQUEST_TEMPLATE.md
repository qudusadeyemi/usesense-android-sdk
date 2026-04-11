<!--
  Thanks for contributing to the UseSense Android SDK.

  External code contributions are not accepted at this time; this
  template is primarily for internal maintainers and sanctioned
  partners. If you're filing a bug report or feature request,
  please open an issue instead.
-->

## Summary

<!-- 1-3 sentences: what does this PR change and why? -->

## Type of change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that changes existing public API)
- [ ] Documentation or tooling only
- [ ] Release prep (version bump + CHANGELOG entry)

## Checklist

- [ ] `./gradlew :sdk:assembleRelease :sdk:test :sdk:lintRelease` passes locally
- [ ] `./gradlew :sdk:ktlintCheck` reports no new violations
- [ ] Any new public API has KDoc comments
- [ ] `CHANGELOG.md` has been updated for user-visible changes
- [ ] `README.md` install snippets still compile if the version was bumped
- [ ] No secrets, API keys, or keystores committed

## Testing notes

<!--
  Describe how you tested this locally. For SDK changes, note which
  Android API levels you smoke-tested on (the minimum supported is 28).
-->

## Related issues

<!-- Closes #123, relates to #456 -->
