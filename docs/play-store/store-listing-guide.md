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
