package qupath.ext.demo.parameters;

import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.demo.parameters.command.ParameterAdjustmentCommand;
import qupath.ext.demo.parameters.command.runners.ThresholdWatershedRunner;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;


/**
 * This is a simple extension to demonstrate how changing parameters can impact the results of
 * image analysis.
 * <p>
 * It uses a simple image processing workflow, intended for use on 8-bit single-channel images
 * (e.g. to detect cell nuclei).
 */
public class ParameterDemoExtension implements QuPathExtension {
	
	private final static Logger logger = LoggerFactory.getLogger(ParameterDemoExtension.class);

	private final static String EXTENSION_NAME = "Parameter demo";

	private final static String EXTENSION_DESCRIPTION = "This is a demo to show how image processing parameters impact";

	private final static Version EXTENSION_QUPATH_VERSION = Version.parse("v0.4.3");

	private boolean isInstalled = false;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addMenuItem(qupath);
	}

	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		MenuItem menuItem = new MenuItem("Threshold parameter demo");
		var command = new ParameterAdjustmentCommand(qupath, () -> new ThresholdWatershedRunner());
		menuItem.setOnAction(e -> command.run());
		menu.getItems().add(menuItem);
	}
	
	
	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}
	
	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}

}
