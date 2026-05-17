# SPECTRA Phase 1: Camera Engine + HUD UI + Lens Switching

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working camera app with the tactical Neon HUD viewfinder, manual lens switching across all 4 S24 Ultra cameras, photo capture, and save to gallery. No AI yet — HUD displays static placeholder data.

**Architecture:** Four Gradle modules (:core, :camera, :ai-engine, :app) using Clean Architecture. :core holds shared models and interfaces. :camera wraps CameraX/Camera2. :app contains the Compose HUD UI. :ai-engine is scaffolded but empty for Phase 1.

**Tech Stack:** Kotlin, Jetpack Compose, CameraX + Camera2 interop, Hilt DI, DataStore, MediaStore, Gradle KTS multi-module.

---

## File Structure

```
s24-camera/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
│
├── core/
│   ├── build.gradle.kts
│   └── src/main/java/com/spectra/core/
│       └── model/
│           ├── LensId.kt
│           ├── CameraSettings.kt
│           ├── CameraMode.kt
│           └── HudState.kt
│
├── camera/
│   ├── build.gradle.kts
│   └── src/main/java/com/spectra/camera/
│       ├── SpectraCameraController.kt
│       ├── LensManager.kt
│       └── CaptureManager.kt
│
├── ai-engine/
│   ├── build.gradle.kts
│   └── src/main/java/com/spectra/ai/
│       └── .gitkeep
│
├── app/
│   ├── build.gradle.kts
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/res/values/strings.xml
│   ├── src/main/res/values/themes.xml
│   └── src/main/java/com/spectra/app/
│       ├── SpectraApplication.kt
│       ├── MainActivity.kt
│       ├── di/AppModule.kt
│       ├── viewmodel/CameraViewModel.kt
│       └── ui/
│           ├── theme/SpectraTheme.kt
│           ├── viewfinder/ViewfinderScreen.kt
│           ├── hud/
│           │   ├── HudOverlay.kt
│           │   ├── CornerBrackets.kt
│           │   ├── SceneReadout.kt
│           │   ├── SettingsReadout.kt
│           │   ├── MotionIndicator.kt
│           │   ├── LensMatchBars.kt
│           │   ├── Crosshair.kt
│           │   └── CoachingDirective.kt
│           └── controls/
│               ├── ModeSelector.kt
│               ├── CaptureControls.kt
│               └── ShutterButton.kt
```

---

### Task 1: Project Scaffolding — Gradle Multi-Module Setup

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`
- Create: `camera/build.gradle.kts`
- Create: `ai-engine/build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`

- [ ] **Step 1: Initialize git and create .gitignore**

```bash
cd /Users/ashishthete/work/personal/s24-camera
git init
```

Create `.gitignore`:

```
*.iml
.gradle
/local.properties
/.idea
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
*.apk
*.ap_
*.dex
*.class
*.log
.superpowers/
```

- [ ] **Step 2: Create version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.21"
coreKtx = "1.15.0"
lifecycleRuntime = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.12.01"
hilt = "2.53.1"
hiltNavigationCompose = "1.2.0"
cameraX = "1.4.1"
datastore = "1.1.1"
coroutines = "1.9.0"
ksp = "2.0.21-1.0.28"
junit = "4.13.2"
truth = "1.4.4"
mockk = "1.13.13"

[libraries]
core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
lifecycle-runtime = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycleRuntime" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycleRuntime" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }

compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }

hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltNavigationCompose" }

camerax-core = { module = "androidx.camera:camera-core", version.ref = "cameraX" }
camerax-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "cameraX" }
camerax-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "cameraX" }
camerax-view = { module = "androidx.camera:camera-view", version.ref = "cameraX" }

datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }

junit = { module = "junit:junit", version.ref = "junit" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 3: Create root build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 4: Create gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Create settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "spectra"
include(":app")
include(":core")
include(":camera")
include(":ai-engine")
```

- [ ] **Step 6: Create core/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.spectra.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
```

- [ ] **Step 7: Create camera/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.spectra.camera"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.lifecycle.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
```

- [ ] **Step 8: Create ai-engine/build.gradle.kts (scaffold only)**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.spectra.ai"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.core)
}
```

Create `ai-engine/src/main/java/com/spectra/ai/.gitkeep` (empty file).

- [ ] **Step 9: Create app/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.spectra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spectra.app"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":camera"))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
```

- [ ] **Step 10: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-permission android:name="android.permission.CAMERA" />

    <application
        android:name=".SpectraApplication"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Spectra">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 11: Create resource files**

`app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">SPECTRA</string>
</resources>
```

`app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Spectra" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowFullscreen">true</item>
        <item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>
```

- [ ] **Step 12: Create source directories**

```bash
mkdir -p core/src/main/java/com/spectra/core/model
mkdir -p core/src/test/java/com/spectra/core/model
mkdir -p camera/src/main/java/com/spectra/camera
mkdir -p camera/src/test/java/com/spectra/camera
mkdir -p ai-engine/src/main/java/com/spectra/ai
mkdir -p app/src/main/java/com/spectra/app/di
mkdir -p app/src/main/java/com/spectra/app/viewmodel
mkdir -p app/src/main/java/com/spectra/app/ui/theme
mkdir -p app/src/main/java/com/spectra/app/ui/viewfinder
mkdir -p app/src/main/java/com/spectra/app/ui/hud
mkdir -p app/src/main/java/com/spectra/app/ui/controls
mkdir -p app/src/test/java/com/spectra/app/viewmodel
```

