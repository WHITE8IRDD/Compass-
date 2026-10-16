# Release status

## Current public release

**COMPASS 0.2** at tag [`v0.2`](https://github.com/WHITE8IRDD/Compass-/releases/tag/v0.2) remains the GitHub Latest release. The tag and APK contents were not changed. The release notes were updated on 2026-10-16 to add a prominent APK download link and replace the obsolete keystore advice.

- Tagged APK: [`dist/Compass-0.2.apk`](https://github.com/WHITE8IRDD/Compass-/blob/v0.2/dist/Compass-0.2.apk)
- Direct download: <https://raw.githubusercontent.com/WHITE8IRDD/Compass-/v0.2/dist/Compass-0.2.apk>
- Size: **4,052,764 bytes**
- SHA-256: `0d2e11e3674cb5651f692bfc5b96e9a130dff18a06e98c846e2e72e80df30f25`
- ZIP integrity passed; APK signature schemes v1, v2, and v3 verified locally.

The release currently has no binary assets attached. An attempt to upload the existing APK and checksum file failed when the build environment's connection to `uploads.github.com` returned EOF. The release page now links directly to the APK stored in the `v0.2` tag. This source tree has not been published as a new release.

The current-source offline APK is a separate, **unsigned test build**; it is not the published v0.2 artifact. Its checksum and limitations are recorded in [VERIFICATION.md](VERIFICATION.md).

## Signing and release safety

Signing material used for the existing APK was tracked in repository history and must be treated as exposed. The files have been removed from the working tree; do not reuse the old signing identity. Current builds are unsigned by default. Optional signing is configured through externally supplied `COMPASS_RELEASE_*` environment variables; see [BUILD.md](BUILD.md).

No signed APK of the current source has been produced. Any future public release needs a new version/tag and newly provisioned private signing material, plus a verified build, signature, checksum, device testing, and explicit release approval. A different signing key may prevent in-place upgrades. Do not overwrite or recreate `v0.2`.

## Historical notes

The old v0.1 release description is preserved in [RELEASE-NOTES.md](RELEASE-NOTES.md) for historical context only. Its old signing, tag, and publication commands are obsolete and must not be used for the current source.
