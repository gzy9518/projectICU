/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.projecttango.examples.java.hellovideo;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Display;
import android.widget.TextView;
import android.widget.Toast;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.projecttango.tangosupport.TangoPointCloudManager;
import com.projecttango.tangosupport.TangoSupport;

import java.io.File;
import java.nio.FloatBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/* FOR TEXT TO SPEECH */

/**
 * This is a stripped down simple example that shows how to use the Tango APIs to render the Tango
 * RGB camera into an OpenGL texture.
 * It creates a standard Android {@code GLSurfaceView} with a simple renderer and connects to
 * the Tango service with the appropriate configuration for Video rendering.
 * Each time a new RGB video frame is available through the Tango APIs, it is updated to the
 * OpenGL texture and the corresponding timestamp is printed on the logcat and on screen.
 * <p/>
 * Only the minimum OpenGL code necessary to understand how to render the specific texture format
 * produced by the Tango RGB camera is provided. You can find these details in
 * {@code HelloVideoRenderer}.
 * If you're looking for an example that also renders an actual 3D object with an augmented reality
 * effect, see java_augmented_reality_example and/or java_augmented_reality_opengl_example.
 */
public class HelloVideoActivity extends Activity {
    private static final String TAG = HelloVideoActivity.class.getSimpleName();
    private static final int SECS_TO_MILLISECS = 1000;
    private static final int INVALID_TEXTURE_ID = 0;
    private static final String sTimestampFormat = "Timestamp: %f";

    private Tango mTango;
    private TangoConfig mConfig;
    private TangoPointCloudManager mPointCloudManager;

    private GLSurfaceView mSurfaceView;
    private HelloVideoRenderer mRenderer;
    private TextView mTimestampTextView;
    private TextView mPointCountTextView;
    private TextView mAverageZTextView;
    private Vibrator Lichen;
    private TextToSpeech tts;
    private FaceDetector fd; // for face detection
    private double ttsPreviousAlertTimeStamp;
    private double ttsPreviousFaceTimeStamp;
    private double mPointCloudPreviousTimeStamp;
    private double fdPreviousTimeStamp;

    //private Tango mTango;
    //private TangoConfig mConfig;
    private boolean mIsConnected = false;

    // NOTE: Naming indicates which thread is in charge of updating this variable
    private int mConnectedTextureIdGlThread = INVALID_TEXTURE_ID;
    private AtomicBoolean mIsFrameAvailableTangoThread = new AtomicBoolean(false);
    private static final DecimalFormat FORMAT_THREE_DECIMAL = new DecimalFormat("0.000");
    private static final double UPDATE_INTERVAL_MS = 100.0;

    private double mPointCloudTimeToNextUpdate = UPDATE_INTERVAL_MS;

    private int mDisplayRotation = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPointCloudManager = new TangoPointCloudManager();
        mTimestampTextView = (TextView) findViewById(R.id.timestamp_textview);
        mSurfaceView = (GLSurfaceView) findViewById(R.id.surfaceview);