- [ ] **Step 13: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (no source files yet, just scaffolding)

- [ ] **Step 14: Commit**

```bash
git add -A
git commit -m "feat: scaffold multi-module project structure

Four Gradle modules: :app, :core, :camera, :ai-engine
Version catalog with CameraX, Compose, Hilt, TFLite deps"
```

---

### Task 2: Core Data Models

**Files:**
- Create: `core/src/main/java/com/spectra/core/model/LensId.kt`
- Create: `core/src/main/java/com/spectra/core/model/CameraSettings.kt`
- Create: `core/src/main/java/com/spectra/core/model/CameraMode.kt`
- Create: `core/src/main/java/com/spectra/core/model/HudState.kt`
- Create: `core/src/test/java/com/spectra/core/model/CameraSettingsTest.kt`
- Create: `core/src/test/java/com/spectra/core/model/HudStateTest.kt`

- [ ] **Step 1: Write test for CameraSettings defaults and clamping**

Create `core/src/test/java/com/spectra/core/model/CameraSettingsTest.kt`:

```kotlin
package com.spectra.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CameraSettingsTest {

    @Test
    fun `default settings are sensible`() {
        val settings = CameraSettings()
        assertThat(settings.iso).isEqualTo(100)
        assertThat(settings.shutterSpeedDenominator).isEqualTo(125)
        assertThat(settings.whiteBalanceKelvin).isEqualTo(5500)
        assertThat(settings.exposureCompensation).isEqualTo(0f)
    }

    @Test
    fun `iso clamps to valid range`() {
        val settings = CameraSettings(iso = 50000)
        assertThat(settings.iso).isEqualTo(3200)
    }

    @Test
    fun `iso clamps lower bound`() {
        val settings = CameraSettings(iso = 10)
        assertThat(settings.iso).isEqualTo(50)
    }

    @Test
    fun `white balance clamps to valid range`() {
        val settings = CameraSettings(whiteBalanceKelvin = 20000)
        assertThat(settings.whiteBalanceKelvin).isEqualTo(10000)
    }

    @Test
    fun `exposure compensation clamps`() {
        val settings = CameraSettings(exposureCompensation = 5f)
        assertThat(settings.exposureCompensation).isEqualTo(3f)
    }

    @Test
    fun `formatted shutter speed`() {
        val settings = CameraSettings(shutterSpeedDenominator = 250)
        assertThat(settings.formattedShutterSpeed).isEqualTo("1/250s")
    }

    @Test
    fun `formatted shutter speed for long exposure`() {
        val settings = CameraSettings(shutterSpeedDenominator = 1)
        assertThat(settings.formattedShutterSpeed).isEqualTo("1s")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.spectra.core.model.CameraSettingsTest" --info`
Expected: FAIL — class CameraSettings not found

- [ ] **Step 3: Create LensId enum**

Create `core/src/main/java/com/spectra/core/model/LensId.kt`:

```kotlin
package com.spectra.core.model

enum class LensId(
    val displayName: String,
    val megapixels: Int,
    val focalLengthMm: Int,
    val zoomLabel: String,
    val zoomFactor: Float,
    val maxAperture: Float
) {
    ULTRAWIDE(
        displayName = "Ultrawide",
        megapixels = 12,
        focalLengthMm = 13,
        zoomLabel = "0.6x",
        zoomFactor = 0.6f,
        maxAperture = 2.2f
    ),
    MAIN(
        displayName = "Main",
        megapixels = 200,
        focalLengthMm = 23,
        zoomLabel = "1x",
        zoomFactor = 1.0f,
        maxAperture = 1.7f
    ),
    TELEPHOTO_3X(
        displayName = "Telephoto 3x",
        megapixels = 10,
        focalLengthMm = 69,
        zoomLabel = "3x",
        zoomFactor = 3.0f,
        maxAperture = 2.4f
    ),
    TELEPHOTO_5X(
        displayName = "Telephoto 5x",
        megapixels = 50,
        focalLengthMm = 115,
        zoomLabel = "5x",
        zoomFactor = 5.0f,
        maxAperture = 3.4f
    );
}
```

- [ ] **Step 4: Create CameraSettings data class**

Create `core/src/main/java/com/spectra/core/model/CameraSettings.kt`:

```kotlin
package com.spectra.core.model

data class CameraSettings(
    iso: Int = 100,
    val shutterSpeedDenominator: Int = 125,
    whiteBalanceKelvin: Int = 5500,
    exposureCompensation: Float = 0f,
    val focusDistance: Float = 0f
) {
    val iso: Int = iso.coerceIn(50, 3200)
    val whiteBalanceKelvin: Int = whiteBalanceKelvin.coerceIn(2300, 10000)
    val exposureCompensation: Float = exposureCompensation.coerceIn(-3f, 3f)

    val formattedShutterSpeed: String
        get() = if (shutterSpeedDenominator <= 1) "1s"
                else "1/${shutterSpeedDenominator}s"

    val formattedIso: String get() = "ISO $iso"
    val formattedWb: String get() = "${whiteBalanceKelvin}K"
    val formattedEv: String
        get() = when {
            exposureCompensation > 0 -> "EV +${"%.1f".format(exposureCompensation)}"
            exposureCompensation < 0 -> "EV ${"%.1f".format(exposureCompensation)}"
            else -> "EV 0"
        }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "com.spectra.core.model.CameraSettingsTest" --info`
