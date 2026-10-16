# Deviation: Compose → framework Views

**Status: disclosed, documented, and reversible.**

---

## 1. What was asked for

> "A complete, production-quality Android app ... Kotlin, Jetpack Compose,
> Material 3, single-activity, MVVM, coroutines, minSdk 26, target latest
> stable."

## 2. What was built

The app is written in **Kotlin**, single-activity, coroutine-based, with a
repository layer, and it targets `minSdk 26` / `targetSdk 35` - all as
requested.

The **UI layer is built on the Android framework's own `View` system**
instead of Jetpack Compose, and there is **no Material 3 dependency**
either. Everything else in the brief - the security requirements, the
monochrome design, the unlock mechanics, the import/export flows, the
file-by-file source order, the build instructions, the signed release APK -
is implemented as requested.

## 3. Why

The build machine for this project has **no access to Google Maven or Maven
Central** (both are network-blocked in this environment). That means:

* the Android Gradle Plugin could not be resolved,
* `androidx.compose.*`, `androidx.activity`, `androidx.lifecycle`,
  `androidx.biometric` - none of them could be resolved,
* the Compose compiler plugin had no runtime to work with.

A project that cannot resolve its dependencies cannot be compiled, cannot
be tested and cannot be signed. The only honest options were:

| Option | Outcome |
|---|---|
| Ship a Compose project that was never compiled | ❌ violates "do not deliver an unverified build" |
| **Ship a framework-only app that is compiled, tested and signed** | ✅ chosen |
| Drop the app | ❌ |

Writing the UI against the framework only also produced a **better**
security posture: the app now has exactly one third-party dependency
(`kotlinx-coroutines-core`, which has no permissions and no native code),
instead of the Compose runtime, AndroidX, and the Material library.

Everything that was lost is *presentation*: Compose gives you nicer
declarative code and less boilerplate. The behaviour, the data flow and the
security model are unchanged - and they are the parts that matter here.

---

## 4. What the framework version does instead

| Compose / Material 3 | This project |
|---|---|
| `@Composable` functions | `Screen` classes that build a `View` tree in `buildLayout()` |
| `setContent { ... }` | `MainActivity.show(screen)` swaps the root view |
| `remember { }` | plain fields on the screen class |
| `LaunchedEffect` | `CoroutineScope` per screen, started in `onShow()`, cancelled in `onHide()` |
| `Scaffold` / `TopAppBar` | a `LinearLayout` with a hairline below it |
| `MaterialTheme.colorScheme` | `ui/Design.kt` -> `Palette`, `Spacing`, `Type` |
| `Text` / `Button` | `Design.label()` / `Design.body()` / `Design.outlineButton()` |
| `Icons` | `ui/widget/CompassView.kt` draws the compass with `Canvas` |
| `Canvas` / `Modifier.drawBehind` | `View.onDraw()` with `Paint` |
| `rememberLazyListState` | `ListView` + `BaseAdapter` |
| `androidx.biometric` | `android.hardware.biometrics.BiometricPrompt` (the platform class that the library wraps) |
| `ViewModel` | the repository + `LockController` live in the `AppGraph` for the whole process |
| `Navigation Compose` | a `route` string in `MainActivity` |

The mapping is one-to-one and mechanical.

---

## 5. How to port it (the migration path)

### Step 1 - add the dependencies

In `gradle/libs.versions.toml`:

```toml
[versions]
composeBom = "2024.09.00"
activityCompose = "1.9.2"
lifecycle = "2.8.6"

[libraries]
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }

[plugins]
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

In `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)      // <- new
}

android {
    buildFeatures { compose = true }         // <- new
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.core)
}
```

And in `gradle.properties`:

```properties
android.useAndroidX=true
```

### Step 2 - the theme

The monochrome design system maps onto Material 3 like this:

```kotlin
// ui/theme/Theme.kt
private val CompassColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF2A2A2A),
    error = Color.White,          // an "error" in a monochrome app is still white
)

