/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.*;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
       "switched cameras": [
           {
               "name": <virtual camera name>
               "key": <network table key used for selection>
               // if NT value is a string, it's treated as a name
               // if NT value is a double, it's treated as an integer index
           }
       ]
   }
 */

public final class Main {
  private static String configFile = "/boot/frc.json";

  @SuppressWarnings("MemberName")
  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  @SuppressWarnings("MemberName")
  public static class SwitchedCameraConfig {
    public String name;
    public String key;
  };

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();
  public static List<SwitchedCameraConfig> switchedCameraConfigs = new ArrayList<>();
  public static List<VideoSource> cameras = new ArrayList<>();

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read single switched camera configuration.
   */
  public static boolean readSwitchedCameraConfig(JsonObject config) {
    SwitchedCameraConfig cam = new SwitchedCameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read switched camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement keyElement = config.get("key");
    if (keyElement == null) {
      parseError("switched camera '" + cam.name + "': could not read key");
      return false;
    }
    cam.key = keyElement.getAsString();

    switchedCameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    if (obj.has("switched cameras")) {
      JsonArray switchedCameras = obj.get("switched cameras").getAsJsonArray();
      for (JsonElement camera : switchedCameras) {
        if (!readSwitchedCameraConfig(camera.getAsJsonObject())) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    CameraServer inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = inst.startAutomaticCapture(camera);

    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * Start running the switched camera.
   */
  public static MjpegServer startSwitchedCamera(SwitchedCameraConfig config) {
    System.out.println("Starting switched camera '" + config.name + "' on " + config.key);
    MjpegServer server = CameraServer.getInstance().addSwitchedCamera(config.name);

    NetworkTableInstance.getDefault()
        .getEntry(config.key)
        .addListener(event -> {
              if (event.value.isDouble()) {
                int i = (int) event.value.getDouble();
                if (i >= 0 && i < cameras.size()) {
                  server.setSource(cameras.get(i));
                }
              } else if (event.value.isString()) {
                String str = event.value.getString();
                for (int i = 0; i < cameraConfigs.size(); i++) {
                  if (str.equals(cameraConfigs.get(i).name)) {
                    server.setSource(cameras.get(i));
                    break;
                  }
                }
              }
            },
            EntryListenerFlags.kImmediate | EntryListenerFlags.kNew | EntryListenerFlags.kUpdate);

    return server;
  }


  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
    }

    // start cameras
    for (CameraConfig config : cameraConfigs) {
      cameras.add(startCamera(config));
    }

    // start switched cameras
    for (SwitchedCameraConfig config : switchedCameraConfigs) {
      startSwitchedCamera(config);
    }

    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new MyPipeline(), pipeline -> {
        // do something with pipeline results
      });
      /* something like this for GRIP:
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new GripPipeline(), pipeline -> {
        ...
      });
       */
      visionThread.start();
    }

