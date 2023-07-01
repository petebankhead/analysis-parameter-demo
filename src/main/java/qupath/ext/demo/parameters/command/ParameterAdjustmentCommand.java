package qupath.ext.demo.parameters.command;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.demo.parameters.command.runners.AnalysisRunner;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.images.ImageData;
import qupath.lib.plugins.parameters.ParameterChangeListener;
import qupath.lib.plugins.parameters.ParameterList;

import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

public class ParameterAdjustmentCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ParameterAdjustmentCommand.class);

    private static final String title = "Parameter demo";

    private QuPathGUI qupath;

    private Stage stage;

    private ParameterChangeListener parameterChangeListener = this::parameterChanged;

    private Map<ImageData<BufferedImage>, Future<?>> runningTasks = new ConcurrentHashMap<>();

    private Comparator<ImageData<?>> comparator = Comparator.comparing(ParameterAdjustmentCommand::getName)
            .thenComparing(ImageData::getServerPath);

    private ObservableMap<ImageData<?>, AnalysisResult> resultsMap = FXCollections.observableMap(
            new TreeMap<>(comparator));

    private ExecutorService pool;

    private XYChart.Series<Number, String> seriesCounts = new XYChart.Series<>();
    private XYChart.Series<Number, String> seriesMeanArea = new XYChart.Series<>();
    private XYChart.Series<Number, String> seriesMeanIntensity = new XYChart.Series<>();

    private Supplier<AnalysisRunner> analysisRunnerSupplier;
    private AnalysisRunner runner;

    public ParameterAdjustmentCommand(QuPathGUI qupath, Supplier<AnalysisRunner> analysisRunnerSupplier) {
        this.qupath = qupath;
        this.analysisRunnerSupplier = analysisRunnerSupplier;
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

        this.runner = analysisRunnerSupplier.get();
        Objects.requireNonNull(runner, "Analysis runner cannot be null");
        pool = Executors.newCachedThreadPool(ThreadTools.createThreadFactory("parameter-test", true));

        var params = runner.createParameterList();

        var parameterPanel = new ParameterPanelFX(params);
        parameterPanel.addParameterChangeListener(parameterChangeListener);
        runner.updateParameterPane(parameterPanel);

        BorderPane parameterPane = new BorderPane(parameterPanel.getPane());
        parameterPane.setPadding(new Insets(5.0));
        var separator = new Separator();
        parameterPane.setBottom(separator);
        separator.setPadding(new Insets(10.0));

        BorderPane pane = new BorderPane();
        pane.setTop(parameterPane);

        var table = createTable();
        var chartPane = createBarChartPane();

        TabPane tabPane = new TabPane();
        tabPane.getTabs().setAll(
                new Tab("Table", table),
                new Tab("Charts", chartPane)
        );
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setSide(Side.BOTTOM);
        pane.setCenter(tabPane);
        pane.setPadding(new Insets(5.0));

        resultsMap.addListener((MapChangeListener<ImageData<?>, AnalysisResult>) change -> {
            table.getItems().setAll(resultsMap.values());
            updateBarCharts();
        });

        Stage stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle(title);
        stage.setScene(new Scene(pane));

        submitAnalysisTasks(params);

        stage.setOnCloseRequest(e -> {
            parameterPanel.removeParameterChangeListener(parameterChangeListener);
            stage.close();
            this.stage = null;
            resultsMap.clear();
            this.runner = null;
            pool.shutdownNow();
        });
        return stage;
    }


    private Pane createBarChartPane() {
        VBox box = new VBox(
                Utils.createHorizontalBarChart("Counts", seriesCounts),
                Utils.createHorizontalBarChart("Mean area", seriesMeanArea),
                Utils.createHorizontalBarChart("Mean intensity", seriesMeanIntensity)
        );
        for (var child : box.getChildren()) {
            VBox.setVgrow(child, Priority.ALWAYS);
        }
        box.setFillWidth(true);

        var pane = new BorderPane(box);
        var btnRanges = createResetRangesButton();
        pane.setBottom(btnRanges);
        pane.setPadding(new Insets(5.0));
        return pane;
    }

    private Button createResetRangesButton() {
        var btnReset = new Button("Reset ranges");
        BorderPane.setAlignment(btnReset, Pos.CENTER);
        btnReset.setOnAction(e -> {
            seriesCounts.getChart().getXAxis().setAutoRanging(true);
            seriesMeanArea.getChart().getXAxis().setAutoRanging(true);
            seriesMeanIntensity.getChart().getXAxis().setAutoRanging(true);
        });
        return btnReset;
    }

    private TableView<AnalysisResult> createTable() {
        var df = new DecimalFormat("0.0");

        var table = new TableView<AnalysisResult>();
        var colTitle = new TableColumn<AnalysisResult, String>("Image");
        colTitle.setCellValueFactory(v -> v.getValue().titleProperty());

        var colNumObjects = new TableColumn<AnalysisResult, Number>("Count");
        colNumObjects.setCellValueFactory(v -> v.getValue().numObjectsProperty());

        var colMeanArea = new TableColumn<AnalysisResult, Number>("Mean area");
        colMeanArea.setCellValueFactory(v -> v.getValue().meanAreaProperty());
        colMeanArea.setCellFactory(v -> new NumberTableCell<>(df));

        var colMeanIntensity = new TableColumn<AnalysisResult, Number>("Mean intensity");
        colMeanIntensity.setCellValueFactory(v -> v.getValue().meanIntensityProperty());
        colMeanIntensity.setCellFactory(v -> new NumberTableCell<>(df));

        table.getColumns().setAll(
                colTitle, colNumObjects, colMeanArea, colMeanIntensity
        );
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPadding(new Insets(5.0));
        return table;
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
            var numObjects = result.numObjectsProperty().get();
            maxCounts = Math.max(maxCounts, numObjects);
            counts.add(new XYChart.Data<>(numObjects, title));

            var meanArea = result.meanAreaProperty().get();
            maxAreas = Math.max(maxAreas, meanArea);
            areas.add(new XYChart.Data<>(meanArea, title));

            var meanIntensity = result.meanIntensityProperty().get();
            maxIntensities = Math.max(maxIntensities, meanIntensity);
            intensities.add(new XYChart.Data<>(meanIntensity, title));
        }
        seriesCounts.getData().setAll(counts);
        Utils.setBarColors(seriesCounts);
        Utils.setDataTooltipToXValue(seriesCounts);

        seriesMeanArea.getData().setAll(areas);
        Utils.setBarColors(seriesMeanArea);
        Utils.setDataTooltipToXValue(seriesMeanArea);

        seriesMeanIntensity.getData().setAll(intensities);
        Utils.setBarColors(seriesMeanIntensity);
        Utils.setDataTooltipToXValue(seriesMeanIntensity);
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
                var result = runner.runAnalysis(imageData, parameterList);
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

}
