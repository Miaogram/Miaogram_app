/*
 * This is the source code of Telegram for Android v. 2.x
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2015.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Looper;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.android.AndroidUtilities;
import org.telegram.android.LocaleController;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.AnimationCompat.AnimatorListenerAdapterProxy;
import org.telegram.ui.AnimationCompat.AnimatorSetProxy;
import org.telegram.ui.AnimationCompat.ObjectAnimatorProxy;
import org.telegram.ui.AnimationCompat.ViewProxy;
import org.telegram.ui.Cells.PhotoEditToolCell;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.Semaphore;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;
import javax.microedition.khronos.opengles.GL10;

public class PhotoFilterView extends FrameLayout {

    private boolean showOriginal;

    private float previousValue;

    private int selectedTool = -1;
    private int enhanceTool = 0;
    private int exposureTool = 1;
    private int contrastTool = 2;
    private int warmthTool = 3;
    private int saturationTool = 4;
    private int highlightsTool = 5;
    private int shadowsTool = 6;
    private int vignetteTool = 7;
    private int grainTool = 8;
    private int blurTool = -1;
    private int sharpenTool = 9;

    private float highlightsValue = 0; //0 100
    private float contrastValue = 0; //-100 100
    private float shadowsValue = 0; //0 100
    private float exposureValue = 0; //-100 100
    private float enhanceValue = 0; //0 100
    private float saturationValue = 0; //-100 100
    private float warmthValue = 0; //-100 100
    private float vignetteValue = 0; //0 100
    private float grainValue = 0; //0 100
    private float sharpenValue = 0; //0 100

    private ToolsAdapter toolsAdapter;
    private PhotoEditorSeekBar valueSeekBar;
    private FrameLayout toolsView;
    private FrameLayout editView;
    private TextView paramTextView;
    private TextView valueTextView;
    private TextView doneTextView;
    private TextView cancelTextView;
    private TextureView textureView;
    private EGLThread eglThread;
    private RecyclerListView recyclerListView;

    private Bitmap bitmapToEdit;
    private int orientation;

    public class EGLThread extends DispatchQueue {

        private final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        private final int EGL_OPENGL_ES2_BIT = 4;
        private SurfaceTexture surfaceTexture;
        private EGL10 egl10;
        private EGLDisplay eglDisplay;
        private EGLConfig eglConfig;
        private EGLContext eglContext;
        private EGLSurface eglSurface;
        private GL gl;
        private boolean initied;

        private Bitmap currentBitmap;

        private int rgbToHsvShaderProgram;
        private int rgbToHsvPositionHandle;
        private int rgbToHsvInputTexCoordHandle;
        private int rgbToHsvSourceImageHandle;

        private int enhanceShaderProgram;
        private int enhancePositionHandle;
        private int enhanceInputTexCoordHandle;
        private int enhanceSourceImageHandle;
        private int enhanceIntensityHandle;
        private int enhanceInputImageTexture2Handle;

        private int toolsShaderProgram;
        private int positionHandle;
        private int inputTexCoordHandle;
        private int sourceImageHandle;
        private int shadowsHandle;
        private int highlightsHandle;
        private int exposureHandle;
        private int contrastHandle;
        private int saturationHandle;
        private int warmthHandle;
        private int vignetteHandle;
        private int grainHandle;
        private int widthHandle;
        private int heightHandle;

        private int sharpenShaderProgram;
        private int sharpenHandle;
        private int sharpenWidthHandle;
        private int sharpenHeightHandle;
        private int sharpenPositionHandle;
        private int sharpenInputTexCoordHandle;
        private int sharpenSourceImageHandle;

        private int simpleShaderProgram;
        private int simplePositionHandle;
        private int simpleInputTexCoordHandle;
        private int simpleSourceImageHandle;

        private int[] enhanceTextures = new int[2];
        private int[] renderTexture = new int[2];
        private int[] renderFrameBuffer = new int[2];
        private boolean hsvGenerated;
        private int renderBufferWidth;
        private int renderBufferHeight;
        private volatile int surfaceWidth;
        private volatile int surfaceHeight;

        private FloatBuffer vertexBuffer;
        private FloatBuffer textureBuffer;
        private FloatBuffer vertexInvertBuffer;

        private final static int PGPhotoEnhanceHistogramBins = 256;
        private final static int PGPhotoEnhanceSegments = 4;

        private static final String rgbToHsvFragmentShaderCode =
                "precision highp float;" +
                "varying vec2 texCoord;" +
                "uniform sampler2D sourceImage;" +
                "vec3 rgb_to_hsv(vec3 c) {" +
                    "vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);" +
                    "vec4 p = c.g < c.b ? vec4(c.bg, K.wz) : vec4(c.gb, K.xy);" +
                    "vec4 q = c.r < p.x ? vec4(p.xyw, c.r) : vec4(c.r, p.yzx);" +
                    "float d = q.x - min(q.w, q.y);" +
                    "float e = 1.0e-10;" +
                    "return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);" +
                "}" +
                "void main() {" +
                    "vec4 texel = texture2D(sourceImage, texCoord);" +
                    "gl_FragColor = vec4(rgb_to_hsv(texel.rgb), texel.a);" +
                "}";

        private static final String enhanceFragmentShaderCode =
                "precision highp float;" +
                "varying vec2 texCoord;" +
                "uniform sampler2D sourceImage;" +
                "uniform sampler2D inputImageTexture2;" +
                "uniform float intensity;" +
                "float enhance(float value) {" +
                    "const vec2 offset = vec2(0.001953125, 0.03125);" +
                    "value = value + offset.x;" +
                    "vec2 coord = (clamp(texCoord, 0.125, 1.0 - 0.125001) - 0.125) * 4.0;" +
                    "vec2 frac = fract(coord);" +
                    "coord = floor(coord);" +
                    "float p00 = float(coord.y * 4.0 + coord.x) * 0.0625 + offset.y;" +
                    "float p01 = float(coord.y * 4.0 + coord.x + 1.0) * 0.0625 + offset.y;" +
                    "float p10 = float((coord.y + 1.0) * 4.0 + coord.x) * 0.0625 + offset.y;" +
                    "float p11 = float((coord.y + 1.0) * 4.0 + coord.x + 1.0) * 0.0625 + offset.y;" +
                    "vec3 c00 = texture2D(inputImageTexture2, vec2(value, p00)).rgb;" +
                    "vec3 c01 = texture2D(inputImageTexture2, vec2(value, p01)).rgb;" +
                    "vec3 c10 = texture2D(inputImageTexture2, vec2(value, p10)).rgb;" +
                    "vec3 c11 = texture2D(inputImageTexture2, vec2(value, p11)).rgb;" +
                    "float c1 = ((c00.r - c00.g) / (c00.b - c00.g));" +
                    "float c2 = ((c01.r - c01.g) / (c01.b - c01.g));" +
                    "float c3 = ((c10.r - c10.g) / (c10.b - c10.g));" +
                    "float c4 = ((c11.r - c11.g) / (c11.b - c11.g));" +
                    "float c1_2 = mix(c1, c2, frac.x);" +
                    "float c3_4 = mix(c3, c4, frac.x);" +
                    "return mix(c1_2, c3_4, frac.y);" +
                "}" +
                "vec3 hsv_to_rgb(vec3 c) {" +
                    "vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);" +
                    "vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);" +
                    "return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);" +
                "}" +
                "void main() {" +
                    "vec4 texel = texture2D(sourceImage, texCoord);" +
                    "vec4 hsv = texel;" +
                    "hsv.y = min(1.0, hsv.y * 1.2);" +
                    "hsv.z = min(1.0, enhance(hsv.z) * 1.1);" +
                    "gl_FragColor = vec4(hsv_to_rgb(mix(texel.xyz, hsv.xyz, intensity)), texel.w);" +
                "}";

        private static final String simpleVertexShaderCode =
                "attribute vec4 position;" +
                "attribute vec2 inputTexCoord;" +
                "varying vec2 texCoord;" +
                "void main() {" +
                    "gl_Position = position;" +
                    "texCoord = inputTexCoord;" +
                "}";

        private static final String simpleFragmentShaderCode =
                "varying highp vec2 texCoord;" +
                "uniform sampler2D sourceImage;" +
                "void main() {" +
                    "gl_FragColor = texture2D(sourceImage, texCoord);" +
                "}";

        private static final String sharpenVertexShaderCode =
                "attribute vec4 position;" +
                "attribute vec2 inputTexCoord;" +
                "varying vec2 texCoord;" +

                "uniform highp float inputWidth;" +
                "uniform highp float inputHeight;" +
                "varying vec2 leftTexCoord;" +
                "varying vec2 rightTexCoord;" +
                "varying vec2 topTexCoord;" +
                "varying vec2 bottomTexCoord;" +

                "void main() {" +
                    "gl_Position = position;" +
                    "texCoord = inputTexCoord;" +
                    "highp vec2 widthStep = vec2(1.0 / inputWidth, 0.0);" +
                    "highp vec2 heightStep = vec2(0.0, 1.0 / inputHeight);" +
                    "leftTexCoord = inputTexCoord - widthStep;" +
                    "rightTexCoord = inputTexCoord + widthStep;" +
                    "topTexCoord = inputTexCoord + heightStep;" +
                    "bottomTexCoord = inputTexCoord - heightStep;" +
                "}";

        private static final String sharpenFragmentShaderCode =
                "precision highp float;" +
                "varying vec2 texCoord;" +
                "varying vec2 leftTexCoord;" +
                "varying vec2 rightTexCoord;" +
                "varying vec2 topTexCoord;" +
                "varying vec2 bottomTexCoord;" +
                "uniform sampler2D sourceImage;" +
                "uniform float sharpen;" +

                "void main() {" +
                    "vec4 result = texture2D(sourceImage, texCoord);" +

                    "vec3 leftTextureColor = texture2D(sourceImage, leftTexCoord).rgb;" +
                    "vec3 rightTextureColor = texture2D(sourceImage, rightTexCoord).rgb;" +
                    "vec3 topTextureColor = texture2D(sourceImage, topTexCoord).rgb;" +
                    "vec3 bottomTextureColor = texture2D(sourceImage, bottomTexCoord).rgb;" +
                    "result.rgb = result.rgb * (1.0 + 4.0 * sharpen) - (leftTextureColor + rightTextureColor + topTextureColor + bottomTextureColor) * sharpen;" +

                    "gl_FragColor = result;" +
                "}";

        private static final String toolsFragmentShaderCode =
                "precision highp float;" +
                "varying vec2 texCoord;" +
                "uniform float inputWidth;" +
                "uniform float inputHeight;" +
                "uniform sampler2D sourceImage;" +
                "uniform float shadows;" +
                "uniform float width;" +
                "uniform float height;" +
                "const vec3 hsLuminanceWeighting = vec3(0.3, 0.3, 0.3);" +
                "uniform float highlights;" +
                "uniform float exposure;" +
                "uniform float contrast;" +
                "const vec3 satLuminanceWeighting = vec3(0.2126, 0.7152, 0.0722);" +
                "uniform float saturation;" +
                "uniform float warmth;" +
                "uniform float grain;" +
                "const float permTexUnit = 1.0 / 256.0;" +
                "const float permTexUnitHalf = 0.5 / 256.0;" +
                "const float grainsize = 2.3;" +
                "uniform float vignette;" +
                "float getLuma(vec3 rgbP) { " +
                    "return (0.299 * rgbP.r) + (0.587 * rgbP.g) + (0.114 * rgbP.b); " +
                "}" +
                "vec3 rgbToYuv(vec3 inP) {" +
                    "vec3 outP;" +
                    "outP.r = getLuma(inP);" +
                    "outP.g = (1.0 / 1.772) * (inP.b - outP.r);" +
                    "outP.b = (1.0 / 1.402) * (inP.r - outP.r);" +
                    "return outP; " +
                "}" +
                "vec3 yuvToRgb(vec3 inP) {" +
                    "return vec3(1.402 * inP.b + inP.r, (inP.r - (0.299 * 1.402 / 0.587) * inP.b - (0.114 * 1.772 / 0.587) * inP.g), 1.772 * inP.g + inP.r);" +
                "}" +
                "float easeInOutSigmoid(float value, float strength) {" +
                    "float t = 1.0 / (1.0 - strength);" +
                    "if (value > 0.5) {" +
                        "return 1.0 - pow(2.0 - 2.0 * value, t) * 0.5;" +
                    "} else {" +
                        "return pow(2.0 * value, t) * 0.5; " +
                    "}" +
                "}" +
                "vec4 rnm(in vec2 tc) {" +
                    "float noise = sin(dot(tc,vec2(12.9898,78.233))) * 43758.5453;" +
                    "float noiseR = fract(noise)*2.0-1.0;" +
                    "float noiseG = fract(noise*1.2154)*2.0-1.0;" +
                    "float noiseB = fract(noise*1.3453)*2.0-1.0;" +
                    "float noiseA = fract(noise*1.3647)*2.0-1.0;" +
                    "return vec4(noiseR,noiseG,noiseB,noiseA);" +
                "}" +
                "float fade(in float t) {" +
                    "return t*t*t*(t*(t*6.0-15.0)+10.0);" +
                "}" +
                "float pnoise3D(in vec3 p) {" +
                    "vec3 pi = permTexUnit*floor(p)+permTexUnitHalf;" +
                    "vec3 pf = fract(p);" +
                    "float perm00 = rnm(pi.xy).a;" +
                    "vec3 grad000 = rnm(vec2(perm00, pi.z)).rgb * 4.0 - 1.0;" +
                    "float n000 = dot(grad000, pf);" +
                    "vec3 grad001 = rnm(vec2(perm00, pi.z + permTexUnit)).rgb * 4.0 - 1.0;" +
                    "float n001 = dot(grad001, pf - vec3(0.0, 0.0, 1.0));" +
                    "float perm01 = rnm(pi.xy + vec2(0.0, permTexUnit)).a;" +
                    "vec3 grad010 = rnm(vec2(perm01, pi.z)).rgb * 4.0 - 1.0;" +
                    "float n010 = dot(grad010, pf - vec3(0.0, 1.0, 0.0));" +
                    "vec3 grad011 = rnm(vec2(perm01, pi.z + permTexUnit)).rgb * 4.0 - 1.0;" +
                    "float n011 = dot(grad011, pf - vec3(0.0, 1.0, 1.0));" +
                    "float perm10 = rnm(pi.xy + vec2(permTexUnit, 0.0)).a;" +
                    "vec3 grad100 = rnm(vec2(perm10, pi.z)).rgb * 4.0 - 1.0;" +
                    "float n100 = dot(grad100, pf - vec3(1.0, 0.0, 0.0));" +
                    "vec3 grad101 = rnm(vec2(perm10, pi.z + permTexUnit)).rgb * 4.0 - 1.0;" +
                    "float n101 = dot(grad101, pf - vec3(1.0, 0.0, 1.0));" +
                    "float perm11 = rnm(pi.xy + vec2(permTexUnit, permTexUnit)).a;" +
                    "vec3 grad110 = rnm(vec2(perm11, pi.z)).rgb * 4.0 - 1.0;" +
                    "float n110 = dot(grad110, pf - vec3(1.0, 1.0, 0.0));" +
                    "vec3 grad111 = rnm(vec2(perm11, pi.z + permTexUnit)).rgb * 4.0 - 1.0;" +
                    "float n111 = dot(grad111, pf - vec3(1.0, 1.0, 1.0));" +
                    "vec4 n_x = mix(vec4(n000, n001, n010, n011), vec4(n100, n101, n110, n111), fade(pf.x));" +
                    "vec2 n_xy = mix(n_x.xy, n_x.zw, fade(pf.y));" +
                    "float n_xyz = mix(n_xy.x, n_xy.y, fade(pf.z));" +
                    "return n_xyz;" +
                "}" +
                "vec2 coordRot(in vec2 tc, in float angle) {" +
                    "float rotX = ((tc.x * 2.0 - 1.0) * cos(angle)) - ((tc.y * 2.0 - 1.0) * sin(angle));" +
                    "float rotY = ((tc.y * 2.0 - 1.0) * cos(angle)) + ((tc.x * 2.0 - 1.0) * sin(angle));" +
                    "return vec2(rotX * 0.5 + 0.5, rotY * 0.5 + 0.5);" +
                "}" +
                "void main() {" +
                    "vec4 result = texture2D(sourceImage, texCoord);" +
                    "const float toolEpsilon = 0.005;" +

                    "float hsLuminance = dot(result.rgb, hsLuminanceWeighting);" +
                    "float shadow = clamp((pow(hsLuminance, 1.0 / (shadows + 1.0)) + (-0.76) * pow(hsLuminance, 2.0 / (shadows + 1.0))) - hsLuminance, 0.0, 1.0);" +
                    "float highlight = clamp((1.0 - (pow(1.0 - hsLuminance, 1.0 / (2.0 - highlights)) + (-0.8) * pow(1.0 - hsLuminance, 2.0 / (2.0 - highlights)))) - hsLuminance, -1.0, 0.0);" +
                    "vec3 shresult = (hsLuminance + shadow + highlight) * (result.rgb / hsLuminance);" +
                    "result = vec4(shresult.rgb, result.a);" +

                    "if (abs(exposure) > toolEpsilon) {" +
                        "float mag = exposure * 1.045;" +
                        "float exppower = 1.0 + abs(mag);" +
                        "if (mag < 0.0) {" +
                            "exppower = 1.0 / exppower;" +
                        "}" +
                        "result.r = 1.0 - pow((1.0 - result.r), exppower);" +
                        "result.g = 1.0 - pow((1.0 - result.g), exppower);" +
                        "result.b = 1.0 - pow((1.0 - result.b), exppower);" +
                    "}" +
                    "result = vec4(((result.rgb - vec3(0.5)) * contrast + vec3(0.5)), result.a);" +
                    "float satLuminance = dot(result.rgb, satLuminanceWeighting);" +
                    "vec3 greyScaleColor = vec3(satLuminance);" +
                    "result = vec4(mix(greyScaleColor, result.rgb, saturation), result.a);" +
                    "if (abs(warmth) > toolEpsilon) {" +
                        "vec3 yuvVec; if (warmth > 0.0 ) {" +
                            "yuvVec = vec3(0.1765, -0.1255, 0.0902);" +
                        "} else {" +
                            "yuvVec = -vec3(0.0588, 0.1569, -0.1255);" +
                        "}" +
                        "vec3 yuvColor = rgbToYuv(result.rgb);" +
                        "float luma = yuvColor.r;" +
                        "float curveScale = sin(luma * 3.14159);" +
                        "yuvColor += 0.375 * warmth * curveScale * yuvVec;" +
                        "result.rgb = yuvToRgb(yuvColor);" +
                    "}" +
                    "if (abs(grain) > toolEpsilon) {" +
                        "vec3 rotOffset = vec3(1.425, 3.892, 5.835);" +
                        "vec2 rotCoordsR = coordRot(texCoord, rotOffset.x);" +
                        "vec3 noise = vec3(pnoise3D(vec3(rotCoordsR * vec2(width / grainsize, height / grainsize),0.0)));" +
                        "vec3 lumcoeff = vec3(0.299,0.587,0.114);" +
                        "float luminance = dot(result.rgb, lumcoeff);" +
                        "float lum = smoothstep(0.2, 0.0, luminance);" +
                        "lum += luminance;" +
                        "noise = mix(noise,vec3(0.0),pow(lum,4.0));" +
                        "result.rgb = result.rgb + noise * grain;" +
                    "}" +
                    "if (abs(vignette) > toolEpsilon) {" +
                        "const float midpoint = 0.7;" +
                        "const float fuzziness = 0.62;" +
                        "float radDist = length(texCoord - 0.5) / sqrt(0.5);" +
                        "float mag = easeInOutSigmoid(radDist * midpoint, fuzziness) * vignette * 0.645;" +
                        "result.rgb = mix(pow(result.rgb, vec3(1.0 / (1.0 - mag))), vec3(0.0), mag * mag);" +
                    "}" +

                    "gl_FragColor = result;" +
                "}";

        public EGLThread(SurfaceTexture surface, Bitmap bitmap) {
            super("EGLThread");
            surfaceTexture = surface;
            currentBitmap = bitmap;
        }

        private int loadShader(int type, String shaderCode) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, shaderCode);
            GLES20.glCompileShader(shader);
            int[] compileStatus = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
            if (compileStatus[0] == 0) {
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
            return shader;
        }

        private boolean initGL() {
            egl10 = (EGL10) EGLContext.getEGL();

            eglDisplay = egl10.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
                FileLog.e("tmessages", "eglGetDisplay failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                finish();
                return false;
            }

            int[] version = new int[2];
            if (!egl10.eglInitialize(eglDisplay, version)) {
                FileLog.e("tmessages", "eglInitialize failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                finish();
                return false;
            }

            int[] configsCount = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            int[] configSpec = new int[] {
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 0,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_NONE
            };
            if (!egl10.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount)) {
                FileLog.e("tmessages", "eglChooseConfig failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                finish();
                return false;
            } else if (configsCount[0] > 0) {
                eglConfig = configs[0];
            } else {
                FileLog.e("tmessages", "eglConfig not initialized");
                finish();
                return false;
            }

            int[] attrib_list = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
            eglContext = egl10.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrib_list);
            if (eglContext == null) {
                FileLog.e("tmessages", "eglCreateContext failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                finish();
                return false;
            }

            if (surfaceTexture instanceof SurfaceTexture) {
                eglSurface = egl10.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
            } else {
                finish();
                return false;
            }

            if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
                FileLog.e("tmessages", "createWindowSurface failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                finish();
                return false;
            }
            if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                FileLog.e("tmessages", "eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                finish();
                return false;
            }
            gl = eglContext.getGL();


            float squareCoordinates[] = {
                    -1.0f, 1.0f,
                    1.0f, 1.0f,
                    -1.0f, -1.0f,
                    1.0f, -1.0f};

            ByteBuffer bb = ByteBuffer.allocateDirect(squareCoordinates.length * 4);
            bb.order(ByteOrder.nativeOrder());
            vertexBuffer = bb.asFloatBuffer();
            vertexBuffer.put(squareCoordinates);
            vertexBuffer.position(0);

            float squareCoordinates2[] = {
                    -1.0f, -1.0f,
                    1.0f, -1.0f,
                    -1.0f, 1.0f,
                    1.0f, 1.0f};

            bb = ByteBuffer.allocateDirect(squareCoordinates2.length * 4);
            bb.order(ByteOrder.nativeOrder());
            vertexInvertBuffer = bb.asFloatBuffer();
            vertexInvertBuffer.put(squareCoordinates2);
            vertexInvertBuffer.position(0);

            float textureCoordinates[] = {
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    0.0f, 1.0f,
                    1.0f, 1.0f,
            };

            bb = ByteBuffer.allocateDirect(textureCoordinates.length * 4);
            bb.order(ByteOrder.nativeOrder());
            textureBuffer = bb.asFloatBuffer();
            textureBuffer.put(textureCoordinates);
            textureBuffer.position(0);

            GLES20.glGenTextures(2, enhanceTextures, 0);

            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, simpleVertexShaderCode);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, toolsFragmentShaderCode);

            if (vertexShader != 0 && fragmentShader != 0) {
                toolsShaderProgram = GLES20.glCreateProgram();
                GLES20.glAttachShader(toolsShaderProgram, vertexShader);
                GLES20.glAttachShader(toolsShaderProgram, fragmentShader);
                GLES20.glBindAttribLocation(toolsShaderProgram, 0, "position");
                GLES20.glBindAttribLocation(toolsShaderProgram, 1, "inputTexCoord");

                GLES20.glLinkProgram(toolsShaderProgram);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(toolsShaderProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] == 0) {
                    /*String infoLog = GLES20.glGetProgramInfoLog(toolsShaderProgram);
                    FileLog.e("tmessages", "link error = " + infoLog);*/
                    GLES20.glDeleteProgram(toolsShaderProgram);
                    toolsShaderProgram = 0;
                } else {
                    positionHandle = GLES20.glGetAttribLocation(toolsShaderProgram, "position");
                    inputTexCoordHandle = GLES20.glGetAttribLocation(toolsShaderProgram, "inputTexCoord");
                    sourceImageHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "sourceImage");
                    shadowsHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "shadows");
                    highlightsHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "highlights");
                    exposureHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "exposure");
                    contrastHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "contrast");
                    saturationHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "saturation");
                    warmthHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "warmth");
                    vignetteHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "vignette");
                    grainHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "grain");
                    widthHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "width");
                    heightHandle = GLES20.glGetUniformLocation(toolsShaderProgram, "height");
                }
            } else {
                finish();
                return false;
            }

            vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, sharpenVertexShaderCode);
            fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, sharpenFragmentShaderCode);

            if (vertexShader != 0 && fragmentShader != 0) {
                sharpenShaderProgram = GLES20.glCreateProgram();
                GLES20.glAttachShader(sharpenShaderProgram, vertexShader);
                GLES20.glAttachShader(sharpenShaderProgram, fragmentShader);
                GLES20.glBindAttribLocation(sharpenShaderProgram, 0, "position");
                GLES20.glBindAttribLocation(sharpenShaderProgram, 1, "inputTexCoord");

                GLES20.glLinkProgram(sharpenShaderProgram);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(sharpenShaderProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] == 0) {
                    GLES20.glDeleteProgram(sharpenShaderProgram);
                    sharpenShaderProgram = 0;
                } else {
                    sharpenPositionHandle = GLES20.glGetAttribLocation(sharpenShaderProgram, "position");
                    sharpenInputTexCoordHandle = GLES20.glGetAttribLocation(sharpenShaderProgram, "inputTexCoord");
                    sharpenSourceImageHandle = GLES20.glGetUniformLocation(sharpenShaderProgram, "sourceImage");
                    sharpenWidthHandle = GLES20.glGetUniformLocation(sharpenShaderProgram, "inputWidth");
                    sharpenHeightHandle = GLES20.glGetUniformLocation(sharpenShaderProgram, "inputHeight");
                    sharpenHandle = GLES20.glGetUniformLocation(sharpenShaderProgram, "sharpen");
                }
            } else {
                finish();
                return false;
            }

            vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, simpleVertexShaderCode);
            fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, rgbToHsvFragmentShaderCode);
            if (vertexShader != 0 && fragmentShader != 0) {
                rgbToHsvShaderProgram = GLES20.glCreateProgram();
                GLES20.glAttachShader(rgbToHsvShaderProgram, vertexShader);
                GLES20.glAttachShader(rgbToHsvShaderProgram, fragmentShader);
                GLES20.glBindAttribLocation(rgbToHsvShaderProgram, 0, "position");
                GLES20.glBindAttribLocation(rgbToHsvShaderProgram, 1, "inputTexCoord");

                GLES20.glLinkProgram(rgbToHsvShaderProgram);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(rgbToHsvShaderProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] == 0) {
                    GLES20.glDeleteProgram(rgbToHsvShaderProgram);
                    rgbToHsvShaderProgram = 0;
                } else {
                    rgbToHsvPositionHandle = GLES20.glGetAttribLocation(rgbToHsvShaderProgram, "position");
                    rgbToHsvInputTexCoordHandle = GLES20.glGetAttribLocation(rgbToHsvShaderProgram, "inputTexCoord");
                    rgbToHsvSourceImageHandle = GLES20.glGetUniformLocation(rgbToHsvShaderProgram, "sourceImage");
                }
            } else {
                finish();
                return false;
            }

            vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, simpleVertexShaderCode);
            fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, enhanceFragmentShaderCode);
            if (vertexShader != 0 && fragmentShader != 0) {
                enhanceShaderProgram = GLES20.glCreateProgram();
                GLES20.glAttachShader(enhanceShaderProgram, vertexShader);
                GLES20.glAttachShader(enhanceShaderProgram, fragmentShader);
                GLES20.glBindAttribLocation(enhanceShaderProgram, 0, "position");
                GLES20.glBindAttribLocation(enhanceShaderProgram, 1, "inputTexCoord");

                GLES20.glLinkProgram(enhanceShaderProgram);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(enhanceShaderProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] == 0) {
                    GLES20.glDeleteProgram(enhanceShaderProgram);
                    enhanceShaderProgram = 0;
                } else {
                    enhancePositionHandle = GLES20.glGetAttribLocation(enhanceShaderProgram, "position");
                    enhanceInputTexCoordHandle = GLES20.glGetAttribLocation(enhanceShaderProgram, "inputTexCoord");
                    enhanceSourceImageHandle = GLES20.glGetUniformLocation(enhanceShaderProgram, "sourceImage");
                    enhanceIntensityHandle = GLES20.glGetUniformLocation(enhanceShaderProgram, "intensity");
                    enhanceInputImageTexture2Handle = GLES20.glGetUniformLocation(enhanceShaderProgram, "inputImageTexture2");
                }
            } else {
                finish();
                return false;
            }

            vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, simpleVertexShaderCode);
            fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, simpleFragmentShaderCode);
            if (vertexShader != 0 && fragmentShader != 0) {
                simpleShaderProgram = GLES20.glCreateProgram();
                GLES20.glAttachShader(simpleShaderProgram, vertexShader);
                GLES20.glAttachShader(simpleShaderProgram, fragmentShader);
                GLES20.glBindAttribLocation(simpleShaderProgram, 0, "position");
                GLES20.glBindAttribLocation(simpleShaderProgram, 1, "inputTexCoord");

                GLES20.glLinkProgram(simpleShaderProgram);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(simpleShaderProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] == 0) {
                    GLES20.glDeleteProgram(simpleShaderProgram);
                    simpleShaderProgram = 0;
                } else {
                    simplePositionHandle = GLES20.glGetAttribLocation(simpleShaderProgram, "position");
                    simpleInputTexCoordHandle = GLES20.glGetAttribLocation(simpleShaderProgram, "inputTexCoord");
                    simpleSourceImageHandle = GLES20.glGetUniformLocation(simpleShaderProgram, "sourceImage");
                }
            } else {
                finish();
                return false;
            }

            if (currentBitmap != null) {
                loadTexture(currentBitmap);
            }

            return true;
        }

        public void finish() {
            if (eglSurface != null) {
                egl10.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                egl10.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = null;
            }
            if (eglContext != null) {
                egl10.eglDestroyContext(eglDisplay, eglContext);
                eglContext = null;
            }
            if (eglDisplay != null) {
                egl10.eglTerminate(eglDisplay);
                eglDisplay = null;
            }
        }

        private Runnable drawRunnable = new Runnable() {
            @Override
            public void run() {
                if (!initied) {
                    return;
                }

                if (!eglContext.equals(egl10.eglGetCurrentContext()) || !eglSurface.equals(egl10.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
                    if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        FileLog.e("tmessages", "eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                        return;
                    }
                }

                GLES20.glViewport(0, 0, renderBufferWidth, renderBufferHeight);
                //enhance draw
                if (!hsvGenerated) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderFrameBuffer[0]);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, renderTexture[0], 0);
                    GLES20.glClear(0);

                    GLES20.glUseProgram(rgbToHsvShaderProgram);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderTexture[1]);
                    GLES20.glUniform1i(rgbToHsvSourceImageHandle, 0);
                    GLES20.glEnableVertexAttribArray(rgbToHsvInputTexCoordHandle);
                    GLES20.glVertexAttribPointer(rgbToHsvInputTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
                    GLES20.glEnableVertexAttribArray(rgbToHsvPositionHandle);
                    GLES20.glVertexAttribPointer(rgbToHsvPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                    ByteBuffer hsvBuffer = ByteBuffer.allocateDirect(renderBufferWidth * renderBufferHeight * 4);
                    GLES20.glReadPixels(0, 0, renderBufferWidth, renderBufferHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, hsvBuffer);

                    GLES20.glBindTexture(GL10.GL_TEXTURE_2D, enhanceTextures[0]);
                    GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
                    GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
                    GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, renderBufferWidth, renderBufferHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, hsvBuffer);

                    ByteBuffer buffer = null;
                    try {
                        buffer = ByteBuffer.allocateDirect(PGPhotoEnhanceSegments * PGPhotoEnhanceSegments * PGPhotoEnhanceHistogramBins * 4);
                        Utilities.calcCDT(hsvBuffer, renderBufferWidth, renderBufferHeight, buffer);
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }

                    GLES20.glBindTexture(GL10.GL_TEXTURE_2D, enhanceTextures[1]);
                    GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
                    GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
                    GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 16, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);

                    hsvGenerated = true;
                }

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderFrameBuffer[1]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, renderTexture[1], 0);
                GLES20.glClear(0);

                GLES20.glUseProgram(enhanceShaderProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, enhanceTextures[0]);
                GLES20.glUniform1i(enhanceSourceImageHandle, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, enhanceTextures[1]);
                GLES20.glUniform1i(enhanceInputImageTexture2Handle, 1);
                if (showOriginal) {
                    GLES20.glUniform1f(enhanceIntensityHandle, 0);
                } else {
                    GLES20.glUniform1f(enhanceIntensityHandle, getEnhanceValue());
                }

                GLES20.glEnableVertexAttribArray(enhanceInputTexCoordHandle);
                GLES20.glVertexAttribPointer(enhanceInputTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
                GLES20.glEnableVertexAttribArray(enhancePositionHandle);
                GLES20.glVertexAttribPointer(enhancePositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                //sharpen draw
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderFrameBuffer[0]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, renderTexture[0], 0);
                GLES20.glClear(0);

                GLES20.glUseProgram(sharpenShaderProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderTexture[1]);
                GLES20.glUniform1i(sharpenSourceImageHandle, 0);
                if (showOriginal) {
                    GLES20.glUniform1f(sharpenHandle, 0);
                } else {
                    GLES20.glUniform1f(sharpenHandle, getSharpenValue());
                }
                GLES20.glUniform1f(sharpenWidthHandle, renderBufferWidth);
                GLES20.glUniform1f(sharpenHeightHandle, renderBufferHeight);
                GLES20.glEnableVertexAttribArray(sharpenInputTexCoordHandle);
                GLES20.glVertexAttribPointer(sharpenInputTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
                GLES20.glEnableVertexAttribArray(sharpenPositionHandle);
                GLES20.glVertexAttribPointer(sharpenPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexInvertBuffer);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                //custom params draw
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderFrameBuffer[1]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, renderTexture[1], 0);
                GLES20.glClear(0);

                GLES20.glUseProgram(toolsShaderProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderTexture[0]);
                GLES20.glUniform1i(sourceImageHandle, 0);
                if (showOriginal) {
                    GLES20.glUniform1f(shadowsHandle, 0);
                    GLES20.glUniform1f(highlightsHandle, 1);
                    GLES20.glUniform1f(exposureHandle, 0);
                    GLES20.glUniform1f(contrastHandle, 1);
                    GLES20.glUniform1f(saturationHandle, 1);
                    GLES20.glUniform1f(warmthHandle, 0);
                    GLES20.glUniform1f(vignetteHandle, 0);
                    GLES20.glUniform1f(grainHandle, 0);
                } else {
                    GLES20.glUniform1f(shadowsHandle, getShadowsValue());
                    GLES20.glUniform1f(highlightsHandle, getHighlightsValue());
                    GLES20.glUniform1f(exposureHandle, getExposureValue());
                    GLES20.glUniform1f(contrastHandle, getContrastValue());
                    GLES20.glUniform1f(saturationHandle, getSaturationValue());
                    GLES20.glUniform1f(warmthHandle, getWarmthValue());
                    GLES20.glUniform1f(vignetteHandle, getVignetteValue());
                    GLES20.glUniform1f(grainHandle, getGrainValue());
                }
                GLES20.glUniform1f(widthHandle, renderBufferWidth);
                GLES20.glUniform1f(heightHandle, renderBufferHeight);
                GLES20.glEnableVertexAttribArray(inputTexCoordHandle);
                GLES20.glVertexAttribPointer(inputTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
                GLES20.glEnableVertexAttribArray(positionHandle);
                GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexInvertBuffer);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                //onscreen draw
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glClear(0);

                GLES20.glUseProgram(simpleShaderProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderTexture[1]);
                GLES20.glUniform1i(simpleSourceImageHandle, 0);
                GLES20.glEnableVertexAttribArray(simpleInputTexCoordHandle);
                GLES20.glVertexAttribPointer(simpleInputTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
                GLES20.glEnableVertexAttribArray(simplePositionHandle);
                GLES20.glVertexAttribPointer(simplePositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                egl10.eglSwapBuffers(eglDisplay, eglSurface);
            }
        };

        private Bitmap getRenderBufferBitmap() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(renderBufferWidth * renderBufferHeight * 4);
            GLES20.glReadPixels(0, 0, renderBufferWidth, renderBufferHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
            Bitmap bitmap = Bitmap.createBitmap(renderBufferWidth, renderBufferHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        }

        public Bitmap getTexture() {
            if (!initied) {
                return null;
            }
            final Semaphore semaphore = new Semaphore(0);
            final Bitmap object[] = new Bitmap[1];
            try {
                postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderFrameBuffer[1]);
                        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, renderTexture[1], 0);
                        GLES20.glClear(0);
                        object[0] = getRenderBufferBitmap();
                        semaphore.release();
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                        GLES20.glClear(0);
                    }
                });
                semaphore.acquire();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
            return object[0];
        }

        private Bitmap createBitmap(Bitmap bitmap, int w, int h, float scale) {
            Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint();
            paint.setFilterBitmap(true);

            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(-bitmap.getWidth() / 2, -bitmap.getHeight() / 2);
            matrix.postRotate(orientation);
            if (orientation == 90 || orientation == 270) {
                matrix.postTranslate(bitmap.getHeight() / 2, bitmap.getWidth() / 2);
            } else {
                matrix.postTranslate(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
            }
            canvas.drawBitmap(bitmap, matrix, paint);
            try {
                canvas.setBitmap(null);
            } catch (Exception e) {
                //don't promt, this will crash on 2.x
            }

            return result;
        }

        private void loadTexture(Bitmap bitmap) {
            renderBufferWidth = bitmap.getWidth();
            renderBufferHeight = bitmap.getHeight();
            float maxSize = AndroidUtilities.getPhotoSize();
            if (renderBufferWidth > maxSize || renderBufferHeight > maxSize || orientation != 0) {
                float scale = 1;
                if (renderBufferWidth > maxSize || renderBufferHeight > maxSize) {
                    float scaleX = maxSize / bitmap.getWidth();
                    float scaleY = maxSize / bitmap.getHeight();
                    if (scaleX < scaleY) {
                        renderBufferWidth = (int) maxSize;
                        renderBufferHeight = (int) (bitmap.getHeight() * scaleX);
                        scale = scaleX;
                    } else {
                        renderBufferHeight = (int) maxSize;
                        renderBufferWidth = (int) (bitmap.getWidth() * scaleY);
                        scale = scaleY;
                    }
                }

                if (orientation == 90 || orientation == 270) {
                    int temp = renderBufferWidth;
                    renderBufferWidth = renderBufferHeight;
                    renderBufferHeight = temp;
                }

                currentBitmap = createBitmap(bitmap, renderBufferWidth, renderBufferHeight, scale);
            }
            GLES20.glGenFramebuffers(2, renderFrameBuffer, 0);
            GLES20.glGenTextures(2, renderTexture, 0);

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderTexture[0]);
            GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
            GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
            GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, renderBufferWidth, renderBufferHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            GLES20.glBindTexture(GL10.GL_TEXTURE_2D, renderTexture[1]);
            GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
            GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
            GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, currentBitmap, 0);
        }

        public void shutdown() {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    finish();
                    currentBitmap = null;
                    Looper looper = Looper.myLooper();
                    if (looper != null) {
                        looper.quit();
                    }
                }
            });
        }

        public void setSurfaceTextureSize(int width, int height) {
            surfaceWidth = width;
            surfaceHeight = height;
        }

        @Override
        public void run() {
            initied = initGL();
            super.run();
        }

        public void requestRender() {
            cancelRunnable(drawRunnable);
            postRunnable(drawRunnable);
        }
    }

    public PhotoFilterView(Context context, Bitmap bitmap, int rotation) {
        super(context);

        bitmapToEdit = bitmap;
        orientation = rotation;

        textureView = new TextureView(context);
        if (Build.VERSION.SDK_INT == 14 || Build.VERSION.SDK_INT == 15) {
            //setLayerType(LAYER_TYPE_HARDWARE, null);
            //textureView.setLayerType(LAYER_TYPE_HARDWARE, null);
        }
        addView(textureView);
        textureView.setVisibility(INVISIBLE);
        LayoutParams layoutParams = (LayoutParams) textureView.getLayoutParams();
        layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        textureView.setLayoutParams(layoutParams);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (eglThread == null && surface != null) {
                    eglThread = new EGLThread(surface, bitmapToEdit);
                    eglThread.setSurfaceTextureSize(width, height);
                    eglThread.requestRender();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, final int width, final int height) {
                if (eglThread != null) {
                    eglThread.setSurfaceTextureSize(width, height);
                    eglThread.requestRender();
                    eglThread.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            eglThread.requestRender();
                        }
                    });
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (eglThread != null) {
                    eglThread.shutdown();
                    eglThread = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });

        toolsView = new FrameLayout(context);
        addView(toolsView);
        layoutParams = (LayoutParams) toolsView.getLayoutParams();
        layoutParams.width = LayoutParams.MATCH_PARENT;
        layoutParams.height = AndroidUtilities.dp(126);
        layoutParams.gravity = Gravity.LEFT | Gravity.BOTTOM;
        toolsView.setLayoutParams(layoutParams);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(0xff1a1a1a);
        toolsView.addView(frameLayout);
        layoutParams = (LayoutParams) frameLayout.getLayoutParams();
        layoutParams.width = LayoutParams.MATCH_PARENT;
        layoutParams.height = AndroidUtilities.dp(48);
        layoutParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        frameLayout.setLayoutParams(layoutParams);

        cancelTextView = new TextView(context);
        cancelTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        cancelTextView.setTextColor(0xffffffff);
        cancelTextView.setGravity(Gravity.CENTER);
        cancelTextView.setBackgroundResource(R.drawable.bar_selector_picker);
        cancelTextView.setPadding(AndroidUtilities.dp(29), 0, AndroidUtilities.dp(29), 0);
        cancelTextView.setText(LocaleController.getString("Cancel", R.string.Cancel).toUpperCase());
        cancelTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        frameLayout.addView(cancelTextView);
        layoutParams = (LayoutParams) cancelTextView.getLayoutParams();
        layoutParams.width = LayoutParams.WRAP_CONTENT;
        layoutParams.height = LayoutParams.MATCH_PARENT;
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        cancelTextView.setLayoutParams(layoutParams);

        doneTextView = new TextView(context);
        doneTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        doneTextView.setTextColor(0xff51bdf3);
        doneTextView.setGravity(Gravity.CENTER);
        doneTextView.setBackgroundResource(R.drawable.bar_selector_picker);
        doneTextView.setPadding(AndroidUtilities.dp(29), 0, AndroidUtilities.dp(29), 0);
        doneTextView.setText(LocaleController.getString("Done", R.string.Done).toUpperCase());
        doneTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        frameLayout.addView(doneTextView);
        layoutParams = (LayoutParams) doneTextView.getLayoutParams();
        layoutParams.width = LayoutParams.WRAP_CONTENT;
        layoutParams.height = LayoutParams.MATCH_PARENT;
        layoutParams.gravity = Gravity.TOP | Gravity.RIGHT;
        doneTextView.setLayoutParams(layoutParams);

        recyclerListView = new RecyclerListView(context);
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(LinearLayoutManager.HORIZONTAL);
        recyclerListView.setLayoutManager(layoutManager);
        recyclerListView.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= 9) {
            recyclerListView.setOverScrollMode(RecyclerListView.OVER_SCROLL_NEVER);
        }
        recyclerListView.setAdapter(toolsAdapter = new ToolsAdapter(context));
        toolsView.addView(recyclerListView);
        layoutParams = (FrameLayout.LayoutParams) recyclerListView.getLayoutParams();
        layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        layoutParams.height = AndroidUtilities.dp(60);
        layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        recyclerListView.setLayoutParams(layoutParams);
        recyclerListView.addOnItemTouchListener(new RecyclerListView.RecyclerListViewItemClickListener(context, new RecyclerListView.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int i) {
                selectedTool = i;
                if (i == enhanceTool) {
                    previousValue = enhanceValue;
                    valueSeekBar.setMinMax(0, 100);
                    paramTextView.setText(LocaleController.getString("Enhance", R.string.Enhance));
                } else if (i == highlightsTool) {
                    previousValue = highlightsValue;
                    valueSeekBar.setMinMax(0, 100);
                    paramTextView.setText(LocaleController.getString("Highlights", R.string.Highlights));
                } else if (i == contrastTool) {
                    previousValue = contrastValue;
                    valueSeekBar.setMinMax(-100, 100);
                    paramTextView.setText(LocaleController.getString("Contrast", R.string.Contrast));
                } else if (i == exposureTool) {
                    previousValue = exposureValue;
                    valueSeekBar.setMinMax(-100, 100);
                    paramTextView.setText(LocaleController.getString("Exposure", R.string.Exposure));
                } else if (i == warmthTool) {
                    previousValue = warmthValue;
                    valueSeekBar.setMinMax(-100, 100);
                    paramTextView.setText(LocaleController.getString("Warmth", R.string.Warmth));
                } else if (i == saturationTool) {
                    previousValue = saturationValue;
                    valueSeekBar.setMinMax(-100, 100);
                    paramTextView.setText(LocaleController.getString("Saturation", R.string.Saturation));
                } else if (i == vignetteTool) {
                    previousValue = vignetteValue;
                    valueSeekBar.setMinMax(0, 100);
                    paramTextView.setText(LocaleController.getString("Vignette", R.string.Vignette));
                } else if (i == shadowsTool) {
                    previousValue = shadowsValue;
                    valueSeekBar.setMinMax(0, 100);
                    paramTextView.setText(LocaleController.getString("Shadows", R.string.Shadows));
                } else if (i == grainTool) {
                    previousValue = grainValue;
                    valueSeekBar.setMinMax(0, 100);
                    paramTextView.setText(LocaleController.getString("Grain", R.string.Grain));
                } else if (i == sharpenTool) {
                    previousValue = sharpenValue;
                    valueSeekBar.setMinMax(0, 100);
                    paramTextView.setText(LocaleController.getString("Sharpen", R.string.Sharpen));
                } else if (i == blurTool) {

                }
                valueSeekBar.setProgress((int) previousValue, false);
                updateValueTextView();
                switchToOrFromEditMode();
            }
        }));

        editView = new FrameLayout(context);
        editView.setVisibility(GONE);
        addView(editView);
        layoutParams = (LayoutParams) editView.getLayoutParams();
        layoutParams.width = LayoutParams.MATCH_PARENT;
        layoutParams.height = AndroidUtilities.dp(126);
        layoutParams.gravity = Gravity.LEFT | Gravity.BOTTOM;
        editView.setLayoutParams(layoutParams);

        frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(0xff1a1a1a);
        editView.addView(frameLayout);
        layoutParams = (LayoutParams) frameLayout.getLayoutParams();
        layoutParams.width = LayoutParams.MATCH_PARENT;
        layoutParams.height = AndroidUtilities.dp(48);
        layoutParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        frameLayout.setLayoutParams(layoutParams);

        ImageView imageView = new ImageView(context);
        imageView.setImageResource(R.drawable.edit_cancel);
        imageView.setBackgroundResource(R.drawable.bar_selector_picker);
        imageView.setPadding(AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22), 0);
        frameLayout.addView(imageView);
        layoutParams = (LayoutParams) imageView.getLayoutParams();
        layoutParams.width = LayoutParams.WRAP_CONTENT;
        layoutParams.height = LayoutParams.MATCH_PARENT;
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        imageView.setLayoutParams(layoutParams);
        imageView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedTool == enhanceTool) {
                    enhanceValue = previousValue;
                } else if (selectedTool == highlightsTool) {
                    highlightsValue = previousValue;
                } else if (selectedTool == contrastTool) {
                    contrastValue = previousValue;
                } else if (selectedTool == exposureTool) {
                    exposureValue = previousValue;
                } else if (selectedTool == warmthTool) {
                    warmthValue = previousValue;
                } else if (selectedTool == saturationTool) {
                    saturationValue = previousValue;
                } else if (selectedTool == vignetteTool) {
                    vignetteValue = previousValue;
                } else if (selectedTool == shadowsTool) {
                    shadowsValue = previousValue;
                } else if (selectedTool == grainTool) {
                    grainValue = previousValue;
                } else if (selectedTool == sharpenTool) {
                    sharpenValue = previousValue;
                }
                if (eglThread != null) {
                    eglThread.requestRender();
                }
                switchToOrFromEditMode();
            }
        });

        imageView = new ImageView(context);
        imageView.setImageResource(R.drawable.edit_doneblue);
        imageView.setBackgroundResource(R.drawable.bar_selector_picker);
        imageView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(1), AndroidUtilities.dp(22), 0);
        frameLayout.addView(imageView);
        layoutParams = (LayoutParams) imageView.getLayoutParams();
        layoutParams.width = LayoutParams.WRAP_CONTENT;
        layoutParams.height = LayoutParams.MATCH_PARENT;
        layoutParams.gravity = Gravity.TOP | Gravity.RIGHT;
        imageView.setLayoutParams(layoutParams);
        imageView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toolsAdapter.notifyDataSetChanged();
                switchToOrFromEditMode();
            }
        });

        paramTextView = new TextView(context);
        paramTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        paramTextView.setTextColor(0xff808080);
        frameLayout.addView(paramTextView);
        layoutParams = (LayoutParams) paramTextView.getLayoutParams();
        layoutParams.width = LayoutParams.WRAP_CONTENT;
        layoutParams.height = LayoutParams.WRAP_CONTENT;
        layoutParams.gravity = Gravity.CENTER_HORIZONTAL;
        layoutParams.topMargin = AndroidUtilities.dp(26);
        paramTextView.setLayoutParams(layoutParams);

        valueTextView = new TextView(context);
        valueTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        valueTextView.setTextColor(0xffffffff);
        frameLayout.addView(valueTextView);
        layoutParams = (LayoutParams) valueTextView.getLayoutParams();
        layoutParams.width = LayoutParams.WRAP_CONTENT;
        layoutParams.height = LayoutParams.WRAP_CONTENT;
        layoutParams.gravity = Gravity.CENTER_HORIZONTAL;
        layoutParams.topMargin = AndroidUtilities.dp(3);
        valueTextView.setLayoutParams(layoutParams);

        valueSeekBar = new PhotoEditorSeekBar(context);
        valueSeekBar.setDelegate(new PhotoEditorSeekBar.PhotoEditorSeekBarDelegate() {
            @Override
            public void onProgressChanged() {
                int progress = valueSeekBar.getProgress();
                if (selectedTool == enhanceTool) {
                    enhanceValue = progress;
                } else if (selectedTool == highlightsTool) {
                    highlightsValue = progress;
                } else if (selectedTool == contrastTool) {
                    contrastValue = progress;
                } else if (selectedTool == exposureTool) {
                    exposureValue = progress;
                } else if (selectedTool == warmthTool) {
                    warmthValue = progress;
                } else if (selectedTool == saturationTool) {
                    saturationValue = progress;
                } else if (selectedTool == vignetteTool) {
                    vignetteValue = progress;
                } else if (selectedTool == shadowsTool) {
                    shadowsValue = progress;
                } else if (selectedTool == grainTool) {
                    grainValue = progress;
                } else if (selectedTool == sharpenTool) {
                    sharpenValue = progress;
                }
                updateValueTextView();
                if (eglThread != null) {
                    eglThread.requestRender();
                }
            }
        });
        editView.addView(valueSeekBar);
        layoutParams = (FrameLayout.LayoutParams) valueSeekBar.getLayoutParams();
        layoutParams.height = AndroidUtilities.dp(60);
        layoutParams.leftMargin = AndroidUtilities.dp(14);
        layoutParams.rightMargin = AndroidUtilities.dp(14);
        layoutParams.topMargin = AndroidUtilities.dp(10);
        if (AndroidUtilities.isTablet()) {
            layoutParams.width = AndroidUtilities.dp(498);
            layoutParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        } else {
            layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
            layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        }
        valueSeekBar.setLayoutParams(layoutParams);
    }

    private void updateValueTextView() {
        int value = 0;
        if (selectedTool == enhanceTool) {
            value = (int) enhanceValue;
        } else if (selectedTool == highlightsTool) {
            value = (int) highlightsValue;
        } else if (selectedTool == contrastTool) {
            value = (int) contrastValue;
        } else if (selectedTool == exposureTool) {
            value = (int) exposureValue;
        } else if (selectedTool == warmthTool) {
            value = (int) warmthValue;
        } else if (selectedTool == saturationTool) {
            value = (int) saturationValue;
        } else if (selectedTool == vignetteTool) {
            value = (int) vignetteValue;
        } else if (selectedTool == shadowsTool) {
            value = (int) shadowsValue;
        } else if (selectedTool == grainTool) {
            value = (int) grainValue;
        } else if (selectedTool == sharpenTool) {
            value = (int) sharpenValue;
        }
        if (value > 0) {
            valueTextView.setText("+" + value);
        } else {
            valueTextView.setText("" + value);
        }
    }

    public boolean hasChanges() {
        return enhanceValue != 0 || contrastValue != 0 || highlightsValue != 0 || exposureValue != 0 || warmthValue != 0 || saturationValue != 0 || vignetteValue != 0 ||
                shadowsValue != 0 || grainValue != 0 || sharpenValue != 0;
    }

    public void onTouch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            LayoutParams layoutParams = (LayoutParams) textureView.getLayoutParams();
            if (layoutParams != null && event.getX() >= layoutParams.leftMargin && event.getY() >= layoutParams.topMargin && event.getX() <= layoutParams.leftMargin + layoutParams.width && event.getY() <= layoutParams.topMargin + layoutParams.height) {
                setShowOriginal(true);
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            setShowOriginal(false);
        }
    }

    public void setShowOriginal(boolean value) {
        if (showOriginal == value) {
            return;
        }
        showOriginal = value;
        if (eglThread != null) {
            eglThread.requestRender();
        }
    }

    public void switchToOrFromEditMode() {
        final View viewFrom;
        final View viewTo;
        if (editView.getVisibility() == GONE) {
            viewFrom = toolsView;
            viewTo = editView;
        } else {
            selectedTool = -1;
            viewFrom = editView;
            viewTo = toolsView;
        }

        AnimatorSetProxy animatorSet = new AnimatorSetProxy();
        animatorSet.playTogether(
                ObjectAnimatorProxy.ofFloat(viewFrom, "translationY", 0, AndroidUtilities.dp(126))
        );
        animatorSet.addListener(new AnimatorListenerAdapterProxy() {
            @Override
            public void onAnimationEnd(Object animation) {
                viewFrom.clearAnimation();
                viewFrom.setVisibility(GONE);
                viewTo.setVisibility(VISIBLE);
                ViewProxy.setTranslationY(viewTo, AndroidUtilities.dp(126));

                AnimatorSetProxy animatorSet = new AnimatorSetProxy();
                animatorSet.playTogether(
                        ObjectAnimatorProxy.ofFloat(viewTo, "translationY", 0)
                );
                animatorSet.addListener(new AnimatorListenerAdapterProxy() {
                    @Override
                    public void onAnimationEnd(Object animation) {
                        viewTo.clearAnimation();
                        if (selectedTool == enhanceTool) {
                            checkEnhance();
                        }
                    }
                });
                animatorSet.setDuration(200);
                animatorSet.start();
            }
        });
        animatorSet.setDuration(200);
        animatorSet.start();
    }

    public void shutdown() {
        if (eglThread != null) {
            eglThread.shutdown();
            eglThread = null;
        }
        textureView.setVisibility(GONE);
    }

    public void init() {
        textureView.setVisibility(VISIBLE);
    }

    public Bitmap getBitmap() {
        return eglThread != null ? eglThread.getTexture() : null;
    }

    private void fixLayout(int viewWidth, int viewHeight) {
        if (bitmapToEdit == null) {
            return;
        }

        viewWidth -= AndroidUtilities.dp(28);
        viewHeight -= AndroidUtilities.dp(14 + 140);

        float bitmapW = bitmapToEdit.getWidth();
        float bitmapH = bitmapToEdit.getHeight();
        if (orientation == 90 || orientation == 270) {
            bitmapW = bitmapToEdit.getHeight();
            bitmapH = bitmapToEdit.getWidth();
        } else {
            bitmapW = bitmapToEdit.getWidth();
            bitmapH = bitmapToEdit.getHeight();
        }
        float scaleX = viewWidth / bitmapW;
        float scaleY = viewHeight / bitmapH;
        if (scaleX > scaleY) {
            bitmapH = viewHeight;
            bitmapW = (int) Math.ceil(bitmapW * scaleY);
        } else {
            bitmapW = viewWidth;
            bitmapH = (int) Math.ceil(bitmapH * scaleX);
        }

        int bitmapX = (int) Math.ceil((viewWidth - bitmapW) / 2 + AndroidUtilities.dp(14));
        int bitmapY = (int) Math.ceil((viewHeight - bitmapH) / 2 + AndroidUtilities.dp(14));

        LayoutParams layoutParams = (LayoutParams) textureView.getLayoutParams();
        layoutParams.leftMargin = bitmapX;
        layoutParams.topMargin = bitmapY;
        layoutParams.width = (int) bitmapW;
        layoutParams.height = (int) bitmapH;
        textureView.setLayoutParams(layoutParams);

        if (AndroidUtilities.isTablet()) {
            int total = AndroidUtilities.dp(86) * 10;
            layoutParams = (FrameLayout.LayoutParams) recyclerListView.getLayoutParams();
            if (total < viewWidth) {
                layoutParams.width = total;
                layoutParams.leftMargin = (viewWidth - total) / 2;
            } else {
                layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
                layoutParams.leftMargin = 0;
            }
            recyclerListView.setLayoutParams(layoutParams);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        fixLayout(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private float getShadowsValue() {
        return (shadowsValue / 100.0f) * 0.65f;
    }

    private float getHighlightsValue() {
        return 1 - (highlightsValue / 100.0f);
    }

    private float getEnhanceValue() {
        return (enhanceValue / 100.0f);
    }

    private float getExposureValue() {
        return (exposureValue / 100.0f);
    }

    private float getContrastValue() {
        return (contrastValue / 100.0f) * 0.3f + 1;
    }

    private float getWarmthValue() {
        return warmthValue / 100.0f;
    }

    private float getVignetteValue() {
        return vignetteValue / 100.0f;
    }

    private float getSharpenValue() {
        return 0.11f + sharpenValue / 100.0f * 0.6f;
    }

    private float getGrainValue() {
        return grainValue / 100.0f * 0.04f;
    }

    private float getSaturationValue() {
        float parameterValue = (saturationValue / 100.0f);
        if (parameterValue > 0) {
            parameterValue *= 1.05f;
        }
        return parameterValue += 1;
    }

    public FrameLayout getToolsView() {
        return toolsView;
    }

    public FrameLayout getEditView() {
        return editView;
    }

    public TextView getDoneTextView() {
        return doneTextView;
    }

    public TextView getCancelTextView() {
        return cancelTextView;
    }

    public void setEditViewFirst() {
        selectedTool = 0;
        previousValue = enhanceValue;
        enhanceValue = 50;
        valueSeekBar.setMinMax(0, 100);
        paramTextView.setText(LocaleController.getString("Enhance", R.string.Enhance));
        editView.setVisibility(VISIBLE);
        toolsView.setVisibility(GONE);
        valueSeekBar.setProgress(50, false);
        updateValueTextView();
    }

    private void checkEnhance() {
        if (enhanceValue == 0) {
            AnimatorSetProxy animatorSetProxy = new AnimatorSetProxy();
            animatorSetProxy.setDuration(200);
            animatorSetProxy.playTogether(ObjectAnimatorProxy.ofInt(valueSeekBar, "progress", 50));
            animatorSetProxy.start();
        }
    }

    public class ToolsAdapter extends RecyclerView.Adapter {

        private Context mContext;

        private class Holder extends RecyclerView.ViewHolder {

            public Holder(View itemView) {
                super(itemView);
            }
        }

        public ToolsAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return 10;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
            PhotoEditToolCell view = new PhotoEditToolCell(mContext);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int i) {
            Holder holder = (Holder) viewHolder;
            if (i == enhanceTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_enhance, LocaleController.getString("Enhance", R.string.Enhance), enhanceValue);
            } else if (i == highlightsTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_highlights, LocaleController.getString("Highlights", R.string.Highlights), highlightsValue);
            } else if (i == contrastTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_contrast, LocaleController.getString("Contrast", R.string.Contrast), contrastValue);
            } else if (i == exposureTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_brightness, LocaleController.getString("Exposure", R.string.Exposure), exposureValue);
            } else if (i == warmthTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_warmth, LocaleController.getString("Warmth", R.string.Warmth), warmthValue);
            } else if (i == saturationTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_saturation, LocaleController.getString("Saturation", R.string.Saturation), saturationValue);
            } else if (i == vignetteTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_vignette, LocaleController.getString("Vignette", R.string.Vignette), vignetteValue);
            } else if (i == shadowsTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_shadows, LocaleController.getString("Shadows", R.string.Shadows), shadowsValue);
            } else if (i == grainTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_grain, LocaleController.getString("Grain", R.string.Grain), grainValue);
            } else if (i == sharpenTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_details, LocaleController.getString("Sharpen", R.string.Sharpen), sharpenValue);
            } else if (i == blurTool) {
                ((PhotoEditToolCell) holder.itemView).setIconAndTextAndValue(R.drawable.tool_details, LocaleController.getString("Blur", R.string.Blur), 0); //TODO add value
            }
        }
    }
}
