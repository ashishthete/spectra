# SPECTRA Play Store Publish Pipeline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Set up everything needed to build a signed release APK/AAB, create the Play Store listing, and establish a GitHub Actions CI/CD pipeline that automatically publishes to Play Store internal track on every tagged release.

**Architecture:** Release signing via a `keystore.properties` file (git-ignored) referencing a JKS keystore. GitHub Actions builds and signs the AAB, then Fastlane `supply` pushes it to the Play Console internal track. Play Store listing metadata (descriptions, changelogs, screenshots) lives in `fastlane/metadata/android/` following Fastlane's convention so it can be updated from code.

**Tech Stack:** Gradle (signing + bundling), GitHub Actions (CI), Fastlane Supply (Play Store upload), Google Play Console (listing + review)

---

## Pre-Requisites (Manual Steps — Not Automated)

Before starting the tasks below, the developer must complete these one-time manual steps:

1. **Create a Google Play Developer account** at https://play.google.com/console — costs $25 one-time fee
2. **Create the app** in Play Console: click "Create app", name it "SPECTRA", select "App" (not Game), Free
3. **Create a Google Cloud service account** for API access:
   - Play Console → Setup → API access → Link a Google Cloud project
   - Create a service account with "Service Account User" role
   - Grant it "Release manager" access in Play Console → Users & permissions
   - Download the JSON key file — save it as `play-store-key.json` (never commit this)
4. **Upload a first AAB manually** via Play Console → Internal testing → Create release. Google requires the first upload to be manual. After that, Fastlane can automate subsequent uploads.

---

## File Structure

```
s24-camera/
├── keystore.properties              (NEW — git-ignored, local signing config)
├── release.keystore                 (NEW — git-ignored, signing keystore)
├── app/
│   └── build.gradle.kts             (MODIFY — add signingConfigs, versionCode bump, AAB)
├── .github/
│   └── workflows/
│       └── release.yml              (NEW — CI/CD pipeline)
├── fastlane/
│   ├── Appfile                      (NEW — app package + JSON key path)
│   ├── Fastfile                     (NEW — lanes: test, build, deploy)
│   └── metadata/
│       └── android/
│           └── en-US/
│               ├── title.txt        (NEW — app name, max 30 chars)
│               ├── short_description.txt  (NEW — max 80 chars)
│               ├── full_description.txt   (NEW — max 4000 chars)
│               └── changelogs/
│                   └── 1.txt        (NEW — changelog for versionCode 1)
├── Gemfile                          (NEW — fastlane dependency)
├── .gitignore                       (MODIFY — add signing files)
└── docs/
    └── play-store/
        ├── privacy-policy.md        (NEW — required by Play Store)
        └── store-listing-guide.md   (NEW — screenshot specs, feature graphic specs)
```

---

### Task 1: Generate Release Keystore and Signing Config

**Files:**
- Create: `keystore.properties`
- Create: `release.keystore` (via keytool)
- Modify: `.gitignore`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add signing files to .gitignore**

Add these lines to the end of `/Users/ashishthete/work/personal/s24-camera/.gitignore`:

```
# Release signing
keystore.properties
*.keystore
*.jks
play-store-key.json
fastlane/report.xml
fastlane/README.md
```

- [ ] **Step 2: Generate the release keystore**

Run:
```bash
keytool -genkeypair \
  -alias spectra-release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore release.keystore \
  -storepass <YOUR_STORE_PASSWORD> \
  -keypass <YOUR_KEY_PASSWORD> \
  -dname "CN=Ashish Thete, OU=SPECTRA, O=SPECTRA, L=Unknown, ST=Unknown, C=IN"
```

Expected: `release.keystore` file created (2-3KB). **Save the passwords somewhere secure — you cannot recover them.**

- [ ] **Step 3: Create keystore.properties**

Create `/Users/ashishthete/work/personal/s24-camera/keystore.properties`:

```properties
storeFile=../release.keystore
storePassword=YOUR_STORE_PASSWORD_HERE
keyAlias=spectra-release
keyPassword=YOUR_KEY_PASSWORD_HERE
```

