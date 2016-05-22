//
//  RPPGSimple.hpp
//  Heartbeat
//
//  Created by Philipp Rouast on 29/02/2016.
//  Copyright © 2016 Philipp Roüast. All rights reserved.
//

#ifndef RPPGSimple_hpp
#define RPPGSimple_hpp

#include <string>
#include <stdio.h>
#include <fstream>
#include <opencv2/objdetect/objdetect.hpp>

#include <jni.h>                                                                // Include jni header

class RPPGSimple {
    
public:

    // Constructor
    RPPGSimple() {;}

    void load(jobject listener, JNIEnv *jenv,                                   // Listener and environment for Java callback
              const int width, const int height,
              const double timeBase,
              const int samplingFrequency, const int rescanInterval,
              const std::string logFileName,
              const std::string faceClassifierFilename,
              const std::string leftEyeClassifierFilename,
              const std::string rightEyeClassifierFilename,
              const bool log, const bool draw);

    void processFrame(cv::Mat &frameRGB, cv::Mat &frameGray, int64_t time);

    void exit(JNIEnv *jenv);

private:

    void detectFace(cv::Mat &frameRGB, cv::Mat &frameGray);
    void setNearestBox(std::vector<cv::Rect> boxes);
    void detectEyes(cv::Mat &frameRGB);
    void updateMask();
    void extractSignal_den_detr_mean();
    void extractSignal_den_band();
    void estimateHeartrate();
    void draw(cv::Mat &frameRGB);

    void callback(int64_t now, double meanBpm, double minBpm, double maxBpm);   // Callback to Java

    // The JavaVM
    JavaVM *jvm;

    // The listener
    jobject listener;

    // The classifiers
    cv::CascadeClassifier faceClassifier;
    cv::CascadeClassifier leftEyeClassifier;
    cv::CascadeClassifier rightEyeClassifier;

    // Settings
    cv::Size minFaceSize;
    double rescanInterval;
    int samplingFrequency;
    double timeBase;
    bool logMode;
    bool drawMode;

    // State variables
    int64_t time;
    double fps;
    int64_t lastSamplingTime;
    int64_t lastScanTime;
    int64_t now;
    bool valid;
    bool updateFlag;

    // Mask
    cv::Rect box;
    cv::Rect rightEye;
    cv::Rect leftEye;
    cv::Mat mask;

    // Signal
    cv::Mat1d g;
    cv::Mat1d t;
    cv::Mat1d jumps;
    cv::Mat1d signal;
    cv::Mat1d bpms;
    cv::Mat1d powerSpectrum;
    double meanBpm;
    double minBpm;
    double maxBpm;

    // Logfiles
    std::ofstream logfile;
    std::ofstream logfileDetailed;
    std::string logfilepath;
};

#endif /* RPPGSimple_hpp */
