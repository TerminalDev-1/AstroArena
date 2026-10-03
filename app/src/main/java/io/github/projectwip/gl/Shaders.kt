package io.github.projectwip.gl

import android.opengl.GLES30
import android.util.Log

/** A linked GLSL program with cached uniform locations. */
class Program(vertex: String, fragment: String, val name: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            Log.e("Program", "$name link failed: $log")
            throw IllegalStateException("$name link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            Log.e("Program", "$name shader compile failed: $log")
            throw IllegalStateException("$name compile failed: $log")
        }
        return s
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun mat4(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(name), 1, false, m, 0)
    fun f(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun i(name: String, v: Int) = GLES30.glUniform1i(loc(name), v)
    fun v3(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc(name), x, y, z)
    fun v4(name: String, x: Float, y: Float, z: Float, w: Float) = GLES30.glUniform4f(loc(name), x, y, z, w)
    fun v3a(name: String, arr: FloatArray, count: Int) = GLES30.glUniform3fv(loc(name), count, arr, 0)
}

object Shaders {
    /** Shared vertex stage: model transform, grass wind, outline inflation, shadow-space position. */
    const val LIT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec4 aColor;
layout(location=3) in float aExtra;
uniform mat4 uModel;
uniform mat4 uViewProj;
uniform mat4 uLightVP;
uniform float uTime;
uniform float uSway;
uniform float uOutline;
out vec3 vNormal;
out vec4 vColor;
out vec4 vLightPos;
out vec3 vWorld;
out float vExtra;
void main() {
    vec4 w = uModel * vec4(aPos + aNormal * uOutline, 1.0);
    float k = aExtra * uSway;
    w.x += sin(uTime * 1.9 + w.z * 0.9 + w.x * 0.4) * 0.09 * k;
    w.z += cos(uTime * 1.4 + w.x * 0.7) * 0.06 * k;
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vColor = aColor;
    vExtra = aExtra;
    vLightPos = uLightVP * w;
    gl_Position = uViewProj * w;
}
"""

    /**
     * Stylised "toon" lighting: a soft two-band sun term with shadow map, hemisphere ambient, rim light.
     * uMode: 0 = lit, 1 = flat colour (outlines, decals), 2 = x-ray silhouette, 3 = unlit vertex colour (sky).
     */
    const val LIT_FS = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
uniform sampler2DShadow uShadow;
uniform vec4 uTint;
uniform vec3 uLightDir;
uniform vec3 uCamPos;
uniform vec3 uSky;
uniform vec3 uGround;
uniform vec3 uSun;
uniform float uFlash;
uniform float uEmissive;
uniform float uRim;
uniform float uShadowOn;
uniform float uShadowTexel;
uniform int uMode;
uniform vec3 uReveal[3];
uniform float uRevealOn;
uniform float uDissolve;
in vec3 vNormal;
in vec4 vColor;
in vec4 vLightPos;
in vec3 vWorld;
in float vExtra;
out vec4 o;

float shadowAt() {
    vec3 p = vLightPos.xyz / vLightPos.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    float t = uShadowTexel;
    float b = 0.0018;
    float s = texture(uShadow, vec3(p.xy + vec2(-t, -t), p.z - b));
    s += texture(uShadow, vec3(p.xy + vec2( t, -t), p.z - b));
    s += texture(uShadow, vec3(p.xy + vec2(-t,  t), p.z - b));
    s += texture(uShadow, vec3(p.xy + vec2( t,  t), p.z - b));
    return s * 0.25;
}

void main() {
    // Screen-door fade (fighters slipping into / out of cover): no blending, so outlines and depth stay clean.
    if (uDissolve > 0.0 && fract(dot(floor(gl_FragCoord.xy * 0.5), vec2(0.7548777, 0.5698403))) < uDissolve) discard;
    if (uMode == 1) { o = uTint; return; }
    if (uMode == 2) { o = vec4(uTint.rgb, uTint.a); return; }
    if (uMode == 3) { o = vec4(vColor.rgb * uTint.rgb, 1.0); return; }
    vec3 n = normalize(vNormal);
    vec3 base = vColor.rgb * uTint.rgb;
    float ndl = dot(n, -uLightDir);
    float sh = uShadowOn > 0.5 ? shadowAt() : 1.0;
    float band = smoothstep(0.0, 0.18, ndl);
    float lit = band * sh;
    vec3 amb = mix(uGround, uSky, n.y * 0.5 + 0.5);
    vec3 col = base * (amb + uSun * lit);
    vec3 v = normalize(uCamPos - vWorld);
    float rim = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 3.0) * uRim;
    col += rim * mix(vec3(1.0), base, 0.3);
    col = mix(col, base * 1.35 + 0.08, uEmissive);
    col = mix(col, vec3(1.0), uFlash);
    float alpha = vColor.a * uTint.a;
    if (uRevealOn > 0.5) {
        // Grass near friendly fighters turns see-through.
        for (int i = 0; i < 3; i++) {
            float d = distance(vWorld.xz, uReveal[i].xz);
            if (uReveal[i].y > 0.5 && d < 1.9) alpha = min(alpha, mix(0.28, 1.0, smoothstep(1.2, 1.9, d)));
        }
    }
    o = vec4(col, alpha);
}
"""

    const val DEPTH_FS = """#version 300 es
precision highp float;
uniform float uDissolve;
out vec4 o;
void main() {
    if (uDissolve > 0.0 && fract(dot(floor(gl_FragCoord.xy * 0.5), vec2(0.7548777, 0.5698403))) < uDissolve) discard;
    o = vec4(1.0);
}
"""

    /** Animated coolant: scrolling caustic bands + sun glint. */
    const val WATER_FS = """#version 300 es
precision highp float;
uniform float uTime;
uniform vec3 uCamPos;
uniform vec3 uLightDir;
in vec3 vNormal;
in vec4 vColor;
in vec4 vLightPos;
in vec3 vWorld;
in float vExtra;
out vec4 o;
void main() {
    vec2 p = vWorld.xz;
    float w1 = sin(p.x * 3.1 + uTime * 1.6) * sin(p.y * 2.3 - uTime * 1.2);
    float w2 = sin((p.x + p.y) * 4.7 - uTime * 2.1);
    float c = smoothstep(0.55, 0.95, w1 * 0.6 + w2 * 0.4);
    vec3 deep = vec3(0.09, 0.47, 0.78);
    vec3 shallow = vec3(0.24, 0.75, 0.95);
    vec3 col = mix(deep, shallow, 0.5 + 0.5 * w1) + c * vec3(0.55, 0.9, 1.0) * 0.6;
    vec3 v = normalize(uCamPos - vWorld);
    vec3 h = normalize(v - uLightDir);
    vec3 n = normalize(vec3(w1 * 0.15, 1.0, w2 * 0.15));
    col += pow(max(dot(n, h), 0.0), 60.0) * 0.8;
    o = vec4(col, 0.92);
}
"""

    /** Camera-facing glow sprites (particles, projectile glows). */
    const val SPRITE_VS = """#version 300 es
layout(location=0) in vec3 aCenter;
layout(location=1) in vec2 aCorner;
layout(location=2) in vec4 aColor;
layout(location=3) in float aSize;
uniform mat4 uViewProj;
uniform vec3 uRight;
uniform vec3 uUp;
out vec2 vUv;
out vec4 vColor;
void main() {
    vec3 p = aCenter + (uRight * aCorner.x + uUp * aCorner.y) * aSize;
    vUv = aCorner;
    vColor = aColor;
    gl_Position = uViewProj * vec4(p, 1.0);
}
"""

    const val SPRITE_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
in vec4 vColor;
out vec4 o;
void main() {
    float d = length(vUv);
    if (d > 1.0) discard;
    float core = smoothstep(1.0, 0.0, d);
    float hot = smoothstep(0.45, 0.0, d);
    o = vec4(mix(vColor.rgb, vec3(1.0), hot * 0.6), vColor.a * core);
}
"""
}