- [ ] **Step 4: Add signingConfigs to app/build.gradle.kts**

Replace the entire `android { }` block in `app/build.gradle.kts` with:

```kotlin
android {
    namespace = "com.spectra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spectra.app"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val props = java.util.Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                props.load(propsFile.inputStream())
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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

    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }
}
```

- [ ] **Step 5: Build a signed release AAB locally to verify**

Run:
```bash
./gradlew :app:bundleRelease --no-daemon
```

Expected: `app/build/outputs/bundle/release/app-release.aab` created (~15-30MB). No "unsigned" warnings.

- [ ] **Step 6: Verify the AAB is signed**

Run:
```bash
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab 2>&1 | head -5
```

Expected: Output contains "jar verified".

- [ ] **Step 7: Commit**

```bash
git add .gitignore app/build.gradle.kts
git commit -m "feat: add release signing config and AAB bundling"
```

---

### Task 2: Play Store Listing Metadata (Fastlane Format)

**Files:**
- Create: `fastlane/metadata/android/en-US/title.txt`
- Create: `fastlane/metadata/android/en-US/short_description.txt`
- Create: `fastlane/metadata/android/en-US/full_description.txt`
- Create: `fastlane/metadata/android/en-US/changelogs/1.txt`

- [ ] **Step 1: Create the metadata directory structure**

Run:
```bash
mkdir -p fastlane/metadata/android/en-US/changelogs
mkdir -p fastlane/metadata/android/en-US/images/phoneScreenshots
mkdir -p fastlane/metadata/android/en-US/images/featureGraphic
```

- [ ] **Step 2: Create title.txt**

Create `fastlane/metadata/android/en-US/title.txt` (max 30 characters):

```
SPECTRA - AI Camera
```

- [ ] **Step 3: Create short_description.txt**

Create `fastlane/metadata/android/en-US/short_description.txt` (max 80 characters):

```
AI-powered camera with scene detection, smart settings, and pro-grade processing
```

- [ ] **Step 4: Create full_description.txt**

Create `fastlane/metadata/android/en-US/full_description.txt` (max 4000 characters):

```
SPECTRA is an intelligent camera app that uses AI to analyze every scene and automatically dial in professional-grade settings — so you get stunning photos without touching a single slider.

SMART SCENE DETECTION
SPECTRA identifies what you're shooting — portraits, landscapes, food, macro, night, action — and applies the optimal ISO, shutter speed, and white balance in real-time. No more blown highlights or murky shadows.

AI COACHING
Real-time coaching tips appear in the viewfinder: "Hold steady", "Backlit subject — tap face to brighten", "Step back and use 3x — face will look more natural". SPECTRA teaches you photography while you shoot.

PROFESSIONAL PROCESSING PIPELINE
Every photo passes through a multi-stage pipeline:
- Bilateral noise reduction in YCbCr with ISO-adaptive strength
- Guided filter tone mapping for natural HDR
- Lab-space skin tone correction for accurate, diverse skin rendering
- Laplacian pyramid edge-aware sharpening
- Scene-adaptive filmic tone curves
- Mertens exposure fusion for high-contrast scenes

PORTRAIT MODE
Depth-aware bokeh with ML-based depth estimation. Subject isolation, depth-selective warmth, and computational portrait lighting (Studio, Contour, Stage, High-Key modes).

MULTI-FRAME CAPTURE
Adaptive frame count based on stability, motion, and ISO. Burst merge with sub-pixel alignment for maximum detail and minimum noise.

PRO MODE
Full manual control: ISO, shutter speed, white balance, focus distance. Live histogram, focus peaking, zebra stripes, false color exposure, and waveform monitor.

SMART REVIEW
Side-by-side comparison of original vs. AI-enhanced photo. Quality scoring ensures AI never makes your photo worse — if it does, the original is kept automatically.

TIERED EXPERIENCE
- Everyday: Simple, jargon-free interface. "Cleaned up grain" instead of "Bilateral NR".
- Creator: Photography terminology. Practical tips.
- Pro: Full technical details. All manual controls unlocked.

ZERO SHUTTER LAG
Pre-buffered frames mean you never miss the decisive moment.

PRIVACY
All AI processing happens on-device. No photos are uploaded to any server. Your photos stay yours.

REQUIREMENTS
- Samsung Galaxy S24 Ultra (optimized for S24 hardware)
- Android 14+
```

