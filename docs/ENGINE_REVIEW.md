# Heirloom engine review

The engine completed a real photograph through all five models. The app needed a new design and several repairs to how it prepares, checks, and presents photos. This review covers the Android app, credit gateway, and every restoration stage.

## Real run before the repairs

On October 11, 2026, the live Cloud Run service restored Dorothea Lange's public-domain *Migrant Mother* photograph. Total time was 126.5 seconds, including startup. Actual model processing took 92.3 seconds.

| Step | Time | Result |
| --- | ---: | --- |
| Scratch and damage repair | 34.1 seconds | Completed |
| Face restoration | 25.7 seconds | Completed |
| Detail enlargement | 4.4 seconds | Completed |
| Face comparison | 2.0 seconds | Completed; similarity score 0.895 |
| Colorization | 26.1 seconds | Completed |

The output was a real 855,420-byte JPEG. The image had visibly invented color, including reddish hair on one child. A similarity score is not a percentage of correctness or a promise that every face is unchanged.

## Faults repaired

1. **Color was added without a choice.** The app now preserves the original tones by default. The selected-photo screen has an **Add color** switch. Its label explains that AI guesses the colors. The choice travels through the app, foreground service, gateway, and model endpoint. Retry keeps the same choice.
2. **The face check ran before the last edit.** The engine now compares the original with the finished photo after optional colorization.
3. **A complete model failure could look like success.** Every repair step caught its own failure, then the engine could return the original as a finished result. The gateway would treat that as a completed, paid restoration. If all three repair models fail, the engine now sends a failure instead of a final result. The gateway's existing failure path returns the reserved credit.
4. **A fallback could return only a face crop.** CodeFormer previously searched every PNG in its output tree when its full image was missing. It now requires the full image from `final_results`.
5. **Large phone photos could turn sideways.** The app resized images without first applying their EXIF rotation. It now applies all eight EXIF orientation cases before writing the upload JPEG.
6. **The photo button opened a print scanner.** Gallery selection now uses Android's photo picker. Scanning a printed photo has its own button.
7. **Results were hard to judge.** Two small cropped tiles became one large, full-image comparison. The draggable divider and accessible slider expose both images without cropping them.
8. **The app overstated the face score.** The interface no longer calls a cosine similarity a “percent match” or declares “Identity preserved.” It says which face check ran and asks the user to compare the result.
9. **Credit packs could stay on “Loading” forever.** Loading and unavailable states are now distinct. The sheet has a retry action and shows only actual Google Play prices.
10. **The pack chooser could close while browsing.** Existing credits no longer dismiss it immediately. An increase after a verified purchase closes it.

## What the source still does

- The face comparison checks the primary detected face. It does not verify every person in a group photograph.
- Inputs are reduced to a maximum dimension of 1,024 pixels before model processing. Enlargement produces a larger image; it does not recover all original scan detail.
- A single failed repair model can still be skipped if another repair model succeeds. The result details show skipped steps.
- The in-memory restoration session survives activity recreation. It does not recover the session after Android kills the whole process. A completed image remains in the private cache until Android clears it.
- A long restoration can exceed the Cloud Run request deadline. The app reports this as a failure. Model subprocess time limits can be longer than the service's 600-second deadline.
- Google Play still verifies real purchases for the release app. The separately installed Studio build uses the existing development gateway and an access key supplied only to George's phone. The public APK contains no development key.

## Checks completed during this work

- A live photo passed every original model stage.
- Six pipeline checks passed, including default tone preservation, checking the final image, failing when every repair fails, image orientation, and the existing scratch-repair fallback checks.
- The gateway's six checks and TypeScript checks passed.
- Final Android compilation, unit checks, lint, and release bundle checks passed. The Studio APK was installed on the phone and inspected in normal and large-text layouts.

## Source map

- `app/src/main/java/com/heirloom/app/ui`: screens, comparison, report, saving, and sharing.
- `app/src/main/java/com/heirloom/app/data`: image preparation, streaming, lifecycle, and errors.
- `app/src/main/java/com/heirloom/app/billing`: Google Play, server balance, and access.
- `worker/src/index.ts`: receipt sync, authentication, proxy, reports, and stream completion.
- `worker/src/ledger.ts`: reservations, balances, completed work, and refunds.
- `worker/src/googlePlay.ts`: purchase and app verification.
- `pipeline/app/server.py`: model order, failures, temporary files, and streaming.
- `pipeline/app/stages`: model invocations and output selection.
- `pipeline/app/adaface.py`, `face_align.py`, and `grayscale.py`: face alignment, comparison, and color detection.

## Final phone and deployment proof

The repaired engine is live on Cloud Run revision `heirloom-pipeline-00010-rcr`. Its source is the pipeline in commit `b211acb`.

The installed Studio RC8 APK was built from `e33d066`. SHA-256: `12e7fa659a0489ac0fc59be51ac25c117bc5f065351596f9a9c6d305c35505fd`.

A second real run started from Android's photo picker. The test JPEG was physically sideways, with EXIF orientation 6 and a 1,800-pixel long edge. This exercised the Android resize and rotation path. The app displayed it upright, restored it through the development credit gateway and the repaired engine, then saved it to `Pictures/Heirloom/heirloom_1791760900256.jpg` (3,280 × 4,096 pixels; 1,740,711 bytes). The server recorded a successful HTTP 200 response in 77.0 seconds. The balance changed from three credits to two. The app also remained active as a foreground service while its screen was left.

