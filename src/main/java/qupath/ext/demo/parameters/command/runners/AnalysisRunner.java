package qupath.ext.demo.parameters.command.runners;

import qupath.ext.demo.parameters.command.AnalysisResult;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.images.ImageData;
import qupath.lib.plugins.parameters.ParameterList;

import java.awt.image.BufferedImage;

public interface AnalysisRunner {

    /**
     * Create a parameter list accepted by this runner.
     * By default, this returns an empty list.
     * @return
     */
    default ParameterList createParameterList() {
        return new ParameterList();
    }

    /**
     * Optionally update the behavior of the parameter pane.
     * This can be used to enable/disable properties if required.
     * @param parameterPane
     * @return
     */
    default void updateParameterPane(ParameterPanelFX parameterPane) {}

    /**
     * Run the analysis.
     * @param imageData the image to analyse; this may or may not use any selected region of interest
     * @param params the parameters to use
     * @return the analysis result if run to completion, or null if it was interrupted (or failed)
     * @implSpec this must be thread-safe. It should also complete quickly for a responsive UI.
     */
    AnalysisResult runAnalysis(ImageData<BufferedImage> imageData, ParameterList params);

}