- [ ] **Step 5: Create changelog for versionCode 1**

Create `fastlane/metadata/android/en-US/changelogs/1.txt` (max 500 characters):

```
Initial release of SPECTRA Camera.

- AI scene detection with 8 presets (Auto, Portrait, Landscape, Night, Food, Action, Macro, Pro)
- Real-time coaching with adaptive hints
- Multi-frame HDR with Mertens fusion
- Depth-aware portrait bokeh
- Lab-space skin tone correction
- Edge-aware Laplacian sharpening
- Smart review with original vs. AI comparison
- Pro mode with histogram, focus peaking, zebras
- Zero shutter lag capture
```

- [ ] **Step 6: Commit**

```bash
git add fastlane/metadata/
git commit -m "feat: add Play Store listing metadata"
```

---

### Task 3: Privacy Policy

**Files:**
- Create: `docs/play-store/privacy-policy.md`

Google Play requires a privacy policy URL for apps that use camera, location, or internet permissions.

- [ ] **Step 1: Create privacy policy**

Create `docs/play-store/privacy-policy.md`:

```markdown
# SPECTRA Camera — Privacy Policy

**Effective Date:** 2026-05-20
**Developer:** Ashish Thete
**Contact:** thete.ashish1@gmail.com

## Overview

SPECTRA Camera ("the App") is designed with privacy as a core principle. All AI processing, scene analysis, and image enhancement happen entirely on your device. No photos, videos, or camera data are transmitted to external servers.

## Data Collection

### Camera & Photos
The App accesses your device camera to capture photos and videos. All captured media is stored locally on your device in the standard Android media gallery. The App does not upload, transmit, or share your photos with any third party.

### Location (Optional)
If you grant location permission, the App may embed GPS coordinates in photo EXIF metadata — the same behavior as your device's built-in camera. Location data is stored only in the photo file on your device. You can deny location permission and the App will function normally without it.

### Device Sensors
The App reads accelerometer and gyroscope data to detect motion, level the horizon, and stabilize video. This sensor data is processed in real-time and not stored or transmitted.

### AI Processing
All machine learning inference (scene classification, depth estimation, face detection) runs on-device using TensorFlow Lite and ML Kit. No image data leaves your device for AI processing.

### Analytics
The App does not include any third-party analytics SDKs. No usage data, crash reports, or behavioral data is collected or transmitted.

### Internet Permission
The Internet permission is declared for optional cloud coaching tips (text-only suggestions, no image data transmitted). This feature can be disabled. No personal data is sent.

## Data Sharing

SPECTRA Camera does not sell, share, or transfer any user data to third parties.

## Data Retention

All data remains on your device. Deleting the App removes all App data. Photos in your gallery are not affected by App deletion.

## Children's Privacy

The App does not knowingly collect data from children under 13. The App does not contain ads or in-app purchases.

## Changes to This Policy

Updates to this policy will be posted at this URL. The effective date at the top indicates the latest revision.

## Contact

For privacy questions: thete.ashish1@gmail.com
```

- [ ] **Step 2: Host the privacy policy**

You need a public URL for this privacy policy. Options (pick one):
- **GitHub Pages:** Push the repo to GitHub, enable Pages on `docs/` folder. URL will be `https://<username>.github.io/<repo>/play-store/privacy-policy`
- **GitHub Gist:** Create a public gist with the content. Simpler, no repo needed.
- **Google Sites / Notion:** Create a free page, paste the content.

Whichever you choose, note the URL — you'll enter it in Play Console during Task 7.

- [ ] **Step 3: Commit**

```bash
git add docs/play-store/privacy-policy.md
git commit -m "docs: add privacy policy for Play Store"
```

---

### Task 4: Store Listing Guide (Screenshot & Graphic Specs)

