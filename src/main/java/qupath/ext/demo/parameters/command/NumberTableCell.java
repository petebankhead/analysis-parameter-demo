package qupath.ext.demo.parameters.command;

import javafx.scene.control.TableCell;

import java.text.NumberFormat;

/**
 * A TableCell that displays a Number using a NumberFormat.
 * @param <T>
 */
public class NumberTableCell<T> extends TableCell<T, Number> {

    private NumberFormat format;

    NumberTableCell(NumberFormat format) {
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
