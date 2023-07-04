package qupath.ext.demo.parameters.command;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.analysis.stats.Histogram;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper class for representing the result of a simple image analysis task.
 * Currently, this provides a list of PathObjects and some basic statistics.
 */
public class AnalysisResult {

    // TODO: Either use these or remove them
    private final ImageData<BufferedImage> imageData;
    private final String params;

    private final List<PathObject> pathObjects;

    private final IntegerProperty numObjects;

    private StringProperty title;

    private ObjectProperty<Histogram> histogramArea;
    private ObjectProperty<Histogram> histogramIntensity;
    private final DoubleProperty meanArea;
    private final DoubleProperty meanIntensity;

    public AnalysisResult(ImageData<BufferedImage> imageData, String params, List<PathObject> pathObjects) {
        this.imageData = imageData;
        this.params = params;
        this.pathObjects = Collections.unmodifiableList(new ArrayList<>(pathObjects));

        this.title = new SimpleStringProperty(imageData.getServer().getMetadata().getName());
        this.numObjects = new SimpleIntegerProperty(pathObjects.size());

        int nBins = 16;
        Histogram histIntensity = Histogram.makeMeasurementHistogram(pathObjects, "Mean", nBins);
        this.histogramIntensity = new SimpleObjectProperty<>(histIntensity);
        this.meanIntensity = histIntensity == null ? new SimpleDoubleProperty(Double.NaN) : new SimpleDoubleProperty(histIntensity.getMeanValue());

        Histogram histArea = Histogram.makeMeasurementHistogram(pathObjects, "Area", nBins);
        this.histogramArea = new SimpleObjectProperty<>(histArea);
        this.meanArea = histArea == null ? new SimpleDoubleProperty(Double.NaN) : new SimpleDoubleProperty(histArea.getMeanValue());
    }

    public List<PathObject> getObjects() {
        return pathObjects;
    }

    public ReadOnlyStringProperty titleProperty() {
        return title;
    }

    public ReadOnlyIntegerProperty numObjectsProperty() {
        return numObjects;
    }

    public ReadOnlyDoubleProperty meanIntensityProperty() {
        return meanIntensity;
    }

    public ReadOnlyDoubleProperty meanAreaProperty() {
        return meanArea;
    }

    public ReadOnlyObjectProperty<Histogram> histogramAreaProperty() {
        return histogramArea;
    }

    public ReadOnlyObjectProperty<Histogram> histogramIntensityProperty() {
        return histogramIntensity;
    }

}