**Files:**
- Create: `docs/play-store/store-listing-guide.md`

- [ ] **Step 1: Create the store listing guide**

Create `docs/play-store/store-listing-guide.md`:

```markdown
# SPECTRA — Play Store Listing Guide

## Required Assets

### App Icon
- **Size:** 512 x 512px PNG (32-bit, alpha)
- **Source:** Generated from `app/src/main/res/drawable/ic_launcher_foreground.xml` + background
- **Generate:** Android Studio → Right-click res → New → Image Asset → Launcher Icons

### Feature Graphic
- **Size:** 1024 x 500px JPEG/PNG
- **Content:** App name "SPECTRA" + tagline "AI Camera" + sample photo showing the viewfinder UI
- **Save to:** `fastlane/metadata/android/en-US/images/featureGraphic/1.png`

### Phone Screenshots (minimum 2, recommended 8)
- **Size:** 1080 x 2400px (or 2400 x 1080 for landscape) JPEG/PNG
- **Required screenshots (capture on actual S24 Ultra):**

| # | Screen | What to show |
|---|--------|--------------|
| 1 | Viewfinder | AI coaching hint visible, scene label showing "Portrait" |
| 2 | Viewfinder | Pro mode with histogram, focus peaking |
| 3 | Smart Review | Side-by-side original vs. AI enhanced |
| 4 | Viewfinder | Night mode with "Hold steady" coaching |
| 5 | Viewfinder | Landscape mode with horizon level indicator |
| 6 | Viewfinder | Portrait bokeh result |
| 7 | Settings | Preset selector showing all 8 presets |
| 8 | AI Explainer | Post-capture explainer showing processing stages |

- **Save to:** `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png` through `8.png`

## Play Console Content Rating

When asked during setup:
- **Violence:** None
- **Sexual content:** None
- **Language:** None
- **Controlled substances:** None
- **Gambling:** None
- **User-generated content:** No (app doesn't upload/share content)
- **Accounts:** No (no user accounts)

Rating result: **Rated for Everyone (IARC 3+)**

## Play Console Data Safety

When filling out the Data Safety form:
- **Does your app collect or share any user data?** → Yes (location in EXIF, if permitted)
- **Location:**
  - Collected: Yes (approximate + precise, optional)
  - Purpose: App functionality (EXIF metadata)
  - Shared: No
  - Required: No
- **Photos and videos:**
  - Collected: No (stored by OS media store, not by app)
  - Shared: No
- **Does your app use encryption?** → Yes (HTTPS for optional cloud coaching)
- **Is your app a government app?** → No

## App Category & Tags
- **Category:** Photography
- **Tags:** Camera, AI, Photography, Portrait, HDR, Night mode
```

- [ ] **Step 2: Commit**

```bash
git add docs/play-store/store-listing-guide.md
git commit -m "docs: add store listing guide with screenshot specs"
```

---

### Task 5: Fastlane Setup

**Files:**
- Create: `Gemfile`
- Create: `fastlane/Appfile`
- Create: `fastlane/Fastfile`

- [ ] **Step 1: Create Gemfile**

Create `/Users/ashishthete/work/personal/s24-camera/Gemfile`:

```ruby
source "https://rubygems.org"

gem "fastlane", "~> 2.225"
```

- [ ] **Step 2: Install Fastlane**

Run:
```bash
bundle install
```

Expected: Fastlane installed. `Gemfile.lock` created.

- [ ] **Step 3: Create Appfile**

Create `fastlane/Appfile`:

```ruby
json_key_file("play-store-key.json")
package_name("com.spectra.app")
```

- [ ] **Step 4: Create Fastfile**

Create `fastlane/Fastfile`:

