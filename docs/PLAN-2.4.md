# Compass 2.4 - implementation plan (working notes)

Written after the code audit of the `a7dce21` tree. This is the engineering
plan for the 2.4 work; the user-facing documents are `AUDIT.md`,
`VERIFICATION.md` and `RELEASE-NOTES.md`.

## What the audit found (evidence: the files listed in AUDIT.md)

| Requirement | State in the audited tree |
|---|---|
| Encrypted app-private vault (`filesDir/vault/<id>.bin` + `.meta`) | Implemented, AES-256-GCM via Android Keystore, streamed in 64 KB chunks |
| Import verifies before publishing metadata | Implemented (`import()`), never deletes the source |
| Durable source-removal journal | Implemented (`SOURCE_REMOVAL_PENDING` -> `markSourceRemoved`) |
| Restore + read-back verification + conflict-safe naming | Implemented for SAF trees, MediaStore, and save-picker targets |
| Crash/restart recovery of half-finished transactions | Implemented (`recoverTransactionsLocked`) |
| PIN (PBKDF2 250k, lockout) + platform biometrics | Implemented |
| Offline (no `INTERNET` permission) | Implemented |
| Browsable library: videos/photos | Implemented via MediaStore |
| Browsable library: documents/APKs/other | Only **one** SAF folder grant; flat list, no sections |
| Lock from the library | Requires a per-file Android delete-consent dialog, one at a time |
| Unlock | Automatic for MediaStore + SAF origins, but refuses when source status is unknown |
| Plaintext "export" action | Still present in the vault row menu (the brief asks to remove it) |

## Decisions

1. **Keep the encrypted app-private vault.** A plaintext rename into a private
   directory would be *weaker* than what exists and would also break the
   "large files, no double storage" goal. Encryption is the strongest
   protection an un-rooted app can offer, so the lock operation stays
   "verified encrypt -> verify -> remove the public original".
2. **No `MANAGE_EXTERNAL_STORAGE`.** The previous session documented this
   deliberately (`tools/selftest/StartupSafetyTest.kt` asserts the manifest
   does not contain it) and the brief explicitly allows limited discovery
   ("do not promise that Compass can hide every file in every directory").
   Discovery therefore stays on MediaStore + SAF grants, and the UI states
   which files need a grant.
3. **Source removal is automation, not a second feature.** Locking now
   removes the public original *inside* the lock flow:
   - SAF document URIs: `DocumentsContract.deleteDocument`, no dialog.
   - MediaStore media on Android 11+: one batched `createDeleteRequest`
     consent for the whole selection.
   - MediaStore on Android 8-9: `resolver.delete` with the legacy write
     permission.
   Every removal is verified afterwards; the item is only reported as hidden
   when the source is provably gone.
4. **Remove the plaintext export path** from the UI, navigator and repository.
5. **Multiple SAF folder grants** instead of one, with dead grants pruned
   against `persistedUriPermissions`.
6. **Sectioned home screen** (Videos / Photos / Documents / APKs / Other /
   Locked) with per-section previews and a per-category full list, search,
   sort, refresh, multi-select and bulk lock.
7. **Recovery-first semantics stay**: never delete the only verified copy,
   journal every operation state, verify digests after every move.

## Work order

1. Data layer: `MediaFile`/`VaultFile` fields, pure `LibraryLogic`, multi-tree
   repository, MediaStore section queries.
2. Vault layer: unified automatic restore, SAf/MediaStore removal helpers,
   drop export.
3. UI: new `LibraryScreen` (sections + category + locked), preview screen
   lock action, settings storage section.
4. Tests: JVM logic test + end-to-end file transaction test with the real
   cipher and the real atomic writer + updated static checks.
5. Release: offline build, sign with a fresh key, checksum, GitHub release.
