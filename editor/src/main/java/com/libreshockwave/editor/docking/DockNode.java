package com.libreshockwave.editor.docking;

import javax.swing.*;

/**
 * A node in the recursive dock layout tree.
 * The layout is a binary tree where splits contain two children
 * separated by a JSplitPane, leaves contain tabbed panels,
 * and the center holds the JDesktopPane.
 */
public sealed interface DockNode permits DockSplit, DockLeaf, DockCenter {
    JComponent getComponent();
}