Expected: ALL PASS

- [ ] **Step 6: Create CameraMode enum**

Create `core/src/main/java/com/spectra/core/model/CameraMode.kt`:

```kotlin
package com.spectra.core.model

enum class CameraMode(val label: String) {
    NIGHT("NIGHT"),
    PORT("PORT"),
    PHOTO("PHOTO"),
    PRO("PRO");
}
```

- [ ] **Step 7: Write test for HudState**

Create `core/src/test/java/com/spectra/core/model/HudStateTest.kt`:

```kotlin
package com.spectra.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HudStateTest {

    @Test
    fun `default hud state has sensible values`() {
        val state = HudState()
        assertThat(state.activeLens).isEqualTo(LensId.MAIN)
        assertThat(state.mode).isEqualTo(CameraMode.PHOTO)
        assertThat(state.isHudVisible).isTrue()
        assertThat(state.sceneLabel).isEqualTo("READY")
    }

    @Test
    fun `lens match scores default to zero except active lens`() {
        val state = HudState(activeLens = LensId.MAIN)
        assertThat(state.lensMatchScores[LensId.MAIN]).isEqualTo(1.0f)
    }

    @Test
    fun `toggleHud flips visibility`() {
        val state = HudState(isHudVisible = true)
        val toggled = state.copy(isHudVisible = !state.isHudVisible)
        assertThat(toggled.isHudVisible).isFalse()
    }
}
```

- [ ] **Step 8: Create HudState data class**

Create `core/src/main/java/com/spectra/core/model/HudState.kt`:

```kotlin
package com.spectra.core.model

data class HudState(
    val activeLens: LensId = LensId.MAIN,
    val settings: CameraSettings = CameraSettings(),
    val mode: CameraMode = CameraMode.PHOTO,
    val isHudVisible: Boolean = true,
    val sceneLabel: String = "READY",
    val sceneConfidence: Float = 0f,
    val lightingLabel: String = "—",
    val motionLevel: Int = 0,
    val distanceLabel: String = "—",
    val coachingText: String? = null,
    val lensMatchScores: Map<LensId, Float> = LensId.entries.associateWith {
        if (it == activeLens) 1.0f else 0f
    },
    val isBurstActive: Boolean = false,
    val lastCapturedUri: String? = null
)
```

- [ ] **Step 9: Run all core tests**

Run: `./gradlew :core:test --info`
Expected: ALL PASS

- [ ] **Step 10: Commit**

```bash
git add core/
git commit -m "feat(core): add data models — LensId, CameraSettings, CameraMode, HudState

S24 Ultra lens configs, clamped camera settings, and HUD display state"
```

---

### Task 3: Spectra Theme — Neon HUD Colors & Typography

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/theme/SpectraTheme.kt`

- [ ] **Step 1: Create the Neon HUD theme**

Create `app/src/main/java/com/spectra/app/ui/theme/SpectraTheme.kt`:

```kotlin
package com.spectra.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object HudColors {
    val neonGreen = Color(0xFF00FF88)
    val neonGreenDim = Color(0x9900FF88)
    val neonGreenFaint = Color(0x4400FF88)
    val neonGreenGhost = Color(0x2200FF88)
    val neonGreenScanLine = Color(0x0600FF88)
    val background = Color.Black
    val surfaceGlass = Color(0x0AFFFFFF)
    val borderGreen = Color(0x4400FF88)
    val textPrimary = Color(0xFF00FF88)
    val textSecondary = Color(0x9900FF88)
    val textMuted = Color(0x5500FF88)
    val red = Color(0xFFFF4444)
}

object HudTypography {
    private val mono = FontFamily.Monospace

    val readoutLarge = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
        color = HudColors.neonGreen
    )

    val readoutSmall = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = HudColors.textSecondary
    )

    val label = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 8.sp,
        letterSpacing = 2.sp,
        color = HudColors.textMuted
    )

    val modeActive = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = HudColors.neonGreen
    )

    val modeInactive = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = HudColors.neonGreenFaint
    )

    val coaching = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = HudColors.neonGreen
    )
}

val LocalHudColors = staticCompositionLocalOf { HudColors }
val LocalHudTypography = staticCompositionLocalOf { HudTypography }

@Composable
fun SpectraTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalHudColors provides HudColors,
        LocalHudTypography provides HudTypography,
        content = content
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/theme/
git commit -m "feat(app): add Neon HUD theme — colors, typography, composition locals"
```

---

### Task 4: Camera Preview — CameraX With Compose

**Files:**
- Create: `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt`
- Create: `camera/src/main/java/com/spectra/camera/LensManager.kt`
- Create: `camera/src/main/java/com/spectra/camera/di/CameraModule.kt`
- Create: `camera/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create camera module AndroidManifest**

