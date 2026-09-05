package com.nokia.browser.model;

import javax.microedition.lcdui.Font;
import java.util.Vector;

/**
 * Parsed and laid-out representation of a web page for 240x320 display.
 */
public class WebPage {
    public String title;
    public String url;
    public boolean isHttps;
    public Vector elements;
    public int totalHeight;

    public WebPage() {
        this.title = "Untitled";
        this.url = "";
        this.isHttps = false;
        this.elements = new Vector();
        this.totalHeight = 0;
    }

    public void addElement(PageElement el) {
        elements.addElement(el);
    }

    public int getElementCount() {
        return elements.size();
    }

    public PageElement getElement(int index) {
        if (index >= 0 && index < elements.size()) {
            return (PageElement) elements.elementAt(index);
        }
        return null;
    }

    /**
     * Compute visual layout for 240x320 screen geometry.
     * Content width is 240 minus 8px padding = 232px.
     */
    public void performLayout(Font regularFont, Font boldFont, Font titleFont, int screenWidth) {
        int contentWidth = screenWidth - 8;
        int currentY = 4;

        int size = elements.size();
        for (int i = 0; i < size; i++) {
            PageElement el = (PageElement) elements.elementAt(i);
            el.y = currentY;

            Font f = regularFont;
            if (el.type == PageElement.TYPE_HEADING) {
                f = (el.level <= 2) ? titleFont : boldFont;
            } else if (el.type == PageElement.TYPE_LINK) {
                f = boldFont;
            } else if (el.type == PageElement.TYPE_QUOTE) {
                contentWidth = screenWidth - 16; // Indented quote
            } else {
                contentWidth = screenWidth - 8;
            }

            el.calculateLayout(f, contentWidth);
            currentY += el.height;
        }

        totalHeight = currentY + 10;
    }

    /**
     * Incrementally updates element height and shifts downstream elements' Y coordinates.
     * Avoids expensive full page re-layouts when images finish downloading.
     */
    public void updateElementHeight(int elementIndex, int newHeight) {
        if (elementIndex < 0 || elementIndex >= elements.size()) return;
        PageElement target = (PageElement) elements.elementAt(elementIndex);
        int delta = newHeight - target.height;
        target.height = newHeight;
        if (delta != 0) {
            int size = elements.size();
            for (int i = elementIndex + 1; i < size; i++) {
                PageElement el = (PageElement) elements.elementAt(i);
                el.y += delta;
            }
            totalHeight += delta;
        }
    }

    /**
     * Finds next selectable element (link/media) index after currentIndex.
     */
    public int getNextSelectable(int currentIndex) {
        int size = elements.size();
        for (int i = currentIndex + 1; i < size; i++) {
            PageElement el = (PageElement) elements.elementAt(i);
            if (el.isSelectable()) {
                return i;
            }
        }
        return currentIndex;
    }

    /**
     * Finds previous selectable element (link/media) before currentIndex.
     */
    public int getPrevSelectable(int currentIndex) {
        for (int i = currentIndex - 1; i >= 0; i--) {
            PageElement el = (PageElement) elements.elementAt(i);
            if (el.isSelectable()) {
                return i;
            }
        }
        return currentIndex;
    }

    /**
     * Finds first selectable element on the page.
     */
    public int getFirstSelectable() {
        int size = elements.size();
        for (int i = 0; i < size; i++) {
            PageElement el = (PageElement) elements.elementAt(i);
            if (el.isSelectable()) {
                return i;
            }
        }
        return -1;
    }
}
