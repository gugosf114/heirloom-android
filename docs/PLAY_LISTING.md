# Play Store launch kit — Heirloom

## Listing copy

**App name (30 characters max):** `Heirloom: Restore Photos`

**Short description (80 characters max):**

`Repair old family photos with honest AI results. No subscription or watermark.`

**Full description:**

That box of old family photos — scratched, faded, and creased — deserves
better than a filter.

Heirloom restores old and damaged photographs with a careful multi-step
process. It repairs damage, restores faces, sharpens detail, and can colorize
black-and-white photos.

What makes Heirloom different:

★ HONEST RESTORATION — Heirloom checks whether a restored face still resembles
the original person and warns you when the result needs review.

★ NO SUBSCRIPTION — Try three restorations free, then buy only the restoration
credits you need. Nothing renews automatically.

★ PRIVATE BY DESIGN — No account, ads, analytics, or cloud photo library.
Photos are used only for the requested restoration and are deleted from the
server when it finishes.

★ NO WATERMARKS — Saved results belong in your family archive, not in an ad.

For your family. Not your followers.

## Product configuration

- App package: `com.wimlabs.heirloom`
- App price: Free
- Launch countries: United States only
- Product type: Consumable one-time products
- `heirloom_restorations_5_v1` — 5 restorations — $2.99
- `heirloom_restorations_20_v1` — 20 restorations — $7.99
- `heirloom_restorations_50_v1` — 50 restorations — $14.99
- No subscription products
- Privacy policy:
  `https://gugosf114.github.io/heirloom-android/privacy.html`
- Terms:
  `https://gugosf114.github.io/heirloom-android/terms.html`

Prices are configured in Play Console and shown by the app from Google Play.

## Data safety answers

- Photos: collected for app functionality, processed ephemerally, encrypted in
  transit, not retained after the request, not used for training.
- User-provided report text: collected only when the user submits a report, for
  safety and app quality, retained for 90 days.
- App interaction/model-result metadata in a submitted report: collected for
  safety and app quality, retained for 90 days.
- Purchase history: processed by Google Play Billing for app functionality.
- Device or other identifiers: an app-scoped, one-way-hashed Android identifier
  is used to enforce the three-restoration trial and restore the balance after
  reinstalling on the same device.
- App integrity information: processed through Google Play Integrity to prevent
  modified or unlicensed clients from spending restoration compute.
- No account name/email, contacts, precise location, advertising ID, ads, or
  analytics SDK.
- Photos are handled by service providers Cloudflare and Google Cloud solely to
  provide the restoration.

## Content and policy declarations

- Contains AI-generated or AI-modified content: Yes.
- In-app reporting: `Report a problem` is available on every completed result.
- Ads: No.
- Target audience: General audience; not designed specifically for children.
- Content rating: Photo utility. Answer the questionnaire truthfully; do not
  preselect a rating.

## Release order

1. Create the app in Play Console.
2. Complete App content, Data safety, privacy-policy, and content-rating forms.
3. Create and activate all three consumable products above for the United
   States only.
4. Add license testers.
5. Upload the signed AAB to Internal testing and finish a real purchase test.
6. Capture actual app screenshots from the tested release.
7. Start the required closed test if the account is subject to Google's
   12-testers-for-14-days personal-account rule.
8. Submit production access and then the production release.

## Operational notes

- Release builds use Play Integrity to obtain a server session. The shared app
  key is development-only and is not a production purchase entitlement.
- The Worker must have the `BILLING` D1 binding, Google service-account secret,
  and Play package/version settings before a release can restore photos.
- The Cloudflare Worker and Cloud Run service must share
  `PIPELINE_SHARED_SECRET`.
- Cloud Run is limited to one concurrent request per GPU instance and one
  instance total. It scales to zero when idle, so the first request after idle
  can take longer.
