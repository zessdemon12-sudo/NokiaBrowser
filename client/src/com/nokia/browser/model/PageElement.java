package com.nokia.browser.model;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Image;
import java.util.Vector;

/**
 * Represents a single visual web element adapted for 240x320 display.
 */
public class PageElement {
    public static final int TYPE_HEADING   = 1;
    public static final int TYPE_PARAGRAPH = 2;
    public static final int TYPE_QUOTE     = 3;
    public static final int TYPE_LIST_ITEM = 4;
    public static final int TYPE_LINK      = 5;
    public static final int TYPE_IMAGE     = 6;
    public static final int TYPE_AUDIO     = 7;
    public static final int TYPE_VIDEO     = 8;
    public static final int TYPE_HR        = 9;

    public int type;
    public String text;
    public String url;
    public int level; // 1-6 for headings
    public String extra; // Alt text or mime info
    public Image image;  // Decoded image if loaded

    // Layout attributes calculated for 240px screen
    public int y;
    public int height;
    public String[] lines;

    public PageElement(int type, String text, String url, int level, String extra) {
        this.type = type;
        this.text = text != null ? text : "";
        this.url = url != null ? url : "";
        this.level = level;
        this.extra = extra != null ? extra : "";
        this.y = 0;
        this.height = 0;
        this.lines = new String[0];
    }

    public boolean isSelectable() {
        return type == TYPE_LINK || type == TYPE_AUDIO || type == TYPE_VIDEO;
    }

    /**
     * Wrap text into lines that fit within maxLineWidth using given font.
     */
    public void calculateLayout(Font font, int maxLineWidth) {
        if (type == TYPE_HR) {
            height = 8;
            lines = new String[0];
            return;
        }

        if (type == TYPE_IMAGE) {
            if (image != null) {
                height = image.getHeight() + 6;
            } else {
                height = 24; // Placeholder height
            }
            return;
        }

        if (type == TYPE_AUDIO || type == TYPE_VIDEO) {
            // Media card height
            height = 36;
            return;
        }

        if (text == null || text.length() == 0) {
            height = 0;
            lines = new String[0];
            return;
        }

        int fontHeight = font.getHeight();
        int lineSpacing = (type == TYPE_HEADING) ? 4 : 2;

        // Fast-path: single-line check (majority of headings, menu items, short links)
        if (font.stringWidth(text) <= maxLineWidth) {
            lines = new String[] { text };
            if (type == TYPE_HEADING) {
                height = fontHeight + lineSpacing + 8;
            } else if (type == TYPE_QUOTE) {
                height = fontHeight + lineSpacing + 6;
            } else {
                height = fontHeight + lineSpacing + 4;
            }
            return;
        }

        // Multi-line word-level wrapping
        Vector lineVec = new Vector();
        int len = text.length();
        int start = 0;
        int prevSpace = -1;

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                int w = font.substringWidth(text, start, i - start);
                if (w > maxLineWidth) {
                    if (prevSpace > start) {
                        lineVec.addElement(text.substring(start, prevSpace));
                        start = prevSpace + 1;
                        prevSpace = i;
                    } else {
                        // Word itself exceeds line width; hard split via binary search
                        int breakIdx = findCharBreak(font, text, start, i, maxLineWidth);
                        lineVec.addElement(text.substring(start, breakIdx));
                        start = breakIdx;
                        i = start;
                        prevSpace = -1;
                    }
                } else {
                    prevSpace = i;
                }
            }
        }

        // Remainder after last space
        if (start < len) {
            int remW = font.substringWidth(text, start, len - start);
            if (remW > maxLineWidth) {
                if (prevSpace > start) {
                    lineVec.addElement(text.substring(start, prevSpace));
                    start = prevSpace + 1;
                }
                while (start < len) {
                    int w = font.substringWidth(text, start, len - start);
                    if (w <= maxLineWidth) {
                        lineVec.addElement(text.substring(start, len));
                        break;
                    } else {
                        int breakIdx = findCharBreak(font, text, start, len, maxLineWidth);
                        lineVec.addElement(text.substring(start, breakIdx));
                        start = breakIdx;
                    }
                }
            } else {
                lineVec.addElement(text.substring(start, len));
            }
        }

        lines = new String[lineVec.size()];
        for (int i = 0; i < lineVec.size(); i++) {
            lines[i] = (String) lineVec.elementAt(i);
        }

        if (type == TYPE_HEADING) {
            height = lines.length * (fontHeight + lineSpacing) + 8;
        } else if (type == TYPE_QUOTE) {
            height = lines.length * (fontHeight + lineSpacing) + 6;
        } else {
            height = lines.length * (fontHeight + lineSpacing) + 4;
        }
    }

    private static int findCharBreak(Font font, String text, int start, int end, int maxLineWidth) {
        int low = start + 1;
        int high = end;
        int best = start + 1;
        while (low <= high) {
            int mid = (low + high) / 2;
            int w = font.substringWidth(text, start, mid - start);
            if (w <= maxLineWidth) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }
}
