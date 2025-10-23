package ai.deepar.deepar_example;

import android.Manifest;
import android.app.Application;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.Image;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.AspectRatio;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;

import ai.deepar.ar.ARErrorType;
import ai.deepar.ar.AREventListener;
import ai.deepar.ar.CameraResolutionPreset;
import ai.deepar.ar.DeepAR;
import ai.deepar.ar.DeepARImageFormat;
import io.antmedia.webrtcandroidframework.api.DefaultWebRTCListener;
import io.antmedia.webrtcandroidframework.api.IWebRTCClient;
import io.antmedia.webrtcandroidframework.api.IWebRTCListener;
import io.antmedia.webrtcandroidframework.core.WebRTCClient;

public class MainActivity extends AppCompatActivity implements AREventListener {

    private static final String TAG = "MainActivity";

    // Default camera lens value, change to CameraSelector.LENS_FACING_BACK to initialize with back camera
    private int defaultLensFacing = CameraSelector.LENS_FACING_FRONT;
    private int lensFacing = defaultLensFacing;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ByteBuffer[] buffers;
    private int allocatedBufferSize;
    private int currentBuffer = 0;
    private static final int NUMBER_OF_BUFFERS=2;

    private DeepAR deepAR;
    private GLSurfaceView surfaceView;
    private DeepARRenderer renderer;
    private ImageProxyRenderer imageProxyRenderer;
    private boolean useImageProxyRenderer = true; // set true to render ImageProxy directly

