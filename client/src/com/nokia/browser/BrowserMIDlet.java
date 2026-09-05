package com.nokia.browser;

import com.nokia.browser.media.MediaPlayerCanvas;
import com.nokia.browser.model.WebPage;
import com.nokia.browser.net.NetworkManager;
import com.nokia.browser.storage.StorageManager;
import com.nokia.browser.ui.BrowserCanvas;

import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;
import java.util.Vector;

/**
 * Main MIDlet for Modern Nokia J2ME Web Browser (240x320).
 */
public class BrowserMIDlet extends MIDlet implements CommandListener, NetworkManager.NetworkCallback {

    private Display display;
    private BrowserCanvas canvas;
    private StorageManager storage;
    private NetworkManager network;

    // Commands
    private Command cmdOk;
    private Command cmdCancel;
    private Command cmdBack;
    private Command cmdSelect;
    private Command cmdDelete;

    // UI Dialogs
    private TextBox addressBox;
    private TextBox searchBox;
    private TextBox frogFindBox;
    private TextBox kamTapeSearchBox;
    private TextBox youTubeSearchBox;
    private List optionsList;
    private List bookmarksList;
    private List historyList;
    private Form settingsForm;
    private TextField txtGatewayUrl;
    private ChoiceGroup choiceImages;
    private ChoiceGroup choiceFontSize;
    private ChoiceGroup choiceSearchEngine;

    public static BrowserMIDlet instance;
    private boolean isStarted = false;

    public BrowserMIDlet() {
        storage = new StorageManager();
        network = new NetworkManager(storage);

        cmdOk = new Command("OK", Command.OK, 1);
        cmdCancel = new Command("Cancel", Command.CANCEL, 2);
        cmdBack = new Command("Back", Command.BACK, 2);
        cmdSelect = new Command("Select", Command.ITEM, 1);
        cmdDelete = new Command("Delete", Command.ITEM, 3);
    }

