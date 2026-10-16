# Compass Enhanced 0.2 — implementation audit

**Audit date:** 2026-10-16. The working tree is based on the existing
Compass `0.2` release source line; it is not itself a published release.

## Baseline findings

Before these changes, the project had a functioning compass/PIN shell and
AES-GCM vault, but imports were not verified before the UI offered source
deletion, deletion consent/results were not reliably tracked, there was no
separate public media library or restore transaction, biometric readiness
was inferred unreliably, and the old documentation overstated device/release
verification. The original published APK and release are retained as-is.

## Implemented in the working tree

| Area | Current implementation intent | Verification status |
|---|---|---|
| Import integrity | Stream into an app-private `.part` ciphertext; decrypt to a streaming SHA-256 sink; compare byte count/digest; atomically publish payload; write encrypted metadata. Repository import never deletes the provider source. | Kotlin compile, static ordering checks, crypto tests. No Android provider/device exercise. |
| Public source removal | After repository import returns, request Android/provider removal per item; use platform consent where required; verify source absence/read failure; persist `PRIVATE_ONLY` only on confirmed removal; preserve explicit public/unknown status otherwise. | Static source checks only. Runtime consent and provider behavior unverified. |
| Restore/export | Restore to a known MediaStore location using a pending row where possible, or use SAF destination; verify GCM, size, digest, read-back, and final publish before deleting ciphertext. Export leaves encrypted source intact. | Static ordering checks only. Provider read-back and failure cleanup unverified. |
| Vault library | Separate All Media (public MediaStore rows) and Locked Files views; paging, search, sorting, folders, thumbnails, file details, lock/unlock, retry source removal, restore/export/delete actions. | Kotlin compile/package checks. UI behavior unverified. |
| Public preview/player | In-app public image preview and video/audio playback; protected viewer includes image/video/audio paths. | Kotlin compile only. Codec/Surface/permission behavior unverified. |
| Permissions | Legacy storage permission is API-limited; modern per-media read permissions and Android 14 selected-visual-media permission are declared; SAF picker remains available. Partial access is labelled in the library. | Manifest/package inspection only. Runtime permission matrix unverified. |
| Biometrics | Framework BiometricPrompt/Manager on newer supported Android and FingerprintManager fallback on API 26–27; enrollment/readiness and cancellation/error states surfaced; app PIN remains fallback. No biometric data is accessed. | Kotlin compile only. No hardware/device prompt test. |
| Lock/cache cleanup | Auto-lock hides the protected route, cancels screen work, drops thumbnail caches, and schedules best-effort cleanup of private decrypted playback/cache files. | Static/code inspection only. Lock timing and filesystem behavior unverified. |
| Metadata failure behavior | Corrupt or missing vault sidecars/payloads surface as errors rather than silently presenting a valid empty vault; folder metadata failures are surfaced too. | Static test and compile. No corruption/device test. |
| Signing configuration | Signing files removed from the working tree; Gradle/offline builds use optional external signing environment variables rather than committed key/password files. | Source inspected. Existing Git history still contains prior tracked signing material. |

## Checks actually run

- `tools/run-selftest.sh`: **23/23 crypto checks passed** and **27/27
  startup/transaction-order checks passed**.
- Kotlin source compilation against the available API 34 `android.jar`:
  **0 errors**, with framework deprecation/redundancy warnings.
- Offline resource/link/dex/package build completed as an **unsigned** APK.
- `aapt2` reports package `com.compass.vault`, version `0.2`, code `3`, min
  SDK `26`, target SDK `35`; expected modern media permissions are present.
- Python ZIP integrity check passed; no `INTERNET` permission or searched
  `INTERNET` DEX string was found.
- The offline APK SHA-256 and build/toolchain caveats are recorded in
  [`VERIFICATION.md`](VERIFICATION.md).

The offline compiler/runtime tool versions are not the project's configured
Gradle tool versions; D8 emitted Kotlin metadata warnings. No normal Gradle
release build, `apksigner` verification, device install, emulator run, or
runtime SAF/MediaStore/biometric test has been performed.

## Data-safety decisions and limitations

- An encrypted copy is not treated as safe to delete a source until a
  decrypt-and-digest verification succeeds.
- A public source is not reported fully hidden unless Android/provider
  removal is confirmed. Android gallery trash, cloud sync, copied files,
  alternate URIs, and other copies are outside Compass's control.
- When Android permission scoping makes an empty query ambiguous, the vault
  retains the conservative public/unknown status rather than inferring a
  deletion.
- A restore output must be readable and match its expected size/digest before
  the encrypted copy is deleted. Provider behavior and partial-file cleanup
  still require device testing.
- Android Keystore protects the master key but the current biometric prompt
  is an app-level authentication gate; it does not cryptographically bind
  every cipher operation to biometric approval.
- Best-effort random overwrite on flash storage is not a guaranteed physical
  erasure because of wear-leveling. The primary at-rest protection is
  encryption; private playback files are plaintext while in use.

## Existing release and signing incident

The GitHub release titled **COMPASS 0.2**, tag `v0.2`, remains published and
Latest. The tag and APK bytes are unchanged. The release notes were updated
to add a direct tagged-APK download link and replace the obsolete keystore
warning. An attempt to upload the APK and checksum as release assets failed
because the sandbox connection to `uploads.github.com` returned EOF; a follow-up
GitHub API query confirms the release still has no attached assets. The prior
signing key and password file were tracked in repository history and must be
treated as exposed. They were removed from this working tree; do not reuse that
identity for new builds. The existing APK's ZIP integrity, SHA-256, and v1/v2/v3
signature verification were checked locally. Any future update needs a
separately approved signing and versioning plan; an unrelated key cannot update
an installed copy.

## Research notes

Platform storage and biometric references, plus comparator projects, are
summarized in [`RESEARCH.md`](RESEARCH.md). No third-party project code was
copied.
