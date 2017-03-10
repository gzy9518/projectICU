package com.projecttango.examples.java.hellovideo;

/**
 * Created by ericsgu on 3/9/2017.
 */
//import statements for opencv
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.highgui.Highgui;
import org.opencv.objdetect.CascadeClassifier;


import android.app.Activity;
import android.content.Context;
import android.hardware.Camera;
import android.hardware.display.DisplayManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
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

import java.nio.FloatBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class FaceDetector {
    Context c;
    CascadeClassifier faceDetector;
    String classifierPath;
    int count;

    public FaceDetector(Context c, String classifierPath) {
        this.c = c;
        init(c);
        this.classifierPath = classifierPath;
        count = 0;
    }

    public void init(Context c) {
        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_6, c, mLoaderCallback);
    }

    public int facedetect(String facePath) {
        System.out.println("\nRunning FaceDetector");
        Mat image = Highgui.imread(facePath);

        MatOfRect faceDetections = new MatOfRect();
        faceDetector.detectMultiScale(image, faceDetections);

//        for (Rect rect : faceDetections.toArray()) {
//            Core.rectangle(image, new Point(rect.x, rect.y), new Point(rect.x + rect.width, rect.y + rect.height),
//                    new Scalar(0, 255, 0));
//        }

//        if (faceDetections.toArray().length >= 1) {
//            String path = "/storage/emulated/0/Android/data/com.projecttango.examples.java.hellovideo/files/Pictures/";
//            String filename = path + "output" + count + ".png";
//            System.out.println(String.format("Writing %s", filename));
//            Highgui.imwrite(filename, image);
//            count++;
//        }

        return faceDetections.toArray().length;
    }

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this.c) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    System.out.println("OpenCV loaded successfully");
                    faceDetector = new CascadeClassifier(classifierPath);
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };


}
