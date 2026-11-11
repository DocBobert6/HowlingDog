package app.howlingdog

/**
 * The GLSL source for the CRT shader chain (GLES20 / GLSL ES
 * 1.00). Kept as plain strings in pure Kotlin (no Android
 * imports) so the text itself is unit-testable on the JVM —
 * the renderer (CrtRenderer) only compiles it.
 *
 * The chain is one combined pass, in the order a CRT shapes a
 * picture: horizontal softness (composite blur), colour grade,
 * scanlines, aperture grille, then a light vignette. With every
 * strength at zero and a neutral grade the pass reproduces the
 * plain texture sample exactly (the vignette is gated by its
 * own uniform, so "shader off" is truly off).
 *
 * The grille reuses Mariner's LCD-shader trick: one sine per
 * colour channel, phase-shifted by a third of a cycle, so the
 * three sub-pixels of each texel get their own stripe.
 */
object CrtShaders {
    const val VERTEX = """
        attribute vec2 aPos;
        attribute vec2 aTex;
        varying vec2 vTex;
        void main() {
          gl_Position = vec4(aPos, 0.0, 1.0);
          vTex = aTex;
        }
    """

    const val FRAGMENT = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec2 uTexSize;
        uniform float uScan;
        uniform float uGrille;
        uniform float uBlur;
        uniform float uSat;
        uniform float uWarm;
        uniform float uGamma;
        uniform float uBright;
        uniform float uBlack;
        uniform float uVignette;
        void main() {
          vec2 texel = vec2(1.0 / uTexSize.x, 0.0);
          vec3 c0 = texture2D(uTex, vTex).rgb;
          vec3 cl = texture2D(uTex, vTex - texel).rgb;
          vec3 cr = texture2D(uTex, vTex + texel).rgb;
          vec3 col = mix(c0, (cl + c0 + cr) / 3.0, uBlur);
          float luma = dot(col, vec3(0.299, 0.587, 0.114));
          col = mix(vec3(luma), col, uSat);
          col *= vec3(1.0 + uWarm * 0.004, 1.0, 1.0 - uWarm * 0.004);
          col = pow(max(col, vec3(0.0)), vec3(uGamma));
          col = col * uBright + vec3(uBlack);
          float scan = 0.5 + 0.5 * sin(vTex.y * uTexSize.y * 3.14159265);
          col *= 1.0 - uScan * (1.0 - scan) * 0.6;
          vec3 off = vec3(3.14159265) * vec3(0.5, 0.5 - 2.0 / 3.0, 0.5 - 4.0 / 3.0);
          vec3 g = (4.0 + sin(vTex.x * uTexSize.x * 3.0 * 3.14159265 + off)) / 5.0;
          col *= mix(vec3(1.0), g, uGrille);
          vec2 d = vTex - 0.5;
          col *= 1.0 - uVignette * dot(d, d);
          gl_FragColor = vec4(col, 1.0);
        }
    """

    /** Fallback if the CRT program fails to compile: a plain texture sample, so the game stays visible. */
    const val PLAIN_FRAGMENT = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        void main() {
          gl_FragColor = texture2D(uTex, vTex);
        }
    """

