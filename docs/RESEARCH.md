# Architecture and platform research

Research performed before changing storage or biometric behavior. No third-party source files were copied into Compass.

## Comparable open-source Android apps

- **Photok** ([source](https://github.com/leonlatsch/Photok), [encryption specification](https://github.com/leonlatsch/Photok/blob/master/ENCRYPTION.md), Apache-2.0): its documentation describes a versioned encrypted-file format and, in its current architecture, separating a vault master key from password/biometric key-encryption keys. It also documents why legacy formats were deprecated and retained only for migration. Compass keeps its existing per-file AES-GCM format for this release (to avoid an unsafe format migration) and borrows the engineering lesson: document formats, migration, and authentication boundaries rather than making generic “AES-256” claims. No Photok code was reused.
- **DroidFS** ([source and README](https://github.com/hardcore-sushi/DroidFS), AGPL-3.0): an encrypted-volume/file-manager design uses an in-app file browser and calls out the mismatch between path-based filesystem libraries and Android `ContentProvider`/SAF URIs. Compass likewise keeps protected files app-private and uses URI-based APIs at the public-storage boundary. DroidFS code was not copied; its AGPL license and filesystem architecture are not a fit for this app.
- **Fossify Gallery** ([source](https://github.com/FossifyOrg/Gallery), GPL-3.0): useful as a gallery/browser reference. Folder exclusion via `.nomedia` only affects media indexing; it does not encrypt data or prevent a file manager from opening the path. Compass does not treat `.nomedia`, dot-prefixed filenames, or a hidden UI row as file protection and does not reuse GPL code.

## Android platform constraints followed

- Android's [shared-media documentation](https://developer.android.com/training/data-storage/shared/media) recommends MediaStore queries/thumbnails for shared media, and its removal documentation requires recoverable user approval for other apps' media (including `createDeleteRequest()` on Android 11+). Therefore a UI cannot promise silent deletion: Compass waits for the platform result, checks whether the source is still discoverable, and reports a remaining public copy when it cannot verify removal.
- Android's [biometric sign-in guidance](https://developer.android.com/identity/sign-in/biometric-auth) recommends `BiometricManager`/`BiometricPrompt` and explicit handling of hardware, enrollment, cancellation, and failures. Newer devices use the system prompt; older Android releases need a PIN fallback. Compass never reads or stores biometric templates.
- Android's scoped-storage model distinguishes media collections from arbitrary documents. MediaStore can preserve a media volume/relative path and publish a restore only after a pending write finishes. SAF does not provide a portable way to recreate every deleted provider-specific parent, so document restoration uses the system create-document picker rather than guessing a path.

## Licensing and reuse

The inspected projects use Apache-2.0, AGPL-3.0, and GPL-3.0 licenses respectively. They were used as architecture/documentation references only; no third-party application implementation code was copied into Compass. Platform API use follows Android's own documentation.