    private FrameLayout remoteViewContainer;
    WebRTCClient webRTCClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deepAR = new DeepAR(this);
        deepAR.setLicenseKey("1e6b2bcc6d9257db488d29b3021fa2b5fe346bc62d4c090222d88d09428b84a691a05f155a5c5258");
        deepAR.initialize(this, this);
        setContentView(R.layout.activity_main);
        remoteViewContainer = (FrameLayout) findViewById(R.id.remote_video_view_container);
        setup();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ActivityCompat.requestPermissions(this, new String[]{ Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO },
                    1);

    }

    void setup() {
        setupCamera();
        initializeEngine();

        String streamId = "test1";
        webRTCClient = IWebRTCClient.builder()
                .setServerUrl("ws://192.168.0.108:5080/LiveApp/websocket")
                .setActivity(this)
                .setVideoSource(IWebRTCClient.StreamSource.CUSTOM)
                .setWebRTCListener(createWebRTCListener())
                .setInitiateBeforeStream(true)
                .build();


        surfaceView = new GLSurfaceView(this);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8,8,8,8,16,0);
        if (useImageProxyRenderer) {
            imageProxyRenderer = new ImageProxyRenderer();
            surfaceView.setRenderer(imageProxyRenderer);
        } else {
            renderer = new DeepARRenderer(deepAR ,webRTCClient, this);
            surfaceView.setEGLContextFactory(new DeepARRenderer.MyContextFactory(renderer));
            surfaceView.setRenderer(renderer);
        }
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);


        FrameLayout local = findViewById(R.id.localPreview);
        local.addView(surfaceView);

        final Button startStopBtn = findViewById(R.id.startCall);
        startStopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startStopStream(v,streamId);
            }
        });
    }
    private IWebRTCListener createWebRTCListener() {
        return new DefaultWebRTCListener() {
            @Override
            public void onIceConnected(String streamId) {
                if (renderer != null) {
                    renderer.setCallInProgress(true);
                }
            }
            @Override
            public void onIceDisconnected(String streamId){
                if (renderer != null) {
                    renderer.setCallInProgress(false);
                }
            }
            @Override
            public void onPublishStarted(String streamId) {
                super.onPublishStarted(streamId);
                //broadcastingView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPublishFinished(String streamId) {
                super.onPublishFinished(streamId);
                //broadcastingView.setVisibility(View.GONE);
            }
        };
    }


    public void startStopStream(View v,String streamId) {
        if (!webRTCClient.isStreaming(streamId)) {
            ((Button) v).setText("Stop");
            Log.i(getClass().getSimpleName(), "Calling publish start");

            webRTCClient.publish(streamId);
        }
        else {
            ((Button) v).setText("Start");
            Log.i(getClass().getSimpleName(), "Calling publish start");
            webRTCClient.stop(streamId);
        }
    }



    /*
        get interface orientation from
        https://stackoverflow.com/questions/10380989/how-do-i-get-the-current-orientation-activityinfo-screen-orientation-of-an-a/10383164
     */
    private int getScreenOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        int orientation;
        // if the device's natural orientation is portrait:
        if ((rotation == Surface.ROTATION_0
                || rotation == Surface.ROTATION_180) && height > width ||
                (rotation == Surface.ROTATION_90
                        || rotation == Surface.ROTATION_270) && width > height) {
            switch(rotation) {
                case Surface.ROTATION_0:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
                case Surface.ROTATION_90:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
                case Surface.ROTATION_180:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    break;
                case Surface.ROTATION_270:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    break;
                default:
                    Log.e(TAG, "Unknown screen orientation. Defaulting to " +
                            "portrait.");
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
            }
        }
        // if the device's natural orientation is landscape or if the device
        // is square:
        else {
            switch(rotation) {
                case Surface.ROTATION_0:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
                case Surface.ROTATION_90:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
                case Surface.ROTATION_180:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    break;
                case Surface.ROTATION_270:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    break;
                default:
                    Log.e(TAG, "Unknown screen orientation. Defaulting to " +
                            "landscape.");
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
            }
        }

        return orientation;
    }

    private void setupCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    bindImageAnalysis(cameraProvider);
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        CameraResolutionPreset cameraPreset = CameraResolutionPreset.P640x480;
        int width;
        int height;
        int orientation = getScreenOrientation();
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE || orientation ==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE){
            width = cameraPreset.getWidth();
            height =  cameraPreset.getHeight();
        } else {
            width = cameraPreset.getHeight();
            height = cameraPreset.getWidth();
        }

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(width, height))
                .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy image) {
                if (useImageProxyRenderer && imageProxyRenderer != null) {
                    boolean isFront = lensFacing == CameraSelector.LENS_FACING_FRONT;
                    int degrees = image.getImageInfo().getRotationDegrees();
                    int applyDegrees = isFront ? degrees : -degrees;
                    imageProxyRenderer.submitImage(
                            image,
                            isFront,
                            applyDegrees
                    );
                    image.close();
                    return;
                }
                ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
                ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
                ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

                int ySize = yBuffer.remaining();
                int uSize = uBuffer.remaining();
                int vSize = vBuffer.remaining();

                int width = image.getWidth();
                int height = image.getHeight();

                int yRowStride = image.getPlanes()[0].getRowStride();
                int uRowStride = image.getPlanes()[1].getRowStride();
                int vRowStride = image.getPlanes()[2].getRowStride();
                int uPixelStride = image.getPlanes()[1].getPixelStride();
                int vPixelStride = image.getPlanes()[2].getPixelStride();

                int imageBufferSize = ySize + uSize + vSize;
                if (allocatedBufferSize < imageBufferSize) {
                    initializeBuffers(imageBufferSize);
                }

                byte[] byteData = new byte[imageBufferSize];
                int outputOffset = 0;

                for (int row = 0; row < height; row++) {
                    yBuffer.position(row * yRowStride);
                    yBuffer.get(byteData, outputOffset, width);
                    outputOffset += width;
                }

                int chromaHeight = height / 2;
                int chromaWidth = width / 2;

                for (int row = 0; row < chromaHeight; row++) {
                    for (int col = 0; col < chromaWidth; col++) {
                        // V then U (NV21 layout)
                        byte v = vBuffer.get(row * vRowStride + col * vPixelStride);
                        byte u = uBuffer.get(row * uRowStride + col * uPixelStride);
                        byteData[outputOffset++] = v;
                        byteData[outputOffset++] = u;
                    }
                }

                buffers[currentBuffer].put(byteData);
                buffers[currentBuffer].position(0);

                if (deepAR != null) {
                    deepAR.receiveFrame(
                            buffers[currentBuffer],
                            width,
                            height,
                            image.getImageInfo().getRotationDegrees(),
                            lensFacing == CameraSelector.LENS_FACING_FRONT,
                            DeepARImageFormat.YUV_420_888,
                            uPixelStride
                    );
                }

                currentBuffer = (currentBuffer + 1) % NUMBER_OF_BUFFERS;
                image.close();
            }
        });

        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle((LifecycleOwner)this, cameraSelector, imageAnalysis);

    }

    private void initializeBuffers(int size) {
        if (buffers == null) {
            buffers = new ByteBuffer[NUMBER_OF_BUFFERS];
        }
        for (int i = 0; i < NUMBER_OF_BUFFERS; i++) {
            buffers[i] = ByteBuffer.allocateDirect(size);
            buffers[i].order(ByteOrder.nativeOrder());
            buffers[i].position(0);
        }
        allocatedBufferSize = size;
    }

    void setRemoteViewWeight(float weight) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) remoteViewContainer.getLayoutParams();
        params.weight = weight;
        remoteViewContainer.setLayoutParams(params);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceView != null) {
            surfaceView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (surfaceView != null) {
            surfaceView.onPause();
        }
    }

    @Override
    protected void onStop() {
        ProcessCameraProvider cameraProvider = null;
        try {
            cameraProvider = cameraProviderFuture.get();
            cameraProvider.unbindAll();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        deepAR.release();
        //mRtcEngine.leaveChannel();
        //RtcEngine.destroy();
    }


    ArrayList<String> effects = new ArrayList<>();
    private void initializeEngine() {
        effects.add("none");
        effects.add("viking_helmet.deepar");
        effects.add("MakeupLook.deepar");
        effects.add("Split_View_Look.deepar");
        effects.add("Emotions_Exaggerator.deepar");
        effects.add("Emotion_Meter.deepar");
        effects.add("Stallone.deepar");
        effects.add("flower_face.deepar");
        effects.add("Humanoid.deepar");
        effects.add("Neon_Devil_Horns.deepar");
        effects.add("Ping_Pong.deepar");
        effects.add("Pixel_Hearts.deepar");
        effects.add("Snail.deepar");
        effects.add("Hope.deepar");
        effects.add("Vendetta_Mask.deepar");
        effects.add("Fire_Effect.deepar");
        effects.add("Elephant_Trunk.deepar");

    }
    private int currentEffect=0;
    private String getFilterPath(String filterName) {
        if (filterName.equals("none")) {
            return null;
        }
        return "file:///android_asset/" + filterName;
    }
    public void nextEffect(View v) {
        currentEffect = (currentEffect + 1) % effects.size();
        deepAR.switchEffect("effect", getFilterPath(effects.get(currentEffect)));
    }

    public void previousEffect(View v) {
        currentEffect = (currentEffect - 1 + effects.size()) % effects.size();
        deepAR.switchEffect("effect", getFilterPath(effects.get(currentEffect)));
    }

    private void setupRemoteVideo(int uid) {

        if (remoteViewContainer.getChildCount() >= 1) {
            return;
        }
        setRemoteViewWeight(1.f);

        //yaha
        surfaceView = new GLSurfaceView(this);


        remoteViewContainer.addView(surfaceView);

        //mRtcEngine.setupRemoteVideo(new VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid));
        surfaceView.setTag(uid);
    }

    private void onRemoteUserLeft() {

        remoteViewContainer.removeAllViews();
        setRemoteViewWeight(0.f);

    }

    @Override
    public void screenshotTaken(Bitmap bitmap) {

    }

    @Override
    public void videoRecordingStarted() {

    }

    @Override
    public void videoRecordingFinished() {

    }

    @Override
    public void videoRecordingFailed() {

    }

    @Override
    public void videoRecordingPrepared() {

    }

    @Override
    public void shutdownFinished() {

    }

    @Override
    public void initialized() {
        nextEffect(null);
    }

    @Override
    public void faceVisibilityChanged(boolean b) {

    }

    @Override
    public void imageVisibilityChanged(String s, boolean b) {

    }

    @Override
    public void frameAvailable(Image image) {

    }

    @Override
    public void error(ARErrorType arErrorType, String s) {

    }

    @Override
    public void effectSwitched(String s) {

    }


}
