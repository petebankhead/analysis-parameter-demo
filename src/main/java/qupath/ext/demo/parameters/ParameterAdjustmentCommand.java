package qupath.ext.demo.parameters;

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
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.converter.NumberStringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.processing.RoiLabeling;
import qupath.imagej.processing.SimpleThresholding;
import qupath.imagej.tools.IJTools;
import qupath.lib.analysis.stats.Histogram;
import qupath.lib.analysis.stats.RunningStatistics;
import qupath.lib.color.ColorMaps;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.gui.viewer.overlays.BufferedImageOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.parameters.ParameterChangeListener;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    private Map<ImageData<BufferedImage>, Future<?>> runningTasks = new ConcurrentHashMap<>();

    private Comparator<ImageData<?>> comparator = Comparator.comparing(ParameterAdjustmentCommand::getName)
            .thenComparing(ImageData::getServerPath);

    private ObservableMap<ImageData<?>, AnalysisResult> resultsMap = FXCollections.observableMap(
            new TreeMap<>(comparator));

    private Map<String, AnalysisResult> cachedResults = new ConcurrentHashMap<>();

    private Map<ImagePlus, FloatProcessor> noiseCache = new ConcurrentHashMap<>();

    private ExecutorService pool = Executors.newCachedThreadPool(ThreadTools.createThreadFactory("parameter-test", true));

    private XYChart.Series<Number, String> seriesCounts = new XYChart.Series<>();
    private XYChart.Series<Number, String> seriesMeanArea = new XYChart.Series<>();
    private XYChart.Series<Number, String> seriesMeanIntensity = new XYChart.Series<>();

    ParameterAdjustmentCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    private static String getName(ImageData<?> imageData) {
        if (imageData == null)
            return "";
        else
            return imageData.getServer().getMetadata().getName();
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

        params.addTitleParameter("Analysis parameters");
        params.addDoubleParameter("gaussianSigma",
                "Gaussian sigma",
                0.0,
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

        params.addDoubleParameter("threshold",
                "Threshold",
                1.0,
                "",
                0.0,
                255,
                "The absolute threshold value.");

        params.addBooleanParameter("doWatershed",
                "Watershed",
                false,
                "Apply a watershed transform to the smoothed image");

        params.addDoubleParameter("tolerance",
                "Tolerance",
                0.0,
                "",
                0.0,
                10.0,
                "The watershed tolerance");

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
        resultsMap.addListener((MapChangeListener<ImageData<?>, AnalysisResult>) change -> {
            table.getItems().setAll(resultsMap.values());
            updateBarCharts();
        });

        parameterPanel.addParameterChangeListener((a, b, c) -> {
            parameterPanel.setParameterEnabled("tolerance", params.getBooleanParameterValue("doWatershed"));
        });
//        resultsProperty.addListener((v, o, n) -> {
//            if (n != null) {
//                table.getItems().setAll(n);
//                n.imageData.getHierarchy().clearAll();
//                n.imageData.getHierarchy().addObjects(n.getObjects());
//            } else {
//                table.getItems().clear();
//            }
//        });

        BorderPane parameterPane = new BorderPane(parameterPanel.getPane());
        parameterPane.setPadding(new Insets(5.0));
        var separator = new Separator();
        parameterPane.setBottom(separator);
        separator.setPadding(new Insets(10.0));

        BorderPane pane = new BorderPane();
        pane.setTop(parameterPane);

//        var chartPane = new HBox(
//                createBarChart("Counts", seriesCounts),
//                createBarChart("Mean area", seriesMeanArea),
//                createBarChart("Mean intensity", seriesMeanIntensity)
//        );
//        for (var child : chartPane.getChildren()) {
//            HBox.setHgrow(child, Priority.ALWAYS);
//        }
//        chartPane.setFillHeight(true);
        var chartPane = new VBox(
                createBarChart("Counts", seriesCounts),
                createBarChart("Mean area", seriesMeanArea),
                createBarChart("Mean intensity", seriesMeanIntensity)
        );
        for (var child : chartPane.getChildren()) {
            VBox.setVgrow(child, Priority.ALWAYS);
        }
        chartPane.setFillWidth(true);
//        var splitPane = new SplitPane(
//                table,
//                chartPane
//                );
//        splitPane.setOrientation(Orientation.VERTICAL);
//        pane.setBottom(new BorderPane(splitPane));
        pane.setCenter(chartPane);
        pane.setPadding(new Insets(5.0));

        Button btnReset = new Button("Reset ranges");
        BorderPane.setAlignment(btnReset, Pos.CENTER);
        btnReset.setOnAction(e -> {
            seriesCounts.getChart().getXAxis().setAutoRanging(true);
            seriesMeanArea.getChart().getXAxis().setAutoRanging(true);
            seriesMeanIntensity.getChart().getXAxis().setAutoRanging(true);
        });
        pane.setBottom(btnReset);

        Stage stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle(title);
        stage.setScene(new Scene(pane));

        submitAnalysisTasks(params);

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
            resultsMap.clear();
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

    private Node createBarChart(String title, XYChart.Series<Number, String> series) {
        var xAxis = new CategoryAxis();
        var yAxis = new NumberAxis();
        BarChart<Number, String> barChart = new BarChart<>(yAxis, xAxis);
        barChart.setTitle(title);
        barChart.getData().add(series);
        barChart.setPrefHeight(120.0);
        barChart.setPrefWidth(120.0);
        barChart.setMinHeight(60);
        barChart.setMinWidth(60);
        barChart.setStyle("-fx-font-size: 11px;");
        barChart.setCategoryGap(0);
        barChart.setBarGap(2);
        barChart.setAnimated(false);
        barChart.setLegendVisible(false);
        barChart.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                yAxis.setAutoRanging(true);
//                yAxis.setAutoRanging(false);
            }
        });
        yAxis.setMinorTickVisible(false);
        yAxis.setTickMarkVisible(false);
