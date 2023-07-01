package qupath.ext.demo.parameters.command.runners;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Measurements;
import ij.plugin.filter.MaximumFinder;
import ij.process.AutoThresholder;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.demo.parameters.command.AnalysisResult;
import qupath.imagej.processing.RoiLabeling;
import qupath.imagej.processing.SimpleThresholding;
import qupath.imagej.tools.IJTools;
import qupath.lib.color.ColorMaps;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class ThresholdWatershedRunner implements AnalysisRunner {

    private static final Logger logger = LoggerFactory.getLogger(ThresholdWatershedRunner.class);

    private enum Connectivity {
        FOUR_CONNECTED,
        EIGHT_CONNECTED
    }

    private Map<String, AnalysisResult> cachedResults = new ConcurrentHashMap<>();

    private Map<ImageData<BufferedImage>, ImagePlus> imageMap = new ConcurrentHashMap<>();

    private Map<ImagePlus, FloatProcessor> noiseCache = new ConcurrentHashMap<>();

    @Override
    public ParameterList createParameterList() {
        ParameterList params = new ParameterList();

        params.addTitleParameter("Analysis parameters");
        params.addDoubleParameter("gaussianSigma",
                "Gaussian sigma",
                0,
                "px",
                0.0,
                5.0,
                "The sigma for the Gaussian blur used to smooth the image before thresholding.");

        List<String> methods = new ArrayList<>();
        String manual = "Manual";
        methods.add(manual);
        for (var method : AutoThresholder.getMethods())
            methods.add(method);
        params.addChoiceParameter("autoThreshold",
                "Auto threshold",
                manual,
                methods,
                "The automated thresholding method.");

        params.addIntParameter("threshold",
                "Threshold",
                1,
                "",
                0.0,
                255,
                "The absolute threshold value.");

        params.addBooleanParameter("doWatershed",
                "Watershed",
                false,
                "Apply a watershed transform to the smoothed image");

        params.addIntParameter("tolerance",
                "Tolerance",
                0,
                "",
                0.0,
                20.0,
                "The watershed tolerance (using ImageJ's 'Find Maxima')");

        boolean useQuality = false;
        if (useQuality) {
            params.addTitleParameter("Quality parameters");
            params.addDoubleParameter("noise",
                    "Noise sigma",
                    0.0,
                    "",
                    0.0,
                    50,
                    "The sigma value of Gaussian noise added to the image");
        }

//        params.addChoiceParameter("connectivity",
//                "Connectivity",
//                Connectivity.FOUR_CONNECTED,
//                Arrays.asList(Connectivity.values()),
//                "The absolute threshold value.");
        return params;
    }


    @Override
    public void updateParameterPane(ParameterPanelFX parameterPane) {
        var params = parameterPane.getParameters();
        parameterPane.addParameterChangeListener((a, b, c) -> updateEnabled(params, parameterPane));
        updateEnabled(params, parameterPane);
    }

    private static void updateEnabled(ParameterList params, ParameterPanelFX parameterPane) {
        parameterPane.setParameterEnabled("tolerance", params.getBooleanParameterValue("doWatershed"));
    }


    @Override
    public AnalysisResult runAnalysis(ImageData<BufferedImage> imageData, ParameterList params) {
        var paramsString = ParameterList.convertToJson(params);

        if (Thread.interrupted())
            return null;

        String key = imageData.getServer().getPath() + ":" + paramsString;
        if (cachedResults.containsKey(key)) {
            return cachedResults.get(key);
        }

        RegionRequest request = RegionRequest.createInstance(imageData.getServer(), imageData.getServer().getDownsampleForResolution(imageData.getServer().nResolutions() - 1));
        var imp = imageMap.computeIfAbsent(imageData, id -> getImagePlus(id, request));

        imp.killRoi();
        var ipOrig = imp.getProcessor().duplicate();

        var fp = imp.getProcessor().convertToFloatProcessor();
        double sigma = params.getDoubleParameterValue("gaussianSigma");
        String thresholdMethod = (String) params.getChoiceParameterValue("autoThreshold");
        double threshold = params.getIntParameterValue("threshold");
        boolean doWatershed = params.containsKey("doWatershed") ? params.getBooleanParameterValue("doWatershed") : true;
        double tolerance = params.getIntParameterValue("tolerance");

        if (Thread.interrupted())
            return null;

        if (params.containsKey("noise")) {
            double noise = params.getDoubleParameterValue("noise");

            if (noise > 0) {
                var noiseProcessor = noiseCache.computeIfAbsent(imp, imp2 -> {
                    var fpNoise = new FloatProcessor(imp2.getWidth(), imp2.getHeight());
                    fpNoise.noise(1.0);
                    return fpNoise;
                });
                float[] pixels = (float[]) fp.getPixels();
                float[] noisePixels = (float[]) noiseProcessor.getPixels();
                for (int i = 0; i < pixels.length; i++)
                    pixels[i] += noisePixels[i] * (float) noise;
            }
        }

        if (Thread.interrupted())
            return null;

//        Connectivity connectivity = (Connectivity) params.getChoiceParameterValue("connectivity");
        Connectivity connectivity = Connectivity.FOUR_CONNECTED; // Doesn't matter with Maximum Finder involved
        if (sigma > 0)
            fp.blurGaussian(sigma);
//        fp.setThreshold(sigma, 255, ImageProcessor.NO_LUT_UPDATE);

        if (!Objects.equals(thresholdMethod, "Manual")) {
            fp.setAutoThreshold(thresholdMethod, true, ImageProcessor.NO_LUT_UPDATE);
            threshold = fp.getMinThreshold();
        }

        if (Thread.interrupted())
            return null;

        ByteProcessor bp;
        if (doWatershed)
            bp = new MaximumFinder().findMaxima(fp, tolerance, threshold, MaximumFinder.SEGMENTED, false, false);
        else
            bp = SimpleThresholding.thresholdAbove(fp, (float) threshold);

        if (Thread.interrupted())
            return null;

        ImageProcessor ipLabels = RoiLabeling.labelImage(bp, 0.5f, connectivity == Connectivity.EIGHT_CONNECTED);

        if (Thread.interrupted())
            return null;

        int n = (int) ipLabels.getStatistics().max;
        List<PathObject> pathObjects = new ArrayList<>();
        double maxArea = 0;
        if (n > 0) {
            Roi[] rois = RoiLabeling.labelsToConnectedROIs(ipLabels, n);
            if (Thread.interrupted())
                return null;

            for (Roi roi : rois) {
                var pathObject = IJTools.convertToAnnotation(roi,
                        request.getMinX(), request.getMinY(),
                        request.getDownsample(), ImagePlane.getDefaultPlane());
                ipOrig.setRoi(roi);
                ImageStatistics.getStatistics(ipOrig, Measurements.MEAN + Measurements.AREA + Measurements.MIN_MAX, imp.getCalibration());
                ImageStatistics stats = ipOrig.getStatistics();
                try (var ml = pathObject.getMeasurementList()) {
                    ml.put("Mean", stats.mean);
                    ml.put("Area", stats.area);
                    ml.put("Min", stats.min);
                    ml.put("Max", stats.max);
                }
                maxArea = Math.max(maxArea, stats.area);
                pathObjects.add(pathObject);
            }
            var cmap = ColorMaps.getDefaultColorMap();
            for (var pathObject : pathObjects) {
                Integer color = cmap.getColor(
                        pathObject.getMeasurementList().getOrDefault("Area", 0.0) / maxArea,
                        -1.0, 1.0);
                pathObject.setColor(color);
            }

            if (Thread.interrupted())
                return null;
        }
        var results = new AnalysisResult(imageData, paramsString, pathObjects);

        cachedResults.put(key, results);
        return results;
    }


    private static ImagePlus getImagePlus(ImageData<BufferedImage> imageData, RegionRequest request) {
        try {
            var server = imageData.getServer();
            var imp = IJTools.convertToImagePlus(server, request).getImage();
            if (imp.getType() != ImagePlus.GRAY8) {
                IJ.run(imp, "8-bit", " ");
            }
            return imp;
        } catch (IOException e) {
            logger.warn("Failed to load image", e);
            return null;
        }
    }


}
