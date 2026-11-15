package app.howlingdog

/**
 * The Neo Geo reconstruction engine (v2.1.0): a shader-only,
 * two-pass-at-source-resolution reconstruction of the 304x224
 * frame, retuned from the Game Boy multi-emulator's engine for
 * Neo Geo. The three fragments below are that engine's
 * RECON_A1 / RECON_A2 / RECON_B, copied verbatim except for the
 * adaptations listed here (the sim caught a from-memory
 * transcription that had drifted — the copy is the proof):
 *
 * - Pass A1 drops the colour-fold (uMode / uGammaLut /
 *   colourCorrect): the Geolith core has no frontend colour
 *   correction, so there is nothing to fold and nothing to
 *   double-correct. A1 reads the raw frame and outputs (raw
 *   colour, pattern flag) per texel. The five pattern
 *   detectors are verbatim.
 * - Pass A2 is verbatim (it reads A1's output and the raw
 *   frame; its luma is plain). No adaptation.
 * - Pass B drops the Worm Light pool (uWl* uniforms and the
 *   add) — HowlingDog has no lamp — and replaces the 15-bit
 *   snap (uSnap15, /31) with a 12-bit snap (uSnap12, /15)
 *   for the Neo Geo 4096-colour palette, and adds a uGrid
 *   uniform: a scanline darkening at display size (the same
 *   idea as the edge shader's Sharp CRT one-tap combined
 *   mode). uGrid is 0 when the grid toggle is off (Steps
 *   1-4) and RECON_GRID_STRENGTH when it is on (Step 5).
 *
 * Pass A1 (source resolution, into FBO_A): the 3x3 pattern
 * detectors (checkerboard, stripes, Bayer 2x2 and 4x4) run on
 * the raw frame. Pass A2 (source resolution, into FBO_B):
 * the 5x5 field (pattern average) + 5x5 colour mean, the
 * detectors recomputed, the dither average (priority bayer4 >
 * bayer2 > stripeH > checker/stripeV), the shade-distance
 * guard, the 1px isolated test, the Sobel edge test, and the
 * text test classify each texel (flat / edge / dither / text /
 * 1px detail) and collapse dither fields to their intended
 * colour. Pass B (display size, to the screen): the 5x5 Sobel
 * line reconstruction with 6-direction interpolation, corner
 * preservation, and a 1-display-px transition centred on the
 * line — only for edge-class texels. Text and 1px detail pass
 * through nearest; flat and dither pass through nearest
 * (already collapsed in Pass A).
 *
 * A pass into an FBO stores its output flipped exactly when
 * (inputFlipped == uFlipV); a pass to the screen is right-side
 * up exactly when (uFlipV == inputFlipped). The constants
 * below are the triple the flip fix proved (A1 1, A2 0, B 1);
 * reconOrientationIsCorrect pins the composition so a flipped
 * image can never ship, and the sim runs the old wrong triple
 * (A1 0, A2 0, B 1) as a negative control. The flip lives in
 * RECON_VERTEX (uFlipV), so every tap in a pass flips together.
 *
 * OpenGL ES 2.0 (GLSL ES 1.00) throughout, like the edge
 * shader. No temporal state, no previous frame: the
 * reconstruction is a pure function of the frame.
 */
object ReconShaders {

    /** Pass A1 reads the Bitmap with uFlipV 1 (the recon-flip fix). */
    const val RECON_A1_FLIP_V = true

    /** Pass A2 reads texA + the raw frame with uFlipV 0. */
    const val RECON_A2_FLIP_V = false

    /** Pass B reads texB with uFlipV 1, to the screen. */
    const val RECON_B_FLIP_V = true

    /** Grid-on-reconstruction strength: a fixed 15% starting value. */
    const val RECON_GRID_STRENGTH = 0.15f

    /** The vertex shader for all three reconstruction passes. */
    const val RECON_VERTEX = """
        attribute vec2 aPos;
        attribute vec2 aTex;
        uniform float uFlipV;
        varying vec2 vTex;
        void main() {
          gl_Position = vec4(aPos, 0.0, 1.0);
          vTex = vec2(aTex.x, mix(aTex.y, 1.0 - aTex.y, uFlipV));
        }
    """

