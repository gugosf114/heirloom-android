# Heirloom

> [!TIP]
> ## [✨ DOWNLOAD THE PREMIUM REDESIGN FOR REVIEW](https://github.com/gugosf114/heirloom-android/raw/refs/heads/agent/premium-redesign/Heirloom-premium-review.apk)
> This review APK updates the app on your phone without changing the public release.

> [!IMPORTANT]
> ## [⬇ DOWNLOAD THE NEWEST HEIRLOOM APK](https://github.com/gugosf114/heirloom-android/releases/latest/download/Heirloom-1.0.0-rc3.apk)
> **Current phone build: RC3 · Android version code 3**

Android photo restoration for old and damaged family photographs.

**For your family. Not your followers.**

## Product

Heirloom restores one selected photo at a time. It repairs damage, restores
faces, upscales detail, checks whether the restored face still resembles the
original, and colorizes black-and-white input when appropriate.

- One free restoration
- One-time Google Play unlock
- No ads, watermarks, accounts, or subscriptions
- Unlimited use without a paywall for users in Armenia
- A visible warning when the identity check detects drift
- In-app reporting for unsafe, unexpected, or poor AI output

## Architecture

```text
Android (Kotlin + Compose)
  -> authenticated Cloudflare Worker
  -> Google Cloud Run L4 GPU
     1. Bringing Old Photos Back to Life
     2. CodeFormer
     3. Real-ESRGAN
     4. AdaFace identity check
     5. DDColor for black-and-white input
  -> inline JPEG response
  -> app-private cache on the phone
```

The restoration models are self-hosted in Cloud Run. Replicate is not part of
the runtime architecture.

The Worker streams photo requests and responses without storing them. Cloud
Run uses a request-specific temporary directory and deletes it when processing
finishes. User-submitted problem reports contain metadata and optional text,
never the photo, and expire after 90 days.

## Repository

```text
app/       Android application
worker/    Cloudflare gateway and report intake
pipeline/  Self-hosted Cloud Run restoration service
docs/      Store website, privacy policy, terms, and listing copy
```

## Verify

```bash
cd worker
npm ci
npm test
npx tsc --noEmit

cd ..
./gradlew testDebugUnitTest lintDebug bundleRelease
```

Release signing and the app-to-Worker key are provided through private Gradle
properties. Backend deployment secrets are stored in Cloudflare Workers
secrets and Google Secret Manager; none belong in this repository.
