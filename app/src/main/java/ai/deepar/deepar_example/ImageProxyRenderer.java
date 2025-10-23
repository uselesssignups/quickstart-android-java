package ai.deepar.deepar_example;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import android.graphics.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Renderer that takes CameraX ImageProxy frames and renders them directly via OpenGL.
 * Uploads Y, U and V planes to GL_LUMINANCE textures and converts to RGB in the shader.
 */
public class ImageProxyRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "ImageProxyRenderer";

    // Fullscreen quad (triangle strip)
    private static final float[] POSITIONS = new float[]{
            -1f,  1f, 0f,
             1f,  1f, 0f,
            -1f, -1f, 0f,
             1f, -1f, 0f
    };
    private static final float[] TEXCOORDS = new float[]{
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
    };

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat3 uTexTransform;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){\n" +
            "  gl_Position = aPosition;\n" +
            "  vec3 t = uTexTransform * vec3(aTexCoord, 1.0);\n" +
            "  vTexCoord = t.xy;\n" +
            "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D y_tex;\n" +
            "uniform sampler2D u_tex;\n" +
            "uniform sampler2D v_tex;\n" +
            "void main(){\n" +
            "  float y = texture2D(y_tex, vTexCoord).r;\n" +
            "  float u = texture2D(u_tex, vTexCoord).r - 0.5;\n" +
            "  float v = texture2D(v_tex, vTexCoord).r - 0.5;\n" +
            "  float r = y + 1.402 * v;\n" +
            "  float g = y - 0.344136 * u - 0.714136 * v;\n" +
            "  float b = y + 1.772 * u;\n" +
            "  gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}";

    private FloatBuffer positionBuffer;
    private FloatBuffer texCoordBuffer;
    private int program = -1;
    private int aPositionLoc = -1;
    private int aTexCoordLoc = -1;
    private int uYLoc = -1, uULoc = -1, uVLoc = -1;
    private int uTexTransformLoc = -1;

    private int texY = 0, texU = 0, texV = 0;
    private boolean texturesInitialized = false;

    // Latest frame data copied from ImageProxy
    private final ReentrantLock frameLock = new ReentrantLock();
    private ByteBuffer yData; // size width*height
    private ByteBuffer uData; // size (width/2)*(height/2)
    private ByteBuffer vData; // size (width/2)*(height/2)
    private int frameWidth = 0, frameHeight = 0;
    private boolean frameAvailable = false;

    // Transform controls
    private int rotationDegrees = 0; // 0, 90, 180, 270
    private boolean mirrorX = false; // mirror horizontally
    private final float[] texTransform = identity3();
    private int surfaceWidth = 0, surfaceHeight = 0;

    public void setMirror(boolean mirror) {
        mirrorX = mirror;
        updateTexTransform();
    }

    public void setRotationDegrees(int degrees) {
        rotationDegrees = ((degrees % 360) + 360) % 360;
        updateTexTransform();
    }

    public void submitImage(@NonNull ImageProxy image, boolean mirror, int rotationDegrees) {
        final Rect crop = image.getCropRect();
        // Use crop rect to avoid CameraX-imposed center crop causing apparent zoom
        final int width = crop.width();
        final int height = crop.height();
        setMirror(mirror);
        // CameraX provides clockwise rotation to apply; counter-rotate sampling.
        setRotationDegrees(-rotationDegrees);

        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane = image.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane = image.getPlanes()[2];

        int yRowStride = yPlane.getRowStride();
        int uRowStride = uPlane.getRowStride();
        int vRowStride = vPlane.getRowStride();
        int yPixelStride = yPlane.getPixelStride();
        int uPixelStride = uPlane.getPixelStride();
        int vPixelStride = vPlane.getPixelStride();

        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        // Offsets into planes for crop rect (guaranteed even for 420)
        int cropLeft = crop.left;
        int cropTop = crop.top;
        int chromaCropLeft = cropLeft / 2;
        int chromaCropTop = cropTop / 2;

        // Ensure buffers allocated
        frameLock.lock();
        try {
            if (frameWidth != width || frameHeight != height || yData == null) {
                yData = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder());
                uData = ByteBuffer.allocateDirect(chromaWidth * chromaHeight).order(ByteOrder.nativeOrder());
                vData = ByteBuffer.allocateDirect(chromaWidth * chromaHeight).order(ByteOrder.nativeOrder());
                frameWidth = width;
                frameHeight = height;
                texturesInitialized = false; // re-init texture sizes on next draw
            }

            yData.position(0);
            uData.position(0);
            vData.position(0);

            ByteBuffer yBuf = yPlane.getBuffer();
            for (int row = 0; row < height; row++) {
                int srcRow = cropTop + row;
                int rowStart = srcRow * yRowStride + cropLeft * yPixelStride;
                if (yPixelStride == 1) {
                    // Fast path: contiguous bytes
                    int oldPos = yBuf.position();
                    yBuf.position(rowStart);
                    for (int col = 0; col < width; col++) {
                        yData.put(yBuf.get());
                    }
                    yBuf.position(oldPos);
                } else {
                    for (int col = 0; col < width; col++) {
                        yData.put(yBuf.get(rowStart + col * yPixelStride));
                    }
                }
            }

            ByteBuffer uBuf = uPlane.getBuffer();
            ByteBuffer vBuf = vPlane.getBuffer();
            for (int row = 0; row < chromaHeight; row++) {
                int srcRow = chromaCropTop + row;
                for (int col = 0; col < chromaWidth; col++) {
                    int srcCol = chromaCropLeft + col;
                    uData.put(uBuf.get(srcRow * uRowStride + srcCol * uPixelStride));
                    vData.put(vBuf.get(srcRow * vRowStride + srcCol * vPixelStride));
                }
            }

            yData.position(0);
            uData.position(0);
            vData.position(0);
            frameAvailable = true;
        } finally {
            frameLock.unlock();
        }
    }

    @Override
    public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);

        positionBuffer = ByteBuffer.allocateDirect(POSITIONS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        positionBuffer.put(POSITIONS).position(0);

        texCoordBuffer = ByteBuffer.allocateDirect(TEXCOORDS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(TEXCOORDS).position(0);

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) throw new RuntimeException("Program creation failed");
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        uYLoc = GLES20.glGetUniformLocation(program, "y_tex");
        uULoc = GLES20.glGetUniformLocation(program, "u_tex");
        uVLoc = GLES20.glGetUniformLocation(program, "v_tex");
        uTexTransformLoc = GLES20.glGetUniformLocation(program, "uTexTransform");

        int[] textures = new int[3];
        GLES20.glGenTextures(3, textures, 0);
        texY = textures[0];
        texU = textures[1];
        texV = textures[2];

        setupLuminanceTexture(texY);
        setupLuminanceTexture(texU);
        setupLuminanceTexture(texV);

        texturesInitialized = false; // will initialize dimensions on first frame
    }

    @Override
    public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        boolean upload = false;
        ByteBuffer y = null, u = null, v = null;
        int w = 0, h = 0;
        frameLock.lock();
        try {
            if (frameAvailable && yData != null && uData != null && vData != null) {
                y = yData.duplicate();
                u = uData.duplicate();
                v = vData.duplicate();
                y.position(0); u.position(0); v.position(0);
                w = frameWidth; h = frameHeight;
                frameAvailable = false;
                upload = true;
            }
        } finally {
            frameLock.unlock();
        }

        if (!upload) {
            return;
        }

        // Update geometry to preserve aspect ratio (fit center)
        updateScaledPositions(w, h);

        GLES20.glUseProgram(program);

        int chromaW = w / 2;
        int chromaH = h / 2;

        if (!texturesInitialized) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texY);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, y);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texU);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, chromaW, chromaH, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, u);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texV);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, chromaW, chromaH, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, v);

            texturesInitialized = true;
        } else {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texY);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, y);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texU);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, chromaW, chromaH, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, u);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texV);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, chromaW, chromaH, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, v);
        }

        GLES20.glUniform1i(uYLoc, 0);
        GLES20.glUniform1i(uULoc, 1);
        GLES20.glUniform1i(uVLoc, 2);

        FloatBuffer mat = ByteBuffer.allocateDirect(9 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mat.put(texTransform).position(0);
        GLES20.glUniformMatrix3fv(uTexTransformLoc, 1, false, mat);

        GLES20.glEnableVertexAttribArray(aPositionLoc);
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, positionBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoordLoc);
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glDisableVertexAttribArray(aTexCoordLoc);
        GLES20.glUseProgram(0);
    }

    private static void setupLuminanceTexture(int texId) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int createProgram(String vSrc, String fSrc) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vSrc);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fSrc);
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

    private static float[] identity3() {
        return new float[]{
                1,0,0,
                0,1,0,
                0,0,1
        };
    }

    private void updateTexTransform() {
        float[] t1 = translate(-0.5f, -0.5f);
        float[] sMirror = mirrorX ? scale(-1f, 1f) : identity3();
        float[] sFlipY = scale(1f, -1f);
        float[] rot = rotate(rotationDegrees);
        float[] t2 = translate(0.5f, 0.5f);

        float[] m = multiply(t2, multiply(rot, multiply(sFlipY, multiply(sMirror, t1))));
        System.arraycopy(m, 0, texTransform, 0, 9);
    }

    private void updateScaledPositions(int imgW, int imgH) {
        if (positionBuffer == null) {
            return;
        }
        float xScale = 1f;
        float yScale = 1f;
        if (surfaceWidth > 0 && surfaceHeight > 0 && imgW > 0 && imgH > 0) {
            boolean swap = ((rotationDegrees % 360) + 360) % 360 == 90 || ((rotationDegrees % 360) + 360) % 360 == 270;
            int effW = swap ? imgH : imgW;
            int effH = swap ? imgW : imgH;

            float viewAspect = (float) surfaceWidth / (float) surfaceHeight;
            float imgAspect = (float) effW / (float) effH;
            if (viewAspect > imgAspect) {
                // view is wider than image; limit width
                xScale = imgAspect / viewAspect;
                yScale = 1f;
            } else {
                // view is taller than image; limit height
                xScale = 1f;
                yScale = viewAspect / imgAspect;
            }
        }
        float[] positions = new float[]{
                -xScale,  yScale, 0f,
                 xScale,  yScale, 0f,
                -xScale, -yScale, 0f,
                 xScale, -yScale, 0f
        };
        positionBuffer.clear();
        positionBuffer.put(positions).position(0);
    }

    private static float[] translate(float tx, float ty) {
        return new float[]{
                1, 0, tx,
                0, 1, ty,
                0, 0, 1
        };
    }

    private static float[] scale(float sx, float sy) {
        return new float[]{
                sx, 0, 0,
                0, sy, 0,
                0, 0, 1
        };
    }

    private static float[] rotate(int deg) {
        double r = Math.toRadians(((deg % 360) + 360) % 360);
        float c = (float) Math.cos(r);
        float s = (float) Math.sin(r);
        return new float[]{
                c, -s, 0,
                s,  c, 0,
                0,  0, 1
        };
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] m = new float[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                m[r*3 + c] = a[r*3] * b[c] + a[r*3 + 1] * b[c + 3] + a[r*3 + 2] * b[c + 6];
            }
        }
        return m;
    }
}