@Composable
fun CompassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CompassColors,
        typography = CompassTypography,   // Inter, see below
        content = content
    )
}
```

`Palette.WHITE` -> `Color.White`, `Palette.BLACK` -> `Color.Black`,
`Palette.GRAY` -> `Color(0xFFB4B4B4)`, `Palette.GRAY_DIM` ->
`Color(0xFF7A7A7A)`, `Palette.HAIRLINE` -> `Color(0xFF2A2A2A)`.

For the font, keep the bundled variable font and use it in the
`Typography`:

```kotlin
val InterFamily = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal),
    Font(R.font.inter_variable, weight = FontWeight.Medium),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold),
)

val CompassTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium),
    titleLarge  = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold),
    labelLarge  = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.18.sp),
    bodyLarge   = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal),
)
```

(Compose cannot use the *variable* axes of a variable font directly, so
the three static weights above are the usual approach.)

### Step 3 - port one screen

The compass screen is the best candidate, because it is the one that has
to look the most premium. The `Canvas` drawing translates almost 1:1:

```kotlin
@Composable
fun CompassScreen(
    heading: Float,
    unlockProgress: Float,
    onHold: (Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(320.dp)) {
            drawCircle(
                color = Color.White,
                style = Stroke(width = 1.2.dp.toPx()),
                radius = size.minDimension / 2f,
            )
            // ... the ticks, the letters and the needle come straight from
            // ui/widget/CompassView.kt
        }
        Text(
            text = "%03d°".format(heading.toInt()),
            color = Color.White,
            style = MaterialTheme.typography.displayLarge,
        )
    }
}
```

The rest of the app follows the same pattern:

| Framework screen | Compose screen | Notes |
|---|---|---|
| `CompassScreen` | `CompassScreen()` | the `Canvas` code moves over almost unchanged |
| `PinScreen` | `PinScreen()` | the keypad becomes a `LazyVerticalGrid` or a `Column` of `Row`s; `PinDotsView` becomes a `Row` of `Box`es |
| `SetupScreen` | `SetupScreen()` | a `when (step)` in a `Box` |
| `VaultScreen` | `VaultScreen()` | `LazyColumn` + `items()` |
| `ViewerScreen` | `ViewerScreen()` | `AndroidView` for `SurfaceView` + `MediaPlayer` |
| `SettingsScreen` | `SettingsScreen()` | a `Column` of `SettingRow`s |

### Step 4 - navigation

Replace the `route` string in `MainActivity` with
`androidx.navigation.compose.NavHost`, or keep the current one-screen-at-a-
time approach (it is honestly fine for six screens and it keeps the
`Screen.onShow()/onHide()` lifecycle, which the auto-lock relies on).

### Step 5 - do not touch the security layer

`security/`, `data/` and `util/` are **UI independent**. They do not
change at all during a Compose port. That is the whole point of putting
them in their own packages.

---

## 6. What does *not* change

Everything security relevant:

* `security/KeystoreCipher.kt` - AES-256-GCM, the Keystore key, the IVs.
* `security/PinHasher.kt`, `security/PinStore.kt` - the PIN hash, the
  lock-out, the decoy.
* `security/SecureWipe.kt` - the overwriting delete.
* `security/LockController.kt` - the auto-lock state machine.
* `data/VaultRepository.kt` - the streaming import/export, the thumbnails.
* `AndroidManifest.xml` - the missing `INTERNET` permission, `FLAG_SECURE`.
* The whole `tools/` directory - the build, the icon generator, the self
  test.

The Compose port is a **presentation layer** change. It cannot weaken the
security, as long as you do not add a dependency that declares
`INTERNET` - check the merged manifest after every port step:

```bash
./gradlew :app:processReleaseManifest
grep -r "INTERNET" app/build/intermediates/merged_manifest/ || echo "still offline"
```

---

## 7. Honest summary

| | |
|---|---|
| Requested | Jetpack Compose + Material 3 |
| Delivered | Framework `View`s, hand-drawn monochrome design system |
| Why | The Compose artifacts cannot be downloaded in this environment, and an uncompiled app cannot be verified |
| Risk | Low - the change is confined to `ui/`, is mechanical, and is documented step by step above |
| Effort to port | ~2-3 hours for all six screens, if you already know Compose |
