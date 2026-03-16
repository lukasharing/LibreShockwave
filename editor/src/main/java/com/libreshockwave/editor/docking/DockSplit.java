package com.libreshockwave.editor.docking;

import javax.swing.*;

/**
 * A binary split node containing two children separated by a JSplitPane.
 * The orientation is either HORIZONTAL_SPLIT (left/right) or VERTICAL_SPLIT (top/bottom).
 */
public final class DockSplit implements DockNode {

    private DockNode first;
    private DockNode second;
    private final int orientation;
    private final JSplitPane splitPane;

    private static final int DIVIDER_SIZE = Math.max(UIManager.getInt("SplitPane.dividerSize"), 4);

    public DockSplit(DockNode first, DockNode second, int orientation, double dividerFraction) {
        this.first = first;
        this.second = second;
        this.orientation = orientation;

        splitPane = new JSplitPane(orientation, first.getComponent(), second.getComponent());
        splitPane.setContinuousLayout(true);
        splitPane.setDividerSize(DIVIDER_SIZE);

        // Set resize weight so the "first" side gets the fraction
        splitPane.setResizeWeight(dividerFraction);

        // Defer divider location to after layout
        SwingUtilities.invokeLater(() -> {
            int total = orientation == JSplitPane.HORIZONTAL_SPLIT
                ? splitPane.getWidth() : splitPane.getHeight();
            if (total > 0) {
                splitPane.setDividerLocation((int) (total * dividerFraction));
            }
        });
    }

    @Override
    public JComponent getComponent() {
        return splitPane;
    }

    public DockNode getFirst() {
        return first;
    }

    public DockNode getSecond() {
        return second;
    }

    public int getOrientation() {
        return orientation;
    }

    public JSplitPane getSplitPane() {
        return splitPane;
    }

    public void setFirst(DockNode node) {
        this.first = node;
        splitPane.setLeftComponent(node.getComponent());
    }

    public void setSecond(DockNode node) {
        this.second = node;
        splitPane.setRightComponent(node.getComponent());
    }

    /** Get the current divider position as a fraction (0.0–1.0). */
    public double getDividerFraction() {
        int total = orientation == JSplitPane.HORIZONTAL_SPLIT
            ? splitPane.getWidth() : splitPane.getHeight();
        if (total <= 0) return 0.5;
        return (double) splitPane.getDividerLocation() / total;
    }
}