//        yAxis.setAutoRanging(false);
//        yAxis.setLowerBound(0);

        // Need to retain reference?
        new SeriesAxisLimiter(series);

        return barChart;
//        var pane = new BorderPane(barChart);
//        var cbLock = new CheckBox("");
//        cbLock.setTooltip(new Tooltip("Lock bar length"));
//        yAxis.autoRangingProperty().bind(cbLock.selectedProperty().not());
//        pane.setRight(cbLock);
//        return pane;
    }

    private void updateBarCharts() {
        List<XYChart.Data<Number, String>> counts = new ArrayList<>();
        List<XYChart.Data<Number, String>> areas = new ArrayList<>();
        List<XYChart.Data<Number, String>> intensities = new ArrayList<>();
        int maxCounts = 0;
        double maxAreas = 0;
        double maxIntensities = 0;
        for (var entry : resultsMap.entrySet()) {
            var imageData = entry.getKey();
            var result = entry.getValue();
            var title = getName(imageData);
            var numObjects = result.numObjects.get();
            maxCounts = Math.max(maxCounts, numObjects);
            var meanArea = result.meanArea.get();
            maxAreas = Math.max(maxAreas, meanArea);
            var meanIntensity = result.meanIntensity.get();
            maxIntensities = Math.max(maxIntensities, meanIntensity);
            counts.add(new XYChart.Data<>(numObjects, title));
            areas.add(new XYChart.Data<>(meanArea, title));
            intensities.add(new XYChart.Data<>(meanIntensity, title));
        }
        seriesCounts.getData().setAll(counts);
//        expandAxisToMax((NumberAxis)seriesCounts.getChart().getXAxis(), maxCounts);

        seriesMeanArea.getData().setAll(areas);
//        expandAxisToMax((NumberAxis)seriesMeanArea.getChart().getXAxis(), maxAreas);

        seriesMeanIntensity.getData().setAll(intensities);
//        expandAxisToMax((NumberAxis)seriesMeanIntensity.getChart().getXAxis(), maxIntensities);

        setBarColors(seriesCounts);
        setBarColors(seriesMeanArea);
        setBarColors(seriesMeanIntensity);
    }

    private static void setBarColors(XYChart.Series<?, ?> series) {
        int count = 1;
        for (var data : series.getData()) {
            setBarColor(data, count);
            count++;
        }
    }

    private static void setBarColor(XYChart.Data<?, ?> data, int count) {
        var node = data.getNode();
        if (node != null) {
            node.setStyle("-fx-background-color: CHART_COLOR_" + (count % 8) + ";");
        }
    }


    private static void expandAxisToMax(NumberAxis axis, double maxValue) {
//        if (axis.autoRangingProperty().isBound())
//            return; // Can't set if bound
//        axis.setAutoRanging(false);
//        axis.setUpperBound(Math.max(axis.getUpperBound(), maxValue * 1.1));
    }


    private static <T> void setOrAdd(List<T> list, T item, int ind) {
        if (ind < list.size())
            list.set(ind, item);
        else
            list.add(item);
    }


    private void parameterChanged(ParameterList parameterList, String key, boolean isAdjusting) {
        submitAnalysisTasks(parameterList.duplicate());
    }

    private void submitAnalysisTasks(ParameterList parameterList) {
        for (var viewer : qupath.getViewers()) {
            submitAnalysisTask(viewer.getImageData(), parameterList);
        }
    }

    private void submitAnalysisTask(ImageData<BufferedImage> imageData, ParameterList parameterList) {
        if (imageData == null)
            return;
        var future = pool.submit(() -> {
            try {
                var result = runAnalysis(imageData, parameterList);
                if (result == null)
                    return;
                Platform.runLater(() -> {
                    imageData.getHierarchy().clearAll();
                    imageData.getHierarchy().addObjects(result.getObjects());
                    resultsMap.put(imageData, result);
                });
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });
        var previous = runningTasks.put(imageData, future);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
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
        double threshold = params.getDoubleParameterValue("threshold");
        boolean doWatershed = params.containsKey("doWatershed") ? params.getBooleanParameterValue("doWatershed") : true;
        double tolerance = params.getDoubleParameterValue("tolerance");

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

            for (var v : qupath.getViewers()) {
                if (v.getImageData() == imageData) {
                    var channel = v.getImageDisplay().availableChannels().get(0);
                    fp.setMinAndMax(channel.getMinDisplay(), channel.getMaxDisplay());
                    var overlay = new BufferedImageOverlay(v, fp.getBufferedImage());
                    if (noise > 0)
                        v.setCustomPixelLayerOverlay(overlay);
                    else
                        v.resetCustomPixelLayerOverlay();
                    break;
                }
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

//        ImageProcessor ipLabels = RoiLabeling.labelImage(fp, (float)threshold, connectivity == Connectivity.EIGHT_CONNECTED);

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

            if (Thread.interrupted())
                return null;

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


    static class SeriesAxisLimiter {

        private final XYChart.Series<Number, String> series;
        private final SortedSet<Double> values = new TreeSet<>(Comparator.reverseOrder());
        private final RunningStatistics stats = new RunningStatistics();

        SeriesAxisLimiter(XYChart.Series<Number, String> series) {
            this.series = series;
            this.series.getData().addListener(this::dataChanged);
        }

        private void dataChanged(ListChangeListener.Change<? extends XYChart.Data<Number, String>> c) {
            double max = getMax(series.getData()) * 1.1;
            NumberAxis axis = (NumberAxis) series.getChart().getXAxis();
            if (!Double.isFinite(max))
                return;
            double currentMax = 0;
            for (var data : series.getData()) {
                Number value = data.getXValue();
                if (value != null) {
                    double doubleVal = value.doubleValue();
                    values.add(doubleVal);
                    stats.addValue(doubleVal);
                    if (doubleVal > currentMax)
                        currentMax = doubleVal;
                }
            }
//            values.add(max);
            double preferredUpperBound = getPreferredUpperBound(2);
            axis.setAutoRanging(false);
            double upperBound = Math.max(preferredUpperBound, currentMax);
            axis.setUpperBound(upperBound);
            axis.setTickLabelFormatter(new NumberStringConverter("0.#"));
            axis.setTickUnit(upperBound / 10.0);
        }

        private double getPreferredUpperBound(double percentile) {
            if (values.isEmpty())
                return 0;
//            double threshold = stats.getMean() + stats.getStdDev() * 3;
//            System.err.println(threshold);
//            for (Double d : values) {
//                if (d <= threshold)
//                    return d;
//            }
//            return threshold;

            int ind = (int) (percentile / 100.0 * values.size());
            int count = 0;
            for (Double d : values) {
                if (count == ind)
                    return d;
                count++;
            }
            return values.last();
        }

        private Double getMax(List<? extends XYChart.Data<Number, String>> data) {
            return data.stream().mapToDouble(d -> d.getXValue().doubleValue()).max().orElse(Double.NaN);
        }


    }


}