    /**
     * 2.0, feature 7 — the dither-aware edge-smoothing shader
     * (GLES20 / GLSL ES 1.00, one fragment pass). It detects
     * hard sprite edges and fills intermediate anti-aliased
     * pixels along them, so a diagonal reads as a clean line
     * instead of a staircase.
     *
     * Dither-aware: a 2x2 checkerboard (the Neo Geo's
     * transparency, shadow and shading dither) is detected and
     * preserved — the center texel is output unchanged — so the
     * dither does not blur into mud or stay as a hard
     * checkerboard. A generic upscaler does one or the other.
     *
     * Directional interpolation: the blend runs ALONG the
     * detected edge direction, not across it (the ScaleFX
     * approach), so diagonals stay clean instead of smearing.
     * Corner preservation: a texel with a strong gradient in
     * both directions is a corner and is left sharp, not rounded
     * into a blob. Color-aware blending: the blend is clamped
     * to the range of the colors it mixes, so no muddy
     * intermediate color appears across a high-contrast edge and
     * the Neo Geo palette stays clean.
     *
     * Temporal stability: the output is a pure function of the
     * current frame (no noise, no time uniform, no frame
     * history), so a moving sprite's edges do not wobble or
     * shimmer frame to frame.
     *
     * The result is very clean, sharp pixel art — NOT
     * hand-drawn vector art. It does not turn pixel art into
     * Hollow Knight-style art, and the code does not claim it
     * does.
     *
     * uScan is the scanline strength (0 for plain edge
     * smoothing; > 0 for the "sharp CRT" combined mode, which is
     * edge smoothing then scanlines only, one tap).
     */
    const val EDGE_FRAGMENT = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec2 uTexSize;
        uniform float uScan;
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
          // Dither: in a checkerboard 2x2 the center matches its
          // diagonal neighbour and differs from BOTH orthogonal
          // neighbours. Requiring both is what keeps a diagonal
          // edge from reading as dither: a diagonal's 2x2 matches
          // the diagonal neighbour but differs from only one
          // orthogonal neighbour, so it is an edge, not dither.
          // When it holds, preserve the dither (output the
          // center unchanged).
          float dDiag = distance(c0, cSE);
          float dE = distance(c0, cE);
          float dS = distance(c0, cS);
          float dither = step(dDiag * 3.0 + 0.02, dE + dS) * step(0.1, dE) * step(0.1, dS);
          // Edge: a Sobel-like luma gradient over the 3x3.
          float lN = edgeLuma(cN);
          float lS = edgeLuma(cS);
          float lE = edgeLuma(cE);
          float lW = edgeLuma(cW);
          float lNE = edgeLuma(cNE);
          float lNW = edgeLuma(cNW);
          float lSE = edgeLuma(cSE);
          float lSW = edgeLuma(cSW);
          float gx = (lE + lNE + lSE) - (lW + lNW + lSW);
          float gy = (lN + lNE + lNW) - (lS + lSE + lSW);
          float edgeStrength = abs(gx) + abs(gy);
          // Corner: both diagonal pairs match. A diagonal edge
          // has a strong gradient in BOTH directions (so a
          // min(|gx|, |gy|) test misreads every diagonal as a
          // corner and never smooths it), but its two diagonal
          // pairs do not both match — one pair straddles the
          // edge. A true corner (two edges meeting) has all
          // four diagonal neighbours on the same side, so both
          // pairs match. Keep it sharp.
          float corner = step(distance(cNW, cSE), 0.1) * step(distance(cNE, cSW), 0.1) * step(0.05, edgeStrength);
          // Directional blend: along the edge, not across it.
          // The weight grows toward the texel boundary (the
          // sub-pixel position within the source texel), so the
          // centre of a texel stays its own colour and the
          // boundary pixel is the anti-aliased mix.
          vec2 sub = fract(vTex * uTexSize);
          float wAlong;
          vec3 along;
          vec3 lo;
          vec3 hi;
          if (abs(gx) > abs(gy)) {
            along = mix(cS, cN, sub.y);
            wAlong = abs(sub.y - 0.5) * 2.0;
            lo = min(min(c0, cS), cN);
            hi = max(max(c0, cS), cN);
          } else {
            along = mix(cW, cE, sub.x);
            wAlong = abs(sub.x - 0.5) * 2.0;
            lo = min(min(c0, cW), cE);
            hi = max(max(c0, cW), cE);
          }
          float edge = smoothstep(0.05, 0.35, edgeStrength);
          float w = edge * wAlong * (1.0 - dither) * (1.0 - corner);
          vec3 col = mix(c0, along, clamp(w, 0.0, 0.75));
          col = clamp(col, lo, hi);
          float scan = 0.5 + 0.5 * sin(vTex.y * uTexSize.y * 3.14159265);
          col *= 1.0 - uScan * (1.0 - scan) * 0.6;
          gl_FragColor = vec4(col, 1.0);
        }
    """
}
