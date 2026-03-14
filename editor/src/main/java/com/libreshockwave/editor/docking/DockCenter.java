package com.libreshockwave.editor.docking;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The center node wrapping the JDesktopPane.
 * There is always exactly one DockCenter in the tree.
 * <p>
 * Supports optional center-docked tabs: when panels are docked to center,
 * they appear as tabs alongside a "Stage" tab containing the desktop.
 * When no center panels are docked, the desktop is shown directly (no tab bar).
 */
public final class DockCenter implements DockNode {

    private final JDesktopPane desktop;
    private final JPanel wrapper = new JPanel(new BorderLayout());
    private JTabbedPane tabs;
    private final List<String> panelTitles = new ArrayList<>();
    private DockingManager manager;

    public DockCenter(JDesktopPane desktop) {
        this.desktop = desktop;
        wrapper.add(desktop, BorderLayout.CENTER);
    }

    void setManager(DockingManager manager) {
        this.manager = manager;
    }

    @Override
    public JComponent getComponent() {
        return wrapper;
    }

    public JDesktopPane getDesktop() {
        return desktop;
    }

    /** Add a panel as a center tab. */
    public void addTab(String title, Container content) {
        panelTitles.add(title);

        if (tabs == null) {
            // Switch from bare desktop to tabbed mode
            wrapper.remove(desktop);
            tabs = new JTabbedPane(JTabbedPane.TOP);
            tabs.addTab("Stage", desktop);
            wrapper.add(tabs, BorderLayout.CENTER);
        }

        JPanel tabContent = new JPanel(new BorderLayout());
        tabContent.add(content, BorderLayout.CENTER);
        tabs.addTab(title, tabContent);
        tabs.setTabComponentAt(tabs.getTabCount() - 1, createTabLabel(title));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);

        wrapper.revalidate();
        wrapper.repaint();
    }

    /** Remove a center-docked tab. Returns the content container, or null. */
    public Container removeTab(String title) {
        int idx = panelTitles.indexOf(title);
        if (idx < 0) return null;

        // Tab index is idx+1 because tab 0 is always "Stage"
        int tabIdx = idx + 1;
        JPanel tabContent = (JPanel) tabs.getComponentAt(tabIdx);
        Container content = (Container) tabContent.getComponent(0);
        tabContent.remove(content);
        tabs.removeTabAt(tabIdx);
        panelTitles.remove(idx);

        if (panelTitles.isEmpty()) {
            // Switch back to bare desktop (no tab bar)
            wrapper.remove(tabs);
            tabs = null;
            wrapper.add(desktop, BorderLayout.CENTER);
            wrapper.revalidate();
            wrapper.repaint();
        }

        return content;
    }

    public boolean hasTab(String title) {
        return panelTitles.contains(title);
    }

    public void selectTab(String title) {
        if (tabs == null) return;
        int idx = panelTitles.indexOf(title);
        if (idx >= 0) {
            tabs.setSelectedIndex(idx + 1);
        }
    }

    public boolean hasCenterTabs() {
        return !panelTitles.isEmpty();
    }

    public java.util.List<String> getCenterTitles() {
        return java.util.List.copyOf(panelTitles);
    }

    /** Get content at the given center panel index. */
    public Container getContentAt(int index) {
        if (tabs == null || index < 0) return null;
        int tabIdx = index + 1;
        JPanel tabContent = (JPanel) tabs.getComponentAt(tabIdx);
        return (Container) tabContent.getComponent(0);
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
}