        /* Setup tts */
        tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.US);
                    tts.speak("ICU helper initialized.", TextToSpeech.QUEUE_FLUSH, null);
                }
            }
        });

        // START VIBRATION SERVICE?


        String classifierPath = "/storage/emulated/0/Android/data/com.projecttango.examples.java.hellovideo/files/Pictures"
                + File.separator + "lbpcascade_frontalface.xml";
        fd = new FaceDetector(HelloVideoActivity.this, classifierPath);

        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    synchronized (this) {
                        setDisplayRotation();
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }
            }, null);
        }
        // Set-up a dummy OpenGL renderer associated with this surface view
        setupRenderer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSurfaceView.onResume();

        // Set render mode to RENDERMODE_CONTINUOUSLY to force getting onDraw callbacks until the
        // Tango service is properly set-up and we start getting onFrameAvailable callbacks.
        mSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        fd.init(HelloVideoActivity.this);

        // Initialize Tango Service as a normal Android Service, since we call mTango.disconnect()
        // in onPause, this will unbind Tango Service, so every time when onResume gets called, we
        // should create a new Tango object.
        mTango = new Tango(HelloVideoActivity.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready, this Runnable
            // will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only when there is no UI
            // thread changes involved.
            @Override
            public void run() {
                // Synchronize against disconnecting while the service is being used in
                // the OpenGL thread or in the UI thread.
                synchronized (HelloVideoActivity.this) {
                    try {
                        TangoSupport.initialize();
                        mConfig = setupTangoConfig(mTango);
                        mTango.connect(mConfig);
                        startupTango();
                        mIsConnected = true;
                        setDisplayRotation();
                    } catch (TangoOutOfDateException e) {
                        Log.e(TAG, getString(R.string.exception_out_of_date), e);
                        showsToastAndFinishOnUiThread(R.string.exception_out_of_date);
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.exception_tango_error), e);
                        showsToastAndFinishOnUiThread(R.string.exception_tango_error);
                    } catch (TangoInvalidException e) {
                        Log.e(TAG, getString(R.string.exception_tango_invalid), e);
                        showsToastAndFinishOnUiThread(R.string.exception_tango_invalid);
                    }
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSurfaceView.onPause();
        // Synchronize against disconnecting while the service is being used in the OpenGL
        // thread or in the UI thread.
        // NOTE: DO NOT lock against this same object in the Tango callback thread.
        // Tango.disconnect will block here until all Tango callback calls are finished.
        // If you lock against this object in a Tango callback thread it will cause a deadlock.
        synchronized (this) {
            try {
                mTango.disconnectCamera(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                // We need to invalidate the connected texture ID so that we cause a
                // re-connection in the OpenGL thread after resume
                mConnectedTextureIdGlThread = INVALID_TEXTURE_ID;
                mTango.disconnect();
                mIsConnected = false;

                /* Shut down tts */
                if (tts != null) {
                    tts.stop();
                    tts.shutdown();
                }

            } catch (TangoErrorException e) {
                Log.e(TAG, getString(R.string.exception_tango_error), e);
            }
        }
    }

    /**
     * Sets up the tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        // Create a new Tango Configuration and enable the Camera API
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putInt(TangoConfig.KEY_INT_DEPTH_MODE, TangoConfig.TANGO_DEPTH_MODE_POINT_CLOUD);
        return config;
    }

    /**
     * Set up the callback listeners for the Tango service and obtain other parameters required
     * after Tango connection.
     * Listen to updates from the RGB camera.
     */
    private void startupTango() {
        // Lock configuration and connect to Tango
        // Select coordinate frame pair

        ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<TangoCoordinateFramePair>();

        // Listen for new Tango data
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                // We are not using TangoPoseData for this application.
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                // We are not using onXyzIjAvailable for this app.
            }

            @Override
            public void onPointCloudAvailable(final TangoPointCloudData pointCloud) {
                Vibrator Lichen = (Vibrator) HelloVideoActivity.this.getSystemService(Context.VIBRATOR_SERVICE);
                System.out.println(HelloVideoActivity.this.getExternalFilesDir(Environment.DIRECTORY_PICTURES));
//                if (mTangoUx != null) {
//                    mTangoUx.updatePointCloud(pointCloud);
//                }
                mPointCloudManager.updatePointCloud(pointCloud);

                final double currentTimeStamp = pointCloud.timestamp;
                final double pointCloudFrameDelta =
                        (currentTimeStamp - mPointCloudPreviousTimeStamp) * SECS_TO_MILLISECS;
                final double ttsAlertTimeDelta =
                        (currentTimeStamp - ttsPreviousAlertTimeStamp) * SECS_TO_MILLISECS;
                mPointCloudPreviousTimeStamp = currentTimeStamp;
                final double averageDepth = getAveragedDepth(pointCloud.points,
                        pointCloud.numPoints);

                mPointCloudTimeToNextUpdate -= pointCloudFrameDelta;

                if (mPointCloudTimeToNextUpdate < 0.0) {
                    mPointCloudTimeToNextUpdate = UPDATE_INTERVAL_MS;
                    final String pointCountString = Integer.toString(pointCloud.numPoints);
                }

                double MIN_TRACKING_METERS = 0.50;
                double ARM_LENGTH_METERS = 2.00;
                double WAIT_TIME_MILLISECS = 2000.0; // five seconds
                float[][] averagedXY = getAveragedXY(pointCloud.points, pointCloud.numPoints);
                long longbuzz = 400;
                long shortbuzz = 50;
                long interval = 200;

                double leftX = averagedXY[0][0];
                double leftY = averagedXY[0][1];
                double leftZ = averagedXY[0][2];
                double leftpoints = averagedXY[0][3];

                double midX = averagedXY[1][0];
                double midY = averagedXY[1][1];
                double midZ = averagedXY[1][2];
                double midpoints = averagedXY[1][3];

                double rightX = averagedXY[2][0];
                double rightY = averagedXY[2][1];
                double rightZ = averagedXY[2][2];
                double rightpoints = averagedXY[2][3];

                double MIN_X_METERS = -0.25;
                double MAX_X_METERS = 0.25;
                // double MIN_Y_METERS = -0.5
                // double MAX_Y_METERS = 0.5;
                if (MIN_TRACKING_METERS <= averageDepth &&
                        ttsAlertTimeDelta >= WAIT_TIME_MILLISECS) {
                    // turn around
                    int objectThreshold = 1000;
                    boolean midObject = (midpoints >= objectThreshold) &&
                            (midZ <= ARM_LENGTH_METERS);
                    boolean rightObject = (leftpoints >= objectThreshold) &&
                            (leftZ <= ARM_LENGTH_METERS);
                    boolean leftObject = (rightpoints >= objectThreshold) &&
                            (rightZ <= ARM_LENGTH_METERS);

                    if (leftObject && midObject && rightObject) {
                        long [] pattern = {0, longbuzz, interval, longbuzz, interval, longbuzz};
                        if (!tts.isSpeaking()) {
                            Lichen.cancel();
                            Lichen.vibrate(pattern, 0);
                            ttsPreviousAlertTimeStamp = currentTimeStamp;
                            String warning = "Obstacles ahead within arms length. Turn around.";
                            tts.speak(warning, TextToSpeech.QUEUE_FLUSH, null);
                        }


                    }
                    else if (leftObject && midObject && !rightObject) {
                        long [] pattern = {0, longbuzz, interval, longbuzz, interval, shortbuzz};
                        if (!tts.isSpeaking()) {
                            Lichen.cancel();
                            Lichen.vibrate(pattern, 0);
                            ttsPreviousAlertTimeStamp = currentTimeStamp;
                            String warning = "Try moving right.";
                            tts.speak(warning, TextToSpeech.QUEUE_FLUSH, null);
                        }
                    }
                    else if (!leftObject && midObject && !rightObject) {
                        long [] pattern = {0, shortbuzz, interval, longbuzz, interval, shortbuzz};
                        if (!tts.isSpeaking()) {
                            Lichen.cancel();
                            Lichen.vibrate(pattern, 0);
                            ttsPreviousAlertTimeStamp = currentTimeStamp;
                            String warning = "Try moving left or right.";
                            tts.speak(warning, TextToSpeech.QUEUE_FLUSH, null);
                        }
                    }
                    else if (leftObject && !midObject && rightObject) {
                        long [] pattern = {0, longbuzz, interval, shortbuzz, interval, longbuzz};
                        if (!tts.isSpeaking()) {
                            Lichen.cancel();
                            Lichen.vibrate(pattern, 0);
                            ttsPreviousAlertTimeStamp = currentTimeStamp;
                            String warning = "Obstacles on both sides, move straight.";
                            tts.speak(warning, TextToSpeech.QUEUE_FLUSH, null);
                        }
                    }
                    else if (!leftObject && midObject && rightObject) {
                        long [] pattern = {0, shortbuzz, interval, longbuzz, interval, longbuzz};
                        if (!tts.isSpeaking()) {
                            Lichen.cancel();
                            Lichen.vibrate(pattern, 0);
                            ttsPreviousAlertTimeStamp = currentTimeStamp;
                            String warning = "Try moving left.";
                            tts.speak(warning, TextToSpeech.QUEUE_FLUSH, null);
                        }
                    }
                    else if (leftObject && !midObject && !rightObject) {
                        long [] pattern = {0, shortbuzz, interval, shortbuzz, interval, longbuzz};
                        if (!tts.isSpeaking()) {
                            Lichen.cancel();
                            Lichen.vibrate(pattern, 0);
                            ttsPreviousAlertTimeStamp = currentTimeStamp;
                            String warning = "There's something to your left.";
                            tts.speak(warning, TextToSpeech.QUEUE_FLUSH, null);
                        }
                    }
                    else if (!leftObject && !midObject && rightObject) {
                        long [] pattern = {0, longbuzz, interval, shortbuzz, interval, longbuzz};
                        if (!tts.isSpeaking()) {
                            Lichen.cancel();
                            Lichen.vibrate(pattern, 0);
                            ttsPreviousAlertTimeStamp = currentTimeStamp;
                            String warning = "There's something to your right.";
                            tts.speak(warning, TextToSpeech.QUEUE_FLUSH, null);
                        }
                    }
//                    if (midpoints >= 1000 )
//                    if (midpoints >= 1000 &&
//                            midZ <= ARM_LENGTH_METERS) {
//                        if (leftpoints >= 1000 &&
//                                leftZ <= ARM_LENGTH_METERS) {
//                            if (rightpoints >= 1000 &&
//                                    rightZ <= ARM_LENGTH_METERS) {
//                                if (!tts.isSpeaking()) {
//                                    ttsPreviousAlertTimeStamp = currentTimeStamp;
//                                    String warning = "Obstacles ahead within arms length. Turn around.";
//                                    tts.speak(warning, TextToSpeech.QUEUE_FLUSH, null);
//                                }
//                            }
//                            if (rightpoints <= 1000) {
//                                if (!tts.isSpeaking()) {
//                                    ttsPreviousAlertTimeStamp = currentTimeStamp;
//                                    String moveright = "Try moving left.";
//                                    tts.speak(moveright, TextToSpeech.QUEUE_FLUSH, null);
//                                }
//                            }
//                        }
//                        if (leftpoints <= 1000) {
//                            if (rightpoints <= 1000) {
//                                if (!tts.isSpeaking()) {
//                                    ttsPreviousAlertTimeStamp = currentTimeStamp;
//                                    String moveeither = "Move left or right.";
//                                    tts.speak(moveeither, TextToSpeech.QUEUE_FLUSH, null);
//                                }
//                            }
//                            if (rightpoints >= 1000 &&
//                                    rightZ <= ARM_LENGTH_METERS) {
//                                if (!tts.isSpeaking()) {
//                                    ttsPreviousAlertTimeStamp = currentTimeStamp;
//                                    String moveleft = "Try moving right.";
//                                    tts.speak(moveleft, TextToSpeech.QUEUE_FLUSH, null);
//                                }
//                            }
//                        }
//                    }
//                    if (midpoints <= 1000 &&
//                            rightpoints >= 1000 &&
//                            rightZ <= ARM_LENGTH_METERS &&
//                            leftpoints >= 1000 &&
//                            leftZ <= ARM_LENGTH_METERS) {
//                        if (!tts.isSpeaking()) {
//                            ttsPreviousAlertTimeStamp = currentTimeStamp;
//                            String movestraight = "Obstacles on both sides, move straight.";
//                            tts.speak(movestraight, TextToSpeech.QUEUE_FLUSH, null);
//                        }
//                    }
                }
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                // Ignoring TangoEvents.
            }

//            @Override
            public void onFrameAvailable(int cameraId) {
                // This will get called every time a new RGB camera frame is available to be
                // rendered.
                Log.d(TAG, "onFrameAvailable");

                if (cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR) {
                    // Now that we are receiving onFrameAvailable callbacks, we can switch
                    // to RENDERMODE_WHEN_DIRTY to drive the render loop from this callback.
                    // This will result on a frame rate of  approximately 30FPS, in synchrony with
                    // the RGB camera driver.
                    // If you need to render at a higher rate (i.e.: if you want to render complex
                    // animations smoothly) you  can use RENDERMODE_CONTINUOUSLY throughout the
                    // application lifecycle.
                    if (mSurfaceView.getRenderMode() != GLSurfaceView.RENDERMODE_WHEN_DIRTY) {
                        mSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                    }

                    // Note that the RGB data is not passed as a parameter here.
                    // Instead, this callback indicates that you can call
                    // the {@code updateTexture()} method to have the
                    // RGB data copied directly to the OpenGL texture at the native layer.
                    // Since that call needs to be done from the OpenGL thread, what we do here is
                    // set-up a flag to tell the OpenGL thread to do that in the next run.
                    // NOTE: Even if we are using a render by request method, this flag is still
                    // necessary since the OpenGL thread run requested below is not guaranteed
                    // to run in synchrony with this requesting call.
                    mIsFrameAvailableTangoThread.set(true);
                    // Trigger an OpenGL render to update the OpenGL scene with the new RGB data.
                    mSurfaceView.requestRender();

                    double currTime = System.nanoTime();
                    double currTime_MILLI_SECONDS = currTime / 1e6;
                    double diff = (currTime - fdPreviousTimeStamp) / 1e6;
                    if (diff > 1000) {
                        System.out.println("FaceDetection: Attempting to analyze face");
                        System.out.println("FaceDetection: Delta t: " + diff);
                        fdPreviousTimeStamp = currTime;
                        String facePath = "/storage/emulated/0/Android/data/com.projecttango.examples.java.hellovideo/files/Pictures"
                                + File.separator + "ICU_test.jpg";
                        int nFaces = fd.facedetect(facePath);
                        System.out.println(String.format("FaceDetection: detected %d faces", nFaces));

                        if (nFaces >= 1 ) {
                            double faceDiff = currTime_MILLI_SECONDS - ttsPreviousFaceTimeStamp;
                            System.out.println("FaceDetection: faceDiff: " + faceDiff);
                            if (faceDiff > 2000) {
                                String personinfront = String.format("There are %d faces in front of you", nFaces);
                                tts.speak(personinfront, TextToSpeech.QUEUE_FLUSH, null);
                                ttsPreviousFaceTimeStamp = currTime_MILLI_SECONDS;
                            }
                        }
                    }
                }
            }
        });
    }

    /**
     * Here is where you would set-up your rendering logic. We're replacing it with a minimalistic,
     * dummy example using a standard GLSurfaceView and a basic renderer, for illustration purposes
     * only.
     */
    private void setupRenderer() {
        mSurfaceView.setEGLContextClientVersion(2);
        mRenderer = new HelloVideoRenderer(new HelloVideoRenderer.RenderCallback() {
            @Override
            public void preRender() {
                // Log.d(TAG, "preRender");
                // This is the work that you would do on your main OpenGL render thread.

                // We need to be careful to not run any Tango-dependent code in the OpenGL
                // thread unless we know the Tango service to be properly set-up and connected.
                if (!mIsConnected) {
                    return;
                }

                try {
                    // Synchronize against concurrently disconnecting the service triggered from the
                    // UI thread.
                    synchronized (HelloVideoActivity.this) {
                        // Connect the Tango SDK to the OpenGL texture ID where we are going to
                        // render the camera.
                        // NOTE: This must be done after both the texture is generated and the Tango
                        // service is connected.
                        if (mConnectedTextureIdGlThread == INVALID_TEXTURE_ID) {
                            mConnectedTextureIdGlThread = mRenderer.getTextureId();
                            mTango.connectTextureId(TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                                    mRenderer.getTextureId());
                            Log.d(TAG, "connected to texture id: " + mRenderer.getTextureId());
                        }

                        // If there is a new RGB camera frame available, update the texture and
                        // scene camera pose.
                        if (mIsFrameAvailableTangoThread.compareAndSet(true, false)) {
                            double rgbTimestamp =
                                    mTango.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                            // {@code rgbTimestamp} contains the exact timestamp at which the
                            // rendered RGB frame was acquired.

                            // In order to see more details on how to use this timestamp to modify
                            // the scene camera and achieve an augmented reality effect, please
                            // refer to java_augmented_reality_example and/or
                            // java_augmented_reality_opengl_example projects.

                            // Log and display timestamp for informational purposes
                            Log.d(TAG, "Frame updated. Timestamp: " + rgbTimestamp);

                            // Updating the UI needs to be in a separate thread. Do it through a
                            // final local variable to avoid concurrency issues.
                            final String timestampText = String.format(sTimestampFormat,
                                    rgbTimestamp);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mTimestampTextView.setText(timestampText);
                                }
                            });
                        }
                    }
                } catch (TangoErrorException e) {
                    Log.e(TAG, "Tango API call error within the OpenGL thread", e);
                } catch (Throwable t) {
                    Log.e(TAG, "Exception on the OpenGL thread", t);
                }
            }
        });
        mSurfaceView.setRenderer(mRenderer);
    }

    private float getAveragedDepth(FloatBuffer pointCloudBuffer, int numPoints) {
        float totalZ = 0;
        float averageZ = 0;

        if (numPoints != 0) {
            int numFloats = 4 * numPoints;
            for (int i = 2; i < numFloats; i = i + 4) {
                totalZ = totalZ + pointCloudBuffer.get(i);
            }
            averageZ = totalZ / numPoints;
        }
        return averageZ;
    }

    private float[][] getAveragedXY(FloatBuffer pointCloudBuffer, int numPoints) {
        float ret[][] = new float[3][];
        for (int i = 0; i < 3; i++) {
            ret[i] = new float[4];
            for (int j = 0; j < 4; j ++) {
                ret[i][j] = 0;
            }
        }

        if (numPoints != 0) {
            int numFloats = 4 * numPoints;
            for (int i = 1; i < numFloats; i = i + 4) {
                if (pointCloudBuffer.get(i-1) > .25 &&
                        pointCloudBuffer.get(i-1) < 1) {
                    ret[0][0] = ret[0][0] + pointCloudBuffer.get(i-1);
                    ret[0][1] = ret[0][1] + pointCloudBuffer.get(i);
                    ret[0][2] = ret[0][2]+ pointCloudBuffer.get(i+1);
                    ret[0][3] ++;
                }
                if (pointCloudBuffer.get(i-1) <= .25 &&
                        pointCloudBuffer.get(i-1) >= -.25) {
                    ret[1][0] = ret[1][0] + pointCloudBuffer.get(i-1);
                    ret[1][1] = ret[1][1] + pointCloudBuffer.get(i);
                    ret[1][2] = ret[1][2] + pointCloudBuffer.get(i+1);
                    ret[1][3] ++;
                }
                if (pointCloudBuffer.get(i-1) < -.25 &&
                        pointCloudBuffer.get(i-1) >= -1) {
                    ret[2][0] = ret[2][0] + pointCloudBuffer.get(i-1);
                    ret[2][1] = ret[2][1] + pointCloudBuffer.get(i);
                    ret[2][2] = ret[2][2] + pointCloudBuffer.get(i+1);
                    ret[2][3] ++;
                }
            }
            for (int i =0; i < 3; i ++) {
                for (int j = 0; j < 3; j ++) {
                    ret[i][j] = ret[i][j] / ret[i][3];
                }
            }
        }
        return ret;
    }

    /**
     * Set the color camera background texture rotation and save the camera to display rotation.
     */
    private void setDisplayRotation() {
        Display display = getWindowManager().getDefaultDisplay();
        mDisplayRotation = display.getRotation();

        // We also need to update the camera texture UV coordinates. This must be run in the OpenGL
        // thread.
        mSurfaceView.queueEvent(new Runnable() {
            @Override
            public void run() {
                if (mIsConnected) {
                    mRenderer.updateColorCameraTextureUv(mDisplayRotation);
                }
            }
        });
    }

    /**
     * Display toast on UI thread.
     *
     * @param resId The resource id of the string resource to use. Can be formatted text.
     */
    private void showsToastAndFinishOnUiThread(final int resId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(HelloVideoActivity.this,
                        getString(resId), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }
}
