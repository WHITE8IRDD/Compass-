# Compass 2.4 verification status

**Status date: 2026-10-16.** Source compilation, repository self-tests, an
offline unsigned package, and a separate test-signed APK were validated in the
Arena sandbox. The test APK is signed only for sideload testing, not as a
release. This is not device verification: no Android emulator/device or `adb`
is available here, so provider, permission, authentication, lifecycle, and UI
behavior remain unverified.

## Checks completed

### Toolchain bootstrap

```bash
./tools/bootstrap-toolchain.sh /home/user/tc-kotlin-2.0.21
```

**Passed.** JDK 17.0.9, Kotlin 2.0.21, coroutines 1.8.0, Android API 34
`android.jar`, and offline packaging tools were installed outside the Git
repository. Kotlin 2.0.21 matches the project Gradle plugin version. The
available D8/R8 package is older and still emits metadata parsing warnings.

### Repository self-tests

```bash
./tools/run-selftest.sh
```

- **23/23 crypto assertions passed.** This exercises AES-GCM stream and byte
  round trips, tamper/truncation rejection, PIN hashing, constant-time compare,
  PIN validation, and wipe helpers using a desktop JCE test key.
- **33/33 startup and static transaction-order assertions passed.** These
  inspect source ordering and failure behavior; they do not execute Android
  storage providers or UI operations.

### Offline Kotlin/resource/package build

```bash
TOOLCHAIN=/home/user/tc-kotlin-2.0.21 ./tools/build-offline.sh
```

**Passed with D8 warnings.** Kotlin sources compiled with zero errors;
resources linked; generated `R.java` compiled; D8 produced dex; the APK was
packed. D8 emitted Kotlin-metadata parse/rewrite warnings because the available
R8/D8 tooling is older than the compiler metadata. The available Android
platform is API 34; Gradle declares compile SDK 35. This is not a normal AGP
build and does not establish runtime compatibility.

The resulting unsigned artifact is in the ignored build directory:

```text
build/offline/out/Compass-2.4-unsigned.apk
```

Inspection results:

- Size: **3,917,812 bytes**
- SHA-256: `2f28c61258e6e2c3927c51b694b0b3c51ba676b986465734cd2a095728837551`
- `aapt2 dump badging`: package `com.compass.vault`, version name `2.4`,
  version code `24`, min SDK `26`, target SDK `35`, offline platform build
  version/compile SDK `34`
- ZIP integrity: **passed**; `classes.dex` present (3,442,140 bytes)
- Manifest permissions: biometric, vibration, legacy storage reads limited to
  API 32 and writes limited to API 28, per-media reads, and Android 14+
  selected-visual-media permission
- `INTERNET` permission: **not declared**; searched `INTERNET` string in the
  DEX: **not found**
- This default-output APK contains no signer entries and is unsigned. It is
  not an installable release or update APK. The app bundle does not declare
  internet access.

The default-output artifact is generated under the ignored `build/` directory.
Re-running the build may change its hash if any source or packaging input
changes.

### Test-installable APK

A second offline build was signed with a freshly generated, temporary
**test-only** key so the app can be sideloaded. This did not use the exposed
historical signing key or an owner release key. The temporary keystore was
deleted after signing; it is not part of the repository or download.

- Arena workspace download: `downloads/Compass-2.4-test.apk` (session-local,
  ignored by Git; not committed or hosted on GitHub)
- Size: **3,925,788 bytes**
- SHA-256: `c2a0ecd8f808c8fd5e7314241108d8c7f1243cf886e87bee07aa2518a9ab0776`
- `apksigner verify`: **passed**; APK Signature Scheme v2 and v3 verified
- Certificate SHA-256: `c3d464daa5167a3cb346896dc428fc35f58ede67667598ff61038a03870714c0`
- Package/version are still `com.compass.vault` / `2.4` / code `24`; min SDK
  26 and target SDK 35.

**Installation caution:** This signer differs from the historical v0.2 signer,
so Android will reject it as an update to an existing v0.2 installation.
Uninstalling an app can erase its private vault data. Back up/export important
data first, or use a clean device/emulator. Because the temporary test key was
deleted, a future test APK cannot update this one either; a clean reinstall
will be required. This artifact is for testing only, not distribution or a
release. It has not yet been installed or tested on a device.

## Not performed

- Normal Gradle/AGP debug or release build
- APK installation, emulator run, or device run; no `adb`/emulator available
- Owner-controlled release-signature verification or publication
- MediaStore/SAF read, deletion consent, URI persistence, or restore tests
- Runtime permission matrix, biometric enrollment/prompt, and app-lock timing
- Playback/codec, UI accessibility, performance, or large-folder testing
- Crash/process-death injection during import, source removal, restore, or
  deletion

Do not use valuable files as first-run test data. Keep independent backups;
Compass is not a backup utility. A successful compiler or package step is not
proof that a file was safely removed, restored, or authenticated on Android.

## Manual device test plan

Use disposable source files and retain known-good copies. Test on representative
Android versions (API 26–28, 29–32, 33, and 34–35 where available).

### Library and permissions

- [ ] Deny media permissions; verify selected-folder browsing and individual
      SAF imports still work.
- [ ] Grant selected images, videos, audio, or Android 14+ selected visual
      media; verify only accessible rows appear and partial access is clear.
- [ ] Revoke a permission/grant while running and after restart; verify empty
      queries are never treated as proof of source deletion.
- [ ] Exercise category chips (videos, photos, documents, APK, other), SAF
      recursion/limits, search, all sorts, refresh, thumbnails, and failures.

### Protect/remove/restore

- [ ] Import images, videos, audio, documents, APKs, zero-byte files, large
      files, and batches. Verify content hash/size after restore.
- [ ] Cancel or deny Android source-deletion consent; ensure the public copy is
      reported as remaining and retry is available.
- [ ] Approve removal and independently check the original is actually absent;
      verify only a confirmed result is marked private-only.
- [ ] Force-stop after source removal and during restore; verify recovery keeps
      every valid copy and reports unresolved operations instead of overwriting.
- [ ] Test same-name conflicts, changed content at the original URI, SAF grant
      revocation, read-back failure, partial destination files, and cleanup
      failure.

### Authentication, playback, and lifecycle

- [ ] Test PIN success/failure/lockout, corrupt authentication state, biometric
      success/cancel/error/no-enrollment, and PIN fallback.
- [ ] Preview public items and open protected image/video/audio; test seek,
      pause, completion, unsupported codecs, and cleanup on lock.
- [ ] Background/reopen, trigger auto-lock, force-stop during long operations,
      and inspect recent-task screenshot behavior on each target OS/OEM.
- [ ] Verify APK files are never executed by Compass and inspect exported
      plaintext only at the chosen destination.

## GitHub release and signing status

`gh release list` and `gh release view v0.2` were checked on 2026-10-16. The
existing **COMPASS 0.2** (`v0.2`) remains Latest and has two attached assets:
`Compass-0.2.apk` (4,052,764 bytes) and its `.sha256` file. The repository APK
hash is `0d2e11e3674cb5651f692bfc5b96e9a130dff18a06e98c846e2e72e80df30f25`.
That release is separate from these source changes. No **Compass 2.4** tag or
release was created. A temporary-key test APK exists for sideloading, but no
owner-signed release APK was produced.

Historical signing material is exposed in repository history. Do not reuse
it. A future Compass 2.4 publication requires owner-provisioned fresh signing
material, a compatible official build, signature/checksum verification,
required device testing, and explicit release approval. See [RELEASE.md](RELEASE.md).
