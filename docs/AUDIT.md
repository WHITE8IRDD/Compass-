# Compass 2.4 working-tree audit

**Audit date:** 2026-10-16. This is a source review of the Compass 2.4
candidate, not a claim that Android storage behavior or release readiness has
been proven. Test results are in [VERIFICATION.md](VERIFICATION.md).

## Baseline and scope

The existing app already had a compass/PIN shell and AES-GCM vault, but it
lacked a browsable public library, broad SAF folder discovery, durable
source-removal/restore state, and reliable source-status reporting. This
change keeps the app framework-only and offline, adds a local file browser and
hardens the lock/unlock transaction path. The default post-setup screen is the
library; tapping the COMPASS header opens the existing compass screen.

The existing published `COMPASS 0.2` release is retained as-is. The working
tree is versioned `2.4` / code `24` but is not published or signed.

## Implementation map and evidence

| Area | Working-tree implementation | Evidence / limitation |
|---|---|---|
| Public library | Paged MediaStore image/video/audio rows; SAF-selected tree scan for accessible documents, APKs, and other files; category chips, file type indicators, thumbnails/previews, name search, sort, refresh, and permission/truncation messaging. | Kotlin/offline package compile and static inspection. No Android provider, permission, UI, or performance test. SAF scan is bounded (10,000 files, depth 16); it cannot discover arbitrary shared storage. |
| Import integrity | Stream source into an app-private encrypted `.part`, decrypt ciphertext into a SHA-256 sink, check size/digest, atomically publish ciphertext, then write encrypted sidecar metadata. Repository import never deletes the source. | Crypto self-tests and source-ordering checks. No SAF/MediaStore end-to-end import test. |
| Public source removal | Persist `SOURCE_REMOVAL_PENDING` before requesting provider deletion; use Android consent for MediaStore where required; verify absence/read failure; mark `PRIVATE_ONLY` only after confirmed removal. Recovery rechecks a pending source after process death. | Static checks only. Android consent and provider behavior are unverified. A public original, recycle-bin copy, cloud copy, or alternate URI may remain. |
| Restore/recovery | Journal restore state and destination; attempt recorded SAF folder or MediaStore location when known; use a user-selected SAF destination otherwise; verify ciphertext, byte count, digest, output read-back, and MediaStore publication before tombstoning the encrypted entry. Conflict naming avoids silent overwrite. | Static ordering checks and compilation. Provider semantics, crash injection, read-back, and partial-output cleanup are unverified on Android. Unresolved output is retained as `RESTORE_RECOVERY_REQUIRED`; both copies remain. |
| Delete/export | Explicit plaintext export warns and leaves encrypted item intact. Encrypted delete is user-confirmed and uses a tombstone/recovery step. | Compiled/static review only. Export providers and deletion failures are not device-tested. |
| Authentication state | PIN hash uses PBKDF2-HMAC-SHA256 with a random salt; encrypted state is atomically replaced. A corrupt/unreadable state fails closed instead of silently resetting setup and potentially bypassing the old vault. Lockout state is persisted. | Crypto assertions plus Kotlin compile. No Android Keystore invalidation or on-device lockout test. |
| Biometrics and access gate | Framework biometric/fingerprint APIs gate the protected UI, with PIN fallback. Vault list/viewer routes require the in-process unlocked state. | Kotlin compile/static inspection. Not tested with biometric hardware or lifecycle/process death. The biometric prompt is an app-level gate, not per-file cryptographic key authorization. |
| Permissions and privacy | Media read permissions are optional; SAF is used for user-selected files/folders; no `MANAGE_EXTERNAL_STORAGE` or `INTERNET` permission is declared. APKs are treated as files and not installed/executed by Compass. | Manifest/package inspection. Runtime permission behavior is unverified. |
| Cache and capture | `FLAG_SECURE`; decrypted playback/thumbnail temp files and RAM thumbnail caches have best-effort cleanup on lock/startup. | Source inspection and compile. OEM capture behavior and flash-storage erasure are not guaranteed. |
| Signing | Release builds require owner-controlled external signing values; historical signing material is exposed and not reused. | A separate sideload-test APK is signed with a one-off temporary key and verified; it is not an owner/release signature. |

## Checks completed

- `tools/run-selftest.sh`: **23/23** crypto assertions and **33/33** static
  startup/transaction assertions passed.
- Offline Kotlin/resource/dex/package build completed with **zero Kotlin
  compilation errors** using Kotlin 2.0.21 and Android API 34 stubs.
- D8 completed but emitted Kotlin metadata parse/rewrite warnings; the offline
  Android platform is API 34 while Gradle declares compile SDK 35.
- APK package inspection passed ZIP integrity and confirmed version `2.4`,
  code `24`, min SDK `26`, target SDK `35`, and no manifest `INTERNET`
  permission. The default offline output is unsigned. A separate session-local,
  Git-ignored APK was signed with a disposable test key; `apksigner` verified
  its v2/v3 signatures. It is not committed or hosted on GitHub.
- No official Gradle build, emulator/device run, `adb` test, runtime
  permission test, or Android provider transaction test has been performed.
  The test signing key was deleted; no owner-controlled release signature was
  created.

## Data-safety boundaries

- Compass does not remove a source unless an encrypted copy has been verified
  and the Android/provider removal is subsequently confirmed.
- An `empty` MediaStore query is not proof of source removal, especially with
  partial/revoked grants.
- When restore output cannot be verified/cleaned, the encrypted copy remains
  and the item is flagged for recovery. This may leave a partial or complete
  public destination that needs user review.
- The app cannot remove copies in gallery trash, cloud sync, backups, other
  folders, or other applications; “private-only” refers only to the specific
  source URI Compass verified.
- A biometric prompt does not cryptographically authorize each AES operation.
  A compromised process/device can bypass an app-level UI gate.
- Flash wear-leveling prevents a guarantee of physical secure deletion. The
  vault's primary at-rest protection is encryption; plaintext may exist in a
  private playback cache while in use.
- Clearing app data/uninstalling or losing the Keystore key can make protected
  files unrecoverable. The vault is not a backup.

## Existing release state

GitHub currently lists **COMPASS 0.2** at tag `v0.2` as Latest, with an APK
and checksum asset attached. This branch has not created or published
**Compass 2.4**. The `v0.2` artifact remains separate and unchanged. Its
signing material is exposed in repository history and must not be reused.

No Android code or documentation in this audit should be read as a substitute
for the manual test plan in [VERIFICATION.md](VERIFICATION.md) or as a claim
that the Compass 2.4 candidate is ready for valuable files or release.
