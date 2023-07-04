package qupath.ext.demo.parameters.command;

import javafx.collections.ListChangeListener;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.util.converter.NumberStringConverter;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Helper class to compute the x-axis upper limit for a series.
 * This is intended for use with a horizontal bar chart, where it attempts to set the upper limit based upon the maximum
 * while also recovering in the event that an extreme value occurs (i.e. to avoid getting stuck with unusable limits).
 */
class SeriesAxisLimiter {

    private static int MAX_VALUES = 1000;

    private final XYChart.Series<Number, String> series;

    private final SortedSet<Double> values = new TreeSet<>(Comparator.reverseOrder());

    private double percentile;

    SeriesAxisLimiter(XYChart.Series<Number, String> series) {
        this(series, 5.0);
    }

    SeriesAxisLimiter(XYChart.Series<Number, String> series, double percentile) {
        this.series = series;
        this.series.getData().addListener(this::dataChanged);
        this.percentile = percentile;
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
                if (doubleVal > currentMax)
                    currentMax = doubleVal;
            }
        }
        double preferredUpperBound = getPreferredUpperBound(percentile);
        axis.setAutoRanging(false);
        double upperBound = Math.max(preferredUpperBound, currentMax);
        axis.setUpperBound(upperBound);
        axis.setTickLabelFormatter(new NumberStringConverter("0.#"));
        axis.setTickUnit(upperBound / 10.0);

        // Drop samples to avoid performance issues
        while (values.size() > MAX_VALUES)
            dropSamples(values, upperBound);
    }

    private double getPreferredUpperBound(double percentile) {
        if (values.isEmpty())
            return 0;

        int ind = (int) (percentile / 100.0 * values.size());
        int count = 0;
        for (Double d : values) {
            if (count == ind) {
                double corrected = Math.pow(10.0, Math.floor(Math.log10(d)));
                double result = Math.ceil(d / corrected) * corrected;
//                System.err.println("Corrected: " + corrected + ", result: " + result + ", d: " + d);
                return result;
            }
            count++;
        }
        return values.last();
    }

    private Double getMax(List<? extends XYChart.Data<Number, String>> data) {
        return data.stream().mapToDouble(d -> d.getXValue().doubleValue()).max().orElse(Double.NaN);
    }

    private static void dropSamples(Collection<Double> values, Double keepValue) {
        var iterator = values.iterator();
        int count = 0;
        while (iterator.hasNext()) {
            Double val = iterator.next();
            if (count % 2 != 0 && !Objects.equals(val, keepValue))
                iterator.remove();
            count++;
        }
    }


}
