package com.nokia.browser;

import com.nokia.browser.media.MediaPlayerCanvas;
import com.nokia.browser.model.WebPage;
import com.nokia.browser.net.NetworkManager;
import com.nokia.browser.net.SimManager;
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
    private Command cmdSimInfo;
    private Command cmdResetData;

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
    private ChoiceGroup choiceOrientation;

    // SIM & Mobile Network UI
    private Form simForm;
    private ChoiceGroup choiceSimSlot;
    private ChoiceGroup choiceBearer;
    private ChoiceGroup choiceApn;
    private TextField txtCustomApn;
    private TextField txtCustomProxy;
    private ChoiceGroup choiceDataSaver;

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
        cmdSimInfo = new Command("SIM Info", Command.SCREEN, 2);
        cmdResetData = new Command("Reset Counter", Command.SCREEN, 3);
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
        MediaPlayerCanvas playerCanvas = new MediaPlayerCanvas(display, canvas, mediaUrl, title, isVideo, storage);
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
            initialUrl = "http://";
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
        optionsList.append("Toggle Landscape", null);
        optionsList.append("SIM & Mobile Network", null);
        optionsList.append("Settings", null);
        optionsList.append("About", null);
        optionsList.append("Exit", null);

        optionsList.addCommand(cmdSelect);
        optionsList.addCommand(cmdBack);
        optionsList.setCommandListener(this);
        display.setCurrent(optionsList);
    }

    public void toggleOrientation() {
        boolean currentlyLand = canvas.isLandscape();
        int newOrient = currentlyLand ? com.nokia.browser.storage.StorageManager.ORIENTATION_PORTRAIT : com.nokia.browser.storage.StorageManager.ORIENTATION_LANDSCAPE;
        storage.setOrientation(newOrient);
        canvas.relayoutPage();
        display.setCurrent(canvas);
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

        choiceOrientation = new ChoiceGroup("Orientation:", ChoiceGroup.EXCLUSIVE);
        choiceOrientation.append("Auto (Screen Size)", null);
        choiceOrientation.append("Portrait (240x320)", null);
        choiceOrientation.append("Landscape (320x240)", null);
        choiceOrientation.setSelectedIndex(storage.getOrientation(), true);
        settingsForm.append(choiceOrientation);

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
                "SIM & Network APN Stack\n" +
                "HTTPS TLS 1.3 / 1.2 Support\n" +
                "Audio/Video Media Support\n" +
                "YouTube & KamTape Support\n" +
                "FrogFind & Bing Search\n" +
                "Designed for Nokia S40/S60",
                null, AlertType.INFO);
        about.setTimeout(Alert.FOREVER);
        display.setCurrent(about, canvas);
    }

    public void showSimNetworkSettings() {
        simForm = new Form("SIM & Mobile Network");

        choiceSimSlot = new ChoiceGroup("Active SIM Slot:", ChoiceGroup.EXCLUSIVE);
        choiceSimSlot.append("SIM 1 (Primary)", null);
        choiceSimSlot.append("SIM 2 (Secondary)", null);
        choiceSimSlot.setSelectedIndex(storage.getSimSlot(), true);
        simForm.append(choiceSimSlot);

        choiceBearer = new ChoiceGroup("Network Bearer:", ChoiceGroup.EXCLUSIVE);
        choiceBearer.append("Auto (Device Default)", null);
        choiceBearer.append("2G (GPRS)", null);
        choiceBearer.append("2.5G (EDGE)", null);
        choiceBearer.append("3G (WCDMA)", null);
        choiceBearer.append("3.5G (HSDPA)", null);
        choiceBearer.append("WiFi / WLAN", null);
        choiceBearer.setSelectedIndex(storage.getNetworkBearer(), true);
        simForm.append(choiceBearer);

        choiceApn = new ChoiceGroup("Carrier APN Profile:", ChoiceGroup.EXCLUSIVE);
        choiceApn.append("Auto (Default Internet)", null);
        choiceApn.append("Robi-INTERNET (BD)", null);
        choiceApn.append("Vodafone (live.vodafone.com)", null);
        choiceApn.append("T-Mobile (fast.t-mobile.com)", null);
        choiceApn.append("AT&T (phone)", null);
        choiceApn.append("Airtel (airtelgprs.com)", null);
        choiceApn.append("Jio 4G/5G (jionet)", null);
        choiceApn.append("Orange (orange)", null);
        choiceApn.append("Custom APN...", null);
        choiceApn.setSelectedIndex(storage.getApnPreset(), true);
        simForm.append(choiceApn);

        txtCustomApn = new TextField("Custom APN:", storage.getCustomApn(), 60, TextField.ANY);
        simForm.append(txtCustomApn);

        txtCustomProxy = new TextField("Custom Proxy (IP:Port):", storage.getCustomProxy(), 60, TextField.ANY);
        simForm.append(txtCustomProxy);

        choiceDataSaver = new ChoiceGroup("Mobile Data Saver:", ChoiceGroup.EXCLUSIVE);
        choiceDataSaver.append("Normal (Standard Images)", null);
        choiceDataSaver.append("Max Data Saver (Cellular)", null);
        choiceDataSaver.setSelectedIndex(storage.isDataSaver() ? 1 : 0, true);
        simForm.append(choiceDataSaver);

        simForm.addCommand(cmdOk);
        simForm.addCommand(cmdSimInfo);
        simForm.addCommand(cmdCancel);
        simForm.setCommandListener(this);
        display.setCurrent(simForm);
    }

    public void showSimInfoDialog() {
        SimManager sim = network.getSimManager();
        StringBuffer sb = new StringBuffer();
        sb.append("SIM Slot: ").append(sim.getSimBadge()).append("\n");
        sb.append("Operator: ").append(sim.getDetectedOperator()).append("\n");
        sb.append("MCC-MNC: ").append(sim.getDetectedCountryCode()).append("-").append(sim.getDetectedNetworkCode()).append("\n");
        sb.append("Bearer: ").append(sim.getBearerName()).append("\n");
        sb.append("APN: ").append(sim.getApnName()).append("\n");
        if (sim.getApnProxy().length() > 0) {
            sb.append("Proxy: ").append(sim.getApnProxy()).append("\n");
        }
        sb.append("Signal: ").append(sim.getSignalBars()).append(" / 4 bars\n");
        sb.append("Roaming: ").append(sim.isRoaming() ? "Yes (Roaming)" : "No (Home)").append("\n");
        sb.append("IMEI: ").append(sim.getMaskedImei()).append("\n");
        sb.append("IMSI: ").append(sim.getMaskedImsi()).append("\n");
        sb.append("--------------------\n");
        sb.append("Session Data: ").append(SimManager.formatBytes(sim.getSessionBytes())).append("\n");
        sb.append("Total Mobile: ").append(SimManager.formatBytes(sim.getTotalBytes()));

        Alert info = new Alert("SIM & Network Info", sb.toString(), null, AlertType.INFO);
        info.setTimeout(Alert.FOREVER);
        info.addCommand(cmdResetData);
        info.addCommand(cmdBack);
        info.setCommandListener(this);
        Displayable next = (simForm != null) ? (Displayable) simForm : (Displayable) canvas;
        display.setCurrent(info, next);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == addressBox) {
            if (c == cmdOk) {
                String target = addressBox.getString();
                display.setCurrent(canvas);
                if (target != null) {
                    target = target.trim();
                    String lower = target.toLowerCase();

                    // Prefix normalization for omnibox queries
                    String norm = lower;
                    if (norm.startsWith("search:")) norm = norm.substring(7).trim();
                    else if (norm.startsWith("search ")) norm = norm.substring(7).trim();
                    if (norm.startsWith("https://")) norm = norm.substring(8);
                    else if (norm.startsWith("http://")) norm = norm.substring(7);
                    if (norm.startsWith("www.")) norm = norm.substring(4);
                    else if (norm.startsWith("m.")) norm = norm.substring(2);

                    // Subscriptions & Channels routing
                    if (norm.equals("subs") || norm.equals("subscriptions") || norm.equals("feed") ||
                        norm.equals("feed/subscriptions") || norm.equals("youtube.com/feed/subscriptions")) {
                        loadUrl("https://www.youtube.com/feed/subscriptions", true);
                        return;
                    }
                    if (norm.equals("channels") || norm.equals("feed/channels") || norm.equals("youtube.com/feed/channels")) {
                        loadUrl("https://www.youtube.com/feed/channels", true);
                        return;
                    }

                    // 1. YouTube routing
                    if (norm.equals("youtube") || norm.equals("youtube.com") || norm.equals("youtube.com/") ||
                        norm.equals("youtube.com/search") || norm.equals("youtube.com/results") || norm.equals("yt")) {
                        showYouTubeSearchDialog();
                        return;
                    }
                    if (norm.startsWith("youtube ") || norm.startsWith("yt ") ||
                        norm.startsWith("youtube.com ") || norm.startsWith("youtube.com/results?search_query=")) {
                        String q;
                        if (norm.startsWith("youtube.com/results?search_query=")) q = norm.substring(33);
                        else if (norm.startsWith("youtube.com ")) q = norm.substring(12);
                        else if (norm.startsWith("youtube ")) q = norm.substring(8);
                        else q = norm.substring(3);
                        q = q.trim();
                        if (q.length() > 0) {
                            loadUrl("https://www.youtube.com/results?search_query=" + com.nokia.browser.net.NetworkManager.urlEncode(q), true);
                        } else {
                            showYouTubeSearchDialog();
                        }
                        return;
                    }

                    // 2. KamTape routing
                    if (norm.equals("kamtape") || norm.equals("kamtape.com") || norm.equals("kamtape.com/") ||
                        norm.equals("kamtape.com/search") || norm.equals("kamtape.com/results") || norm.equals("kt")) {
                        showKamTapeSearchDialog();
                        return;
                    }
                    if (norm.startsWith("kamtape ") || norm.startsWith("kt ") ||
                        norm.startsWith("kamtape.com ") || norm.startsWith("kamtape.com/results?search_query=")) {
                        String q;
                        if (norm.startsWith("kamtape.com/results?search_query=")) q = norm.substring(33);
                        else if (norm.startsWith("kamtape.com ")) q = norm.substring(12);
                        else if (norm.startsWith("kamtape ")) q = norm.substring(8);
                        else q = norm.substring(3);
                        q = q.trim();
                        if (q.length() > 0) {
                            loadUrl("https://www.kamtape.com/results?search_query=" + com.nokia.browser.net.NetworkManager.urlEncode(q), true);
                        } else {
                            showKamTapeSearchDialog();
                        }
                        return;
                    }

                    // 3. FrogFind routing
                    if (norm.equals("frogfind") || norm.equals("frogfind.com") || norm.equals("ff")) {
                        showFrogFindSearchDialog();
                        return;
                    }
                    if (norm.startsWith("frogfind ") || norm.startsWith("ff ")) {
                        int sp = norm.indexOf(' ');
                        String q = norm.substring(sp + 1).trim();
                        loadUrl("https://www.frogfind.com/?q=" + com.nokia.browser.net.NetworkManager.urlEncode(q), true);
                        return;
                    }

                    // 4. Robi routing (Robi-INTERNET)
                    if (norm.equals("robi") || norm.equals("robi-internet") || norm.equals("robi-inernet") ||
                        norm.equals("robi internet") || norm.equals("wap.robi.com.bd") || norm.equals("wap.robi.com.bd/") ||
                        norm.equals("robi.com.bd") || norm.equals("robi.com.bd/")) {
                        storage.setApnPreset(com.nokia.browser.net.SimManager.APN_ROBI);
                        loadUrl("http://wap.robi.com.bd", true);
                        return;
                    }
                    if (norm.startsWith("robi ") || norm.startsWith("robi-internet ") || norm.startsWith("robi-inernet ")) {
                        storage.setApnPreset(com.nokia.browser.net.SimManager.APN_ROBI);
                        loadUrl("http://wap.robi.com.bd", true);
                        return;
                    }
                    // 5. FTP routing
                    if (lower.startsWith("ftp ") || lower.startsWith("ftp:")) {
                        String f = target.substring(4).trim();
                        while (f.startsWith("/")) f = f.substring(1);
                        if (f.length() > 0) {
                            loadUrl("ftp://" + f, true);
                        } else {
                            loadUrl("ftp://test.rebex.net", true);
                        }
                        return;
                    }
                    if (norm.startsWith("ftp.") && target.indexOf("://") < 0) {
                        loadUrl("ftp://" + target, true);
                        return;
                    }

                    // 6. Plain HTTP routing
                    if (lower.startsWith("http ") || lower.startsWith("http:")) {
                        String h = target.substring(5).trim();
                        while (h.startsWith("/")) h = h.substring(1);
                        if (h.length() > 0) {
                            loadUrl("http://" + h, true);
                            return;
                        }
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
                else if (idx == 9) toggleOrientation();
                else if (idx == 10) showSimNetworkSettings();
                else if (idx == 11) showSettings();
                else if (idx == 12) showAbout();
                else if (idx == 13) exitBrowser();
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
        } else if (d == simForm) {
            if (c == cmdOk) {
                storage.setSimSlot(choiceSimSlot.getSelectedIndex());
                storage.setNetworkBearer(choiceBearer.getSelectedIndex());
                storage.setApnPreset(choiceApn.getSelectedIndex());
                storage.setCustomApn(txtCustomApn.getString());
                storage.setCustomProxy(txtCustomProxy.getString());
                storage.setDataSaver(choiceDataSaver.getSelectedIndex() == 1);
                canvas.repaint();
                display.setCurrent(canvas);
            } else if (c == cmdSimInfo) {
                showSimInfoDialog();
            } else if (c == cmdCancel) {
                display.setCurrent(canvas);
            }
        } else if (d == settingsForm) {
            if (c == cmdOk) {
                storage.setGatewayUrl(txtGatewayUrl.getString());
                storage.setLoadImages(choiceImages.getSelectedIndex() == 0);
                int fs = choiceFontSize.getSelectedIndex();
                storage.setFontSize(fs);
                storage.setSearchEngine(choiceSearchEngine.getSelectedIndex());
                int orient = choiceOrientation.getSelectedIndex();
                storage.setOrientation(orient);
                canvas.initFonts(fs);
                canvas.relayoutPage();
                display.setCurrent(canvas);
            } else if (c == cmdCancel) {
                display.setCurrent(canvas);
            }
        }

        if (c == cmdResetData) {
            network.getSimManager().resetTotalBytes();
            Alert a = new Alert("Counter Reset", "Mobile data counter has been reset to 0.", null, AlertType.CONFIRMATION);
            a.setTimeout(2000);
            display.setCurrent(a, canvas);
        }
    }
}
