package qupath.ext.demo.parameters.command;

import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Tooltip;

import java.util.Objects;

/**
 * Utility functions for bar-chart-related activities.
 */
public class Utils {


    public static BarChart<Number, String> createHorizontalBarChart(String title, XYChart.Series<Number, String> series) {
        var xAxis = new CategoryAxis();
        var yAxis = new NumberAxis();
        BarChart<Number, String> barChart = new BarChart<>(yAxis, xAxis);
        barChart.setTitle(title);
        barChart.getData().add(series);
        barChart.setPrefHeight(120.0);
        barChart.setPrefWidth(120.0);
        barChart.setMinHeight(60);
        barChart.setMinWidth(60);
//        barChart.setStyle("-fx-font-size: 11px;");
        barChart.setCategoryGap(0);
        barChart.setBarGap(2);
        barChart.setAnimated(false);
        barChart.setLegendVisible(false);
        barChart.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                yAxis.setAutoRanging(true);
            }
        });
        yAxis.setMinorTickVisible(false);
        yAxis.setTickMarkVisible(false);

        // Need to retain reference?
        new SeriesAxisLimiter(series);

        return barChart;
    }


    /**
     * Set the colors of the bars in a series to the default chart colors.
     * This is useful if a single series is used with multiple data items, rather than multiple series.
     * @param series
     * @implNote this does nothing if the node has not yet been set for the data items.
     */
    public static void setBarColors(XYChart.Series<?, ?> series) {
        int count = 1;
        for (var data : series.getData()) {
            setBarColor(data, count);
            count++;
        }
    }

    /**
     * Set the color of a bar in a series to the default chart colors, based upon the index
     * of the data.
     * This does nothing if the node is not set.
     * @param data
     * @param count
     * @implNote this does nothing if the node has not yet been set for the data item.
     */
    public static void setBarColor(XYChart.Data<?, ?> data, int count) {
        var node = data.getNode();
        if (node != null) {
            node.setStyle("-fx-background-color: CHART_COLOR_" + (count % 8) + ";");
        }
    }


    /**
     * Set a tooltip for the node of each data item in a series to the x-value of the data item.
     * @param series
     * @implNote this does nothing if the node has not yet been set for the data items.
     */
    public static void setDataTooltipToXValue(XYChart.Series<?, ?> series) {
        for (var data : series.getData()) {
            setTooltipToXValue(data);
        }
    }

    /**
     * Set a tooltip for the node of each data item in a series to the x-value of the data item.
     * @param data
     * @implNote this does nothing if the node has not yet been set for the data items.
     */
    public static void setTooltipToXValue(XYChart.Data<?, ?> data) {
        var node = data.getNode();
        if (node != null) {
            var val = data.getXValue();
            Tooltip tooltip = new Tooltip(Objects.toString(val));
            Tooltip.install(node, tooltip);
        }
    }

    /**
     * Set a tooltip for the node of each data item in a series to the y-value of the data item.
     * @param series
     * @implNote this does nothing if the node has not yet been set for the data items.
     */
    public static void setDataTooltipToYValue(XYChart.Series<?, ?> series) {
        for (var data : series.getData()) {
            setTooltipToYValue(data);
        }
    }

    /**
     * Set a tooltip for the node of each data item in a series to the y-value of the data item.
     * @param data
     * @implNote this does nothing if the node has not yet been set for the data items.
     */
    public static void setTooltipToYValue(XYChart.Data<?, ?> data) {
        var node = data.getNode();
        if (node != null) {
            var val = data.getXValue();
            Tooltip tooltip = new Tooltip(Objects.toString(val));
            Tooltip.install(node, tooltip);
        }
    }


}