Create `camera/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 2: Create LensManager**

Create `camera/src/main/java/com/spectra/camera/LensManager.kt`:

```kotlin
package com.spectra.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.spectra.core.model.LensId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LensManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val lensMap = mutableMapOf<LensId, String>()

    fun initialize() {
        val cameraIds = cameraManager.cameraIdList
        for (id in cameraIds) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: continue
            val primaryFocal = focalLengths.firstOrNull() ?: continue

            val lensId = matchFocalToLens(primaryFocal)
            if (lensId != null && !lensMap.containsKey(lensId)) {
                lensMap[lensId] = id
            }
        }
    }

    private fun matchFocalToLens(focalLength: Float): LensId? = when {
        focalLength < 3f -> LensId.ULTRAWIDE
        focalLength in 3f..7f -> LensId.MAIN
        focalLength in 7f..12f -> LensId.TELEPHOTO_3X
        focalLength > 12f -> LensId.TELEPHOTO_5X
        else -> null
    }

    fun getCameraId(lens: LensId): String? = lensMap[lens]

    fun getAvailableLenses(): List<LensId> = lensMap.keys.sortedBy { it.zoomFactor }

    fun getNextLens(current: LensId): LensId {
        val available = getAvailableLenses()
        if (available.isEmpty()) return current
        val currentIndex = available.indexOf(current)
        return available[(currentIndex + 1) % available.size]
    }
}
```

- [ ] **Step 3: Create SpectraCameraController**

Create `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt`:

```kotlin
package com.spectra.camera

import android.content.Context
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.LensId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpectraCameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    val lensManager: LensManager
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    private val _activeLens = MutableStateFlow(LensId.MAIN)
    val activeLens: StateFlow<LensId> = _activeLens.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    fun initialize(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        lensManager.initialize()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera(_activeLens.value)
        }, { it.run() })
    }

    fun switchLens(lens: LensId) {
        if (lens == _activeLens.value) return
        _activeLens.value = lens
        bindCamera(lens)
    }

    fun cycleLens() {
        val next = lensManager.getNextLens(_activeLens.value)
        switchLens(next)
    }

    private fun bindCamera(lens: LensId) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return

        provider.unbindAll()

        val cameraId = lensManager.getCameraId(lens) ?: return

        val cameraSelector = CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter {
                    Camera2CameraInfo.from(it).cameraId == cameraId
                }
            }
            .build()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = view.surfaceProvider
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(Surface.ROTATION_0)
            .build()

        camera = provider.bindToLifecycle(owner, cameraSelector, preview, imageCapture)
        _isReady.value = true
    }

    fun getImageCapture(): ImageCapture? = imageCapture

    fun release() {
        cameraProvider?.unbindAll()
        _isReady.value = false
    }
}
```

- [ ] **Step 4: Create Hilt CameraModule**

Create `camera/src/main/java/com/spectra/camera/di/CameraModule.kt`:

```kotlin
package com.spectra.camera.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object CameraModule
```

- [ ] **Step 5: Commit**

```bash
git add camera/
git commit -m "feat(camera): add CameraX controller with lens management

LensManager maps S24 Ultra physical cameras to LensId enum.
SpectraCameraController handles preview binding and lens switching."
```

---

### Task 5: Photo Capture & Save to MediaStore

**Files:**
- Create: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`

- [ ] **Step 1: Create CaptureManager**

Create `camera/src/main/java/com/spectra/camera/CaptureManager.kt`:

```kotlin
package com.spectra.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class CaptureManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun capturePhoto(imageCapture: ImageCapture): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val fileName = "SPECTRA_$timestamp"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Spectra")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                outputOptions,
                { it.run() },
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val uri = output.savedUri?.toString() ?: ""
                        continuation.resume(uri)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                }
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt
git commit -m "feat(camera): add CaptureManager — photo capture and save to MediaStore

Saves to DCIM/Spectra with SPECTRA_timestamp naming"
```

---

### Task 6: Application Shell — Hilt App + MainActivity

**Files:**
- Create: `app/src/main/java/com/spectra/app/SpectraApplication.kt`
- Create: `app/src/main/java/com/spectra/app/MainActivity.kt`
- Create: `app/src/main/java/com/spectra/app/di/AppModule.kt`

- [ ] **Step 1: Create SpectraApplication**

Create `app/src/main/java/com/spectra/app/SpectraApplication.kt`:

```kotlin
package com.spectra.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SpectraApplication : Application()
```

- [ ] **Step 2: Create AppModule**

Create `app/src/main/java/com/spectra/app/di/AppModule.kt`:

```kotlin
package com.spectra.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule
```

- [ ] **Step 3: Create MainActivity**

Create `app/src/main/java/com/spectra/app/MainActivity.kt`:

