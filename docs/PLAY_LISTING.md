# Play Store launch kit — Heirloom

Code side is done: 1 free restoration → one-time unlock (price is set in
Play Console, the app displays whatever you pick), Armenia rides free,
no ads, no subscriptions. Below is George's checklist + listing material.

## George's checklist (in order)

1. **Play Console → Create app**: "Heirloom — Photo Restoration", App,
   Paid features via in-app purchase (the app itself is Free).
2. **Monetize → In-app products → Create product**:
   - Product ID: `heirloom_lifetime_unlock_v1` (must match exactly)
   - Name: "Unlock forever" — Description: "Unlimited photo restorations."
   - **Price: your call — $0.99 or $1.99.** (Cost basis: ~$7/mo fixed +
     roughly $0.10–0.20 of model time per restore on Replicate. At $0.99
     Google keeps 15%, so ~84¢ net = ~4–8 restores of margin per buyer.
     $1.99 doubles the cushion. Either works; it's changeable later.)
   - Activate the product.
3. **Testing**: add your own Gmail under Settings → License testing so
   you can test the purchase without being charged.
4. **Store listing**: copy below. Privacy policy URL:
   `https://gugosf114.github.io/heirloom-android/privacy.html`
5. **Data safety form** (the app has no accounts, no ads, no analytics):
   - **Photos**: collected — yes (uploaded for processing); shared —
     yes, with a service provider (AI processing); NOT stored beyond
     processing; NOT used for any other purpose; encrypted in transit;
     user can't request deletion (nothing is retained).
   - **Purchase history**: handled by Google Play Billing, not the app.
   - Everything else: not collected.
6. **Content rating**: photo utility, no UGC sharing, no objectionable
   content → Everyone.
7. Upload the signed `.aab` (build script produces it), add screenshots
   (before/after restores are the obvious money shots — use your own
   family photos, that IS the pitch), submit.

## Listing copy

**Title (30 chars):** `Heirloom: Restore Old Photos`

**Short description (80 chars):**
`Repair and restore old family photos. Honest results. Pay once, no tricks.`

**Full description:**

That box of old family photos — scratched, faded, creased — deserves
better than a filter.

HEIRLOOM restores old and damaged photos with a careful, multi-step
pipeline: faces are reconstructed, detail is sharpened, damage is
repaired, and black-and-white photos can be brought into color.

What makes it different is what it refuses to do:

★ HONEST RESTORATION — if the restored face has drifted from the person
  in your original, Heirloom tells you instead of pretending. "Still
  actually them" is the whole point.
★ NO SUBSCRIPTION — one free restoration to see the quality, then a
  single one-time unlock. Forever. No monthly anything.
★ NO WATERMARKS. NO ADS. Your grandmother's portrait will not carry a
  logo.
★ NOTHING STORED — photos are processed and discarded. No account, no
  gallery scraping, no cloud library.

For your family. Not your followers.

**Screenshots:** before/after pairs are the sell. Take 3–4 restores of
real old photos and screenshot the result screen (input/output tiles).

**Feature graphic (1024×500):** before/after split of one strong restore
with the tagline. TODO once George picks his best restore.

## Known launch notes

- The purchase button shows "Store unavailable" until the app is on a
  Play testing track AND the product exists — that's Play working as
  designed, not a bug. Internal testing track first.
- Armenia: free automatically (SIM/locale/Play-country check in-app).
- The worker is gated by APP_SHARED_SECRET (set 2026-07-02) — release
  builds must be built on a machine whose gradle.properties carries
  HEIRLOOM_APP_KEY, or restores will 401.
- Cost watch: the AdaFace identity model runs on a Replicate deployment
  with min_instances=1 (~the "$7/mo container"). If volume ever matters,
  the Cloud Run scale-to-zero pipeline (branch `cloud-run-pipeline`) is
  the cheaper backend — app was never repointed to it.
