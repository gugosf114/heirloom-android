> ## [⬇ DOWNLOAD THE LATEST ANDROID APK — 2026-10-26](https://github.com/gugosf114/heirloom-android/releases/latest/download/Heirloom-1.0.0.apk)
> **Current build: 1.0.0 · Android version code 10 · 2026-10-26**

# Heirloom

**Studio RC9:** the redesigned app is installed separately as **Heirloom Studio** on George's phone. Its development access is supplied on-device; the APK contains no development key. Google Play's current release remains RC6.

[Engine review](docs/ENGINE_REVIEW.md) · [Design screenshots](docs/DESIGN.md)

Android photo restoration for old and damaged family photographs.

**For your family. Not your followers.**

## Product

Heirloom restores one selected photo at a time. It repairs damage, restores
faces, upscales detail, checks whether the restored face still resembles the
original, and can add color to black-and-white input when requested. Original tones are kept by default.

- Three free restorations per eligible Play installation
- Consumable Google Play packs: 5, 20, or 50 additional restorations
- Server-verified purchases and restoration credits
- No ads, watermarks, accounts, or subscriptions
- United States launch availability
- A visible warning when the identity check detects drift
- In-app reporting for unsafe, unexpected, or poor AI output

## Architecture

```text
Android (Kotlin + Compose)
  -> Play Integrity + Google Play Billing
  -> authenticated Cloudflare Worker + D1 credit ledger
  -> Google Cloud Run L4 GPU
     1. One image-editing model call (OpenAI gpt-image, or Gemini) repairs
        damage, rebuilds faces, and adds color only when asked
        (RESTORE_ENGINE=generative; the older five-model chain stays behind
        RESTORE_ENGINE=classic: BOPB, CodeFormer, DDColor)
     2. Real-ESRGAN x2
     3. AdaFace check of the final image
  -> inline JPEG response
  -> app-private cache on the phone
```

Real-ESRGAN and AdaFace are self-hosted in Cloud Run. The restoration itself is
one call to an image-editing model (OpenAI by default, Gemini optional); the
photo goes to that provider for the call and is not stored by Heirloom.

The Worker verifies the Play-installed app and Google Play purchase receipts,
then atomically reserves one server-side credit for each restoration. Failed
restorations return the reserved credit. The Worker streams photo requests and
responses without storing them. Cloud Run uses a request-specific temporary
directory and deletes it when processing finishes. User-submitted problem
reports contain metadata and optional text, never the photo, and expire after
90 days.

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

Release signing is provided through private Gradle properties. Production app
authentication uses Play Integrity and server-issued sessions. Backend
deployment secrets are stored in Cloudflare Workers secrets and Google Secret
Manager; none belong in this repository.
