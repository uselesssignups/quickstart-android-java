package ai.deepar.deepar_example;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;


import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;


import ai.deepar.ar.DeepAR;
import io.antmedia.webrtcandroidframework.core.CustomVideoCapturer;
import io.antmedia.webrtcandroidframework.core.WebRTCClient;

import android.graphics.Matrix;

import org.webrtc.RendererCommon;
import org.webrtc.TextureBufferImpl;
import org.webrtc.VideoFrame;
import org.webrtc.YuvConverter;


public class DeepARRenderer implements GLSurfaceView.Renderer {

    private final String vertexShaderCode =
            "attribute vec4 vPosition;" +
                    "attribute vec2 vUv;" +
                    "varying vec2 uv; " +
                    "void main() {" +
                    "gl_Position = vPosition;" +
                    "uv = vUv;" +
                    "}";

    private final String fragmentShaderCode =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;" +
                    "varying vec2 uv; " +
                    "uniform samplerExternalOES sampler;" +
                    "void main() {" +
                    "  gl_FragColor = texture2D(sampler, uv); " +
                    "}";

    static float squareCoords[] = {
            -1.0f,  1.0f, 0.0f,
            -1.0f, -1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            1.0f,  1.0f, 0.0f };

    static float uv[] = {
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
    };

    private final short drawOrder[] = { 0, 1, 2, 0, 2, 3 };

    private int texture;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    FloatBuffer vertexBuffer;
    private FloatBuffer uvbuffer;
    private ShortBuffer drawListBuffer;
    private int program;

    private final Activity context;
    private DeepAR deepAR;
    private boolean updateTexImage;

    private boolean callInProgress = false;

    private EGLContext mEGLCurrentContext;

    private int textureWidth;
    private int textureHeight;

    float[] matrix = new float[16];
    WebRTCClient webRTCClient;
    private Matrix graphicsMatrix = new Matrix();
    static Handler handler;
    YuvConverter yuvConverter;

    public DeepARRenderer(DeepAR deepAR, WebRTCClient webRTCClient, Activity context) {
        this.updateTexImage = false;
        this.deepAR = deepAR;
        this.context = context;
        this.webRTCClient = webRTCClient;
        this.yuvConverter = new YuvConverter();
    }

    public static int loadShader(int type, String shaderCode){
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);

        ByteBuffer bb = ByteBuffer.allocateDirect(squareCoords.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(squareCoords);
        vertexBuffer.position(0);

        ByteBuffer bb2 = ByteBuffer.allocateDirect(squareCoords.length * 4);
        bb2.order(ByteOrder.nativeOrder());
        uvbuffer = bb2.asFloatBuffer();
        uvbuffer.put(uv);
        uvbuffer.position(0);

        ByteBuffer dlb = ByteBuffer.allocateDirect(drawOrder.length * 2);
        dlb.order(ByteOrder.nativeOrder());
        drawListBuffer = dlb.asShortBuffer();
        drawListBuffer.put(drawOrder);
        drawListBuffer.position(0);

        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        android.opengl.Matrix.setIdentityM(this.matrix, 0);

        new Overlay(context.getApplicationContext(),R.drawable.ic_launcher,0,0);
        new Overlay(context, "Hello", 64, Color.WHITE, 0f, 0f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, final int width, final int height) {
        GLES20.glViewport(0, 0, width, height);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        texture = textures[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);

        textureWidth = width;
        textureHeight = height;


        GLES20.glTexImage2D(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0, GLES20.GL_RGBA, textureWidth, textureHeight, 0,GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(texture);
        surface = new Surface(surfaceTexture);

        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                updateTexImage = true;
            }
        });

