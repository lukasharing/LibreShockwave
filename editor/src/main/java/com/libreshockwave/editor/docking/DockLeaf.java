package com.libreshockwave.editor.docking;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A leaf node containing a JTabbedPane with one or more docked panels.
 * Multiple panels in the same leaf are shown as tabs (space-efficient).
 */
public final class DockLeaf implements DockNode {

    private final JTabbedPane tabs;
    private final List<String> panelTitles = new ArrayList<>();
    private DockingManager manager;

    public DockLeaf() {
        tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setMinimumSize(new Dimension(80, 60));
    }

    void setManager(DockingManager manager) {
        this.manager = manager;
    }

    @Override
    public JComponent getComponent() {
        return tabs;
    }

    public JTabbedPane getTabs() {
        return tabs;
    }

    public void addTab(String title, Container content) {
        panelTitles.add(title);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(content, BorderLayout.CENTER);
        tabs.addTab(title, wrapper);
        tabs.setTabComponentAt(tabs.getTabCount() - 1, createTabLabel(title));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
    }

    public void removeTab(String title) {
        int idx = panelTitles.indexOf(title);
        if (idx >= 0) {
            panelTitles.remove(idx);
            tabs.removeTabAt(idx);
        }
    }

    public boolean isEmpty() {
        return panelTitles.isEmpty();
    }

    public int getTabCount() {
        return panelTitles.size();
    }

    public List<String> getTitles() {
        return List.copyOf(panelTitles);
    }

    public boolean hasTab(String title) {
        return panelTitles.contains(title);
    }

    public void selectTab(String title) {
        int idx = panelTitles.indexOf(title);
        if (idx >= 0) {
            tabs.setSelectedIndex(idx);
        }
    }

    /**
     * Get the content wrapper at the given tab index.
     * The wrapper is a JPanel(BorderLayout) containing the actual content at CENTER.
     */
    public Container getContentAt(int index) {
        JPanel wrapper = (JPanel) tabs.getComponentAt(index);
        return (Container) wrapper.getComponent(0);
    }

    private JPanel createTabLabel(String title) {
        JPanel tab = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tab.setOpaque(false);
        tab.add(new JLabel(title));

        JButton closeBtn = new JButton("\u00d7");
        closeBtn.setMargin(new Insets(0, 2, 0, 2));
        closeBtn.setFont(closeBtn.getFont().deriveFont(11f));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusable(false);
        closeBtn.setToolTipText("Float");
        closeBtn.addActionListener(e -> {
            if (manager != null) manager.undockAndShow(title);
        });
        tab.add(closeBtn);

        return tab;
    }

    /**
     * Install right-click context menu on tabs for split/float/move operations.
     */
    void installContextMenu() {
        tabs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) { popup(e); }
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) { popup(e); }

            private void popup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger() || manager == null) return;
                int idx = tabs.indexAtLocation(e.getX(), e.getY());
                if (idx < 0) return;
                String title = tabs.getTitleAt(idx);
                manager.showTabContextMenu(DockLeaf.this, title, tabs, e.getX(), e.getY());
            }
        });
    }
}