    // loop forever
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
  }

     /**
   * Example pipeline.
   */
  public static class MyPipeline implements VisionPipeline {
    //Processing Constants
    private static class VisionConstants {
      private static final double ImageWidth = 320.0;
      private static final double ImageHeight = 240.0;
  
      private static final double[] HsvThresholdHue = {66.0, 100.0};
      private static final double[] HsvThresholdSaturation = {66.0, 240.0};
      private static final double[] HsvThresholdValue = {123.0, 255.0};
  
      private static final double FilterContoursMinArea = 20.0;
      private static final double FilterContoursMinPerimeter = 20.0;
      private static final double FilterContoursMinWidth = 20.0;
      private static final double FilterContoursMinHeight = 20.0;
      private static final double[] FilterContoursSolidity = {0, 60.0};
      private static final double FilterContoursMinVertices = 0.0;
      private static final double FilterContoursMinRatio = 0.0;
    }
    
    //Outputs
    private Mat m_resizeImageOutput = new Mat();  
    private Mat m_hsvThresholdOutput = new Mat();
    private ArrayList<MatOfPoint> m_findContoursOutput = new ArrayList<MatOfPoint>();
    private ArrayList<MatOfPoint> m_filterContoursOutput = new ArrayList<MatOfPoint>();

    private NetworkTable m_ntTable;
    private static class NTE {
      public static NetworkTableEntry targetCount;
      public static NetworkTableEntry centerX;
      public static NetworkTableEntry centerY;
      public static NetworkTableEntry offsetX;
      public static NetworkTableEntry offsetY;
    }
    
    static {
      System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }
    
    public MyPipeline() {
      NetworkTableInstance ntinst = NetworkTableInstance.getDefault();

        if (ntinst != null) {
          m_ntTable = ntinst.getTable("Pi Vision");
        }

        if (m_ntTable != null) {
          NTE.targetCount = m_ntTable.getEntry("targetCount");
          NTE.centerX = m_ntTable.getEntry("centerX");
          NTE.centerY = m_ntTable.getEntry("centerY");
          NTE.offsetX = m_ntTable.getEntry("offsetX");
          NTE.offsetY = m_ntTable.getEntry("offsetY");

          NTE.targetCount.setDefaultNumber(0);
          NTE.centerX.setDefaultDouble(0.0);
          NTE.centerY.setDefaultDouble(0.0);
          NTE.offsetX.setDefaultDouble(0.0);
          NTE.offsetY.setDefaultDouble(0.0);
        }
    }

    @Override
    public void process(Mat source0) {      
      // Step Resize_Image0
      Mat resizeImageInput = source0;
      double resizeImageWidth = VisionConstants.ImageWidth;
      double resizeImageHeight = VisionConstants.ImageHeight;
      int resizeImageInterpolation = Imgproc.INTER_LINEAR;
      resizeImage(resizeImageInput, resizeImageWidth, resizeImageHeight, resizeImageInterpolation, m_resizeImageOutput);

      // Step HSV_Threshold0:
      Mat hsvThresholdInput = m_resizeImageOutput;
      double[] hsvThresholdHue = VisionConstants.HsvThresholdHue;
      double[] hsvThresholdSaturation = VisionConstants.HsvThresholdSaturation;
      double[] hsvThresholdValue = VisionConstants.HsvThresholdValue;
      hsvThreshold(hsvThresholdInput, hsvThresholdHue, hsvThresholdSaturation, hsvThresholdValue, m_hsvThresholdOutput);

      // Step Find_Contours0:
      Mat findContoursInput = m_hsvThresholdOutput;
      boolean findContoursExternalOnly = false;
      findContours(findContoursInput, findContoursExternalOnly, m_findContoursOutput); 
      
      // Step Filter_Contours0:
      ArrayList<MatOfPoint> filterContoursInput = m_findContoursOutput;
      double filterContoursMinArea = VisionConstants.FilterContoursMinArea;
      double filterContoursMinPerimeter = VisionConstants.FilterContoursMinPerimeter;
      double filterContoursMinWidth = VisionConstants.FilterContoursMinWidth;
      double filterContoursMinHeight = VisionConstants.FilterContoursMinHeight;
      double[] filterContoursSolidity = VisionConstants.FilterContoursSolidity;
      double filterContoursMinVertices = VisionConstants.FilterContoursMinVertices;
      double filterContoursMinRatio = VisionConstants.FilterContoursMinRatio;
      filterContours(filterContoursInput, 
        filterContoursMinArea, filterContoursMinPerimeter, filterContoursMinWidth, filterContoursMinHeight, 
        filterContoursSolidity, filterContoursMinVertices, filterContoursMinRatio, 
        m_filterContoursOutput);

      // Step Publish Target Info to Network Table
      ArrayList<MatOfPoint> calcTargetInput = m_filterContoursOutput;
      publishTargetInfo(calcTargetInput);
    }

    
    /**
     * This method is a generated getter for the output of a Resize_Image.
     * @return Mat output from Resize_Image.
     */
    public Mat resizeImageOutput() {
      return m_resizeImageOutput;
    }

    /**
     * This method is a generated getter for the output of a Filter_Contours.
     * @return ArrayList<MatOfPoint> output from Filter_Contours.
     */
    public ArrayList<MatOfPoint> filterContoursOutput() {
      return m_filterContoursOutput;
    }
    

    /**
     * Scales and image to an exact size.
     * @param input The image on which to perform the Resize.
     * @param width The width of the output in pixels.
     * @param height The height of the output in pixels.
     * @param interpolation The type of interpolation.
     * @param output The image in which to store the output.
     */
    private void resizeImage(Mat input, double width, double height, int interpolation, Mat output) {
      Imgproc.resize(input, output, new Size(width, height), 0.0, 0.0, interpolation);
    }

    /**
     * Segment an image based on hue, saturation, and value ranges.
     *
     * @param input The image on which to perform the HSL threshold.
     * @param hue The min and max hue
     * @param sat The min and max saturation
     * @param val The min and max value
     * @param output The image in which to store the output.
     */
    private void hsvThreshold(Mat input, double[] hue, double[] sat, double[] val, Mat output) {
      Imgproc.cvtColor(input, output, Imgproc.COLOR_BGR2HSV);
      Core.inRange(output, new Scalar(hue[0], sat[0], val[0]),
                           new Scalar(hue[1], sat[1], val[1]), output);
    }

    /**
     * Sets the values of pixels in a binary image to their distance to the nearest black pixel.
     * @param input The image on which to perform the Distance Transform.
     * @param type The Transform.
     * @param maskSize the size of the mask.
     * @param output The image in which to store the output.
     */
    private void findContours(Mat input, boolean externalOnly, List<MatOfPoint> contours) {
      Mat hierarchy = new Mat();
      contours.clear();

      int mode;
      if (externalOnly) {
        mode = Imgproc.RETR_EXTERNAL;
      }
      else {
        mode = Imgproc.RETR_LIST;
      }

      int method = Imgproc.CHAIN_APPROX_SIMPLE;
      Imgproc.findContours(input, contours, hierarchy, mode, method);
    }

    /**
     * Filters out contours that do not meet certain criteria.
     * @param inputContours is the input list of contours
     * @param output is the the output list of contours
     * @param minArea is the minimum area of a contour that will be kept
     * @param minPerimeter is the minimum perimeter of a contour that will be kept
     * @param minWidth minimum width of a contour
     * @param minHeight minimum height
     * @param Solidity the minimum and maximum solidity of a contour
     * @param minVertexCount minimum vertex Count of the contours
     * @param minRatio minimum ratio of width to height
     */
    private void filterContours(List<MatOfPoint> inputContours, 
      double minArea, double minPerimeter, double minWidth, double minHeight, 
      double[] solidity, double minVertexCount, double minRatio, 
      List<MatOfPoint> output) {

      final MatOfInt hull = new MatOfInt();
      output.clear();
      
      //operation
      for (int i = 0; i < inputContours.size(); i++) {
        final MatOfPoint contour = inputContours.get(i);
        
        // Filter by Width & Height
        final Rect bb = Imgproc.boundingRect(contour);
        if (bb.width < minWidth) continue;
        if (bb.height < minHeight) continue;
        
        // Filter by Area & Perimeter
        final double area = Imgproc.contourArea(contour);
        if (area < minArea) continue;
        if (Imgproc.arcLength(new MatOfPoint2f(contour.toArray()), true) < minPerimeter) continue;
        
        // Filter by Solidity
        Imgproc.convexHull(contour, hull);
        MatOfPoint mopHull = new MatOfPoint();
        mopHull.create((int) hull.size().height, 1, CvType.CV_32SC2);
        for (int j = 0; j < hull.size().height; j++) {
          int index = (int)hull.get(j, 0)[0];
          double[] point = new double[] { contour.get(index, 0)[0], contour.get(index, 0)[1]};
          mopHull.put(j, 0, point);
        }
        final double solid = 100 * area / Imgproc.contourArea(mopHull);
        if (solid < solidity[0] || solid > solidity[1]) continue;
        
        // Filter by number of vertices
        if (contour.rows() < minVertexCount)	continue;
        
        // Filter by ratio
        final double ratio = bb.width / (double)bb.height;
        if (ratio < minRatio) continue;
        
        // Filter be Concavity
        if (Imgproc.isContourConvex(contour)) continue;

        output.add(contour);
      }
    }
    
    /**
     * Find the center of the provided contour
     * @param contour The contour to find the center of
     * @return The coordinates to the center of the contour
     */
    public Point findCenter(MatOfPoint contour) {
      // Try computing the center of the contour using image moments
      // (see OpenCV or https://en.wikipedia.org/wiki/Image_moment for more info on moments)
      Moments m = Imgproc.moments(contour);
      double centerX = (m.m10 / m.m00);
      double centerY = (m.m01 / m.m00);

      return new Point(centerX, centerY);
    }

    /**
     * Find the offset between the center of the image and a point
     * @param point the point that is offset from the center
     * @return The difference in x & y from the center of the image to the specified point
     */
    public Point findOffset(Point point) {
      double offsetX = point.x - (VisionConstants.ImageWidth / 2);
      double offsetY = point.y - (VisionConstants.ImageHeight / 2);

      return new Point(offsetX, offsetY);
    }

    /**
     * Publish info about the target to the network table
     * @param inputContours
     */
    private void publishTargetInfo(List<MatOfPoint> inputContours) {
      int matches = inputContours.size();

      Point center = new Point(0,0);
      Point offset = new Point(0,0);

      if (matches == 1) {
        // Get the coordinates to the center of the contour
        center = findCenter(inputContours.get(0));
        // Get the offset from the center of the image to the center of the contour
        offset = findOffset(center);
      }
      
      NTE.targetCount.setNumber(matches);
      NTE.centerX.setDouble(center.x);
      NTE.centerY.setDouble(center.y);
      NTE.offsetX.setDouble(offset.x);
      NTE.offsetY.setDouble(offset.y);

    }
  }
}

