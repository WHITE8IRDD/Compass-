# Security model and data-safety notes

This document describes the code in the current working tree, not a claim that
all Android behavior has been validated on a phone. See
[`VERIFICATION.md`](VERIFICATION.md) for checks that ran and tests still
required.

## 1. Threat model and honest boundaries

Compass 2.4 is an offline, app-private encrypted vault paired with a local
file browser. The post-setup home is the library; the existing working compass
screen remains available from its COMPASS heading, and PIN/biometric
authentication gates the encrypted vault. It aims to protect stored file
contents from other ordinary apps while the vault is closed. It is **not** a
backup system, an operating-system security boundary, or a promise that every
copy of a file has disappeared from shared storage.

The Android sandbox, the Keystore implementation, device lock, OS integrity,
and the user-approved storage providers are part of the trust boundary.
Rooted/compromised devices, a compromised OS, a live process/debugger, a
malicious accessibility service or keyboard, coercion, screenshots with an
external camera, and physical flash-forensics are outside Compass's guarantees.

## 2. Encryption at rest

- Vault payloads use streaming **AES-256-GCM** (`AES/GCM/NoPadding`).
- The Android Keystore generates the non-exportable AES key. The app does not
  write key bytes to a file or log. Whether a given device backs the key with
  secure hardware depends on that device.
- Each encryption uses a fresh 96-bit IV and a 128-bit GCM authentication
  tag. Decryption fails on an invalid tag rather than accepting tampered
  bytes.
- Payloads and metadata are stored under app-private internal storage:

  ```text
  filesDir/vault/<id>.bin       encrypted payload
  filesDir/vault/<id>.meta      encrypted metadata (including source tracking)
  filesDir/vault/folders.json   encrypted folder names
  filesDir/.authstate           encrypted PIN/authentication state
  cacheDir/decrypted/           temporary plaintext for seekable playback
  ```

- Import computes a SHA-256 digest and byte count from the source while
  encrypting. It then decrypts the completed ciphertext to a streaming digest
  sink and compares size and digest before atomically publishing the payload
  and writing encrypted metadata.
- Restore decrypts and verifies the encrypted stream, verifies the output
  digest/size by reading the chosen destination back, and only then removes
  the encrypted copy. The exact provider behavior still needs device testing.
- SHA-256 is used as an integrity/identity check for the file contents; it is
  not encryption and is not stored in plaintext metadata.

### Authentication is separate from encryption

The PIN is 4–8 digits and is hashed with PBKDF2-HMAC-SHA256, a random
16-byte salt, and 250,000 rounds. The authentication state is encrypted with
the Keystore key and atomically replaced. Failed attempts trigger escalating
delays and an optional decoy route. If authenticated state cannot be read,
Compass fails closed instead of presenting a fresh setup flow; the encrypted
vault data is preserved, but access can remain unavailable if the Keystore
key is lost or invalidated. Do not clear app data or reinstall in an attempt to
recover it.

The compass bearing, app PIN, and biometric prompt control access to the
protected UI. The platform biometric prompt is **not** used as a cryptographic
key or as a per-file Keystore authorization requirement. Once the app process
is compromised or the UI gate is bypassed on a compromised device, this UI
authentication is not an independent storage boundary.

## 3. Import, public source removal, and status

1. A file can be selected with Android's Storage Access Framework (SAF).
   This grants access to that chosen document and does not require broad
   library access. The All Media library separately needs runtime media
   access and may expose only categories/items selected by Android.
2. Compass streams the selected content into app-private encrypted storage.
   It verifies the ciphertext by decrypting into a digest sink and checks
   byte count and SHA-256. Import itself never deletes the source.
3. Before journaling a delete request, the repository reopens the exact source
   URI and compares its size and digest with the encrypted copy. A changed or
   unreadable source is not submitted for deletion. Only then does the UI ask
   Android/provider to remove that individual source. MediaStore deletion may
   require system consent; SAF providers may have separate behavior.
4. Compass journals source removal before asking for consent. It records
   **PRIVATE_ONLY** only after a successful delete result and a follow-up
   absence/read check. If the process stops during consent, recovery rechecks
   the exact source URI before deciding its status. A cancelled, denied,
   failed, or ambiguous removal stays public/pending and is reported as
   **not fully hidden**.

