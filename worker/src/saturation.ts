/**
 * Cheap B&W detector: decode a JPEG, sample ~4096 pixels uniformly, return
 * the mean color spread (max(r,g,b) - min(r,g,b), normalized to [0,1]).
 *
 * A truly grayscale photo has spread ~0 at every pixel. A color photo has
 * spread averaging well above 0.05. The 0.05 threshold (configurable via
 * the GRAYSCALE_THRESHOLD env var) catches faded sepia photos as "color"
 * because sepia DOES contain hue info — DDColor would muddy them up.
 *
 * jpeg-js is a pure-JS decoder, ~80KB bundled, runs fine in Workers.
 * If a non-JPEG arrives, we throw and the caller treats it as "color"
 * (skip colorization) — safe failure mode.
 */

import jpeg from 'jpeg-js';

export function meanColorSpread(jpegBytes: Uint8Array, sampleCount = 4096): number {
  const decoded = jpeg.decode(jpegBytes, { useTArray: true });
  const { data, width, height } = decoded;
  const totalPixels = width * height;
  if (totalPixels === 0) return 1; // can't decide; treat as color

  const stride = Math.max(1, Math.floor(totalPixels / sampleCount));
  let totalSpread = 0;
  let samples = 0;

  // data is RGBA, 4 bytes per pixel.
  for (let i = 0; i < totalPixels; i += stride) {
    const offset = i * 4;
    const r = data[offset];
    const g = data[offset + 1];
    const b = data[offset + 2];
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    totalSpread += (max - min) / 255;
    samples++;
  }
  return samples === 0 ? 1 : totalSpread / samples;
}

export function isGrayscale(jpegBytes: Uint8Array, threshold: number): boolean {
  try {
    return meanColorSpread(jpegBytes) < threshold;
  } catch {
    // Decode failed (PNG, HEIC, corrupt JPEG). Treat as color — skipping
    // DDColor on an actual B&W image is a feature gap, not a bug.
    return false;
  }
}
