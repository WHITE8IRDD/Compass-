# Compass 2.4 — session report

**Date:** 2026-10-16 · **Branch:** `arena/40269997-compass` · **Commits:**
`d0d264c` (implementation), `0826243` and `84e5402` (release/docs) ·
**Pull request:** [#6](https://github.com/WHITE8IRDD/Compass-/pull/6)

This is the short version of what was changed, what was proven, what was not,
and where the release lives. The detailed documents are
[VERIFICATION.md](VERIFICATION.md) (every check with its raw result),
[RELEASE-NOTES.md](RELEASE-NOTES.md) (the published release notes),
[RELEASE.md](RELEASE.md) (signing and release state),
[SECURITY.md](SECURITY.md) and [PLAN-2.4.md](PLAN-2.4.md) (the plan and the
decisions behind it).

---

## 1. What Compass 2.4 is

An offline file browser with a real vault. The home screen lists the files
Android exposes to the app - sections for **Videos**, **Photos**,
**Documents**, **APK files** and **Other files**, with previews, search,
sorting, multi-select and honest panels that say which locations are missing.
**Lock** encrypts a file into app-private AES-256-GCM storage, verifies the
encrypted copy, removes the public original through Android and verifies the
removal. **Unlock** restores the file to where it came from, with a verified
read-back, and never overwrites anything. No account, no server, no
`INTERNET` permission.

## 2. What changed

| Area | Before | In 2.4 |
|---|---|---|
| Home screen | Compass dial + "All Media" list | Sectioned library with previews, per-category full lists, search, sort, multi-select, bulk lock |
| Lock | "import" that *offered* to delete the original afterwards, plus a plaintext **export** copy | One transaction in `ui/FileLocker.kt`: encrypt → verify by decrypting again → journal the removal intent → remove through Android (SAF provider, batched consent on 11+, item-by-item `RecoverableSecurityException` on 10, write permission on 8-9) → read back every URI → record only what is provably gone |
| Export | Keep the vault copy **and** write a plaintext copy | Removed. There is exactly one protected copy |
| Unlock | Restore to a folder chosen by the user, then delete the vault copy | Automatic restore to the recorded location (SAF folder → MediaStore folder → recorded path on 8-9), verified read-back, conflict-safe naming, save dialog only when the original place is really gone |
| Discovery | MediaStore media + a single SAF folder | MediaStore (including the non-media rows Android exposes on 10-12) + **several** approved SAF folders with revoked-grant detection, paging and a bounded scan |
| Vault metadata | index only | operation state machine (`SOURCE_REMOVAL_PENDING`, `RESTORE_*`), source state (`PRIVATE_ONLY` / `PUBLIC_COPY_PRESENT` / `UNKNOWN`), recovery before listing |
| Screens | `ui/screen/VaultScreen.kt` (deleted) | `ui/screen/LibraryScreen.kt` (library + authenticated Locked Files tab), `ui/FileLocker.kt`, refreshed Settings |
| Tests | 56 assertions | **135** assertions in four programs (see below) |

New files: `data/LibraryLogic.kt`, `ui/FileLocker.kt`,
`ui/screen/LibraryScreen.kt`, `tools/selftest/LibraryLogicTest.kt`,
`tools/selftest/VaultTransactionTest.kt`. Deleted: `ui/screen/VaultScreen.kt`.

## 3. Test results

```bash
TOOLCHAIN=/path/to/toolchain ./tools/run-selftest.sh    # exit code 0
```

| Program | Result |
|---|---|
| `CryptoSelfTest` — AES-GCM round trips, IV handling, truncation/tamper rejection, PBKDF2, constant-time compare, wipe | 24 / 24 pass |
| `LibraryLogicTest` — classification, conflict naming, search, formatting, badges | 34 / 34 pass |
| `VaultTransactionTest` — 9 MB source: stream-encrypt → re-decrypt → compare byte count + SHA-256 → atomic publish → verified restore → delete only after verification, plus truncated/tampered/failed-write/cross-directory/crash cases | 26 / 26 pass |
| `StartupSafetyTest` — static checks incl. the order of every security-critical step and the authentication gate on the Locked Files tab | 51 / 51 pass |

Build: `TOOLCHAIN=... ./tools/build-offline.sh` → **exit 0**, signed APK
`4,007,708 bytes`, SHA-256
`d7d11f41c69263394690b256721a2c09470912ccf69fb8f633c2765094ce5578`, ZIP
integrity OK, stored entries 4-byte aligned, APK Signature Scheme v2+v3
verified (RSA 4096), no `INTERNET` and no all-files permission in the manifest
or in `classes.dex`. The `classes.dex` of the published APK is byte-identical
to a fresh build of the committed sources.

**Not tested, and therefore not claimed:** the APK was never installed or
launched. The build environment has no device, no emulator and no `adb`, and
cannot download an Android system image, so MediaStore/SAF runtime behaviour,
Android's delete-consent dialogs, biometric prompts, lifecycle/auto-lock,
playback codecs, accessibility and performance remain unverified on real
hardware. `docs/VERIFICATION.md` contains the exact list and a manual test
checklist for API 26–35.

## 4. Performance work (design, not measured)

No performance numbers are claimed - nothing was measured on a device. What
was done to avoid obvious problems: MediaStore queries are paged with
`QUERY_ARG_LIMIT`/`QUERY_ARG_OFFSET` instead of loading everything; thumbnails
are decoded on the IO dispatcher, limited to four parallel decodes, cached in
a bounded `LruCache` (12 MB) and re-checked against the row's tag before being
applied, so recycled rows never show the wrong image; file encryption streams
in 64 KB chunks (a 1 GB video never lands in memory); the SAF scan is bounded
(20 000 files / 16 levels / 16 folders) and reports truncation instead of
hanging; the vault index is read once per operation and written atomically;
background operations are cancelled when a screen is hidden.

## 5. Release

- **Release name:** Compass 2.4 (exact) · **tag:** `v2.4` ·
  https://github.com/WHITE8IRDD/Compass-/releases/tag/v2.4
- **Marked pre-release**, so the older **COMPASS 0.2** stays "Latest" until
  the device checklist has been run. Promoting it is one click.
- **APK:** `dist/Compass-2.4.apk` at the tag
  (`https://github.com/WHITE8IRDD/Compass-/raw/v2.4/dist/Compass-2.4.apk`) and
  its `.sha256` next to it.
- **Assets could not be attached:** this environment reaches `api.github.com`
  but **not** `uploads.github.com` (GitHub's asset-upload host), so the upload
  failed with a connection reset. The release body links the identical files
  committed at the tag. The owner can attach them from any normal machine
  with: `gh release upload v2.4 dist/Compass-2.4.apk dist/Compass-2.4.apk.sha256`
- **Signing:** a new key was generated for this release (RSA 4096,
  `CN=Compass, OU=Compass Android releases`, certificate SHA-256
  `88ed48c454d52f62efa6d0299c3b7a26916c5644f991d7ac14356270a8c507be`), because
  the 0.1/0.2 material is exposed in repository history. The keystore and its
  password are **outside** the repository in the workspace directory
  `compass-signing/` (`compass-release.jks`, `passwords.env`,
  `README.txt`). Every future update of `com.compass.vault` must be signed with
  this key.
- **Installation consequence:** a different signer means Android refuses this
  APK as an update of an installed 0.1/0.2 build. Uninstalling erases the old
  app's private vault data, so back up first or use a clean device.

## 6. Known limitations

- Compass can only browse what Android exposes: MediaStore media, the
  non-media rows of the media database on Android 10–12, and folders the owner
  explicitly approves. Other apps' private directories,
  `Android/data`/`Android/obb` and ungranted folders are invisible and are
  never described as protected.
- Removing the public original is Android's operation, not Compass's. If the
  consent dialog is cancelled or a provider refuses, Compass keeps the
  encrypted copy, says **"NOT FULLY HIDDEN · PUBLIC ORIGINAL MAY REMAIN"** and
  offers a retry. Gallery trash, cloud backups and other copies are outside
  its reach.
- The vault key is bound to the Android Keystore and this installation:
  uninstalling the app or losing the key makes the encrypted files
  unrecoverable. Compass is not a backup tool.
- Physical secure erasure on flash storage cannot be guaranteed; the wipe
  helper is best-effort overwriting before unlinking.
- The biometric prompt gates the app-level UI; it does not re-wrap the
  Keystore key, and no biometric data is read, stored or transmitted by
  Compass.
- The app is framework-based (no AndroidX/Compose) and was compiled with the
  offline toolchain, not with AGP: D8 prints Kotlin-metadata warnings and the
  build uses API 34 stubs while Gradle declares compile SDK 35. API-34-stub
  compilation.

## 7. What the owner still has to do

1. **Run the device checklist** in [VERIFICATION.md](VERIFICATION.md) §5 with
   disposable files (API 26–28, 29, 30–32, 33, 34–35 if available). It covers
   discovery, lock/removal, restore, authentication, offline behaviour and
   crash recovery.
2. **Attach the APK and checksum** to the `v2.4` release from a machine that
   can reach `uploads.github.com`, and flip the release from pre-release to
   Latest once the checklist passes.
3. **Merge PR [#6](https://github.com/WHITE8IRDD/Compass-/pull/6)** into
   `main` (the tag already points at the head commit of that branch).
4. **Store `compass-signing/` safely** - losing it means no future update can
   be installed over Compass 2.4.