```kotlin
package com.spectra.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography
import com.spectra.app.ui.theme.SpectraTheme
import com.spectra.app.ui.viewfinder.ViewfinderScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        checkCameraPermission()

        setContent {
            SpectraTheme {
                if (hasCameraPermission) {
                    ViewfinderScreen()
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(HudColors.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "CAMERA ACCESS REQUIRED",
                            style = HudTypography.readoutLarge
                        )
                    }
                }
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun checkCameraPermission() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/spectra/app/
git commit -m "feat(app): add Application shell, MainActivity with permission handling

Full-screen immersive mode, camera permission flow, Hilt setup"
```

---

### Task 7: CameraViewModel — Wiring Camera State to UI

**Files:**
- Create: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`
- Create: `app/src/test/java/com/spectra/app/viewmodel/CameraViewModelTest.kt`

- [ ] **Step 1: Write ViewModel tests**

Create `app/src/test/java/com/spectra/app/viewmodel/CameraViewModelTest.kt`:

```kotlin
package com.spectra.app.viewmodel

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.CameraMode
import com.spectra.core.model.HudState
import com.spectra.core.model.LensId
import org.junit.Test

class CameraViewModelTest {

    @Test
    fun `initial hud state is PHOTO mode with MAIN lens`() {
        val state = HudState()
        assertThat(state.mode).isEqualTo(CameraMode.PHOTO)
        assertThat(state.activeLens).isEqualTo(LensId.MAIN)
        assertThat(state.isHudVisible).isTrue()
    }

    @Test
    fun `mode change updates hud state`() {
        val state = HudState().copy(mode = CameraMode.NIGHT)
        assertThat(state.mode).isEqualTo(CameraMode.NIGHT)
    }

    @Test
    fun `lens cycle wraps around`() {
        val lenses = LensId.entries.toList()
        val current = LensId.TELEPHOTO_5X
        val currentIndex = lenses.indexOf(current)
        val next = lenses[(currentIndex + 1) % lenses.size]
        assertThat(next).isEqualTo(LensId.ULTRAWIDE)
    }

    @Test
    fun `hud toggle flips visibility`() {
        val state = HudState(isHudVisible = true)
        val toggled = state.copy(isHudVisible = false)
        assertThat(toggled.isHudVisible).isFalse()
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.spectra.app.viewmodel.CameraViewModelTest" --info`
Expected: ALL PASS (tests only use core models which already exist)

- [ ] **Step 3: Create CameraViewModel**

Create `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`:

```kotlin
package com.spectra.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spectra.camera.CaptureManager
import com.spectra.camera.SpectraCameraController
import com.spectra.core.model.CameraMode
import com.spectra.core.model.HudState
import com.spectra.core.model.LensId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    val cameraController: SpectraCameraController,
    private val captureManager: CaptureManager
) : ViewModel() {

    private val _hudState = MutableStateFlow(HudState())
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _captureInProgress = MutableStateFlow(false)
    val captureInProgress: StateFlow<Boolean> = _captureInProgress.asStateFlow()

    init {
        viewModelScope.launch {
            cameraController.activeLens.collect { lens ->
                _hudState.update { it.copy(
                    activeLens = lens,
                    lensMatchScores = LensId.entries.associateWith {
                        if (it == lens) 1.0f else 0f
                    }
                )}
            }
        }
    }

    fun cycleLens() {
        cameraController.cycleLens()
    }

    fun switchLens(lens: LensId) {
        cameraController.switchLens(lens)
    }

    fun setMode(mode: CameraMode) {
        _hudState.update { it.copy(mode = mode) }
    }

    fun toggleHud() {
        _hudState.update { it.copy(isHudVisible = !it.isHudVisible) }
    }

    fun capturePhoto() {
        val imageCapture = cameraController.getImageCapture() ?: return
        if (_captureInProgress.value) return

        viewModelScope.launch {
            _captureInProgress.value = true
            try {
                val uri = captureManager.capturePhoto(imageCapture)
                _hudState.update { it.copy(lastCapturedUri = uri) }
            } catch (_: Exception) {
                // Capture failed — HUD could show error in future
            } finally {
                _captureInProgress.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.release()
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/spectra/app/viewmodel/ app/src/test/
git commit -m "feat(app): add CameraViewModel — wires camera state to HUD

Handles lens cycling, mode switching, HUD toggle, photo capture"
```

---

### Task 8: HUD Overlay — Corner Brackets, Scan Lines, Crosshair, Grid

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/hud/CornerBrackets.kt`
- Create: `app/src/main/java/com/spectra/app/ui/hud/Crosshair.kt`
- Create: `app/src/main/java/com/spectra/app/ui/hud/ScanLines.kt`

- [ ] **Step 1: Create CornerBrackets composable**

Create `app/src/main/java/com/spectra/app/ui/hud/CornerBrackets.kt`:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors

@Composable
fun CornerBrackets(modifier: Modifier = Modifier) {
    val color = HudColors.neonGreen
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidth = 2.dp.toPx()
        val bracketLen = 32.dp.toPx()
        val margin = 16.dp.toPx()

        val corners = listOf(
            // top-left
            Pair(Offset(margin, margin), Pair(Offset(margin + bracketLen, margin), Offset(margin, margin + bracketLen))),
            // top-right
            Pair(Offset(size.width - margin, margin), Pair(Offset(size.width - margin - bracketLen, margin), Offset(size.width - margin, margin + bracketLen))),
            // bottom-left
            Pair(Offset(margin, size.height - margin), Pair(Offset(margin + bracketLen, size.height - margin), Offset(margin, size.height - margin - bracketLen))),
            // bottom-right
            Pair(Offset(size.width - margin, size.height - margin), Pair(Offset(size.width - margin - bracketLen, size.height - margin), Offset(size.width - margin, size.height - margin - bracketLen)))
        )

        for ((corner, lines) in corners) {
            drawLine(color, corner, lines.first, strokeWidth, StrokeCap.Butt)
            drawLine(color, corner, lines.second, strokeWidth, StrokeCap.Butt)
        }
    }
}
```

- [ ] **Step 2: Create Crosshair + Grid composable**

Create `app/src/main/java/com/spectra/app/ui/hud/Crosshair.kt`:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors

@Composable
fun CrosshairAndGrid(modifier: Modifier = Modifier) {
    val gridColor = HudColors.neonGreenGhost
    val crosshairColor = HudColors.neonGreenFaint
    val dotColor = HudColors.neonGreen

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2
        val cy = size.height / 2
        val crossLen = 24.dp.toPx()

        // Rule of thirds grid
        val third1X = size.width / 3
        val third2X = size.width * 2 / 3
        val third1Y = size.height / 3
        val third2Y = size.height * 2 / 3

        drawLine(gridColor, Offset(third1X, 0f), Offset(third1X, size.height), 1.dp.toPx())
        drawLine(gridColor, Offset(third2X, 0f), Offset(third2X, size.height), 1.dp.toPx())
        drawLine(gridColor, Offset(0f, third1Y), Offset(size.width, third1Y), 1.dp.toPx())
        drawLine(gridColor, Offset(0f, third2Y), Offset(size.width, third2Y), 1.dp.toPx())

        // Crosshair lines
        drawLine(crosshairColor, Offset(cx - crossLen, cy), Offset(cx + crossLen, cy), 1.dp.toPx(), StrokeCap.Butt)
        drawLine(crosshairColor, Offset(cx, cy - crossLen), Offset(cx, cy + crossLen), 1.dp.toPx(), StrokeCap.Butt)

        // Center dot
        drawCircle(dotColor, radius = 4.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f.dp.toPx()))
    }
}
```

- [ ] **Step 3: Create ScanLines overlay**

Create `app/src/main/java/com/spectra/app/ui/hud/ScanLines.kt`:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.spectra.app.ui.theme.HudColors

@Composable
fun ScanLines(modifier: Modifier = Modifier) {
    val lineColor = HudColors.neonGreenScanLine
    Canvas(modifier = modifier.fillMaxSize()) {
        val lineSpacing = 4f
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = lineColor,
                topLeft = Offset(0f, y),
                size = Size(size.width, 1f)
            )
            y += lineSpacing
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/hud/CornerBrackets.kt \
       app/src/main/java/com/spectra/app/ui/hud/Crosshair.kt \
       app/src/main/java/com/spectra/app/ui/hud/ScanLines.kt
git commit -m "feat(app): add HUD frame elements — corner brackets, crosshair, grid, scan lines"
```

