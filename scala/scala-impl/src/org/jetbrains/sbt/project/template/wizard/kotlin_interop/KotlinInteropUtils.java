package org.jetbrains.sbt.project.template.wizard.kotlin_interop;

import com.intellij.openapi.observable.properties.GraphProperty;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.dsl.builder.Cell;
import com.intellij.ui.layout.CellKt;
import com.intellij.ui.layout.PropertyBinding;
import com.intellij.ui.layout.ValidationInfoBuilder;
import kotlin.Unit;

import javax.swing.*;

public class KotlinInteropUtils {

    @SuppressWarnings("unchecked")
    public static <T, C extends ComboBox<T>> Cell<C> bindItem(Cell<C> cell, PropertyBinding<T> binding) {
        return cell.bind(
                component -> (T) component.getSelectedItem(),
                (component, value) -> {
                    component.setSelectedItem(value);
                    return Unit.INSTANCE;
                },
                binding
        );
    }

    public static <T, C extends ComboBox<T>> Cell<C> bindItem(Cell<C> cell, GraphProperty<T> property) {
        cell.getComponent().setSelectedItem(property.get());
        cell.graphProperty(property);
        CellKt.bind(
                cell.getComponent(),
                property
        );
        return cell;
    }

    public static <C extends JCheckBox> Cell<C> bind(Cell<C> cell, GraphProperty<Boolean> property) {
        cell.getComponent().setSelected(property.get());
        cell.graphProperty(property);
        CellKt.bind(
                cell.getComponent(),
                property
        );
        return cell;
    }


    public static <T extends JComponent> Cell<T> validationOnApply(Cell<T> cell, kotlin.jvm.functions.Function2<ValidationInfoBuilder, T, ValidationInfo> callback) {
        return cell.validationOnApply(callback);
    }

    public static <T extends JComponent> Cell<T> validationOnInput(Cell<T> cell, kotlin.jvm.functions.Function2<ValidationInfoBuilder, T, ValidationInfo> callback) {
        return cell.validationOnInput(callback);
    }
}