package com.nokia.browser.ui;

import com.nokia.browser.BrowserMIDlet;
import com.nokia.browser.model.PageElement;
import com.nokia.browser.model.WebPage;
import com.nokia.browser.storage.StorageManager;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;
import java.util.Vector;

/**
 * 240x320 QVGA & 320x240 Landscape Rendering Engine and Interactive Canvas for Nokia J2ME.
 */
public class BrowserCanvas extends Canvas {

    private BrowserMIDlet midlet;
    private StorageManager storage;
    private WebPage page;

    // Fonts
    private Font regularFont;
    private Font boldFont;
    private Font titleFont;
    private Font smallFont;
    private Font smallBoldFont;

    // Viewport & Scrolling
    private int scrollY;
    private int maxScrollY;
    private int selectedElementIndex;
    private boolean isFullscreen;

    // Software Rotation Offscreen Buffers (for 240x320 portrait devices rotated to landscape)
    private Image offscreenBuffer;
    private Graphics offscreenGraphics;

    // Loading status
    private boolean isLoading;
    private String loadingStatus;
    private int loadingProgress;

    // History stack for Back/Forward
    private Vector historyStack;
    private int historyIndex;
    private int imageLoadSessionId;

    public BrowserCanvas(BrowserMIDlet midlet, StorageManager storage) {
        setFullScreenMode(true);
        this.midlet = midlet;
        this.storage = storage;

        initFonts(storage.getFontSize());

        this.scrollY = 0;
        this.maxScrollY = 0;
        this.selectedElementIndex = -1;
        this.isFullscreen = false;

        this.isLoading = false;
        this.loadingStatus = "";
        this.loadingProgress = 0;

        this.historyStack = new Vector();
        this.historyIndex = -1;

        // Default empty page
        this.page = new WebPage();
    }

    public boolean isLandscape() {
        int o = storage.getOrientation();
        if (o == StorageManager.ORIENTATION_LANDSCAPE) return true;
        if (o == StorageManager.ORIENTATION_PORTRAIT) return false;
        return getWidth() > getHeight();
    }

    public boolean isSoftwareRotation() {
        return isLandscape() && (getWidth() < getHeight());
    }

    public int getLogicalWidth() {
        if (isSoftwareRotation()) {
            return getHeight() > 0 ? getHeight() : 320;
        }
        return getWidth() > 0 ? getWidth() : 240;
    }

    public int getLogicalHeight() {
        if (isSoftwareRotation()) {
            return getWidth() > 0 ? getWidth() : 240;
        }
        return getHeight() > 0 ? getHeight() : 320;
    }

    protected void sizeChanged(int w, int h) {
        relayoutPage();
    }

    public void relayoutPage() {
        if (regularFont != null && boldFont != null && titleFont != null) {
            int lw = getLogicalWidth();
            if (page != null) {
                page.performLayout(regularFont, boldFont, titleFont, lw);
            }
            calculateMaxScroll();
            if (selectedElementIndex >= 0 && page != null) {
                ensureElementVisible(selectedElementIndex);
            }
        }
        repaint();
    }