```ruby
default_platform(:android)

platform :android do

  desc "Run all unit tests"
  lane :test do
    gradle(
      task: "testDebugUnitTest",
      project_dir: "./"
    )
  end

  desc "Build a signed release AAB"
  lane :build do
    gradle(
      task: "bundleRelease",
      project_dir: "./"
    )
  end

  desc "Build and upload to Play Store internal track"
  lane :deploy_internal do
    gradle(
      task: "bundleRelease",
      project_dir: "./"
    )
    upload_to_play_store(
      track: "internal",
      aab: "app/build/outputs/bundle/release/app-release.aab",
      skip_upload_metadata: false,
      skip_upload_images: true,
      skip_upload_screenshots: true
    )
  end

  desc "Promote internal to closed alpha"
  lane :promote_alpha do
    upload_to_play_store(
      track: "internal",
      track_promote_to: "alpha"
    )
  end

  desc "Promote alpha to production"
  lane :promote_production do
    upload_to_play_store(
      track: "alpha",
      track_promote_to: "production",
      rollout: "0.1"
    )
  end

  desc "Upload metadata and screenshots only"
  lane :update_listing do
    upload_to_play_store(
      track: "production",
      skip_upload_aab: true,
      skip_upload_apk: true
    )
  end

end
```

- [ ] **Step 5: Verify Fastlane runs locally**

Run:
```bash
bundle exec fastlane android test
```

Expected: All unit tests pass via Fastlane.

Run:
```bash
bundle exec fastlane android build
```

Expected: `app-release.aab` built successfully.

- [ ] **Step 6: Commit**

```bash
git add Gemfile Gemfile.lock fastlane/Appfile fastlane/Fastfile
git commit -m "feat: add Fastlane for build and Play Store deployment"
```

---

### Task 6: GitHub Actions CI/CD Pipeline

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create workflows directory**

Run:
```bash
mkdir -p .github/workflows
```

- [ ] **Step 2: Create the release workflow**

Create `.github/workflows/release.yml`:

```yaml
name: Release to Play Store

on:
  push:
    tags:
      - 'v*'

  workflow_dispatch:
    inputs:
      track:
        description: 'Play Store track'
        required: true
        default: 'internal'
        type: choice
        options:
          - internal
          - alpha
          - production

permissions:
  contents: read

jobs:
  test:
    name: Run Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('**/*.gradle.kts', '**/gradle-wrapper.properties') }}
          restore-keys: gradle-

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest --no-daemon

  build-and-deploy:
    name: Build & Deploy
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Set up Ruby
        uses: ruby/setup-ruby@v1
        with:
          ruby-version: '3.2'
          bundler-cache: true

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('**/*.gradle.kts', '**/gradle-wrapper.properties') }}
          restore-keys: gradle-

      - name: Decode keystore
        run: echo "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 -d > release.keystore

      - name: Create keystore.properties
        run: |
          cat > keystore.properties << EOF
          storeFile=../release.keystore
          storePassword=${{ secrets.KEYSTORE_PASSWORD }}
          keyAlias=${{ secrets.KEY_ALIAS }}
          keyPassword=${{ secrets.KEY_PASSWORD }}
          EOF

      - name: Create Play Store key
        run: echo "${{ secrets.PLAY_STORE_KEY_JSON }}" | base64 -d > play-store-key.json

      - name: Determine track
        id: track
        run: |
          if [ "${{ github.event_name }}" = "workflow_dispatch" ]; then
            echo "track=${{ github.event.inputs.track }}" >> $GITHUB_OUTPUT
          else
            echo "track=internal" >> $GITHUB_OUTPUT
          fi

      - name: Build release AAB
        run: ./gradlew :app:bundleRelease --no-daemon

      - name: Upload AAB artifact
        uses: actions/upload-artifact@v4
        with:
          name: release-aab
          path: app/build/outputs/bundle/release/app-release.aab

      - name: Deploy to Play Store
        run: bundle exec fastlane android deploy_internal
        env:
          SUPPLY_TRACK: ${{ steps.track.outputs.track }}

      - name: Create GitHub Release
        if: startsWith(github.ref, 'refs/tags/')
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/bundle/release/app-release.aab
          generate_release_notes: true
```

- [ ] **Step 3: Create a CI-only test workflow for PRs**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  pull_request:
    branches: [main, master]
  push:
    branches: [main, master]

permissions:
  contents: read

