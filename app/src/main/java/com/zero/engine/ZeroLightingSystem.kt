package com.zero.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES20
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * P8 次世代 2D 光照与软阴影子系统：
 * 1. 切线空间 2D 法线贴图（Normal Mapping）+ Blinn-Phong 实时立体高光
 * 2. 2D SDF 距离场光线步进软阴影（Analytical Box SDF Soft Shadows），自动剔除自身阴影穿帮
 * 3. 支持实时调节阴影柔和度（Penumbra）、最低暗部亮度与开关
 */
class ZeroLightingSystem(
    private val projectDir: File,
    private val onConsoleLog: (String) -> Unit
) {
    companion object {
        const val MAX_LIGHTS = 8
        const val MAX_OCCLUDERS = 24

        const val P8_LIT_VERTEX_SHADER = """
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

        const val P8_LIT_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform sampler2D uTexture;
            uniform sampler2D uNormalMap;
            uniform vec4 uColor;
            uniform float uFlash;
            uniform int uEnableLight;
            uniform vec3 uAmbient;
            uniform int uLightCount;
            uniform vec4 uLightPos[8];
            uniform vec4 uLightColor[8];

            // P8 法线材质与 SDF 软阴影参数
            uniform float uNormalStrength;
            uniform float uSpecular;
            uniform float uFlipNormalX;
            uniform int uEnableSdfShadow;
            uniform float uShadowSoftness;
            uniform float uShadowMinLight;
            uniform int uOccluderCount;
            uniform vec4 uOccluders[24]; // (cx, cy, halfW, halfH)

            varying vec2 vTexCoord;
            varying vec3 vWorldPos;

            float sdBox2D(vec2 p, vec2 b) {
                vec2 d = abs(p) - b;
                return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
            }

            float mapSceneSdf(vec2 p, vec2 rayOrigin) {
                float minD = 99999.0;
                for (int j = 0; j < 24; j++) {
                    if (j >= uOccluderCount) break;
                    vec4 occ = uOccluders[j];
                    // 自动跳过包含当前像素的遮挡体，防止墙壁/角色产生自阴影黑斑
                    if (sdBox2D(rayOrigin - occ.xy, occ.zw) < 1.5) continue;
                    float d = sdBox2D(p - occ.xy, occ.zw);
                    if (d < minD) minD = d;
                }
                return minD;
            }

            float calcSoftShadow(vec2 ro, vec2 lightXY) {
                if (uEnableSdfShadow == 0 || uOccluderCount <= 0) return 1.0;
                vec2 toLight = lightXY - ro;
                float maxDist = length(toLight);
                if (maxDist < 4.0) return 1.0;
                vec2 rd = toLight / maxDist;

                float res = 1.0;
                float t = 6.0;
                for (int stepIdx = 0; stepIdx < 16; stepIdx++) {
                    if (t >= maxDist) break;
                    float h = mapSceneSdf(ro + rd * t, ro);
                    if (h < 0.5) return uShadowMinLight;
                    res = min(res, uShadowSoftness * h / t);
                    t += clamp(h, 8.0, 90.0);
                }
                return clamp(res, uShadowMinLight, 1.0);
            }

            void main() {
                vec4 base = texture2D(uTexture, vTexCoord) * uColor;
                if (base.a < 0.01) discard;

                vec3 litRgb = base.rgb;
                if (uEnableLight != 0) {
                    vec3 rawN = texture2D(uNormalMap, vTexCoord).xyz * 2.0 - 1.0;
                    rawN.x *= uFlipNormalX;
                    rawN.y = -rawN.y; // 适配 2D 屏幕 Y 轴向下坐标系
                    vec3 N = normalize(mix(vec3(0.0, 0.0, 1.0), rawN, clamp(uNormalStrength, 0.0, 2.5)));
                    vec3 V = vec3(0.0, 0.0, 1.0);

                    vec3 totalDiffuse = uAmbient;
                    vec3 totalSpec = vec3(0.0);

                    for (int i = 0; i < 8; i++) {
                        if (i >= uLightCount) break;
                        vec3 lVec = vec3(uLightPos[i].xy - vWorldPos.xy, max(45.0, uLightPos[i].z - vWorldPos.z + 65.0));
                        float dist = length(lVec.xy);
                        float radius = max(1.0, uLightPos[i].w);
                        if (dist >= radius) continue;

                        float atten = clamp(1.0 - (dist / radius), 0.0, 1.0);
                        atten = atten * atten;

                        vec3 L = normalize(lVec);
                        float ndotl = max(dot(N, L), 0.0);
                        float shadow = calcSoftShadow(vWorldPos.xy, uLightPos[i].xy);

                        totalDiffuse += uLightColor[i].rgb * (ndotl * atten * shadow);

                        if (uSpecular > 0.001 && ndotl > 0.0) {
                            vec3 H = normalize(L + V);
                            float spec = pow(max(dot(N, H), 0.0), 28.0) * uSpecular;
                            totalSpec += uLightColor[i].rgb * (spec * atten * shadow);
                        }
                    }
                    litRgb = base.rgb * totalDiffuse + totalSpec;
                }

                vec3 finalRgb = mix(litRgb, vec3(1.0, 1.0, 1.0), clamp(uFlash, 0.0, 1.0));
                gl_FragColor = vec4(finalRgb, base.a);
            }
        """

        // P8 升级版体积光照 FBO 着色器：光源光晕本身也被 SDF 墙体实时遮挡并投射软阴影
        const val P8_SDF_LIGHT_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform vec4 uColor;
            uniform vec2 uLightCenter;
            uniform int uEnableSdfShadow;
            uniform float uShadowSoftness;
            uniform float uShadowMinLight;
            uniform int uOccluderCount;
            uniform vec4 uOccluders[24];

            varying vec2 vTexCoord;
            varying vec3 vWorldPos;

            float sdBox2D(vec2 p, vec2 b) {
                vec2 d = abs(p) - b;
                return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
            }

            float mapSdf(vec2 p, vec2 ro) {
                float minD = 99999.0;
                for (int j = 0; j < 24; j++) {
                    if (j >= uOccluderCount) break;
                    vec4 occ = uOccluders[j];
                    if (sdBox2D(ro - occ.xy, occ.zw) < 1.5) continue;
                    float d = sdBox2D(p - occ.xy, occ.zw);
                    if (d < minD) minD = d;
                }
                return minD;
            }

            float calcShadow(vec2 ro, vec2 lightXY) {
                if (uEnableSdfShadow == 0 || uOccluderCount <= 0) return 1.0;
                vec2 toL = lightXY - ro;
                float maxDist = length(toL);
                if (maxDist < 4.0) return 1.0;
                vec2 rd = toL / maxDist;
                float res = 1.0;
                float t = 6.0;
                for (int k = 0; k < 16; k++) {
                    if (t >= maxDist) break;
                    float h = mapSdf(ro + rd * t, ro);
                    if (h < 0.5) return uShadowMinLight;
                    res = min(res, uShadowSoftness * h / t);
                    t += clamp(h, 8.0, 90.0);
                }
                return clamp(res, uShadowMinLight, 1.0);
            }

            void main() {
                vec2 d = (vTexCoord - vec2(0.5, 0.5)) * 2.0;
                float dist = length(d);
                float atten = clamp(1.0 - dist, 0.0, 1.0);
                atten = atten * atten;
                if (atten < 0.002) discard;
                float shadow = calcShadow(vWorldPos.xy, uLightCenter);
                gl_FragColor = vec4(uColor.rgb * atten * shadow, 1.0);
            }
        """
    }

    var sdfShadowEnabled: Boolean = true
    var shadowSoftness: Float = 10.0f
    var shadowMinLight: Float = 0.08f

    var p8LitProgram: Int = 0
        private set
    var p8LightFboProgram: Int = 0
        private set
    var flatNormalTexId: Int = 0
        private set

    private val normalTexCache = ConcurrentHashMap<String, Int>()
    val occluderData = FloatArray(MAX_OCCLUDERS * 4)
    var activeOccluderCount: Int = 0
        private set

    fun initGL() {
        flatNormalTexId = createFlatNormalTexture()
        p8LitProgram = ZeroShaders.buildShaderProgram(P8_LIT_VERTEX_SHADER, P8_LIT_FRAGMENT_SHADER, onConsoleLog)
        p8LightFboProgram = ZeroShaders.buildShaderProgram(P8_LIT_VERTEX_SHADER, P8_SDF_LIGHT_FRAGMENT_SHADER, onConsoleLog)
    }

    fun releaseGL() {
        val ids = normalTexCache.values.filter { it != 0 && it != flatNormalTexId }.toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        normalTexCache.clear()
        if (flatNormalTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(flatNormalTexId), 0); flatNormalTexId = 0 }
        if (p8LitProgram != 0) { GLES20.glDeleteProgram(p8LitProgram); p8LitProgram = 0 }
        if (p8LightFboProgram != 0) { GLES20.glDeleteProgram(p8LightFboProgram); p8LightFboProgram = 0 }
    }

    fun getNormalTexture(fileName: String): Int {
        if (fileName.isBlank()) return flatNormalTexId
        normalTexCache[fileName]?.let { return it }
        val file = File(File(projectDir, "textures"), fileName)
        if (!file.exists()) return flatNormalTexId
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return flatNormalTexId
        val id = ZeroShaders.uploadBitmapToGL(bmp)
        bmp.recycle()
        normalTexCache[fileName] = id
        return id
    }

    fun unloadUnusedNormals(activeNames: Set<String>): Int {
        val delKeys = normalTexCache.keys.filter { it !in activeNames }
        val ids = delKeys.mapNotNull { normalTexCache[it] }.filter { it != 0 && it != flatNormalTexId }.toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        delKeys.forEach { normalTexCache.remove(it) }
        return delKeys.size
    }

    /**
     * 每帧收集视口内所有开启 shadowCaster 的节点作为 2D SDF 软阴影遮挡体
     */
    fun collectOccluders(
        nodes: List<ZeroEngineView.GameNode>,
        isBoxInView: (Float, Float, Float, Float) -> Boolean,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        var count = 0
        for (n in nodes) {
            if (count >= MAX_OCCLUDERS) break
            if (!n.visible || n.layer != "World" || !n.shadowCaster || n.nodeType == "PointLight") continue
            val gt = getGlobalTransform(n)
            if (n.nodeType == "TileMap") {
                val ts = max(8f, n.tileSize)
                val half = ts * 0.5f
                for (cell in n.tiles) {
                    if (count >= MAX_OCCLUDERS) break
                    val cx = gt.gx + cell.gx * ts + half
                    val cy = gt.gy + cell.gy * ts + half
                    if (!isBoxInView(cx - half, cx + half, cy - half, cy + half)) continue
                    val base = count * 4
                    occluderData[base] = cx
                    occluderData[base + 1] = cy
                    occluderData[base + 2] = half
                    occluderData[base + 3] = half
                    count++
                }
            } else {
                val hw = abs(n.width * gt.gSx) * 0.5f
                val hh = abs(n.height * gt.gSy) * 0.5f
                if (hw < 1f || hh < 1f) continue
                if (!isBoxInView(gt.gx - hw, gt.gx + hw, gt.gy - hh, gt.gy + hh)) continue
                val base = count * 4
                occluderData[base] = gt.gx
                occluderData[base + 1] = gt.gy
                occluderData[base + 2] = hw
                occluderData[base + 3] = hh
                count++
            }
        }
        activeOccluderCount = count
    }

    fun bindSdfUniforms(prog: Int) {
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableSdfShadow"), if (sdfShadowEnabled) 1 else 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uShadowSoftness"), shadowSoftness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uShadowMinLight"), shadowMinLight)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uOccluderCount"), activeOccluderCount)
        if (activeOccluderCount > 0) {
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uOccluders"), activeOccluderCount, occluderData, 0)
        }
    }

    fun bindNormalMaterial(prog: Int, normalTexFile: String, normalStrength: Float, specular: Float, flipX: Boolean) {
        val nTex = getNormalTexture(normalTexFile)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uNormalMap"), 1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uNormalStrength"), normalStrength)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSpecular"), specular)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlipNormalX"), if (flipX) -1f else 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun createFlatNormalTexture(): Int {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 255))
        }
        val id = ZeroShaders.uploadBitmapToGL(bmp)
        bmp.recycle()
        return id
    }
}