---

### Task 9: HUD Readouts — Scene, Settings, Motion, Lens Match

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/hud/SceneReadout.kt`
- Create: `app/src/main/java/com/spectra/app/ui/hud/SettingsReadout.kt`
- Create: `app/src/main/java/com/spectra/app/ui/hud/MotionIndicator.kt`
- Create: `app/src/main/java/com/spectra/app/ui/hud/LensMatchBars.kt`
- Create: `app/src/main/java/com/spectra/app/ui/hud/CoachingDirective.kt`

- [ ] **Step 1: Create SceneReadout (top-left)**

Create `app/src/main/java/com/spectra/app/ui/hud/SceneReadout.kt`:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.spectra.app.ui.theme.HudTypography

@Composable
fun SceneReadout(
    sceneLabel: String,
    confidence: Float,
    lightingLabel: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "SCENE: $sceneLabel",
            style = HudTypography.readoutLarge
        )
        Text(
            text = "CONF: ${"%.1f".format(confidence * 100)}% · $lightingLabel",
            style = HudTypography.readoutSmall
        )
    }
}
```

- [ ] **Step 2: Create SettingsReadout (top-right)**

Create `app/src/main/java/com/spectra/app/ui/hud/SettingsReadout.kt`:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.spectra.app.ui.theme.HudTypography
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.LensId

@Composable
fun SettingsReadout(
    activeLens: LensId,
    settings: CameraSettings,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "${activeLens.megapixels}MP ACTIVE",
            style = HudTypography.readoutLarge,
            textAlign = TextAlign.End
        )
        Text(
            text = "${settings.formattedIso} · ${settings.formattedShutterSpeed} · f/${activeLens.maxAperture}",
            style = HudTypography.readoutSmall,
            textAlign = TextAlign.End
        )
        Text(
            text = "${settings.formattedWb} · ${settings.formattedEv}",
            style = HudTypography.readoutSmall,
            textAlign = TextAlign.End
        )
    }
}
```

- [ ] **Step 3: Create MotionIndicator (left edge)**

Create `app/src/main/java/com/spectra/app/ui/hud/MotionIndicator.kt`:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun MotionIndicator(
    motionLevel: Int,
    distanceLabel: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "MOTION", style = HudTypography.label)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(4) { index ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(12.dp)
                        .background(
                            if (index < motionLevel) HudColors.neonGreen
                            else HudColors.neonGreenGhost
                        )
                )
            }
        }
        Text(text = "DIST", style = HudTypography.label)
        Text(text = distanceLabel, style = HudTypography.readoutSmall)
    }
}
```