This is conservative, but Android permission scoping and provider behavior
can vary. If an empty query is ambiguous because access is partial or
revoked, Compass must not infer that the original was deleted. The source may
also remain in a gallery trash/recycle bin, cloud backup/sync, another folder,
another app's copy, or under another URI. Compass cannot remove or inspect
those copies and does not claim complete hiding.

An import interrupted before completion can leave an encrypted `.part` or
atomic-write temporary artifact until startup cleanup. The public original
is not eligible for removal until the complete verified import has returned.

## 4. Restore, export, and deletion behavior

- When the original source is still readable and its digest matches, unlock
  recognizes that the identical public copy is already present; it does not
  write over it. The encrypted entry is removed only after that exact content
  is verified.
- For a source marked public but no longer safely inspectable, Compass asks
  for a restore destination instead of guessing that the source is gone or
  silently creating a duplicate.
- For a confirmed private SAF-tree source, Compass attempts to recreate the
  file in the recorded, user-approved folder using a conflict-safe name. For
  supported confirmed private MediaStore media it attempts the saved volume
  and folder using a pending row. Both paths require read-back verification.
  Unsupported locations use the system save picker.
- **Export** is an explicit plaintext copy. The encrypted vault copy stays
  in place. A failed/unverifiable export attempts provider cleanup and keeps
  the encrypted copy; provider cleanup itself can fail and the app reports
  the limitation.
- Deleting a protected entry is a separate user-confirmed destructive action.
  It does not delete a public source copy.

## 5. Shared-media access and network

- The manifest does not request `INTERNET`; shared-media access is local and
  runtime-granted. No analytics or network client is included by this app.
- On Android 13+, image, video, and audio permissions are separate. Android
  14+ can grant only selected visual media. All Media shows only accessible
  rows and labels partial access; users can update grants or use SAF to add
  individual documents.
- `allowBackup="false"`, `fullBackupContent="false"`, and extraction rules
  disable app-data backup through Android's standard app backup paths. This
  does not prevent the user from explicitly exporting plaintext or the
  original files from another app.

## 6. Playback and temporary plaintext

Some decoders and `MediaPlayer` require seekable files. Protected video/audio
and thumbnail work therefore decrypts to a private cache file while in use.
Images are also decoded via a bounded private temporary file to avoid
materializing an unbounded source in RAM. Compass schedules best-effort
random overwrite and deletion of those cache files on close/auto-lock and
clears in-memory thumbnail caches. The app cleans stale private playback
files on startup.

This is **not guaranteed physical erasure**: flash translation layers may
remap blocks, cleanup is best-effort, and process termination can interrupt
writes. Private cache files are also plaintext while present. The principal
protection for vault payloads at rest is encryption, not a promise that
secure overwrite works perfectly on flash storage.

`FLAG_SECURE` is applied to the Activity window. It asks Android to block
ordinary screenshots/screen recording and hide the recent-task preview; OEM,
OS, external-camera, rooted-device, and compromised-process behavior is not
under Compass's control.

## 7. Biometric fallback

Compass uses platform-supported biometric APIs: BiometricPrompt/Manager on
newer releases and the framework fingerprint API on Android 8–8.1. Readiness
checks account for sensor/enrollment availability. Cancellation and errors do
not count as wrong PIN attempts. An app PIN remains available if a sensor is
missing, not enrolled, unsupported, locked out, or temporarily unavailable.
Compass never receives or stores biometric templates. This behavior has not
yet been exercised on device hardware.

## 8. Release signing status

Signing material was previously tracked in repository history. It has been
removed from the current working tree and the build now requires an
owner-controlled external signing configuration. That removal does not erase
Git history or revoke the previous signing identity. Treat it as exposed and
do not reuse it for a new release.

The existing GitHub release **COMPASS 0.2** at tag `v0.2` remains published
and Latest, with the APK and checksum assets attached. It is separate from the
Compass 2.4 working tree. No signed APK or new release was produced from this
source. A different signing identity cannot update an installed app signed
with the previous identity; any future release needs an explicit owner decision
and a safely provisioned new key.

## 9. Before using real files

- Keep independent backups of original files; Compass is not a backup tool.
- First test with disposable files and compare restored bytes.
- Verify Android's delete-consent result and gallery trash behavior on the
  target device; do not rely on a status label until the actual original is
  checked.
- Do not uninstall/reinstall or clear app data while files are in the vault.
  The Keystore key and app-private data are installation/device-bound.
- Review the manual device checklist in [`VERIFICATION.md`](VERIFICATION.md).
