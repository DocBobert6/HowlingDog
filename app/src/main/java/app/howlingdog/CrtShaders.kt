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
}