jobs:
  test:
    name: Test & Build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('**/*.gradle.kts', '**/gradle-wrapper.properties') }}
          restore-keys: gradle-

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest --no-daemon

      - name: Build debug APK
        run: ./gradlew :app:assembleDebug --no-daemon
```

- [ ] **Step 4: Commit**

```bash
git add .github/
git commit -m "feat: add GitHub Actions CI/CD pipeline for Play Store releases"
```

---

### Task 7: Configure GitHub Secrets

This task is manual — done in the GitHub repository Settings UI.

- [ ] **Step 1: Push repository to GitHub**

```bash
git remote add origin https://github.com/<YOUR_USERNAME>/s24-camera.git
git push -u origin master
```

- [ ] **Step 2: Encode the keystore as base64**

Run locally:
```bash
base64 -i release.keystore | pbcopy
```

- [ ] **Step 3: Add secrets in GitHub → Settings → Secrets → Actions**

| Secret Name | Value |
|-------------|-------|
| `RELEASE_KEYSTORE_BASE64` | The base64-encoded keystore (from Step 2) |
| `KEYSTORE_PASSWORD` | Your store password from Task 1 |
| `KEY_ALIAS` | `spectra-release` |
| `KEY_PASSWORD` | Your key password from Task 1 |
| `PLAY_STORE_KEY_JSON` | Base64-encoded `play-store-key.json` (`base64 -i play-store-key.json \| pbcopy`) |

- [ ] **Step 4: Verify CI runs on push**

Push to trigger the CI workflow:
```bash
git push
```

Expected: GitHub Actions "CI" workflow runs, tests pass, debug APK builds.

---

### Task 8: Version Bumping and First Release

**Files:**
- Modify: `app/build.gradle.kts` (versionCode/versionName)

- [ ] **Step 1: Update version for release**

In `app/build.gradle.kts`, the version is already set:
```kotlin
versionCode = 1
versionName = "1.0.0"
```

This is correct for the first release. For future releases, bump `versionCode` (must be strictly increasing) and `versionName` (human-readable).

- [ ] **Step 2: Tag the release**

```bash
git tag -a v1.0.0 -m "SPECTRA Camera v1.0.0 — initial release"
```

- [ ] **Step 3: Push the tag to trigger deployment**

```bash
git push origin v1.0.0
```

Expected: GitHub Actions "Release to Play Store" workflow triggers → tests → builds AAB → uploads to Play Store internal track → creates GitHub Release.

- [ ] **Step 4: Verify in Play Console**

Go to Play Console → Internal testing. The new AAB should appear as a pending release. Add testers (email list) and roll it out to internal testers.

---

### Task 9: Play Console Setup Checklist

This is the manual Play Console configuration. Complete each section:

- [ ] **Step 1: App content → Privacy policy**

Enter the URL from Task 3, Step 2.

- [ ] **Step 2: App content → Ads**

Select: "No, my app does not contain ads"

- [ ] **Step 3: App content → App access**

Select: "All functionality is available without special access" (no login required)

- [ ] **Step 4: App content → Content rating**

Complete the IARC questionnaire using answers from Task 4 guide. Expected result: **Everyone (3+)**

- [ ] **Step 5: App content → Target audience**

Select: "18 and over" (camera apps are general purpose, but avoids COPPA requirements)

- [ ] **Step 6: App content → Data safety**

Fill in using the Data Safety section from Task 4 guide.

- [ ] **Step 7: Store listing → Main store listing**

- App name: SPECTRA - AI Camera
- Short description: (from `short_description.txt`)
- Full description: (from `full_description.txt`)
- App icon: Upload 512x512 PNG
- Feature graphic: Upload 1024x500 PNG
- Phone screenshots: Upload 2-8 screenshots (1080x2400)
- App category: Photography
- Contact email: thete.ashish1@gmail.com

- [ ] **Step 8: Release → Internal testing → Create release**

Upload the first AAB manually (required for first release), or verify the Fastlane upload from Task 8 succeeded. Add tester emails and roll out.

---

### Task 10: Release Checklist Script

**Files:**
- Create: `scripts/release.sh`

- [ ] **Step 1: Create scripts directory**

```bash
mkdir -p scripts
```

- [ ] **Step 2: Create the release script**

Create `scripts/release.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: ./scripts/release.sh <version>"
  echo "Example: ./scripts/release.sh 1.1.0"
  exit 1