The result divider and its accessible slider moved together on the phone. The gallery button, credit sheet's unavailable state, error layout, selected-photo color switch, and controls at 150 percent text size were inspected. Screenshots are in [the design review](DESIGN.md).

Code and checks: [merged change 11](https://github.com/gugosf114/heirloom-android/pull/11), [Android, pipeline, and gateway checks](https://github.com/gugosf114/heirloom-android/actions/runs/38183539068), [installed Studio build](https://github.com/gugosf114/heirloom-android/actions/runs/38183451569).

## October 12 repair for cut off results

A real restoration stopped on the phone even though the models finished. Repeating the same private photograph reproduced an incomplete final reply. Cloudflare recorded `exceededCpu` and 2,020 ms of CPU time in the gateway. The Android error was an unterminated JSON string inside the inline photograph.

The gateway repeatedly joined and rescanned the growing photo string while waiting for its final newline. The earlier smaller archive photograph did not expose this failure. An interrupted gateway could also leave a credit reserved.

The repair inspects at most 16 KiB of event metadata and passes photo bytes through unchanged. The complete final event must arrive before the work is marked successful. Errors and cancelled streams refund the reservation. On billing refresh, reservations abandoned beyond 15 minutes are refunded once; recent, completed, and other clients' work are left alone.

A repeated live request delivered the complete 4,050,483-character final event in 56.7 seconds. Gateway CPU time fell from 2,020 ms to 163 ms. Both production and development gateways have the repair.

Twelve gateway checks passed. They include an 8 MiB response delivered byte-for-byte, split JSON and escaped strings, truncated images, cancellation, and real SQLite checks for credit recovery. Android RC9 adds bounded stage logs that contain no photo data. Malformed JSON errors no longer dump the photograph into logcat.

The build workflow now selects the saved Studio signing key explicitly. Previously, writing a key into the presumed Android debug directory did not make the generated APK use it. The installed RC8, first RC9 build, and saved key had different certificate fingerprints. The final RC9 APK is verified against the saved key. App files, cached photographs, databases, and preferences were backed up and restored during this one-time signing repair; the cached input photograph was checked byte-for-byte afterward.

Code: [repair 12](https://github.com/gugosf114/heirloom-android/pull/12). The private input photograph and its output are kept off this repository.


Final phone proof: Studio RC9 restored the same previously failing private photograph, with color enabled, in 55 seconds. The phone received and stored a 3,037,914-byte JPEG. The gateway recorded HTTP 200, no exceptions, and 126 ms of CPU time. The result was saved through the app's Save photo button and decoded successfully from the phone gallery.

Installed APK SHA-256: `dc9883c4aec0ded8229767c1f17cd95270cdeb160d4ebe862e9044cf10ff9301`. Its signing certificate SHA-256 matches the saved Studio key: `5ce81f8c3d8506f3eee488412a41dc3aa7700f233d7756a78931afb73329bc8d`. The phone retained the previous cached photographs after the signing migration.


## October 13 engine swap: one generative call

Reading the five-model chain end to end showed where the 92 seconds went: three
of the five models booted from scratch on every photo, the image was upscaled
twice, and the input was cut to 1,024 pixels before any model saw it. The 2026
field (Remini, MyHeritage, and the one-call generative editors) is faster and
rebuilds damage more coherently.

`RESTORE_ENGINE=generative` replaces BOPB, CodeFormer, and DDColor with one
image-editing call (`pipeline/app/stages/generative.py`; OpenAI `gpt-image-1.5`
by default, Gemini `gemini-2.5-flash-image` with `RESTORE_PROVIDER=gemini`).
The prompt orders the model to keep every person's identity, framing, and tones,
and to add color only when the person asked. Real-ESRGAN x2 and the AdaFace
check run unchanged on the result. The classic chain remains selectable.

Live checks on October 13 and 14 with Dorothea Lange's *Migrant Mother*, no color:

| Engine | Time | Output | Face score |
| --- | ---: | --- | ---: |
| Old five-model chain (Oct 11) | 92.3 s processing | 4,096 px | 0.895 |
| OpenAI gpt-image-1.5, local call | 44 s | 1,024 x 1,536 | not run |
| Gemini Nano Banana 2.1, live Cloud Run revision 00012 | 27.7 s end to end (16.7 s model, 6.3 s upscale, 3.5 s check) | 1,848 x 2,304 | 0.970 |

Nano Banana Pro timed out at 240 seconds and is not used. Gemini keeps the
input's aspect ratio; OpenAI snapped it to 2:3.

Deployed: `RESTORE_PROVIDER=gemini`, `GEMINI_IMAGE_MODEL=gemini-nano-banana-2.1`.
The Gemini key lives in a separate Google Cloud project on the same billing
account (`heirloom-gem-10140048`, Secret Manager `heirloom-gemini-key` in
bakers-agent): the bakers-agent project itself stays flagged "dunning deny" by
the Gemini API even with a zero balance, a known Google-side flag that payment
does not clear.

The Android app shows the generative step as "Repairing damage".
