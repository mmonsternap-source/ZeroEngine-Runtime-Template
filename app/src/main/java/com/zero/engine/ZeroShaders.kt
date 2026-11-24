package com.zero.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class AtlasFrame(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val sourceW: Float,
    val sourceH: Float
)

data class TextureAtlasData(
    val imageFile: String,
    val atlasW: Float,
    val atlasH: Float,
    val frames: Map<String, AtlasFrame>
)

object ZeroShaders {

    fun buildShaderProgram(vsSrc: String, fsSrc: String, onLog: (String) -> Unit): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc, onLog)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc, onLog)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    fun compileShader(type: Int, src: String, onLog: (String) -> Unit): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            onLog("[GLSL 错误] ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    fun createFboTarget(w: Int, h: Int): Pair<Int, Int> {
        val fbos = IntArray(1)
        val texs = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        GLES20.glGenTextures(1, texs, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texs[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) fbos[0] to texs[0] else {
            GLES20.glDeleteFramebuffers(1, fbos, 0)
            GLES20.glDeleteTextures(1, texs, 0)
            0 to 0
        }
    }

    fun createSolidWhiteTexture(): Int {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val id = uploadBitmapToGL(bmp)
        bmp.recycle()
        return id
    }

    fun uploadBitmapToGL(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
    }

    fun loadAtlasData(
        projectDir: File,
        atlasName: String,
        atlasCache: ConcurrentHashMap<String, TextureAtlasData>,
        textureSizes: ConcurrentHashMap<String, Pair<Int, Int>>,
        ensureImageLoaded: (String) -> Int,
        onLog: (String) -> Unit
    ): TextureAtlasData? {
        val cleaned = atlasName.trim().replace("\\", "/").removePrefix("./")
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val norm = if (cleaned.endsWith(".json", ignoreCase = true)) cleaned else "$cleaned.json"
        atlasCache[norm]?.let { return it }

        val file = File(File(projectDir, "textures"), norm)
        if (!file.exists()) {
            onLog("[ATLAS 警告] 找不到图集文件: textures/$norm")
            return null
        }
        return try {
            val root = JSONObject(file.readText())
            val metaObj = root.optJSONObject("meta")
            val imageFile = root.optString("image", "").ifEmpty { metaObj?.optString("image", "") ?: "" }
            if (imageFile.isEmpty()) return null
            ensureImageLoaded(imageFile)
            val imgSize = textureSizes[imageFile]
            val sizeObj = root.optJSONObject("size") ?: metaObj?.optJSONObject("size")
            val atlasW = sizeObj?.optDouble("w", (imgSize?.first ?: 1024).toDouble())?.toFloat()
                ?: (imgSize?.first ?: 1024).toFloat()
            val atlasH = sizeObj?.optDouble("h", (imgSize?.second ?: 1024).toDouble())?.toFloat()
                ?: (imgSize?.second ?: 1024).toFloat()

            val frameMap = HashMap<String, AtlasFrame>()
            val framesObj = root.optJSONObject("frames")
            if (framesObj != null) {
                val keys = framesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val fo = framesObj.optJSONObject(key) ?: continue
                    val rect = fo.optJSONObject("frame") ?: fo
                    val srcSize = fo.optJSONObject("sourceSize")
                    frameMap[key] = AtlasFrame(
                        x = rect.optDouble("x", 0.0).toFloat(),
                        y = rect.optDouble("y", 0.0).toFloat(),
                        w = rect.optDouble("w", 0.0).toFloat(),
                        h = rect.optDouble("h", 0.0).toFloat(),
                        sourceW = srcSize?.optDouble("w", rect.optDouble("w", 0.0))?.toFloat() ?: rect.optDouble("w", 0.0).toFloat(),
                        sourceH = srcSize?.optDouble("h", rect.optDouble("h", 0.0))?.toFloat() ?: rect.optDouble("h", 0.0).toFloat()
                    )
                }
            } else {
                val framesArr = root.optJSONArray("frames")
                if (framesArr != null) {
                    for (i in 0 until framesArr.length()) {
                        val fo = framesArr.optJSONObject(i) ?: continue
                        val key = fo.optString("filename", "frame_$i")
                        val rect = fo.optJSONObject("frame") ?: fo
                        frameMap[key] = AtlasFrame(
                            x = rect.optDouble("x", 0.0).toFloat(),
                            y = rect.optDouble("y", 0.0).toFloat(),
                            w = rect.optDouble("w", 0.0).toFloat(),
                            h = rect.optDouble("h", 0.0).toFloat(),
                            sourceW = rect.optDouble("w", 0.0).toFloat(),
                            sourceH = rect.optDouble("h", 0.0).toFloat()
                        )
                    }
                }
            }
            val data = TextureAtlasData(imageFile, max(1f, atlasW), max(1f, atlasH), frameMap)
            atlasCache[norm] = data
            data
        } catch (e: Exception) {
            onLog("[ATLAS 解析错误 @ $norm] ${e.message}")
            null
        }
    }

    const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uMVP;
        uniform mat4 uModel;
        uniform vec4 uUvRect;
        varying vec2 vTexCoord;
        varying vec3 vWorldPos;
        void main() {
            vec4 worldPos = uModel * aPosition;
            vWorldPos = worldPos.xyz;
            vTexCoord = uUvRect.xy + aTexCoord * uUvRect.zw;
            gl_Position = uMVP * aPosition;
        }
    """

    const val DEFAULT_LIT_FRAGMENT_SHADER = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec4 uColor;
        uniform float uFlash;
        uniform int uEnableLight;
        uniform vec3 uAmbient;
        uniform int uLightCount;
        uniform vec4 uLightPos[8];
        uniform vec4 uLightColor[8];
        varying vec2 vTexCoord;
        varying vec3 vWorldPos;
        void main() {
            vec4 base = texture2D(uTexture, vTexCoord) * uColor;
            if (base.a < 0.01) discard;
            vec3 litRgb = base.rgb;
            if (uEnableLight != 0) {
                vec3 totalLight = uAmbient;
                for (int i = 0; i < 8; i++) {
                    if (i >= uLightCount) break;
                    float dist = distance(vWorldPos.xy, uLightPos[i].xy);
                    float radius = max(1.0, uLightPos[i].w);
                    float atten = clamp(1.0 - (dist / radius), 0.0, 1.0);
                    atten = atten * atten;
                    totalLight += uLightColor[i].rgb * atten;
                }
                litRgb = base.rgb * totalLight;
            }
            vec3 finalRgb = mix(litRgb, vec3(1.0, 1.0, 1.0), clamp(uFlash, 0.0, 1.0));
            gl_FragColor = vec4(finalRgb, base.a);
        }
    """

    const val BATCH_VERTEX_SHADER = """
        attribute vec3 aPosition;
        attribute vec2 aTexCoord;
        attribute vec4 aColor;
        attribute float aFlash;
        uniform mat4 uVP;
        varying vec2 vTexCoord;
        varying vec4 vColor;
        varying float vFlash;
        varying vec3 vWorldPos;
        void main() {
            vWorldPos = aPosition;
            vTexCoord = aTexCoord;
            vColor = aColor;
            vFlash = aFlash;
            gl_Position = uVP * vec4(aPosition, 1.0);
        }
    """

    const val BATCH_FRAGMENT_SHADER = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform int uEnableLight;
        uniform vec3 uAmbient;
        uniform int uLightCount;
        uniform vec4 uLightPos[8];
        uniform vec4 uLightColor[8];
        varying vec2 vTexCoord;
        varying vec4 vColor;
        varying float vFlash;
        varying vec3 vWorldPos;
        void main() {
            vec4 base = texture2D(uTexture, vTexCoord) * vColor;
            if (base.a < 0.01) discard;
            vec3 litRgb = base.rgb;
            if (uEnableLight != 0) {
                vec3 totalLight = uAmbient;
                for (int i = 0; i < 8; i++) {
                    if (i >= uLightCount) break;
                    float dist = distance(vWorldPos.xy, uLightPos[i].xy);
                    float radius = max(1.0, uLightPos[i].w);
                    float atten = clamp(1.0 - (dist / radius), 0.0, 1.0);
                    atten = atten * atten;
                    totalLight += uLightColor[i].rgb * atten;
                }
                litRgb = base.rgb * totalLight;
            }
            vec3 finalRgb = mix(litRgb, vec3(1.0, 1.0, 1.0), clamp(vFlash, 0.0, 1.0));
            gl_FragColor = vec4(finalRgb, base.a);
        }
    """

    const val LIGHT_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec4 uColor;
        varying vec2 vTexCoord;
        void main() {
            vec2 d = (vTexCoord - vec2(0.5, 0.5)) * 2.0;
            float dist = length(d);
            float atten = clamp(1.0 - dist, 0.0, 1.0);
            atten = atten * atten;
            gl_FragColor = vec4(uColor.rgb * atten, 1.0);
        }
    """

    const val POST_VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = aTexCoord;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """

    const val POST_FRAGMENT_SHADER = """
        precision mediump float;
        uniform sampler2D uSceneTex;
        uniform sampler2D uLightTex;
        uniform vec2 uTexelSize;
        uniform float uBloom;
        uniform float uVignette;
        uniform float uChromatic;
        uniform float uFade;
        varying vec2 vTexCoord;
        void main() {
            vec2 uv = vTexCoord;
            vec3 baseRgb;
            if (uChromatic > 0.0001) {
                float off = uChromatic * 0.015;
                float r = texture2D(uSceneTex, vec2(clamp(uv.x + off, 0.0, 1.0), uv.y)).r;
                float g = texture2D(uSceneTex, uv).g;
                float b = texture2D(uSceneTex, vec2(clamp(uv.x - off, 0.0, 1.0), uv.y)).b;
                baseRgb = vec3(r, g, b);
            } else {
                baseRgb = texture2D(uSceneTex, uv).rgb;
            }
            vec3 lightRgb = texture2D(uLightTex, uv).rgb;
            vec3 col = baseRgb * lightRgb;

            if (uBloom > 0.001) {
                vec2 stepUv = uTexelSize * 3.5;
                vec3 b0 = texture2D(uSceneTex, clamp(uv + vec2( stepUv.x,  stepUv.y), 0.0, 1.0)).rgb * texture2D(uLightTex, clamp(uv + vec2( stepUv.x,  stepUv.y), 0.0, 1.0)).rgb;
                vec3 b1 = texture2D(uSceneTex, clamp(uv + vec2(-stepUv.x,  stepUv.y), 0.0, 1.0)).rgb * texture2D(uLightTex, clamp(uv + vec2(-stepUv.x,  stepUv.y), 0.0, 1.0)).rgb;
                vec3 b2 = texture2D(uSceneTex, clamp(uv + vec2( stepUv.x, -stepUv.y), 0.0, 1.0)).rgb * texture2D(uLightTex, clamp(uv + vec2( stepUv.x, -stepUv.y), 0.0, 1.0)).rgb;
                vec3 b3 = texture2D(uSceneTex, clamp(uv + vec2(-stepUv.x, -stepUv.y), 0.0, 1.0)).rgb * texture2D(uLightTex, clamp(uv + vec2(-stepUv.x, -stepUv.y), 0.0, 1.0)).rgb;
                vec3 avg = (b0 + b1 + b2 + b3) * 0.25;
                vec3 bright = max(avg - vec3(0.55), vec3(0.0));
                col += bright * uBloom * 1.8;
            }

            if (uVignette > 0.001) {
                vec2 d = uv - vec2(0.5, 0.5);
                float vig = 1.0 - dot(d, d) * (uVignette * 2.2);
                col *= clamp(vig, 0.0, 1.0);
            }

            col = mix(col, vec3(0.0, 0.0, 0.0), clamp(uFade, 0.0, 1.0));
            gl_FragColor = vec4(col, 1.0);
        }
    """
}