    /**
     * Pass A1 — detectors, native size, into FBO_A. Outputs
     * (raw colour, pattern flag) per texel. The five pattern
     * detectors are verbatim from the handheld engine (and
     * from the edge shader): checker (the diagonal must
     * actually match), stripes, Bayer 2x2, Bayer 4x4 proxy
     * (opposite sides differ). A2 reads this map back as
     * the field, so this pass outputs the pattern, not a
     * blend. No colour fold (the Geolith core has none).
     */
    const val RECON_A1_FRAGMENT = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec2 uTexSize;
        uniform float uDitherTol;
        float edgeLuma(vec3 c) {
          return dot(c, vec3(0.299, 0.587, 0.114));
        }
        void main() {
          vec2 texel = vec2(1.0 / uTexSize.x, 1.0 / uTexSize.y);
          vec3 c0 = texture2D(uTex, vTex).rgb;
          vec3 cN = texture2D(uTex, vTex + vec2(0.0, texel.y)).rgb;
          vec3 cS = texture2D(uTex, vTex - vec2(0.0, texel.y)).rgb;
          vec3 cE = texture2D(uTex, vTex + vec2(texel.x, 0.0)).rgb;
          vec3 cW = texture2D(uTex, vTex - vec2(texel.x, 0.0)).rgb;
          vec3 cNE = texture2D(uTex, vTex + vec2(texel.x, texel.y)).rgb;
          vec3 cNW = texture2D(uTex, vTex + vec2(-texel.x, texel.y)).rgb;
          vec3 cSE = texture2D(uTex, vTex + vec2(texel.x, -texel.y)).rgb;
          vec3 cSW = texture2D(uTex, vTex + vec2(-texel.x, -texel.y)).rgb;
          float tol = uDitherTol;
          // Patterned dither: regular alternation is shading,
          // not an edge. Checkerboard: the centre matches its
          // diagonal neighbour (the match must be real) and
          // differs from BOTH orthogonal neighbours —
          // requiring both is what keeps a diagonal edge from
          // reading as dither (a diagonal differs from only
          // one orthogonal neighbour). Stripes: the centre
          // matches along the stripe and differs across it.
          // Bayer 2x2: the ordered 2x2 pattern repeats every
          // two texels, so the east and west neighbours match
          // each other, the north and south match each other,
          // and all four diagonals match each other, while
          // the three groups differ from the centre and from
          // each other. Bayer 4x4: a 3x3 window cannot see a
          // 4-texel period, so the local proxy is the
          // pattern's non-monotonic steps — the luma on both
          // sides of the centre sits on the SAME side of it
          // AND differs from it, in both axes — AND the
          // opposite sides differ from each other (a real
          // 4x4 has different levels on E vs W and N vs S).
          float dDiag = distance(c0, cSE);
          float dE = distance(c0, cE);
          float dS = distance(c0, cS);
          float checker = step(dDiag, tol) * step(tol, dE) * step(tol, dS);
          float stripeV = step(distance(c0, cN) + distance(c0, cS), tol) * step(tol, distance(c0, cE)) * step(tol, distance(c0, cW));
          float stripeH = step(distance(c0, cE) + distance(c0, cW), tol) * step(tol, distance(c0, cN)) * step(tol, distance(c0, cS));
          float bayer2 = step(distance(cE, cW), tol) * step(distance(cN, cS), tol) * step(distance(cNE, cSE), tol) * step(distance(cNE, cNW), tol) * step(tol, distance(c0, cE)) * step(tol, distance(c0, cN)) * step(tol, distance(cE, cN));
          float l0 = edgeLuma(c0);
          float lN = edgeLuma(cN);
          float lS = edgeLuma(cS);
          float lE = edgeLuma(cE);
          float lW = edgeLuma(cW);
          float bayer4 = step(0.0, (lE - l0) * (lW - l0)) * step(0.0, (lN - l0) * (lS - l0)) * step(0.03, abs(lE - l0)) * step(0.03, abs(lW - l0)) * step(0.03, abs(lN - l0)) * step(0.03, abs(lS - l0)) * step(0.05, abs(lE - lW)) * step(0.05, abs(lN - lS));
          float pattern = max(max(checker, max(stripeV, stripeH)), max(bayer2, bayer4));
          gl_FragColor = vec4(c0, pattern);
        }
    """

    /**
     * Pass A2 — the 5x5 field, the class and the collapse,
     * at source resolution. Reads A1's output (uTex: RGB the
     * raw colour, A the pattern) and the raw frame (uRawTex:
     * the text ring needs RAW luma — direction is a property
     * of the source art). The class priority is edge, then
     * dither (any pattern texel, or a texel inside a dither
     * field — field >= 0.3 — even when the guard blocks the
     * collapse, so a high-contrast dither texel is never
     * line-reconstructed as an edge), then text (UNGATED),
     * then the field guard (field >= 0.3 -> dither, so a
     * texel inside a dither field is dither, not text), then
     * 1px. Field-only: a thin line's own texels are pattern
     * texels but sit outside any field (the 1px outline's
     * field peaks at 0.24), so they stay TEXT. RGB is the
     * field mean where the field test passes, else the 3x3
     * dither average where a pattern texel passes the guard,
     * else the centre's colour. The collapse is gated by the
     * UNGATED text bypass and the 1px test, except a texel
     * that IS a pattern texel (pattern > 0.5), which is never
     * protected by the text test (the field's own texels
     * must collapse).
     */
    const val RECON_A2_FRAGMENT = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform sampler2D uRawTex;
        uniform vec2 uTexSize;
        uniform float uDitherTol;
        uniform float uDitherGuardHi;
        uniform float uTextAlign;
        uniform float uEdgeLo;
        uniform float uTextStroke;
        float edgeLuma(vec3 c) {
          return dot(c, vec3(0.299, 0.587, 0.114));
        }
        void main() {
          vec2 texel = vec2(1.0 / uTexSize.x, 1.0 / uTexSize.y);
          vec4 v0 = texture2D(uTex, vTex);
          vec4 vN = texture2D(uTex, vTex + vec2(0.0, texel.y));
          vec4 vS = texture2D(uTex, vTex - vec2(0.0, texel.y));
          vec4 vE = texture2D(uTex, vTex + vec2(texel.x, 0.0));
          vec4 vW = texture2D(uTex, vTex - vec2(texel.x, 0.0));
          vec4 vNE = texture2D(uTex, vTex + vec2(texel.x, texel.y));
          vec4 vNW = texture2D(uTex, vTex + vec2(-texel.x, texel.y));
          vec4 vSE = texture2D(uTex, vTex + vec2(texel.x, -texel.y));
          vec4 vSW = texture2D(uTex, vTex + vec2(-texel.x, -texel.y));
          vec3 c0 = v0.rgb;
          vec3 cN = vN.rgb;
          vec3 cS = vS.rgb;
          vec3 cE = vE.rgb;
          vec3 cW = vW.rgb;
          vec3 cNE = vNE.rgb;
          vec3 cNW = vNW.rgb;
          vec3 cSE = vSE.rgb;
          vec3 cSW = vSW.rgb;
          float pattern = v0.a;
          // The 5x5 field: the pattern map averaged over
          // the window, and the 5x5 mean of the colours —
          // the field's intended colour, tracking the local
          // dither ratio, so a gradient dither collapses to
          // its local level, not a global 50/50.
          float field = 0.0;
          vec3 mean5 = vec3(0.0);
          for (int j = -2; j <= 2; j++) {
            for (int i = -2; i <= 2; i++) {
              vec4 s = texture2D(uTex, vTex + vec2(float(i), float(j)) * texel);
              field += s.a;
              mean5 += s.rgb;
            }
          }
          field /= 25.0;
          mean5 /= 25.0;
          float tol = uDitherTol;
          // The centre's detectors, recomputed from A1's
          // colours (same formulas as the edge pass — the
          // priority and the shade distance both need the
          // individual detectors, which the pattern flag
          // alone does not carry).
          float dDiag = distance(c0, cSE);
          float dE = distance(c0, cE);
          float dS = distance(c0, cS);
          float checker = step(dDiag, tol) * step(tol, dE) * step(tol, dS);
          float stripeV = step(distance(c0, cN) + distance(c0, cS), tol) * step(tol, distance(c0, cE)) * step(tol, distance(c0, cW));
          float stripeH = step(distance(c0, cE) + distance(c0, cW), tol) * step(tol, distance(c0, cN)) * step(tol, distance(c0, cS));
          float bayer2 = step(distance(cE, cW), tol) * step(distance(cN, cS), tol) * step(distance(cNE, cSE), tol) * step(distance(cNE, cNW), tol) * step(tol, distance(c0, cE)) * step(tol, distance(c0, cN)) * step(tol, distance(cE, cN));
          float l0 = edgeLuma(c0);
          float lN = edgeLuma(cN);
          float lS = edgeLuma(cS);
          float lE = edgeLuma(cE);
          float lW = edgeLuma(cW);
          float lNE = edgeLuma(cNE);
          float lNW = edgeLuma(cNW);
          float lSE = edgeLuma(cSE);
          float lSW = edgeLuma(cSW);
          float bayer4 = step(0.0, (lE - l0) * (lW - l0)) * step(0.0, (lN - l0) * (lS - l0)) * step(0.03, abs(lE - l0)) * step(0.03, abs(lW - l0)) * step(0.03, abs(lN - l0)) * step(0.03, abs(lS - l0)) * step(0.05, abs(lE - lW)) * step(0.05, abs(lN - lS));
          // The dither average: the intended level of the
          // pattern. Priority bayer4, then bayer2, then
          // stripeH, then stripeV / checker — the same
          // order as the edge pass and the sim.
          vec3 ditherAvg;
          if (bayer4 > 0.5) {
            ditherAvg = (c0 + cN + cS + cE + cW + cNE + cNW + cSE + cSW) / 9.0;
          } else if (bayer2 > 0.5) {
            ditherAvg = (c0 + cE + cN + cNE) * 0.25;
          } else if (stripeH > 0.5) {
            ditherAvg = (c0 + cN) * 0.5;
          } else if (checker > 0.5 || stripeV > 0.5) {
            ditherAvg = (c0 + cE) * 0.5;
          } else {
            ditherAvg = c0;
          }
          // The shade-distance guard: the flattening only
          // runs when the shades it would average are within
          // 0.7 of each other (uDitherGuardHi) — same
          // priority as ditherAvg.
          float shadeDist;
          if (bayer4 > 0.5) {
            shadeDist = max(distance(c0, cN), distance(c0, cS));
            shadeDist = max(shadeDist, max(distance(c0, cE), distance(c0, cW)));
            shadeDist = max(shadeDist, max(distance(c0, cNE), distance(c0, cNW)));
            shadeDist = max(shadeDist, max(distance(c0, cSE), distance(c0, cSW)));
          } else if (bayer2 > 0.5) {
            shadeDist = max(max(distance(c0, cE), distance(c0, cN)), distance(c0, cNE));
            shadeDist = max(shadeDist, max(distance(cE, cN), distance(cE, cNE)));
            shadeDist = max(shadeDist, distance(cN, cNE));
          } else if (stripeH > 0.5) {
            shadeDist = distance(c0, cN);
          } else {
            shadeDist = distance(c0, cE);
          }
          // 1-pixel detail: the eight neighbours all agree
          // with each other and the centre stands apart.
          vec3 nMean = (cN + cS + cE + cW + cNE + cNW + cSE + cSW) / 8.0;
          float spread = max(max(distance(cN, nMean), distance(cS, nMean)), max(distance(cE, nMean), distance(cW, nMean)));
          spread = max(spread, max(max(distance(cNE, nMean), distance(cNW, nMean)), max(distance(cSE, nMean), distance(cSW, nMean))));
          float isolated = step(spread, 0.05) * step(0.15, distance(c0, nMean));
          // Edge: a Sobel-like luma gradient over the 3x3.
          float gx = (lE + lNE + lSE) - (lW + lNW + lSW);
          float gy = (lN + lNE + lNW) - (lS + lSE + lSW);
          float edgeStrength = abs(gx) + abs(gy);
          // Text: a high-contrast edge that runs straight
          // along one axis. The gradient ratio is evaluated
          // over the 5x5 OUTER RING, on RAW luma (the ring
          // costs 12 plain taps on uRawTex) — the same rule
          // as the edge pass: over the ring a staircase's
          // steps average into a diagonal gradient, while a
          // glyph stroke stays one-directional. UNGATED
          // here: the class and the collapse gate both keep
          // the old protection.
          float lMax = max(l0, max(max(lN, lS), max(max(lE, lW), max(max(lNE, lNW), max(lSE, lSW)))));
          float lMin = min(l0, min(min(lN, lS), min(min(lE, lW), min(min(lNE, lNW), min(lSE, lSW)))));
          float rE2 = edgeLuma(texture2D(uRawTex, vTex + vec2(2.0 * texel.x, 0.0)).rgb);
          float rNE2 = edgeLuma(texture2D(uRawTex, vTex + vec2(2.0 * texel.x, texel.y)).rgb);
          float rSE2 = edgeLuma(texture2D(uRawTex, vTex + vec2(2.0 * texel.x, -texel.y)).rgb);
          float rW2 = edgeLuma(texture2D(uRawTex, vTex + vec2(-2.0 * texel.x, 0.0)).rgb);
          float rNW2 = edgeLuma(texture2D(uRawTex, vTex + vec2(-2.0 * texel.x, texel.y)).rgb);
          float rSW2 = edgeLuma(texture2D(uRawTex, vTex + vec2(-2.0 * texel.x, -texel.y)).rgb);
          float rN2 = edgeLuma(texture2D(uRawTex, vTex + vec2(0.0, 2.0 * texel.y)).rgb);
          float rN2E = edgeLuma(texture2D(uRawTex, vTex + vec2(texel.x, 2.0 * texel.y)).rgb);
          float rN2W = edgeLuma(texture2D(uRawTex, vTex + vec2(-texel.x, 2.0 * texel.y)).rgb);
          float rS2 = edgeLuma(texture2D(uRawTex, vTex + vec2(0.0, -2.0 * texel.y)).rgb);
          float rS2E = edgeLuma(texture2D(uRawTex, vTex + vec2(texel.x, -2.0 * texel.y)).rgb);
          float rS2W = edgeLuma(texture2D(uRawTex, vTex + vec2(-texel.x, -2.0 * texel.y)).rgb);
          float gx5 = (rE2 + rNE2 + rSE2) - (rW2 + rNW2 + rSW2);
          float gy5 = (rN2 + rN2E + rN2W) - (rS2 + rS2E + rS2W);
          float axisAligned = step(min(abs(gx5), abs(gy5)), uTextAlign * max(abs(gx5), abs(gy5)) + 0.001);
          float text = step(0.5, lMax - lMin) * axisAligned;
          // Stroke width (Neo Geo: off, uTextStroke — the
          // basic text test suffices for the small HUD font;
          // large title text is a sprite and is smoothed):
          // a large glyph's stroke is flat ink, so walking
          // from the centre texel along the normal (the
          // dominant axis of the 5x5-ring gradient) the raw
          // luma band over the run stays within 0.05 for
          // the stroke's width — 3+ texels on a large
          // glyph. A thin edge breaks the run early, and a
          // shaded / dithered sprite interior drifts out
          // of the band, so the test does not fire there.
          float r0 = edgeLuma(texture2D(uRawTex, vTex).rgb);
          float rE1 = edgeLuma(texture2D(uRawTex, vTex + vec2(texel.x, 0.0)).rgb);
          float rW1 = edgeLuma(texture2D(uRawTex, vTex - vec2(texel.x, 0.0)).rgb);
          float rN1 = edgeLuma(texture2D(uRawTex, vTex + vec2(0.0, texel.y)).rgb);
          float rS1 = edgeLuma(texture2D(uRawTex, vTex - vec2(0.0, texel.y)).rgb);
          float rE3 = edgeLuma(texture2D(uRawTex, vTex + vec2(3.0 * texel.x, 0.0)).rgb);
          float rW3 = edgeLuma(texture2D(uRawTex, vTex - vec2(3.0 * texel.x, 0.0)).rgb);
          float rN3 = edgeLuma(texture2D(uRawTex, vTex + vec2(0.0, 3.0 * texel.y)).rgb);
          float rS3 = edgeLuma(texture2D(uRawTex, vTex - vec2(0.0, 3.0 * texel.y)).rgb);
          float tolStroke = 0.05;
          float bE1 = step(max(r0, rE1) - min(r0, rE1), tolStroke);
          float bE2 = step(max(max(r0, rE1), rE2) - min(min(r0, rE1), rE2), tolStroke);
          float bE3 = step(max(max(max(r0, rE1), rE2), rE3) - min(min(min(r0, rE1), rE2), rE3), tolStroke);
          float bW1 = step(max(r0, rW1) - min(r0, rW1), tolStroke);
          float bW2 = step(max(max(r0, rW1), rW2) - min(min(r0, rW1), rW2), tolStroke);
          float bW3 = step(max(max(max(r0, rW1), rW2), rW3) - min(min(min(r0, rW1), rW2), rW3), tolStroke);
          float bN1 = step(max(r0, rN1) - min(r0, rN1), tolStroke);
          float bN2 = step(max(max(r0, rN1), rN2) - min(min(r0, rN1), rN2), tolStroke);
          float bN3 = step(max(max(max(r0, rN1), rN2), rN3) - min(min(min(r0, rN1), rN2), rN3), tolStroke);
          float bS1 = step(max(r0, rS1) - min(r0, rS1), tolStroke);
          float bS2 = step(max(max(r0, rS1), rS2) - min(min(r0, rS1), rS2), tolStroke);
          float bS3 = step(max(max(max(r0, rS1), rS2), rS3) - min(min(min(r0, rS1), rS2), rS3), tolStroke);
          float runX = max(bE1 * (1.0 + bE2 * (1.0 + bE3)), bW1 * (1.0 + bW2 * (1.0 + bW3)));
          float runY = max(bN1 * (1.0 + bN2 * (1.0 + bN3)), bS1 * (1.0 + bS2 * (1.0 + bS3)));
          float run = abs(gx5) >= abs(gy5) ? runX : runY;
          float stroke = step(3.0, run) * step(0.5, lMax - lMin);
          text = max(text, stroke * uTextStroke);
          // The collapse gates: the field test and the
          // pattern test both need the guard, and both are
          // blocked by text or an isolated dot — except a
          // texel that is itself a pattern texel (pattern
          // > 0.5): a dither field's own texels are never
          // protected by the text test (the one-directional
          // text test fires on a high-contrast dither field,
          // and the field would split half TEXT, half
          // DITHER and only partly collapse). A glyph stroke
          // texel has pattern ~= 0, so it keeps its
          // protection; a dither texel next to a glyph is a
          // dither texel and collapses to the field mean,
          // as it should.
          float guard = step(shadeDist, uDitherGuardHi);
          float noCollapse = max(text * (1.0 - step(0.5, pattern)), isolated);
          float fieldOk = step(0.6, field) * guard * (1.0 - noCollapse);
          float patOk = step(0.5, pattern) * guard * (1.0 - noCollapse);
          // Class priority (later overrides win): edge,
          // then dither, then text, then the field guard,
          // then 1px. The base dither assignment is a
          // pattern texel, or a texel inside a dither field,
          // so a high-contrast dither texel is never
          // line-reconstructed as an edge. The field guard
          // is what lets dither win over text: a texel
          // INSIDE a dither field (field >= 0.3) is dither,
          // not text, even when the text test fires on it.
          float cls = 0.0;
          if (edgeStrength > uEdgeLo) cls = 1.0;
          if (pattern > 0.5 || field >= 0.3) cls = 2.0;
          if (text > 0.5) cls = 3.0;
          if (field >= 0.3) cls = 2.0;
          if (isolated > 0.5) cls = 4.0;
          vec3 rgb = c0;
          if (fieldOk > 0.5) {
            rgb = mean5;
          } else if (patOk > 0.5) {
            rgb = ditherAvg;
          }
          gl_FragColor = vec4(rgb, cls / 4.0);
        }
    """