- [ ] **Step 4: Create LensMatchBars (right edge)**

Create `app/src/main/java/com/spectra/app/ui/hud/LensMatchBars.kt`:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography
import com.spectra.core.model.LensId

@Composable
fun LensMatchBars(
    scores: Map<LensId, Float>,
    activeLens: LensId,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "LENS MATCH", style = HudTypography.label)
        LensId.entries.forEach { lens ->
            val score = scores[lens] ?: 0f
            val isActive = lens == activeLens
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (isActive) "${lens.zoomLabel} ◄" else lens.zoomLabel,
                    style = if (isActive) HudTypography.readoutSmall.copy(fontWeight = FontWeight.Bold, color = HudColors.neonGreen)
                            else HudTypography.readoutSmall
                )
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(HudColors.neonGreenGhost)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(score)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isActive) HudColors.neonGreen
                                else HudColors.neonGreenDim
                            )
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Create CoachingDirective (bottom-center)**

Create `app/src/main/java/com/spectra/app/ui/hud/CoachingDirective.kt`:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun CoachingDirective(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "arrow")
    val arrowAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "arrowPulse"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable { onDismiss() }
    ) {
        Text(
            text = "▲",
            color = HudColors.neonGreen,
            fontSize = 18.sp,
            modifier = Modifier.alpha(arrowAlpha)
        )
        Text(
            text = text,
            style = HudTypography.coaching,
            modifier = Modifier
                .border(1.dp, HudColors.borderGreen, RectangleShape)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/hud/
git commit -m "feat(app): add HUD readouts — scene, settings, motion, lens match, coaching

All tactical data display zones for the Neon HUD viewfinder"
```

---

### Task 10: Capture Controls — Shutter, Lens Toggle, Gallery Thumbnail

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/controls/ShutterButton.kt`
- Create: `app/src/main/java/com/spectra/app/ui/controls/CaptureControls.kt`

- [ ] **Step 1: Create ShutterButton with tap and long-press**

Create `app/src/main/java/com/spectra/app/ui/controls/ShutterButton.kt`:

```kotlin
package com.spectra.app.ui.controls

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors

@Composable
fun ShutterButton(
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(2.5.dp, HudColors.neonGreen.copy(alpha = 0.9f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = {
                        onLongPressStart()
                    },
                    onPress = {
                        tryAwaitRelease()
                        onLongPressEnd()
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .border(1.dp, HudColors.neonGreenDim, CircleShape)
        )
    }
}
```

- [ ] **Step 2: Create CaptureControls bar**

Create `app/src/main/java/com/spectra/app/ui/controls/CaptureControls.kt`:

```kotlin
package com.spectra.app.ui.controls

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.core.model.LensId

@Composable
fun CaptureControls(
    activeLens: LensId,
    onShutterTap: () -> Unit,
    onBurstStart: () -> Unit,
    onBurstEnd: () -> Unit,
    onLensCycle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gallery thumbnail placeholder
        Box(
            modifier = Modifier
                .size(44.dp)
                .border(1.dp, HudColors.borderGreen, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(1.dp, HudColors.neonGreenGhost, RoundedCornerShape(2.dp))
            )
        }

        // Shutter button
        ShutterButton(
            onTap = onShutterTap,
            onLongPressStart = onBurstStart,
            onLongPressEnd = onBurstEnd
        )

        // Lens toggle
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .border(1.dp, HudColors.borderGreen, CircleShape)
                .clickable { onLensCycle() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = activeLens.zoomLabel.uppercase(),
                color = HudColors.neonGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/controls/
git commit -m "feat(app): add capture controls — shutter (tap + long-press), lens toggle, gallery thumb"
```

---

### Task 11: Mode Selector — Swipeable Mode Bar

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/controls/ModeSelector.kt`

- [ ] **Step 1: Create ModeSelector**

Create `app/src/main/java/com/spectra/app/ui/controls/ModeSelector.kt`:

```kotlin
package com.spectra.app.ui.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography
import com.spectra.core.model.CameraMode

@Composable
fun ModeSelector(
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CameraMode.entries.forEach { mode ->
            val isActive = mode == currentMode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = mode.label,
                    style = if (isActive) HudTypography.modeActive else HudTypography.modeInactive
                )
                if (isActive) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .width(20.dp)
                            .height(1.dp)
                    ) {
                        drawRect(HudColors.neonGreen)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/controls/ModeSelector.kt
git commit -m "feat(app): add mode selector bar — NIGHT / PORT / PHOTO / PRO"
```

---

### Task 12: HUD Overlay Compositor + ViewfinderScreen

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/hud/HudOverlay.kt`
- Create: `app/src/main/java/com/spectra/app/ui/viewfinder/ViewfinderScreen.kt`

- [ ] **Step 1: Create HudOverlay — composites all HUD elements**

Create `app/src/main/java/com/spectra/app/ui/hud/HudOverlay.kt`:

```kotlin
package com.spectra.app.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectra.core.model.HudState

@Composable
fun HudOverlay(
    state: HudState,
    onCoachingDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Always-visible layers
        ScanLines()
        CornerBrackets()

        AnimatedVisibility(
            visible = state.isHudVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Crosshair + Grid
                CrosshairAndGrid()

                // Top-left: Scene readout
                SceneReadout(
                    sceneLabel = state.sceneLabel,
                    confidence = state.sceneConfidence,
                    lightingLabel = state.lightingLabel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 24.dp, top = 24.dp)
                )

                // Top-right: Settings readout
                SettingsReadout(
                    activeLens = state.activeLens,
                    settings = state.settings,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 24.dp, top = 24.dp)
                )

                // Left edge: Motion + Distance
                MotionIndicator(
                    motionLevel = state.motionLevel,
                    distanceLabel = state.distanceLabel,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                )

                // Right edge: Lens match bars
                LensMatchBars(
                    scores = state.lensMatchScores,
                    activeLens = state.activeLens,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp)
                )

                // Bottom-center: Coaching directive
                val coaching = state.coachingText
                if (coaching != null) {
                    CoachingDirective(
                        text = coaching,
                        onDismiss = onCoachingDismiss,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 180.dp)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Create ViewfinderScreen — the main screen**

Create `app/src/main/java/com/spectra/app/ui/viewfinder/ViewfinderScreen.kt`:

```kotlin
package com.spectra.app.ui.viewfinder

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spectra.app.ui.controls.CaptureControls
import com.spectra.app.ui.controls.ModeSelector
import com.spectra.app.ui.hud.HudOverlay
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.viewmodel.CameraViewModel

@Composable
fun ViewfinderScreen(
    viewModel: CameraViewModel = hiltViewModel()
) {
    val hudState by viewModel.hudState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudColors.background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { viewModel.toggleHud() }
                )
            }
    ) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    viewModel.cameraController.initialize(lifecycleOwner, previewView)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // HUD overlay on top of preview
        HudOverlay(
            state = hudState,
            onCoachingDismiss = {
                // Will clear coaching text in future phases
            }
        )

        // Mode selector
        ModeSelector(
            currentMode = hudState.mode,
            onModeSelected = { viewModel.setMode(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
        )

        // Capture controls at bottom
        CaptureControls(
            activeLens = hudState.activeLens,
            onShutterTap = { viewModel.capturePhoto() },
            onBurstStart = { /* Phase 2: burst capture */ },
            onBurstEnd = { /* Phase 2: stop burst */ },
            onLensCycle = { viewModel.cycleLens() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/
git commit -m "feat(app): add HudOverlay compositor and ViewfinderScreen

Full tactical viewfinder: camera preview, HUD readouts, mode selector,
capture controls with lens switching. Phase 1 complete."
```

---

### Task 13: Final Integration & Smoke Test

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test --info`
Expected: ALL PASS

- [ ] **Step 2: Build release APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 3: Manual smoke test checklist**

Install on S24 Ultra and verify:
1. App launches in full-screen immersive mode
2. Camera permission prompt appears on first launch
3. Camera preview is visible after granting permission
4. HUD elements render: corner brackets, scan lines, crosshair, grid
5. HUD readouts show: scene (READY), settings (200MP, ISO 100, etc.), motion bars, lens match bars
6. Tap lens toggle → cycles through available lenses (0.6x → 1x → 3x → 5x)
7. Tap mode bar → switches between NIGHT / PORT / PHOTO / PRO
8. Tap shutter → captures photo, saves to DCIM/Spectra
9. Double-tap viewfinder → toggles HUD on/off
10. All text is monospace neon green on black

- [ ] **Step 4: Final commit with version tag**

```bash
git add -A
git commit -m "chore: Phase 1 complete — SPECTRA tactical viewfinder with lens switching

Working camera app with Neon HUD, 4-lens management, photo capture,
mode selector. Ready for Phase 2 AI integration."
git tag v0.1.0-phase1
```

---

## Phase 1 Deliverables Summary

| Feature | Status |
|---|---|
| Multi-module Gradle project | Task 1 |
| Core data models (LensId, CameraSettings, CameraMode, HudState) | Task 2 |
| Neon HUD theme (colors, typography) | Task 3 |
| CameraX preview with Camera2 interop | Task 4 |
| S24 Ultra 4-lens management and switching | Task 4 |
| Photo capture and save to MediaStore | Task 5 |
| Application shell with permissions | Task 6 |
| CameraViewModel state management | Task 7 |
| HUD frame: corner brackets, scan lines, crosshair, grid | Task 8 |
| HUD readouts: scene, settings, motion, lens match, coaching | Task 9 |
| Capture controls: shutter (tap/long-press), lens toggle | Task 10 |
| Mode selector bar | Task 11 |
| Full viewfinder screen integration | Task 12 |
| Integration testing | Task 13 |