        context.runOnUiThread(() -> deepAR.setRenderSurface(surface, textureWidth, textureHeight));
    }

    public GLTextureCopier textureCopier=null;
    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glFinish();
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (updateTexImage) {
            updateTexImage = false;
            synchronized (this) {
                surfaceTexture.updateTexImage();
            }
        }

        surfaceTexture.getTransformMatrix(matrix);

        GLES20.glUseProgram(program);
        int positionHandle = GLES20.glGetAttribLocation(program, "vPosition");
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);

        int uvHandle = GLES20.glGetAttribLocation(program, "vUv");
        GLES20.glEnableVertexAttribArray(uvHandle);
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 8, uvbuffer);

        int sampler = GLES20.glGetUniformLocation(program, "sampler");
        GLES20.glUniform1i(sampler, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.length, GLES20.GL_UNSIGNED_SHORT, drawListBuffer);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(uvHandle);
        GLES20.glUseProgram(0);

        final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

        for (Overlay overlay: Overlay.overlayArray) {
            overlay.drawOverlay();
        }

        if (callInProgress) {

            // 0) Ensure copier created on GL thread
            if (textureCopier == null) {
                textureCopier = new GLTextureCopier();
                textureCopier.init(true); // must run on GL thread
            }

            // 1) Save GL state (minimal)
            int[] tmp1 = new int[1];
            int[] tmp4 = new int[4];
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, tmp1, 0); int prevFbo = tmp1[0];
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, tmp1, 0); int prevProgram = tmp1[0];
            GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, tmp1, 0); int prevActive = tmp1[0];
            GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, tmp1, 0); int prevTex2D = tmp1[0];
            try { GLES20.glGetIntegerv(GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES, tmp1, 0); } catch(Exception e){ tmp1[0]=0; }
            int prevTexOES = tmp1[0];
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, tmp4, 0);
            int prevVpX = tmp4[0], prevVpY = tmp4[1], prevVpW = tmp4[2], prevVpH = tmp4[3];

            float[] texMatrix = new float[16];
            surfaceTexture.getTransformMatrix(texMatrix);

            if (textureCopier == null) {
                textureCopier = new GLTextureCopier();
                textureCopier.init(true); // true for OES
            }

            // 2) Make an RGBA copy of current OES texture (copiedTex is GL_TEXTURE_2D)
            int copiedTex = textureCopier.copy(texture, textureWidth, textureHeight,texMatrix );

            // 3) make sure copy finished and unbind framebuffer (copy should do this, but be safe)
            GLES20.glFlush();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo);
            GLES20.glViewport(prevVpX, prevVpY, prevVpW, prevVpH);

            // 4) build the correct webRTC matrix (float[]) — IMPORTANT: use the returned float[]
            android.graphics.Matrix androidMat = new android.graphics.Matrix();
            androidMat.setValues(matrix);
            androidMat.postTranslate(0.0f, 1.0f);

            // 5) Handler that owns EGL context for conversion: use surfaceTextureHelper handler if available
            Handler eglHandler = null;
            if (webRTCClient != null && webRTCClient.surfaceTextureHelper != null) {
                eglHandler = webRTCClient.surfaceTextureHelper.getHandler();
            }
            if (eglHandler == null) {
                // fallback - must be the GL thread handler; if null, conversion may fail
                eglHandler = handler;
            }
            android.graphics.Matrix bufferMatrix = convertMatrix(texMatrix);
            // 6) Wrap copied texture. NOTE: pass VideoFrame.TextureBuffer.Type.RGB since copiedTex is a 2D RGBA texture
            TextureBufferImpl buffer = new TextureBufferImpl(
                    textureWidth,
                    textureHeight,
                    VideoFrame.TextureBuffer.Type.RGB,
                    copiedTex,
                    bufferMatrix,
                    eglHandler,
                    yuvConverter, // pass the converter as expected by your TextureBufferImpl overloads
                    () -> {
                        // cleanup callback — will run on eglHandler when buffer is released
                        GLES20.glDeleteTextures(1, new int[]{copiedTex}, 0);
                    }
            );

            // 7) Convert to I420 (may return null) — catch exceptions
            VideoFrame.I420Buffer i420Buffer = null;
            try {
                i420Buffer = yuvConverter.convert(buffer);
            } catch (Exception e) {
                Log.e("DeepARRenderer", "convert() threw", e);
            }

            if (i420Buffer != null) {
                try {
                    VideoFrame frame = new VideoFrame(i420Buffer, 0, captureTimeNs);
                    ((CustomVideoCapturer) webRTCClient.getVideoCapturer()).writeFrame(frame);
                } finally {
                }
            } else {
                Log.e("DeepARRenderer", "YuvConverter returned null — conversion failed");
            }

            // 8) release wrapper (triggers cleanup callback on EGL handler)
            buffer.release();

            // 9) restore GL state
            GLES20.glUseProgram(prevProgram);
            GLES20.glActiveTexture(prevActive);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex2D);
            if (prevTexOES != 0) GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, prevTexOES);
            GLES20.glViewport(prevVpX, prevVpY, prevVpW, prevVpH);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo);
        }
    }
    private static Matrix convertMatrix(float[] m) {
        Matrix matrix = new Matrix();

        // Android Matrix is 3x3, while SurfaceTexture gives 4x4
        float[] values = new float[9];
        values[0] = m[0];
        values[1] = m[1];
        values[2] = m[12];
        values[3] = m[4];
        values[4] = m[5];
        values[5] = m[13];
        values[6] = m[8];
        values[7] = m[9];
        values[8] = m[15];

        matrix.setValues(values);
        return matrix;
    }

    public void setCallInProgress(boolean callInProgress) {
        this.callInProgress = callInProgress;
    }

    public static class MyContextFactory implements GLSurfaceView.EGLContextFactory {

        private DeepARRenderer renderer;
        public MyContextFactory(DeepARRenderer renderer) {
            this.renderer = renderer;
            DeepARRenderer.handler =  handler;
        }
        public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
            int[] attrib_list = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL10.EGL_NONE };
            renderer.mEGLCurrentContext =  egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attrib_list);;
            return renderer.mEGLCurrentContext;
        }
        public void destroyContext(EGL10 egl, EGLDisplay display,
                                   EGLContext context) {
            if (!egl.eglDestroyContext(display, context)) {
            }
        }
    }


}

 class GLTextureCopier {

    private static final String TAG = "GLTextureCopier";

    private static final float[] VERTICES = {
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
    };

    private static final float[] TEX_COORDS = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "  vec4 tex = uTexMatrix * vec4(aTexCoord, 0.0, 1.0);\n" +
                    "  vTexCoord = tex.xy;\n" +
                    "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                    "}";

    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}";

    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
                    "uniform sampler2D uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}";

    private final FloatBuffer vertexBuffer;
    private final FloatBuffer texCoordBuffer;

    private int program = -1;
    private int positionLoc, texCoordLoc, textureLoc, texMatrixLoc;
    private int fbo = -1;
    private boolean oes;

    public GLTextureCopier() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(TEX_COORDS).position(0);
    }

    public void init(boolean isOES) {
        oes = isOES;
        String frag = isOES ? FRAGMENT_SHADER_OES : FRAGMENT_SHADER_2D;
        program = createProgram(VERTEX_SHADER, frag);
        if (program == 0) throw new RuntimeException("Program creation failed");

        positionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        textureLoc  = GLES20.glGetUniformLocation(program, "uTexture");
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");

        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        fbo = fbos[0];
        Log.d(TAG, "init done. FBO=" + fbo);
    }

    /**
     * Copies texture applying optional transform matrix.
     * @param srcTexId OES or 2D source texture
     * @param width    texture width
     * @param height   texture height
     * @param transformMatrix may be null (identity used)
     * @return GL_TEXTURE_2D id with copied pixels
     */
    public int copy(int srcTexId, int width, int height, float[] transformMatrix) {
        if (program <= 0) throw new IllegalStateException("init() first");

        // 1. Create target texture
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int dstTex = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dstTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        // 2. FBO attach
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, dstTex, 0);

        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);

        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(texCoordLoc);
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(oes ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D, srcTexId);
        GLES20.glUniform1i(textureLoc, 0);

        // 3. Apply transform matrix if provided
        float[] mat = transformMatrix != null ? transformMatrix : identityMatrix();
        FloatBuffer buf = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(mat).position(0);
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, buf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // Cleanup
        GLES20.glDisableVertexAttribArray(positionLoc);
        GLES20.glDisableVertexAttribArray(texCoordLoc);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glUseProgram(0);

        return dstTex;
    }

    private static float[] identityMatrix() {
        return new float[]{
                1,0,0,0,
                0,1,0,0,
                0,0,1,0,
                0,0,0,1
        };
    }

    private static int createProgram(String vertexSrc, String fragSrc) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragSrc);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] link = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0);
        if (link[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int loadShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Compile error: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    public void release() {
        if (fbo > 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
        if (program > 0) GLES20.glDeleteProgram(program);
        fbo = -1;
        program = -1;
    }
}
 class Overlay {
    private int texture = -1;
    private int overlayProgram = -1;
    private FloatBuffer overlayVertexBuffer;
    private FloatBuffer overlayUvBuffer;

    private float size = 0.3f;   // Width/height in NDC
    private float centerX = 0f;  // -1..1
    private float centerY = 0f;  // -1..1
    public static ArrayList<Overlay> overlayArray = new ArrayList<>();
    private final String vShader =
            "attribute vec4 aPos;\n" +
                    "attribute vec2 aTex;\n" +
                    "varying vec2 vTex;\n" +
                    "void main() {\n" +
                    "  gl_Position = aPos;\n" +
                    "  vTex = aTex;\n" +
                    "}\n";

    private final String fShader =
            "precision mediump float;\n" +
                    "varying vec2 vTex;\n" +
                    "uniform sampler2D tex;\n" +
                    "void main() {\n" +
                    "    vec4 c = texture2D(tex, vTex);\n" +
                    "    gl_FragColor = c;\n" +
                    "}\n";

    public Overlay(Context context, String text, int textSize, int color, float x, float y) {
        centerX = x;
        centerY = y;

        // Prepare paint
        Paint paint = new Paint();
        paint.setTextSize(textSize);
        paint.setColor(color);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.LEFT);

        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);

        int width = (int) Math.ceil(paint.measureText(text));
        int height = (int) Math.ceil(Math.abs(bounds.top) + Math.abs(bounds.bottom));
        if (width == 0) width = 1;
        if (height == 0) height = 1;

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.TRANSPARENT);
        canvas.drawText(text, 0, Math.abs(bounds.top), paint);

        texture = loadTextureFromBitmap(bmp);
        bmp.recycle();

        initShader();
        initBuffers();

        this.overlayArray.add(this);
    }

    public Overlay(Context context, int resId, float x, float y) {
        centerX = x;
        centerY = y;

        Bitmap bmp = BitmapFactory.decodeResource(context.getResources(), resId);
        texture = loadTextureFromBitmap(bmp);
        bmp.recycle();

        initShader();
        initBuffers();
        this.overlayArray.add(this);
    }

    private int loadTextureFromBitmap(Bitmap bmp) {
        int[] texId = new int[1];
        GLES20.glGenTextures(1, texId, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        return texId[0];
    }

    // ------------------- Shader setup -------------------
    private void initShader() {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vShader);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fShader);

        overlayProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(overlayProgram, v);
        GLES20.glAttachShader(overlayProgram, f);
        GLES20.glLinkProgram(overlayProgram);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(overlayProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e("Overlay", "Program link failed: " + GLES20.glGetProgramInfoLog(overlayProgram));
            GLES20.glDeleteProgram(overlayProgram);
            overlayProgram = 0;
        }
    }

    // ------------------- Vertex buffers -------------------
    private void initBuffers() {
        float[] uvs = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
        overlayUvBuffer = ByteBuffer.allocateDirect(uvs.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        overlayUvBuffer.put(uvs).position(0);

        updateVertexBuffer();
    }

    private void updateVertexBuffer() {
        float half = size / 2f;
        float[] verts = {
                centerX - half, centerY + half, 0f,
                centerX + half, centerY + half, 0f,
                centerX - half, centerY - half, 0f,
                centerX + half, centerY - half, 0f
        };

        if (overlayVertexBuffer == null)
            overlayVertexBuffer = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        overlayVertexBuffer.clear();
        overlayVertexBuffer.put(verts).position(0);
    }

    public void moveTo(float x, float y) {
        centerX = x;
        centerY = y;
        updateVertexBuffer();
    }

    public void drawOverlay() {
        if (overlayProgram > 0 && texture > 0) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            GLES20.glUseProgram(overlayProgram);

            int posLoc = GLES20.glGetAttribLocation(overlayProgram, "aPos");
            int texLoc = GLES20.glGetAttribLocation(overlayProgram, "aTex");
            int samplerLoc = GLES20.glGetUniformLocation(overlayProgram, "tex");

            GLES20.glEnableVertexAttribArray(posLoc);
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 12, overlayVertexBuffer);

            GLES20.glEnableVertexAttribArray(texLoc);
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 8, overlayUvBuffer);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(samplerLoc, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(posLoc);
            GLES20.glDisableVertexAttribArray(texLoc);
            GLES20.glUseProgram(0);

            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private int loadShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Overlay", "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}