    public void initFonts(int size) {
        int lcduiSize;
        if (size == 0) lcduiSize = Font.SIZE_SMALL;
        else if (size == 2) lcduiSize = Font.SIZE_LARGE;
        else lcduiSize = Font.SIZE_MEDIUM;

        regularFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, lcduiSize);
        boldFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, lcduiSize);
        titleFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
        smallFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        smallBoldFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);

        if (page != null) {
            page.performLayout(regularFont, boldFont, titleFont, getLogicalWidth());
            calculateMaxScroll();
        }
    }

    public void setLoading(boolean loading, String status) {
        this.isLoading = loading;
        this.loadingStatus = status != null ? status : "";
        if (loading) {
            this.loadingProgress = (loadingProgress + 25) % 100;
        }
        repaint();
    }

    public void setPage(WebPage newPage, boolean addToHistory) {
        this.page = newPage;
        this.isLoading = false;
        this.loadingStatus = "";
        this.scrollY = 0;

        int w = getLogicalWidth();
        page.performLayout(regularFont, boldFont, titleFont, w);
        calculateMaxScroll();

        // Select first selectable element (link or media)
        selectedElementIndex = page.getFirstSelectable();

        if (addToHistory && newPage.url != null && newPage.url.length() > 0) {
            while (historyStack.size() > historyIndex + 1) {
                historyStack.removeElementAt(historyStack.size() - 1);
            }
            historyStack.addElement(newPage.url);
            historyIndex = historyStack.size() - 1;
        }

        repaint();

        final int currentSession = ++imageLoadSessionId;
        startImageLoading(newPage, currentSession);
    }

    private void startImageLoading(final WebPage targetPage, final int sessionId) {
        if (!storage.isLoadImages() || targetPage == null) return;

        Thread loader = new Thread(new Runnable() {
            public void run() {
                int count = targetPage.getElementCount();
                for (int i = 0; i < count; i++) {
                    if (imageLoadSessionId != sessionId) {
                        return; // Navigated away, stop loading
                    }
                    PageElement el = targetPage.getElement(i);
                    if (el != null && el.type == PageElement.TYPE_IMAGE && el.image == null && el.url != null && el.url.length() > 0) {
                        Image img = midlet.getNetwork().fetchImage(el.url);
                        if (img != null) {
                            el.image = img;
                            final int elemIdx = i;
                            final int newHeight = img.getHeight() + 6;
                            midlet.getDisplay().callSerially(new Runnable() {
                                public void run() {
                                    if (imageLoadSessionId == sessionId && page == targetPage) {
                                        targetPage.updateElementHeight(elemIdx, newHeight);
                                        calculateMaxScroll();
                                        repaint();
                                    }
                                }
                            });
                        }
                    }
                }
            }
        });
        loader.setPriority(Thread.MIN_PRIORITY);
        loader.start();
    }

    public WebPage getPage() {
        return page;
    }

    private void calculateMaxScroll() {
        int vh = getViewportHeight();
        if (page != null && page.totalHeight > vh) {
            maxScrollY = page.totalHeight - vh;
        } else {
            maxScrollY = 0;
        }
    }

    private int getHeaderHeight() {
        if (isFullscreen) return 0;
        return isLandscape() ? 20 : 22;
    }

    private int getFooterHeight() {
        if (isFullscreen) return 0;
        return isLandscape() ? 18 : 20;
    }

    private int getViewportHeight() {
        return getLogicalHeight() - getHeaderHeight() - getFooterHeight();
    }

    public void historyBack() {
        if (historyIndex > 0) {
            historyIndex--;
            String prevUrl = (String) historyStack.elementAt(historyIndex);
            midlet.loadUrl(prevUrl, false);
        }
    }

    public void historyForward() {
        if (historyIndex < historyStack.size() - 1) {
            historyIndex++;
            String nextUrl = (String) historyStack.elementAt(historyIndex);
            midlet.loadUrl(nextUrl, false);
        }
    }

    protected void keyPressed(int keyCode) {
        int gameAction = 0;
        try {
            gameAction = getGameAction(keyCode);
        } catch (Exception e) {}

        // In software landscape rotation (holding phone sideways with keypad on right):
        // Physical UP points LEFT visually, physical DOWN points RIGHT visually,
        // physical LEFT points DOWN visually, physical RIGHT points UP visually.
        if (isSoftwareRotation()) {
            int transCode = keyCode;
            int transAction = gameAction;
            if (gameAction == UP || keyCode == -1) {
                transCode = -3;
                transAction = LEFT;
            } else if (gameAction == DOWN || keyCode == -2) {
                transCode = -4;
                transAction = RIGHT;
            } else if (gameAction == LEFT || keyCode == -3) {
                transCode = -2;
                transAction = DOWN;
            } else if (gameAction == RIGHT || keyCode == -4) {
                transCode = -1;
                transAction = UP;
            }
            keyCode = transCode;
            gameAction = transAction;
        }

        // Left Softkey: Menu / Options
        if (keyCode == -6) {
            midlet.showOptionsMenu();
            return;
        }

        // Right Softkey: Back / History back
        if (keyCode == -7) {
            if (historyIndex > 0) {
                historyBack();
            } else {
                midlet.exitBrowser();
            }
            return;
        }

        // Star (*): Toggle Fullscreen
        if (keyCode == '*') {
            isFullscreen = !isFullscreen;
            calculateMaxScroll();
            repaint();
            return;
        }

        // Hash (#): Quick Search / URL entry
        if (keyCode == '#') {
            midlet.showAddressDialog(page != null ? page.url : "");
            return;
        }

        // Key 0: Quick Bookmarks
        if (keyCode == '0') {
            midlet.showBookmarks();
            return;
        }

        // Key 1: Page Up
        if (keyCode == '1') {
            scrollBy(-(getViewportHeight() - 20));
            return;
        }

        // Key 7: Page Down
        if (keyCode == '7') {
            scrollBy(getViewportHeight() - 20);
            return;
        }

        // Key 3: Top
        if (keyCode == '3') {
            scrollY = 0;
            selectedElementIndex = page.getFirstSelectable();
            repaint();
            return;
        }

        // Key 9: Bottom
        if (keyCode == '9') {
            scrollY = maxScrollY;
            repaint();
            return;
        }

        // Key 2: Scroll Up line
        if (keyCode == '2') {
            scrollBy(-24);
            return;
        }

        // Key 8: Scroll Down line
        if (keyCode == '8') {
            scrollBy(24);
            return;
        }

        // Key 4: History Back
        if (keyCode == '4') {
            historyBack();
            return;
        }

        // Key 6: History Forward
        if (keyCode == '6') {
            historyForward();
            return;
        }

        // D-Pad UP: Navigate links upwards or scroll
        if (gameAction == UP || keyCode == -1) {
            int prev = page.getPrevSelectable(selectedElementIndex);
            if (prev != selectedElementIndex) {
                selectedElementIndex = prev;
                ensureElementVisible(selectedElementIndex);
            } else {
                scrollBy(-30);
            }
            repaint();
            return;
        }

        // D-Pad DOWN: Navigate links downwards or scroll
        if (gameAction == DOWN || keyCode == -2) {
            int next = page.getNextSelectable(selectedElementIndex);
            if (next != selectedElementIndex) {
                selectedElementIndex = next;
                ensureElementVisible(selectedElementIndex);
            } else {
                scrollBy(30);
            }
            repaint();
            return;
        }

        // D-Pad LEFT: Scroll Up
        if (gameAction == LEFT || keyCode == -3) {
            scrollBy(-50);
            return;
        }

        // D-Pad RIGHT: Scroll Down
        if (gameAction == RIGHT || keyCode == -4) {
            scrollBy(50);
            return;
        }

        // D-Pad Center / Key 5: Activate selected link or media!
        if (gameAction == FIRE || keyCode == '5' || keyCode == -5 || keyCode == 10) {
            activateSelectedElement();
            return;
        }
    }

    private void scrollBy(int delta) {
        scrollY += delta;
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
        repaint();
    }

    private void ensureElementVisible(int elementIndex) {
        PageElement el = page.getElement(elementIndex);
        if (el == null) return;

        int headerH = getHeaderHeight();
        int vh = getViewportHeight();

        if (el.y < scrollY) {
            scrollY = el.y - 4;
            if (scrollY < 0) scrollY = 0;
        } else if (el.y + el.height > scrollY + vh) {
            scrollY = (el.y + el.height) - vh + 8;
            if (scrollY > maxScrollY) scrollY = maxScrollY;
        }
    }

    private void activateSelectedElement() {
        if (selectedElementIndex < 0 || page == null) return;
        PageElement el = page.getElement(selectedElementIndex);
        if (el == null) return;

        if (el.type == PageElement.TYPE_LINK) {
            if (el.url != null && el.url.length() > 0) {
                if (el.url.indexOf("/video.3gp") >= 0 || el.url.indexOf(".3gp") >= 0) {
                    midlet.launchPlatformMedia(el.url);
                } else if (el.url.equals("search:frogfind") || el.url.indexOf("frogfind.com/search") >= 0) {
                    midlet.showFrogFindSearchDialog();
                } else if (el.url.equals("search:kamtape") || ((el.url.indexOf("kamtape.com/results") >= 0 || el.url.indexOf("kamtape.com/search") >= 0) && el.url.indexOf("search_query=") < 0 && el.url.indexOf("q=") < 0)) {
                    midlet.showKamTapeSearchDialog();
                } else if (el.url.equals("search:youtube") || el.url.startsWith("search:https://www.youtube.com") || el.url.startsWith("search:http://www.youtube.com") || el.url.startsWith("search:www.youtube.com") || el.url.startsWith("search:youtube.com") || ((el.url.indexOf("youtube.com/results") >= 0 || el.url.indexOf("youtube.com/search") >= 0) && el.url.indexOf("search_query=") < 0 && el.url.indexOf("q=") < 0)) {
                    midlet.showYouTubeSearchDialog();
                } else if (el.url.startsWith("search:")) {
                    midlet.showSearchDialog();
                } else {
                    midlet.loadUrl(el.url, true);
                }
            }
        } else if (el.type == PageElement.TYPE_AUDIO) {
            midlet.openMediaPlayer(el.url, el.text, false);
        } else if (el.type == PageElement.TYPE_VIDEO) {
            midlet.openMediaPlayer(el.url, el.text, true);
        }
    }

    protected void pointerPressed(int x, int y) {
        int lx = x;
        int ly = y;
        if (isSoftwareRotation()) {
            int pw = getWidth();
            lx = y;
            ly = pw - 1 - x;
        }

        int lw = getLogicalWidth();
        int lh = getLogicalHeight();
        int headerH = getHeaderHeight();
        int footerH = getFooterHeight();

        if (!isFullscreen && ly < headerH) {
            midlet.showAddressDialog(page != null ? page.url : "");
            return;
        }

        if (!isFullscreen && ly > lh - footerH) {
            if (lx < lw / 2) {
                midlet.showOptionsMenu();
            } else {
                if (historyIndex > 0) historyBack();
                else midlet.exitBrowser();
            }
            return;
        }

        int pageY = ly - headerH + scrollY;
        int count = page.getElementCount();
        for (int i = 0; i < count; i++) {
            PageElement el = page.getElement(i);
            if (pageY >= el.y && pageY <= el.y + el.height) {
                if (el.isSelectable()) {
                    selectedElementIndex = i;
                    repaint();
                    activateSelectedElement();
                }
                return;
            }
        }
    }

    protected void paint(Graphics g) {
        int lw = getLogicalWidth();
        int lh = getLogicalHeight();

        if (isSoftwareRotation()) {
            if (offscreenBuffer == null || offscreenBuffer.getWidth() != lw || offscreenBuffer.getHeight() != lh) {
                offscreenBuffer = Image.createImage(lw, lh);
                offscreenGraphics = offscreenBuffer.getGraphics();
            }
            renderToGraphics(offscreenGraphics, lw, lh);
            g.drawRegion(offscreenBuffer, 0, 0, lw, lh, Sprite.TRANS_ROT90, 0, 0, Graphics.TOP | Graphics.LEFT);
        } else {
            offscreenBuffer = null;
            offscreenGraphics = null;
            renderToGraphics(g, lw, lh);
        }
    }

    private void renderToGraphics(Graphics g, int w, int h) {
        int headerH = getHeaderHeight();
        int footerH = getFooterHeight();
        int vh = h - headerH - footerH;

        // 1. Content Area
        g.setClip(0, headerH, w, vh);
        g.setColor(0xFAFAFA);
        g.fillRect(0, headerH, w, vh);

        int renderY = headerH - scrollY;

        if (page != null && page.getElementCount() > 0) {
            int count = page.getElementCount();
            for (int i = 0; i < count; i++) {
                PageElement el = page.getElement(i);
                int elemScreenY = renderY + el.y;

                if (elemScreenY + el.height < headerH) {
                    continue;
                }
                if (elemScreenY > headerH + vh) {
                    // Early-exit frustum culling: subsequent elements are strictly below viewport
                    break;
                }

                boolean isSelected = (i == selectedElementIndex);
                renderElement(g, el, 4, elemScreenY, w - 8, isSelected);
            }
        } else if (!isLoading) {
            renderWelcomeScreen(g, headerH, vh, w);
        }

        // Reset clip for chrome
        g.setClip(0, 0, w, h);

        // 2. Scrollbar
        if (maxScrollY > 0) {
            int sbW = 4;
            int sbX = w - sbW - 1;
            int sbY = headerH + 2;
            int sbH = vh - 4;

            g.setColor(0xE2E8F0);
            g.fillRect(sbX, sbY, sbW, sbH);

            int thumbH = Math.max(12, (vh * sbH) / (page.totalHeight));
            int thumbY = sbY + (scrollY * (sbH - thumbH)) / maxScrollY;

            g.setColor(0x0284C7);
            g.fillRect(sbX, thumbY, sbW, thumbH);
        }

        // 3. Header Bar
        if (!isFullscreen) {
            renderHeader(g, w, headerH);
        }

        // 4. Footer Bar
        if (!isFullscreen) {
            renderFooter(g, w, h, footerH);
        }
    }

    private void renderElement(Graphics g, PageElement el, int x, int y, int width, boolean isSelected) {
        if (el.type == PageElement.TYPE_HR) {
            g.setColor(0xCBD5E1);
            g.drawLine(x + 4, y + 4, x + width - 4, y + 4);
            return;
        }

        if (el.type == PageElement.TYPE_HEADING) {
            Font f = (el.level <= 2) ? titleFont : boldFont;
            g.setFont(f);
            g.setColor(0x0F2942);

            int fontH = f.getHeight();
            int curY = y + 2;
            for (int j = 0; j < el.lines.length; j++) {
                g.drawString(el.lines[j], x, curY, Graphics.TOP | Graphics.LEFT);
                curY += fontH + 3;
            }

            if (el.level == 1) {
                g.setColor(0x0284C7);
                g.fillRect(x, curY, width, 2);
            }
            return;
        }

        if (el.type == PageElement.TYPE_QUOTE) {
            g.setColor(0x38BDF8);
            g.fillRect(x, y + 2, 3, el.height - 4);

            g.setFont(regularFont);
            g.setColor(0x475569);
            int fontH = regularFont.getHeight();
            int curY = y + 2;
            for (int j = 0; j < el.lines.length; j++) {
                g.drawString(el.lines[j], x + 8, curY, Graphics.TOP | Graphics.LEFT);
                curY += fontH + 2;
            }
            return;
        }

        if (el.type == PageElement.TYPE_LIST_ITEM) {
            g.setFont(regularFont);
            g.setColor(0x0284C7);
            g.drawString("•", x + 2, y + 2, Graphics.TOP | Graphics.LEFT);

            g.setColor(0x1E293B);
            int fontH = regularFont.getHeight();
            int curY = y + 2;
            for (int j = 0; j < el.lines.length; j++) {
                g.drawString(el.lines[j], x + 12, curY, Graphics.TOP | Graphics.LEFT);
                curY += fontH + 2;
            }
            return;
        }

        if (el.type == PageElement.TYPE_LINK) {
            boolean isSearchWidget = (el.url != null && el.url.startsWith("search:")) || (el.text != null && el.text.startsWith("🔍"));
            if (isSearchWidget) {
                g.setColor(isSelected ? 0xE0F2FE : 0xF1F5F9);
                g.fillRoundRect(x, y + 1, width, el.height - 2, 6, 6);
                g.setColor(isSelected ? 0x0284C7 : 0xCBD5E1);
                g.drawRoundRect(x, y + 1, width, el.height - 2, 6, 6);

                g.setFont(boldFont);
                g.setColor(isSelected ? 0x0369A1 : 0x0F172A);
                int fontH = boldFont.getHeight();
                int curY = y + 3;
                for (int j = 0; j < el.lines.length; j++) {
                    g.drawString(el.lines[j], x + 6, curY, Graphics.TOP | Graphics.LEFT);
                    curY += fontH + 2;
                }
                return;
            }

            if (isSelected) {
                g.setColor(0xBAE6FD);
                g.fillRoundRect(x - 2, y, width + 4, el.height, 4, 4);
                g.setColor(0x0284C7);
                g.drawRoundRect(x - 2, y, width + 4, el.height, 4, 4);
            }

            g.setFont(boldFont);
            g.setColor(isSelected ? 0x0369A1 : 0x1D4ED8);

            int fontH = boldFont.getHeight();
            int curY = y + 2;
            for (int j = 0; j < el.lines.length; j++) {
                g.drawString(el.lines[j], x, curY, Graphics.TOP | Graphics.LEFT);
                int strW = boldFont.stringWidth(el.lines[j]);
                g.drawLine(x, curY + fontH, x + strW, curY + fontH);
                curY += fontH + 2;
            }
            return;
        }

        if (el.type == PageElement.TYPE_AUDIO || el.type == PageElement.TYPE_VIDEO) {
            boolean isVid = (el.type == PageElement.TYPE_VIDEO);
            g.setColor(isSelected ? 0x0F172A : 0xF1F5F9);
            g.fillRoundRect(x, y + 2, width, el.height - 4, 6, 6);

            g.setColor(isSelected ? 0x38BDF8 : 0x94A3B8);
            g.drawRoundRect(x, y + 2, width, el.height - 4, 6, 6);

            g.setFont(boldFont);
            g.setColor(isSelected ? 0x38BDF8 : (isVid ? 0xDC2626 : 0x059669));
            String prefix = isVid ? "▶ [Video] " : "♫ [Audio] ";
            g.drawString(prefix, x + 6, y + 6, Graphics.TOP | Graphics.LEFT);

            g.setColor(isSelected ? 0xFFFFFF : 0x1E293B);
            g.setFont(regularFont);
            String title = el.text;
            if (title.length() > 22) title = title.substring(0, 20) + "...";
            g.drawString(title, x + 68, y + 6, Graphics.TOP | Graphics.LEFT);

            g.setColor(isSelected ? 0x4ADE80 : 0x64748B);
            g.setFont(smallFont);
            g.drawString(isSelected ? "Press 5/Fire to Play" : "Select & Press 5", x + 6, y + 20, Graphics.TOP | Graphics.LEFT);
            return;
        }

        if (el.type == PageElement.TYPE_IMAGE) {
            if (el.image != null) {
                int imgW = el.image.getWidth();
                int imgX = x + Math.max(0, (width - imgW) / 2);
                g.drawImage(el.image, imgX, y + 2, Graphics.TOP | Graphics.LEFT);
            } else {
                g.setColor(0xE2E8F0);
                g.fillRoundRect(x + 10, y + 2, width - 20, 20, 4, 4);
                g.setColor(0x64748B);
                g.setFont(smallFont);
                String alt = "[Image: " + (el.extra.length() > 18 ? el.extra.substring(0, 16) + "..." : el.extra) + "]";
                g.drawString(alt, x + width / 2, y + 5, Graphics.HCENTER | Graphics.TOP);
            }
            return;
        }

        // Paragraph text
        g.setFont(regularFont);
        g.setColor(0x1E293B);
        int fontH = regularFont.getHeight();
        int curY = y + 2;
        for (int j = 0; j < el.lines.length; j++) {
            g.drawString(el.lines[j], x, curY, Graphics.TOP | Graphics.LEFT);
            curY += fontH + 2;
        }
    }

    private void renderHeader(Graphics g, int w, int headerH) {
        g.setColor(0x0F172A);
        g.fillRect(0, 0, w, headerH);

        boolean isHttps = (page != null && page.isHttps);
        g.setFont(smallBoldFont);
        if (isHttps) {
            g.setColor(0x22C55E);
            g.drawString("S", 4, 3, Graphics.TOP | Graphics.LEFT);
        } else {
            g.setColor(0x94A3B8);
            g.drawString("W", 4, 3, Graphics.TOP | Graphics.LEFT);
        }

        g.setColor(0xF8FAFC);
        g.setFont(smallFont);

        String headerText;
        if (isLoading) {
            headerText = loadingStatus.length() > 0 ? loadingStatus : "Loading...";
        } else {
            headerText = (page != null && page.title != null) ? page.title : "Nokia Browser";
        }
        if (headerText.length() > 24) {
            headerText = headerText.substring(0, 22) + "...";
        }
        g.drawString(headerText, 18, 3, Graphics.TOP | Graphics.LEFT);

        if (isLoading) {
            g.setColor(0x38BDF8);
            int barW = (w * (loadingProgress % 100)) / 100;
            g.fillRect(0, headerH - 2, barW, 2);
        } else {
            g.setColor(0x334155);
            g.drawLine(0, headerH - 1, w, headerH - 1);
        }
    }

    private void renderFooter(Graphics g, int w, int h, int footerH) {
        int footY = h - footerH;
        g.setColor(0x0F172A);
        g.fillRect(0, footY, w, footerH);

        g.setColor(0x334155);
        g.drawLine(0, footY, w, footY);

        g.setFont(smallBoldFont);
        g.setColor(0x38BDF8);
        g.drawString("Options", 6, footY + 3, Graphics.TOP | Graphics.LEFT);

        g.setColor(0x94A3B8);
        g.setFont(smallFont);
        int pct = (maxScrollY > 0) ? (scrollY * 100 / maxScrollY) : 100;
        g.drawString(pct + "%", w / 2, footY + 3, Graphics.HCENTER | Graphics.TOP);

        g.setColor(0xF8FAFC);
        g.setFont(smallBoldFont);
        g.drawString("Back", w - 6, footY + 3, Graphics.TOP | Graphics.RIGHT);
    }

    private void renderWelcomeScreen(Graphics g, int headerH, int vh, int w) {
        boolean land = isLandscape();
        int startY = headerH + (land ? 4 : 16);
        g.setFont(titleFont);
        g.setColor(0x0F2942);
        g.drawString("Nokia Web", w / 2, startY, Graphics.HCENTER | Graphics.TOP);

        g.setFont(smallFont);
        g.setColor(0x64748B);
        int subY = startY + (land ? 20 : 24);
        g.drawString("Modern HTTPS & Media Browser", w / 2, subY, Graphics.HCENTER | Graphics.TOP);
        String screenStr = land ? "Screen: 320 x 240 Landscape" : "Screen: 240 x 320 QVGA";
        g.drawString(screenStr, w / 2, subY + 14, Graphics.HCENTER | Graphics.TOP);

        int boxY = subY + (land ? 26 : 34);
        int boxH = land ? 112 : 150;
        g.setColor(0xF1F5F9);
        g.fillRoundRect(10, boxY, w - 20, boxH, 8, 8);
        g.setColor(0xCBD5E1);
        g.drawRoundRect(10, boxY, w - 20, boxH, 8, 8);

        g.setColor(0x0F172A);
        g.setFont(boldFont);
        g.drawString("Quick Keypad Controls:", 18, boxY + 6, Graphics.TOP | Graphics.LEFT);

        g.setFont(smallFont);
        g.setColor(0x334155);
        if (land) {
            int ly1 = boxY + 22;
            int col2X = w / 2 + 10;
            g.drawString("• [#] Enter URL / Search", 18, ly1, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [0] Open Bookmarks", 18, ly1 + 16, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [*] Toggle Fullscreen", 18, ly1 + 32, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [5 / Fire] Select / Play", 18, ly1 + 48, Graphics.TOP | Graphics.LEFT);

            g.drawString("• [1 / 7] Page Up / Down", col2X, ly1, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [4 / 6] History Back / Fwd", col2X, ly1 + 16, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [2 / 8] Scroll Line", col2X, ly1 + 32, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [Left Softkey] Full Menu", col2X, ly1 + 48, Graphics.TOP | Graphics.LEFT);
        } else {
            int ly = boxY + 26;
            g.drawString("• [#] Enter URL / Search", 18, ly, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [0] Open Bookmarks", 18, ly + 16, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [*] Toggle Fullscreen", 18, ly + 32, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [5 / Fire] Open Link / Play", 18, ly + 48, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [1 / 7] Page Up / Down", 18, ly + 64, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [4 / 6] History Back / Fwd", 18, ly + 80, Graphics.TOP | Graphics.LEFT);
            g.drawString("• [Left Softkey] Full Menu", 18, ly + 96, Graphics.TOP | Graphics.LEFT);
        }
    }
}