fi

VERSION="$1"
VERSION_CODE=$(date +%Y%m%d%H)

echo "=== SPECTRA Release v${VERSION} (code: ${VERSION_CODE}) ==="

# 1. Update version in build.gradle.kts
sed -i '' "s/versionCode = [0-9]*/versionCode = ${VERSION_CODE}/" app/build.gradle.kts
sed -i '' "s/versionName = \"[^\"]*\"/versionName = \"${VERSION}\"/" app/build.gradle.kts
echo "[1/6] Version updated: ${VERSION} (${VERSION_CODE})"

# 2. Run tests
echo "[2/6] Running tests..."
./gradlew testDebugUnitTest --no-daemon --quiet
echo "       Tests passed."

# 3. Build release AAB
echo "[3/6] Building release AAB..."
./gradlew :app:bundleRelease --no-daemon --quiet
echo "       AAB built: app/build/outputs/bundle/release/app-release.aab"

# 4. Create changelog
CHANGELOG_FILE="fastlane/metadata/android/en-US/changelogs/${VERSION_CODE}.txt"
if [ ! -f "$CHANGELOG_FILE" ]; then
  echo "What's new in v${VERSION}:" > "$CHANGELOG_FILE"
  echo "" >> "$CHANGELOG_FILE"
  git log --oneline "$(git describe --tags --abbrev=0 2>/dev/null || echo HEAD~10)"..HEAD \
    | sed 's/^[a-f0-9]* /- /' >> "$CHANGELOG_FILE"
  echo "[4/6] Changelog generated: ${CHANGELOG_FILE}"
  echo "       Please review and edit before continuing."
  echo ""
  cat "$CHANGELOG_FILE"
  echo ""
  read -p "Press Enter to continue after editing changelog..."
else
  echo "[4/6] Changelog already exists: ${CHANGELOG_FILE}"
fi

# 5. Commit and tag
git add app/build.gradle.kts "$CHANGELOG_FILE"
git commit -m "release: SPECTRA v${VERSION}"
git tag -a "v${VERSION}" -m "SPECTRA Camera v${VERSION}"
echo "[5/6] Committed and tagged v${VERSION}"

# 6. Push
echo "[6/6] Push to trigger deployment:"
echo "       git push && git push origin v${VERSION}"
echo ""
echo "=== Release v${VERSION} ready. Push when ready. ==="
```

- [ ] **Step 3: Make it executable**

```bash
chmod +x scripts/release.sh
```

- [ ] **Step 4: Commit**

```bash
git add scripts/release.sh
git commit -m "feat: add release automation script"
```

---

## Release Flow Summary

```
Developer                    GitHub Actions              Play Console
─────────                    ──────────────              ────────────
./scripts/release.sh 1.1.0
  ├─ bumps version
  ├─ runs tests locally
  ├─ builds AAB
  ├─ generates changelog
  └─ commits + tags
       │
git push && git push --tags
       │
       └──────────────────→ CI triggers on v* tag
                              ├─ runs tests
                              ├─ builds signed AAB
                              ├─ fastlane deploy_internal ──→ Internal track
                              └─ creates GitHub Release       │
                                                              ├─ Testers verify
                                                              │
                            fastlane promote_alpha ──────────→ Alpha track
                                                              │
                            fastlane promote_production ─────→ Production (10%)
                                                              │
                                                        Manual: Roll to 100%
```

---

## Self-Review

**Spec coverage:** All aspects covered — signing, listing, CI/CD, privacy, screenshots, release automation, Play Console setup.

**Placeholder scan:** No TBDs found. Password placeholders are intentional (user must supply their own). All code blocks are complete.

**Type consistency:** `app-release.aab` path is consistent across Tasks 1, 5, 6, 8. `spectra-release` alias is consistent across Tasks 1, 7. Version fields match across Tasks 1, 8, 10.
