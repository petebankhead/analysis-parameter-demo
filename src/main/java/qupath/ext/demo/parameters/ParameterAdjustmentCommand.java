package qupath.ext.demo.parameters;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Measurements;
import ij.plugin.filter.MaximumFinder;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.processing.RoiLabeling;
import qupath.imagej.tools.IJTools;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.analysis.stats.Histogram;
import qupath.lib.color.ColorMaps;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.charts.HistogramPanelFX;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.parameters.ParameterChangeListener;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ParameterAdjustmentCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ParameterAdjustmentCommand.class);

    private static final String title = "Parameter demo";

    private enum Connectivity {
        FOUR_CONNECTED,
        EIGHT_CONNECTED
    }

    private QuPathGUI qupath;

    private Stage stage;

    private ParameterChangeListener parameterChangeListener = this::parameterChanged;

    private ImagePlus imp;

    private Map<ImageData<BufferedImage>, ImagePlus> imageMap = new ConcurrentHashMap<>();

    private ObjectProperty<AnalysisResult> resultsProperty = new SimpleObjectProperty<>();

    private Map<String, AnalysisResult> cachedResults = new ConcurrentHashMap<>();

    private ExecutorService pool = Executors.newCachedThreadPool(ThreadTools.createThreadFactory("parameter-test", true));

    ParameterAdjustmentCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @Override
    public void run() {
        if (stage != null) {
            stage.toFront();
            return;
        }
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showNoImageError(title);
            return;
        }
        stage = createStage();
        stage.show();
    }

    private Stage createStage() {

        ParameterList params = new ParameterList();
        params.addDoubleParameter("gaussianSigma",
                "Gaussian sigma",
                1.0,
                "px",
                0.0,
                5.0,
                "The sigma for the Gaussian blur used to smooth the image before thresholding.");

        params.addDoubleParameter("tolerance",
                "Tolerance",
                1.0,
                "",
                0.0,
                10.0,
                "The watershed tolerance");

        params.addDoubleParameter("threshold",
                "Threshold",
                1.0,
                "",
                0.0,
                255,
                "The absolute threshold value.");

//        params.addChoiceParameter("connectivity",
//                "Connectivity",
//                Connectivity.FOUR_CONNECTED,
//                Arrays.asList(Connectivity.values()),
//                "The absolute threshold value.");

        var parameterPanel = new ParameterPanelFX(params);
        parameterPanel.addParameterChangeListener(parameterChangeListener);

        var df = new DecimalFormat("0.0");

        var table = new TableView<AnalysisResult>();
        var colTitle = new TableColumn<AnalysisResult, String>("Image");
        colTitle.setCellValueFactory(v -> v.getValue().title);

        var colNumObjects = new TableColumn<AnalysisResult, Number>("Count");
        colNumObjects.setCellValueFactory(v -> v.getValue().numObjects);

        var colMeanArea = new TableColumn<AnalysisResult, Number>("Mean area");
        colMeanArea.setCellValueFactory(v -> v.getValue().meanArea);
        colMeanArea.setCellFactory(v -> new NumberTableCell<>(df));

        var colMeanIntensity = new TableColumn<AnalysisResult, Number>("Mean intensity");
        colMeanIntensity.setCellValueFactory(v -> v.getValue().meanIntensity);
        colMeanIntensity.setCellFactory(v -> new NumberTableCell<>(df));

        table.getColumns().setAll(
                colTitle, colNumObjects, colMeanArea, colMeanIntensity
        );
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        resultsProperty.addListener((v, o, n) -> {
            if (n != null) {
                table.getItems().setAll(n);
                n.imageData.getHierarchy().clearAll();
                n.imageData.getHierarchy().addObjects(n.getObjects());
            } else {
                table.getItems().clear();
            }
        });

        BorderPane parameterPane = new BorderPane(parameterPanel.getPane());
        parameterPane.setPadding(new Insets(5.0));
        BorderPane pane = new BorderPane(parameterPane);
        pane.setBottom(table);

        Stage stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle(title);
        stage.setScene(new Scene(pane));

        submitAnalysisTask(qupath.getImageData(), params);

        stage.setOnCloseRequest(e -> {
            parameterPanel.removeParameterChangeListener(parameterChangeListener);
            stage.close();
            this.stage = null;
            if (this.imp != null) {
                this.imp.close();
                this.imp = null;
            }
            imageMap.clear();
            cachedResults.clear();
        });
        return stage;
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


    private void parameterChanged(ParameterList parameterList, String key, boolean isAdjusting) {
        submitAnalysisTask(qupath.getImageData(), parameterList.duplicate());
    }

    private void submitAnalysisTask(ImageData<BufferedImage> imageData, ParameterList parameterList) {
        if (imageData == null)
            return;
        pool.submit(() -> {
            try {
                var result = runAnalysis(qupath.getImageData(), parameterList);
                Platform.runLater(() -> resultsProperty.set(result));
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        });
    }


    private static class AnalysisResult {

        private final ImageData<BufferedImage> imageData;
        private final String params;

        private final List<PathObject> pathObjects;

        private final IntegerProperty numObjects;

        private StringProperty title;

        private ObjectProperty<Histogram> histogramArea;
        private ObjectProperty<Histogram> histogramIntensity;
        private final DoubleProperty meanArea;
        private final DoubleProperty meanIntensity;

        private AnalysisResult(ImageData<BufferedImage> imageData, String params, List<PathObject> pathObjects) {
            this.imageData = imageData;
            this.params = params;
            this.pathObjects = Collections.unmodifiableList(new ArrayList<>(pathObjects));

            this.title = new SimpleStringProperty(imageData.getServer().getMetadata().getName());
            this.numObjects = new SimpleIntegerProperty(pathObjects.size());

            int nBins = 16;
            Histogram histIntensity = Histogram.makeMeasurementHistogram(pathObjects, "Mean", nBins);
            Histogram histArea = Histogram.makeMeasurementHistogram(pathObjects, "Area", nBins);

            this.histogramArea = new SimpleObjectProperty<>(histArea);
            this.histogramIntensity = new SimpleObjectProperty<>(histIntensity);

            this.meanArea = new SimpleDoubleProperty(histArea.getMeanValue());
            this.meanIntensity = new SimpleDoubleProperty(histIntensity.getMeanValue());

//            new HistogramPanelFX().getH
//            new Histogram().
        }

//        private static double calculateMeanArea(List<PathObject> pathObjects) {
//            return pathObjects.stream()
//                    .mapToDouble(p -> p.getROI().getArea())
//                    .average()
//                    .orElse(Double.NaN);
//        }
//
//        private static double calculateMeanIntensity(List<PathObject> pathObjects) {
//            return pathObjects.stream()
//                    .mapToDouble(p -> p.getMeasurements().getOrDefault("Mean", Double.NaN))
//                    .average()
//                    .orElse(Double.NaN);
//        }


        public List<PathObject> getObjects() {
            return pathObjects;
        }

        public IntegerProperty numObjects() {
            return numObjects;
        }

    }

    private AnalysisResult runAnalysis(ImageData<BufferedImage> imageData, ParameterList params) throws IOException {
        var paramsString = ParameterList.convertToJson(params);

        String key = imageData.getServer().getPath() + ":" + paramsString;
        if (cachedResults.containsKey(key)) {
            return cachedResults.get(key);
        }

        RegionRequest request = RegionRequest.createInstance(imageData.getServer(), imageData.getServer().getDownsampleForResolution(imageData.getServer().nResolutions()-1));
        var imp = imageMap.computeIfAbsent(imageData, id -> getImagePlus(id, request));

        var ipOrig = imp.getProcessor();


        var fp = imp.getProcessor().convertToFloatProcessor();
        double sigma = params.getDoubleParameterValue("gaussianSigma");
        double threshold = params.getDoubleParameterValue("threshold");
        double tolerance = params.getDoubleParameterValue("tolerance");
//        Connectivity connectivity = (Connectivity) params.getChoiceParameterValue("connectivity");
        Connectivity connectivity = Connectivity.FOUR_CONNECTED; // Doesn't matter with Maximum Finder involved
        if (sigma > 0)
            fp.blurGaussian(sigma);
//        fp.setThreshold(sigma, 255, ImageProcessor.NO_LUT_UPDATE);

        ByteProcessor bp = new MaximumFinder().findMaxima(fp, tolerance, threshold, MaximumFinder.SEGMENTED, false, false);
        ImageProcessor ipLabels = RoiLabeling.labelImage(bp, 0.5f, connectivity == Connectivity.EIGHT_CONNECTED);

//        ImageProcessor ipLabels = RoiLabeling.labelImage(fp, (float)threshold, connectivity == Connectivity.EIGHT_CONNECTED);

        int n = (int)ipLabels.getStatistics().max;
        List<PathObject> pathObjects = new ArrayList<>();
        double maxArea = 0;
        if (n > 0) {
            Roi[] rois = RoiLabeling.labelsToConnectedROIs(ipLabels, n);
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
//                float h = (float)Math.random();
//                float s = 0.5f + (float)Math.random()/2;
//                float b = 0.5f + (float)Math.random()/2;
//                pathObject.setColor(
//                        Color.getHSBColor(h, s, b).getRGB()
//                );

                pathObjects.add(pathObject);
            }
            var cmap = ColorMaps.getDefaultColorMap();
            for (var pathObject : pathObjects) {
                Integer color = cmap.getColor(
                        pathObject.getMeasurementList().getOrDefault("Area", 0.0) / maxArea,
                        -1.0, 1.0);
                pathObject.setColor(color);
            }

//            ipLabels = ipLabels.convertToFloat();
//            SimpleImage simpleImage = SimpleImages.createFloatImage((float[])ipLabels.getPixels(), ipLabels.getWidth(), ipLabels.getHeight());
//            List<PathObject> pathObjects = ContourTracing.createAnnotations(simpleImage, request, 1, -1);
        }
        var results = new AnalysisResult(imageData, paramsString, pathObjects);
        cachedResults.put(key, results);
        return results;
    }


    private static class NumberTableCell<T> extends TableCell<T, Number> {

        private NumberFormat format;

        private NumberTableCell(NumberFormat format) {
            super();
            this.format = format;
        }

        @Override
        protected void updateItem(Number item, boolean empty) {
            super.updateItem(item, empty);
            if (item == null || empty) {
                setText(null);
            } else {
                setText(format.format(item.doubleValue()));
            }
        }

    }

}