    /**
     * Pass B — the line reconstruction, at display size.
     * Reads Pass A's output (uTex: RGB the collapsed colour,
     * A the class / 4.0). The 5x5 Sobel gradient gives the
     * edge angle (the 3x3 Sobel is too local — on a 1:2
     * staircase it reads 18.4 degrees for a 26.6 degree
     * line; the 5x5 reads 0.0 / 2.4 / 2.1 on 45 / 1:2 /
     * 1:3); the side colours come from the profile ends
     * (t = +-2 along the normal); the offset comes from a
     * 2D interval fit over the 5x5 window (the dark texels'
     * largest normal coordinate and the light texels'
     * smallest bound the line; the midpoint is the offset).
     * The output is a 1-display-px transition centred on
     * the line. Only edge-class texels are reconstructed,
     * with a real interval, a strong enough edge and no
     * corner — and the interval fit needs >= 3 supporting
     * texels per side; text / 1px texels output their
     * nearest texel exactly, flat / dither texels output
     * nearest (the field is already collapsed in Pass A).
     * The 12-bit snap (Neo Geo) follows the edge pass's
     * rule: snap only when the colour actually moved —
     * including a dither texel, whose colour moved in
     * Pass A. No Worm Light (HowlingDog has no lamp).
     * The grid (uGrid, Step 5) is a scanline darkening at
     * display size, the same idea as the edge shader's
     * Sharp CRT one-tap combined mode.
     */
    const val RECON_B_FRAGMENT = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec2 uTexSize;
        uniform float uDisplayScale;
        uniform float uSnap12;
        uniform float uGrid;
        float edgeLuma(vec3 c) {
          return dot(c, vec3(0.299, 0.587, 0.114));
        }
        float sobW(int i) {
          if (i == 0 || i == 4) return 1.0;
          if (i == 1 || i == 3) return 4.0;
          return 6.0;
        }
        void main() {
          vec2 srcPos = vTex * uTexSize;
          vec2 base = floor(srcPos);
          vec2 sub = fract(srcPos);
          vec4 c0v = texture2D(uTex, vTex);
          vec3 c0 = c0v.rgb;
          float cls0 = floor(c0v.a * 4.0 + 0.5);
          // 5x5 Sobel on the Pass A luma. Separable kernel
          // w = [1, 4, 6, 4, 1], k = [-2, -1, 0, 1, 2];
          // gx = sum w(dy) * k(dx) * luma, gy = sum k(dy) *
          // w(dx) * luma. The diagonal RGBs are stashed for
          // the corner test.
          float gx = 0.0;
          float gy = 0.0;
          vec3 cNW = vec3(0.0);
          vec3 cNE = vec3(0.0);
          vec3 cSE = vec3(0.0);
          vec3 cSW = vec3(0.0);
          for (int j = 0; j < 5; j++) {
            for (int i = 0; i < 5; i++) {
              int dx = i - 2;
              int dy = j - 2;
              vec2 tapUV = (base + vec2(float(dx) + 0.5, float(dy) + 0.5)) / uTexSize;
              vec3 s = texture2D(uTex, tapUV).rgb;
              float l = edgeLuma(s);
              gx += sobW(j) * float(i - 2) * l;
              gy += float(j - 2) * sobW(i) * l;
              if (dx == -1 && dy == -1) cNW = s;
              if (dx == 1 && dy == -1) cNE = s;
              if (dx == 1 && dy == 1) cSE = s;
              if (dx == -1 && dy == 1) cSW = s;
            }
          }
          float gn = sqrt(gx * gx + gy * gy) + 0.000000001;
          float nx = gx / gn;
          float ny = gy / gn;
          // Corner: both diagonal pairs match. A corner
          // keeps its sharp point, never a blend.
          float corner = step(distance(cNW, cSE), 0.1) * step(distance(cNE, cSW), 0.1) * step(0.05, abs(gx) + abs(gy));
          // Side colours: the profile ends (t = +-2 along
          // the normal), rounded to the nearest texel.
          vec2 offA = vec2(floor(-2.0 * nx + 0.5), floor(-2.0 * ny + 0.5));
          vec2 offB = vec2(floor(2.0 * nx + 0.5), floor(2.0 * ny + 0.5));
          vec3 cA = texture2D(uTex, (base + offA + vec2(0.5)) / uTexSize).rgb;
          vec3 cB = texture2D(uTex, (base + offB + vec2(0.5)) / uTexSize).rgb;
          float lA = edgeLuma(cA);
          float lB = edgeLuma(cB);
          // 2D interval fit for the offset: over the 5x5
          // window, the dark texels' largest normal
          // coordinate and the light texels' smallest bound
          // the line. A texel counts as dark (light) when
          // its colour is within 0.15 of the dark (light)
          // side colour and closer to it than to the other
          // side; dither / text / 1px texels never count
          // (their colours are not the edge's sides). The
          // sentinels (+-100) are far outside the
          // coordinate range (at most +-2.83), so a bound
          // still at its sentinel means no texel counted.
          float dLow = -100.0;
          float dHigh = 100.0;
          float nDark = 0.0;
          float nLight = 0.0;
          for (int j = 0; j < 5; j++) {
            for (int i = 0; i < 5; i++) {
              int dx = i - 2;
              int dy = j - 2;
              vec2 tapUV = (base + vec2(float(dx) + 0.5, float(dy) + 0.5)) / uTexSize;
              vec4 s = texture2D(uTex, tapUV);
              float cls = floor(s.a * 4.0 + 0.5);
              float sv = nx * float(dx) + ny * float(dy);
              float dA = distance(s.rgb, cA);
              float dB = distance(s.rgb, cB);
              float okCls = 1.0;
              if (cls == 2.0 || cls == 3.0 || cls == 4.0) okCls = 0.0;
              if (dA < 0.15 && dA < dB && okCls > 0.5) { dLow = max(dLow, sv); nDark += 1.0; }
              if (dB < 0.15 && dB < dA && okCls > 0.5) { dHigh = min(dHigh, sv); nLight += 1.0; }
            }
          }
          float okInterval = 1.0;
          if (dLow <= -50.0 || dHigh >= 50.0 || dLow >= dHigh) okInterval = 0.0;
          float d = 0.0;
          if (okInterval > 0.5) d = 0.5 * (dLow + dHigh);
          // Signed distance of the fragment from the line,
          // in texels then display px; the output is a
          // 1-display-px transition centred on the line.
          float sTex = (sub.x - 0.5) * nx + (sub.y - 0.5) * ny - d;
          float sPx = sTex * uDisplayScale;
          float mixw = smoothstep(-0.5, 0.5, sPx);
          vec3 outRecon = cA + (cB - cA) * mixw;
          // Guards: reconstruct only edge-class texels,
          // with a real interval, a strong enough edge, and
          // no corner. (The gradient guard is a weak sanity
          // guard only — the 5x5 Sobel's scale is ~16x the
          // 3x3's, so its threshold is 0.5, not 0.1.)
          // Interval-fit fallback: the fit needs >= 3
          // supporting texels per side — a starved fit
          // (1-2 texels setting the bounds from wrong
          // colours) falls back to nearest, exactly like a
          // missing interval.
          float doRecon = 0.0;
          if (cls0 == 1.0 && okInterval > 0.5 && nDark >= 3.0 && nLight >= 3.0 && abs(lB - lA) >= 0.1 && (abs(gx) + abs(gy)) >= 0.5 && corner < 0.5) doRecon = 1.0;
          vec3 outCol = mix(c0, outRecon, doRecon);
          // 12-bit snap (Neo Geo), same rule as the edge
          // pass: snap only when the colour actually moved —
          // including a dither texel, whose colour moved in
          // Pass A.
          if (uSnap12 > 0.5) {
            vec3 snapped = floor(outCol * 15.0 + 0.5) / 15.0;
            float moved = max(step(0.001, distance(outCol, c0)), step(abs(cls0 - 2.0), 0.5));
            outCol = mix(c0, snapped, moved);
          }
          // The grid (Step 5): a scanline darkening at
          // display size, the same idea as the edge shader's
          // Sharp CRT one-tap combined mode. uGrid is 0
          // when the toggle is off (Steps 1-4),
          // RECON_GRID_STRENGTH when it is on.
          float scan = 0.5 + 0.5 * sin(vTex.y * uTexSize.y * 3.14159265);
          outCol *= 1.0 - uGrid * (1.0 - scan) * 0.6;
          outCol = clamp(outCol, vec3(0.0), vec3(1.0));
          gl_FragColor = vec4(outCol, 1.0);
        }
    """

    /**
     * A pass into an FBO stores its output flipped exactly
     * when (inputFlipped == uFlipV): the Bitmap drawn with 1,
     * an FBO texture drawn with 0.
     */
    internal fun fboOutputFlipped(inputFlipped: Boolean, flipV: Boolean): Boolean =
        inputFlipped == flipV

    /**
     * A pass to the screen is right-side up exactly when
     * (uFlipV == inputFlipped): the Bitmap drawn with 0, an
     * FBO texture drawn with 1.
     */
    internal fun displayIsCorrect(inputFlipped: Boolean, flipV: Boolean): Boolean =
        flipV == inputFlipped

    /**
     * The reconstruction's orientation composition: A2's two
     * inputs (texA and the raw frame) must agree row for row,
     * and Pass B's display must be right-side up. False for
     * the triple the handheld Task 6 shipped (A1 0, A2 0, B 1)
     * — the upside-down bug — and true for the flips drawRecon
     * uses now.
     */
    internal fun reconOrientationIsCorrect(
        a1FlipV: Boolean = RECON_A1_FLIP_V,
        a2FlipV: Boolean = RECON_A2_FLIP_V,
        bFlipV: Boolean = RECON_B_FLIP_V,
    ): Boolean {
        val rawFlipped = false
        val texAFlipped = fboOutputFlipped(rawFlipped, a1FlipV)
        val inputsAgree = texAFlipped == rawFlipped
        val texBFlipped = fboOutputFlipped(texAFlipped, a2FlipV)
        val displayCorrect = displayIsCorrect(texBFlipped, bFlipV)
        return inputsAgree && displayCorrect
    }
}