    public void startApp() {
        if (!isStarted) {
            instance = this;
            display = Display.getDisplay(this);
            canvas = new BrowserCanvas(this, storage);
            display.setCurrent(canvas);
            isStarted = true;

            // Load default home / search page based on configured search engine
            int defEng = storage.getSearchEngine();
            if (defEng == com.nokia.browser.storage.StorageManager.ENGINE_YOUTUBE) {
                loadUrl("https://www.youtube.com", true);
            } else if (defEng == com.nokia.browser.storage.StorageManager.ENGINE_KAMTAPE) {
                loadUrl("https://www.kamtape.com", true);
            } else if (defEng == com.nokia.browser.storage.StorageManager.ENGINE_FROGFIND) {
                loadUrl("https://www.frogfind.com", true);
            } else {
                loadUrl("https://www.bing.com", true);
            }
        }
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {
        // Cleanup
    }

    public void exitBrowser() {
        destroyApp(true);
        notifyDestroyed();
    }

    public Display getDisplay() {
        return display;
    }

    public NetworkManager getNetwork() {
        return network;
    }

    public void loadUrl(String url, boolean addToHistory) {
        if (url == null || url.trim().length() == 0) return;
        url = url.trim();

        display.setCurrent(canvas);
        canvas.setLoading(true, "Connecting...");
        network.loadUrl(url, this);
    }

    public void search(String query) {
        if (query == null || query.trim().length() == 0) return;
        display.setCurrent(canvas);
        canvas.setLoading(true, "Searching...");
        network.search(query.trim(), this);
    }

    public void openMediaPlayer(String mediaUrl, String title, boolean isVideo) {
        MediaPlayerCanvas playerCanvas = new MediaPlayerCanvas(display, canvas, mediaUrl, title, isVideo, storage.getGatewayUrl());
        display.setCurrent(playerCanvas);
    }

    public void launchPlatformMedia(String mediaUrl) {
        try {
            boolean exit = platformRequest(mediaUrl);
            if (exit) {
                destroyApp(true);
                notifyDestroyed();
            }
        } catch (Throwable t) {
            // Fallback to in-app player if platformRequest is unsupported (e.g. in emulator)
            openMediaPlayer(mediaUrl, "3GP Media Player", true);
        }
    }

    // NetworkManager Callbacks
    public void onLoading(final String message) {
        display.callSerially(new Runnable() {
            public void run() {
                canvas.setLoading(true, message);
            }
        });
    }

    public void onPageLoaded(final WebPage page) {
        display.callSerially(new Runnable() {
            public void run() {
                canvas.setPage(page, true);
            }
        });
    }

    public void onError(final String error) {
        display.callSerially(new Runnable() {
            public void run() {
                canvas.setLoading(false, "");
                Alert alert = new Alert("Browsing Error", error, null, AlertType.ERROR);
                alert.setTimeout(3000);
                display.setCurrent(alert, canvas);
            }
        });
    }

    // UI Dialogs
    public void showAddressDialog(String initialUrl) {
        if (initialUrl == null || initialUrl.length() == 0) {
            initialUrl = "https://";
        }
        addressBox = new TextBox("Enter URL / Search", initialUrl, 500, TextField.ANY);
        addressBox.addCommand(cmdOk);
        addressBox.addCommand(cmdCancel);
        addressBox.setCommandListener(this);
        display.setCurrent(addressBox);
    }

    public void showSearchDialog() {
        int eng = storage.getSearchEngine();
        String title;
        if (eng == com.nokia.browser.storage.StorageManager.ENGINE_YOUTUBE) {
            title = "YouTube Video Search";
        } else if (eng == com.nokia.browser.storage.StorageManager.ENGINE_KAMTAPE) {
            title = "KamTape Video Search";
        } else if (eng == com.nokia.browser.storage.StorageManager.ENGINE_FROGFIND) {
            title = "FrogFind Search";
        } else {
            title = "Bing Search";
        }
        searchBox = new TextBox(title, "", 200, TextField.ANY);
        searchBox.addCommand(cmdOk);
        searchBox.addCommand(cmdCancel);
        searchBox.setCommandListener(this);
        display.setCurrent(searchBox);
    }

    public void showFrogFindSearchDialog() {
        frogFindBox = new TextBox("FrogFind Search", "", 200, TextField.ANY);
        frogFindBox.addCommand(cmdOk);
        frogFindBox.addCommand(cmdCancel);
        frogFindBox.setCommandListener(this);
        display.setCurrent(frogFindBox);
    }

    public void showKamTapeSearchDialog() {
        kamTapeSearchBox = new TextBox("KamTape Video Search", "", 200, TextField.ANY);
        kamTapeSearchBox.addCommand(cmdOk);
        kamTapeSearchBox.addCommand(cmdCancel);
        kamTapeSearchBox.setCommandListener(this);
        display.setCurrent(kamTapeSearchBox);
    }

    public void showYouTubeSearchDialog() {
        youTubeSearchBox = new TextBox("YouTube Video Search", "", 200, TextField.ANY);
        youTubeSearchBox.addCommand(cmdOk);
        youTubeSearchBox.addCommand(cmdCancel);
        youTubeSearchBox.setCommandListener(this);
        display.setCurrent(youTubeSearchBox);
    }

    public void showOptionsMenu() {
        optionsList = new List("Browser Menu", List.IMPLICIT);
        optionsList.append("Enter URL (#)", null);
        optionsList.append("Search Web", null);
        optionsList.append("FrogFind Retro Search", null);
        optionsList.append("KamTape Video Search", null);
        optionsList.append("YouTube Video Search", null);
        optionsList.append("Bookmarks (0)", null);
        optionsList.append("Add to Bookmarks", null);
        optionsList.append("History", null);
        optionsList.append("Reload Page", null);
        optionsList.append("Settings", null);
        optionsList.append("About", null);
        optionsList.append("Exit", null);

        optionsList.addCommand(cmdSelect);
        optionsList.addCommand(cmdBack);
        optionsList.setCommandListener(this);
        display.setCurrent(optionsList);
    }

    public void showBookmarks() {
        bookmarksList = new List("Bookmarks", List.IMPLICIT);
        Vector titles = storage.getBookmarkTitles();
        for (int i = 0; i < titles.size(); i++) {
            bookmarksList.append((String) titles.elementAt(i), null);
        }

        bookmarksList.addCommand(cmdSelect);
        bookmarksList.addCommand(cmdDelete);
        bookmarksList.addCommand(cmdBack);
        bookmarksList.setCommandListener(this);
        display.setCurrent(bookmarksList);
    }

    public void showHistory() {
        historyList = new List("History", List.IMPLICIT);
        Vector hist = storage.getHistory();
        for (int i = 0; i < hist.size(); i++) {
            historyList.append((String) hist.elementAt(i), null);
        }

        historyList.addCommand(cmdSelect);
        historyList.addCommand(cmdBack);
        historyList.setCommandListener(this);
        display.setCurrent(historyList);
    }

    public void showSettings() {
        settingsForm = new Form("Settings");

        txtGatewayUrl = new TextField("Gateway URL:", storage.getGatewayUrl(), 120, TextField.URL);
        settingsForm.append(txtGatewayUrl);

        choiceImages = new ChoiceGroup("Images:", ChoiceGroup.EXCLUSIVE);
        choiceImages.append("Load Images", null);
        choiceImages.append("Text Only", null);
        choiceImages.setSelectedIndex(storage.isLoadImages() ? 0 : 1, true);
        settingsForm.append(choiceImages);

        choiceFontSize = new ChoiceGroup("Font Size:", ChoiceGroup.EXCLUSIVE);
        choiceFontSize.append("Small", null);
        choiceFontSize.append("Medium", null);
        choiceFontSize.append("Large", null);
        choiceFontSize.setSelectedIndex(storage.getFontSize(), true);
        settingsForm.append(choiceFontSize);

        choiceSearchEngine = new ChoiceGroup("Default Search:", ChoiceGroup.EXCLUSIVE);
        choiceSearchEngine.append("Bing Search", null);
        choiceSearchEngine.append("FrogFind! (Retro)", null);
        choiceSearchEngine.append("KamTape (Videos)", null);
        choiceSearchEngine.append("YouTube (Videos)", null);
        choiceSearchEngine.setSelectedIndex(storage.getSearchEngine(), true);
        settingsForm.append(choiceSearchEngine);

        settingsForm.addCommand(cmdOk);
        settingsForm.addCommand(cmdCancel);
        settingsForm.setCommandListener(this);
        display.setCurrent(settingsForm);
    }

    public void showAbout() {
        Alert about = new Alert("About Nokia Browser",
                "Modern Nokia J2ME Browser\n" +
                "Resolution: 240x320 QVGA\n" +
                "Profile: MIDP 2.0 / CLDC 1.1\n" +
                "HTTPS TLS 1.3 / 1.2 Support\n" +
                "Audio/Video Media Support\n" +
                "YouTube & KamTape Video Support\n" +
                "FrogFind & Bing Search\n" +
                "Designed for Nokia S40/S60",
                null, AlertType.INFO);
        about.setTimeout(Alert.FOREVER);
        display.setCurrent(about, canvas);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == addressBox) {
            if (c == cmdOk) {
                String target = addressBox.getString();
                display.setCurrent(canvas);
                if (target != null) {
                    target = target.trim();
                    String lower = target.toLowerCase();
                    if (lower.equals("search https://www.youtube.com/") || lower.equals("search https://www.youtube.com") ||
                        lower.equals("search http://www.youtube.com/") || lower.equals("search http://www.youtube.com") ||
                        lower.equals("search:https://www.youtube.com/") || lower.equals("search:https://www.youtube.com") ||
                        lower.equals("search https://youtube.com/") || lower.equals("search https://youtube.com") ||
                        lower.equals("search:https://youtube.com/") || lower.equals("search:https://youtube.com") ||
                        lower.equals("search:youtube") || lower.equals("youtube") || lower.equals("search youtube") ||
                        lower.equals("search youtube.com") || lower.equals("search www.youtube.com") ||
                        lower.equals("search:www.youtube.com") || lower.equals("search:youtube.com") ||
                        lower.equals("https://www.youtube.com/search") || lower.equals("https://www.youtube.com/search/") ||
                        lower.equals("http://www.youtube.com/search") || lower.equals("http://www.youtube.com/search/") ||
                        lower.equals("www.youtube.com/search") || lower.equals("youtube.com/search") ||
                        lower.equals("https://www.youtube.com/results") || lower.equals("http://www.youtube.com/results") ||
                        lower.equals("www.youtube.com/results") || lower.equals("youtube.com/results") ||
                        lower.equals("m.youtube.com/search") || lower.equals("m.youtube.com/results") || lower.equals("m.youtube.com")) {
                        showYouTubeSearchDialog();
                        return;
                    }
                    if (lower.startsWith("search https://www.youtube.com/ ") || lower.startsWith("search https://www.youtube.com ") ||
                        lower.startsWith("search http://www.youtube.com/ ") || lower.startsWith("search http://www.youtube.com ") ||
                        lower.startsWith("search https://youtube.com/ ") || lower.startsWith("search https://youtube.com ") ||
                        lower.startsWith("search:https://www.youtube.com/ ") || lower.startsWith("search:https://www.youtube.com ") ||
                        lower.startsWith("search www.youtube.com ") || lower.startsWith("search youtube.com ") ||
                        lower.startsWith("search youtube ") || lower.startsWith("youtube ") || lower.startsWith("yt ")) {
                        String q = target;
                        if (lower.startsWith("search https://www.youtube.com/ ")) q = target.substring(32);
                        else if (lower.startsWith("search https://www.youtube.com ")) q = target.substring(31);
                        else if (lower.startsWith("search http://www.youtube.com/ ")) q = target.substring(31);
                        else if (lower.startsWith("search http://www.youtube.com ")) q = target.substring(30);
                        else if (lower.startsWith("search https://youtube.com/ ")) q = target.substring(28);
                        else if (lower.startsWith("search https://youtube.com ")) q = target.substring(27);
                        else if (lower.startsWith("search:https://www.youtube.com/ ")) q = target.substring(33);
                        else if (lower.startsWith("search:https://www.youtube.com ")) q = target.substring(32);
                        else if (lower.startsWith("search www.youtube.com ")) q = target.substring(23);
                        else if (lower.startsWith("search youtube.com ")) q = target.substring(19);
                        else if (lower.startsWith("search youtube ")) q = target.substring(15);
                        else if (lower.startsWith("youtube ")) q = target.substring(8);
                        else if (lower.startsWith("yt ")) q = target.substring(3);
                        q = q.trim();
                        if (q.length() > 0) {
                            loadUrl("https://www.youtube.com/results?search_query=" + com.nokia.browser.net.NetworkManager.urlEncode(q), true);
                        } else {
                            showYouTubeSearchDialog();
                        }
                        return;
                    }
                    if (lower.equals("search https://www.kamtape.com/") || lower.equals("search https://www.kamtape.com") ||
                        lower.equals("search http://www.kamtape.com/") || lower.equals("search http://www.kamtape.com") ||
                        lower.equals("search:https://www.kamtape.com/") || lower.equals("search:https://www.kamtape.com") ||
                        lower.equals("search:kamtape") || lower.equals("kamtape") || lower.equals("search kamtape") ||
                        lower.equals("search www.kamtape.com") || lower.equals("search kamtape.com") ||
                        lower.equals("https://www.kamtape.com/search") || lower.equals("http://www.kamtape.com/search") ||
                        lower.equals("www.kamtape.com/search") || lower.equals("kamtape.com/search") ||
                        lower.equals("https://www.kamtape.com/results") || lower.equals("http://www.kamtape.com/results") ||
                        lower.equals("www.kamtape.com/results") || lower.equals("kamtape.com/results")) {
                        showKamTapeSearchDialog();
                        return;
                    }
                    if (lower.startsWith("search https://www.kamtape.com/ ") || lower.startsWith("search https://www.kamtape.com ") ||
                        lower.startsWith("search http://www.kamtape.com/ ") || lower.startsWith("search http://www.kamtape.com ") ||
                        lower.startsWith("search www.kamtape.com ") || lower.startsWith("search kamtape.com ") ||
                        lower.startsWith("search kamtape ") || lower.startsWith("kamtape ") || lower.startsWith("kt ")) {
                        String q = target;
                        if (lower.startsWith("search https://www.kamtape.com/ ")) q = target.substring(32);
                        else if (lower.startsWith("search https://www.kamtape.com ")) q = target.substring(31);
                        else if (lower.startsWith("search http://www.kamtape.com/ ")) q = target.substring(31);
                        else if (lower.startsWith("search http://www.kamtape.com ")) q = target.substring(30);
                        else if (lower.startsWith("search www.kamtape.com ")) q = target.substring(23);
                        else if (lower.startsWith("search kamtape.com ")) q = target.substring(19);
                        else if (lower.startsWith("search kamtape ")) q = target.substring(15);
                        else if (lower.startsWith("kamtape ")) q = target.substring(8);
                        else if (lower.startsWith("kt ")) q = target.substring(3);
                        q = q.trim();
                        if (q.length() > 0) {
                            loadUrl("https://www.kamtape.com/results?search_query=" + com.nokia.browser.net.NetworkManager.urlEncode(q), true);
                        } else {
                            showKamTapeSearchDialog();
                        }
                        return;
                    }
                    if (lower.equals("search:frogfind") || lower.equals("frogfind") || lower.equals("search frogfind")) {
                        showFrogFindSearchDialog();
                        return;
                    }
                    if (lower.startsWith("frogfind ") || lower.startsWith("ff ")) {
                        int sp = target.indexOf(' ');
                        String q = target.substring(sp + 1).trim();
                        loadUrl("https://www.frogfind.com/?q=" + com.nokia.browser.net.NetworkManager.urlEncode(q), true);
                        return;
                    }
                    if (lower.startsWith("search ") || lower.startsWith("? ")) {
                        int sp = target.indexOf(' ');
                        String q = target.substring(sp + 1).trim();
                        search(q);
                        return;
                    }
                    if (target.indexOf(' ') > 0 && target.indexOf("://") < 0) {
                        search(target);
                        return;
                    }
                    loadUrl(target, true);
                }
            } else if (c == cmdCancel) {
                display.setCurrent(canvas);
            }
        } else if (d == searchBox) {
            if (c == cmdOk) {
                String q = searchBox.getString();
                display.setCurrent(canvas);
                search(q);
            } else if (c == cmdCancel) {
                display.setCurrent(canvas);
            }
        } else if (d == frogFindBox) {
            if (c == cmdOk) {
                String q = frogFindBox.getString();
                display.setCurrent(canvas);
                if (q != null && q.trim().length() > 0) {
                    loadUrl("https://www.frogfind.com/?q=" + com.nokia.browser.net.NetworkManager.urlEncode(q.trim()), true);
                }
            } else if (c == cmdCancel) {
                display.setCurrent(canvas);
            }
        } else if (d == kamTapeSearchBox) {
            if (c == cmdOk) {
                String q = kamTapeSearchBox.getString();
                display.setCurrent(canvas);
                if (q != null && q.trim().length() > 0) {
                    loadUrl("https://www.kamtape.com/results?search_query=" + com.nokia.browser.net.NetworkManager.urlEncode(q.trim()), true);
                }
            } else if (c == cmdCancel) {
                display.setCurrent(canvas);
            }
        } else if (d == youTubeSearchBox) {
            if (c == cmdOk) {
                String q = youTubeSearchBox.getString();
                display.setCurrent(canvas);
                if (q != null && q.trim().length() > 0) {
                    loadUrl("https://www.youtube.com/results?search_query=" + com.nokia.browser.net.NetworkManager.urlEncode(q.trim()), true);
                }
            } else if (c == cmdCancel) {
                display.setCurrent(canvas);
            }
        } else if (d == optionsList) {
            if (c == cmdSelect || c == List.SELECT_COMMAND) {
                int idx = optionsList.getSelectedIndex();
                if (idx == 0) showAddressDialog(canvas.getPage() != null ? canvas.getPage().url : "");
                else if (idx == 1) showSearchDialog();
                else if (idx == 2) showFrogFindSearchDialog();
                else if (idx == 3) showKamTapeSearchDialog();
                else if (idx == 4) showYouTubeSearchDialog();
                else if (idx == 5) showBookmarks();
                else if (idx == 6) {
                    if (canvas.getPage() != null && canvas.getPage().url != null) {
                        storage.addBookmark(canvas.getPage().title, canvas.getPage().url);
                        Alert a = new Alert("Bookmark Added", "Saved: " + canvas.getPage().title, null, AlertType.CONFIRMATION);
                        a.setTimeout(2000);
                        display.setCurrent(a, canvas);
                    }
                }
                else if (idx == 7) showHistory();
                else if (idx == 8) {
                    if (canvas.getPage() != null && canvas.getPage().url != null) {
                        loadUrl(canvas.getPage().url, false);
                    }
                }
                else if (idx == 9) showSettings();
                else if (idx == 10) showAbout();
                else if (idx == 11) exitBrowser();
            } else if (c == cmdBack) {
                display.setCurrent(canvas);
            }
        } else if (d == bookmarksList) {
            if (c == cmdSelect || c == List.SELECT_COMMAND) {
                int idx = bookmarksList.getSelectedIndex();
                if (idx >= 0 && idx < storage.getBookmarkUrls().size()) {
                    String bUrl = (String) storage.getBookmarkUrls().elementAt(idx);
                    display.setCurrent(canvas);
                    loadUrl(bUrl, true);
                }
            } else if (c == cmdDelete) {
                int idx = bookmarksList.getSelectedIndex();
                storage.deleteBookmark(idx);
                showBookmarks(); // refresh
            } else if (c == cmdBack) {
                display.setCurrent(canvas);
            }
        } else if (d == historyList) {
            if (c == cmdSelect || c == List.SELECT_COMMAND) {
                int idx = historyList.getSelectedIndex();
                if (idx >= 0 && idx < storage.getHistory().size()) {
                    String hUrl = (String) storage.getHistory().elementAt(idx);
                    display.setCurrent(canvas);
                    loadUrl(hUrl, true);
                }
            } else if (c == cmdBack) {
                display.setCurrent(canvas);
            }
        } else if (d == settingsForm) {
            if (c == cmdOk) {
                storage.setGatewayUrl(txtGatewayUrl.getString());
                storage.setLoadImages(choiceImages.getSelectedIndex() == 0);
                int fs = choiceFontSize.getSelectedIndex();
                storage.setFontSize(fs);
                storage.setSearchEngine(choiceSearchEngine.getSelectedIndex());
                canvas.initFonts(fs);
                display.setCurrent(canvas);
            } else if (c == cmdCancel) {
                display.setCurrent(canvas);
            }
        }
    }
}
