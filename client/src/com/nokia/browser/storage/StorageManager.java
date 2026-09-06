package com.nokia.browser.storage;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;
import java.util.Vector;

/**
 * Persistent storage using J2ME RMS for settings, bookmarks, and history.
 */
public class StorageManager {
    private static final String RS_SETTINGS = "nb_settings";
    private static final String RS_BOOKMARKS = "nb_bookmarks";
    private static final String RS_HISTORY = "nb_history";

    private String gatewayUrl;
    private boolean loadImages;
    private int fontSize; // 0=small, 1=medium, 2=large

    private Vector bookmarkTitles;
    private Vector bookmarkUrls;
    private Vector historyList;

    public StorageManager() {
        this.gatewayUrl = "http://127.0.0.1:8080";
        this.loadImages = true;
        this.fontSize = 1;

        this.bookmarkTitles = new Vector();
        this.bookmarkUrls = new Vector();
        this.historyList = new Vector();

        loadSettings();
        loadBookmarks();
        loadHistory();
    }

    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String url) {
        this.gatewayUrl = url;
        saveSettings();
    }

    public boolean isLoadImages() { return loadImages; }
    public void setLoadImages(boolean load) {
        this.loadImages = load;
        saveSettings();
    }

    public int getFontSize() { return fontSize; }
    public void setFontSize(int size) {
        this.fontSize = size;
        saveSettings();
    }

    public Vector getBookmarkTitles() { return bookmarkTitles; }
    public Vector getBookmarkUrls() { return bookmarkUrls; }

    public void addBookmark(String title, String url) {
        if (title == null || title.length() == 0) title = url;
        bookmarkTitles.addElement(title);
        bookmarkUrls.addElement(url);
        saveBookmarks();
    }

    public void deleteBookmark(int index) {
        if (index >= 0 && index < bookmarkTitles.size()) {
            bookmarkTitles.removeElementAt(index);
            bookmarkUrls.removeElementAt(index);
            saveBookmarks();
        }
    }

    public Vector getHistory() { return historyList; }

    public void addHistory(String url) {
        if (url == null || url.length() == 0) return;
        // Avoid duplicate at top
        if (historyList.size() > 0 && historyList.firstElement().equals(url)) {
            return;
        }
        historyList.insertElementAt(url, 0);
        if (historyList.size() > 25) {
            historyList.removeElementAt(historyList.size() - 1);
        }
        saveHistory();
    }

    public static final int ENGINE_BING     = 0;
    public static final int ENGINE_FROGFIND = 1;
    public static final int ENGINE_KAMTAPE  = 2;
    public static final int ENGINE_YOUTUBE  = 3;

    public static final int ORIENTATION_AUTO      = 0;
    public static final int ORIENTATION_PORTRAIT  = 1;
    public static final int ORIENTATION_LANDSCAPE = 2;

    private int searchEngine = 0; // 0 = Bing, 1 = FrogFind, 2 = KamTape, 3 = YouTube
    private int orientation = 0;  // 0 = Auto, 1 = Portrait (240x320), 2 = Landscape (320x240)

    // SIM and Cellular Network settings
    private int simSlot = 0;         // 0 = SIM 1, 1 = SIM 2
    private int networkBearer = 0;   // 0 = Auto, 1 = GPRS, 2 = EDGE, 3 = 3G, 4 = HSDPA, 5 = WiFi
    private int apnPreset = 0;       // 0 = Auto, 1 = Vodafone, 2 = T-Mobile, 3 = AT&T, 4 = Airtel, 5 = Jio, 6 = Orange, 7 = Custom
    private String customApn = "";
    private String customProxy = "";
    private boolean dataSaver = false;
    private long totalMobileBytes = 0;
    private long unsavedMobileBytes = 0;

    public int getSearchEngine() { return searchEngine; }
    public void setSearchEngine(int engine) {
        this.searchEngine = engine;
        saveSettings();
    }

    public int getOrientation() { return orientation; }
    public void setOrientation(int o) {
        this.orientation = o;
        saveSettings();
    }

    public int getSimSlot() { return simSlot; }
    public void setSimSlot(int slot) {
        this.simSlot = slot;
        saveSettings();
    }

    public int getNetworkBearer() { return networkBearer; }
    public void setNetworkBearer(int b) {
        this.networkBearer = b;
        saveSettings();
    }

    public int getApnPreset() { return apnPreset; }
    public void setApnPreset(int p) {
        this.apnPreset = p;
        saveSettings();
    }

    public String getCustomApn() { return customApn; }
    public void setCustomApn(String a) {
        this.customApn = (a == null) ? "" : a;
        saveSettings();
    }

    public String getCustomProxy() { return customProxy; }
    public void setCustomProxy(String p) {
        this.customProxy = (p == null) ? "" : p;
        saveSettings();
    }

    public boolean isDataSaver() { return dataSaver; }
    public void setDataSaver(boolean ds) {
        this.dataSaver = ds;
        saveSettings();
    }

    public long getTotalMobileBytes() { return totalMobileBytes; }

    public synchronized void addMobileBytes(int count) {
        if (count <= 0) return;
        totalMobileBytes += count;
        unsavedMobileBytes += count;
        // Batch flush every ~32 KB to avoid excessive RMS writes
        if (unsavedMobileBytes >= 32768) {
            unsavedMobileBytes = 0;
            saveSettings();
        }
    }

    public void resetMobileBytes() {
        totalMobileBytes = 0;
        unsavedMobileBytes = 0;
        saveSettings();
    }

    private void loadSettings() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RS_SETTINGS, true);
            if (rs.getNumRecords() >= 3) {
                byte[] b1 = rs.getRecord(1);
                byte[] b2 = rs.getRecord(2);
                byte[] b3 = rs.getRecord(3);
                gatewayUrl = new String(b1);
                loadImages = "1".equals(new String(b2));
                fontSize = Integer.parseInt(new String(b3));
                if (rs.getNumRecords() >= 4) {
                    byte[] b4 = rs.getRecord(4);
                    searchEngine = Integer.parseInt(new String(b4));
                }
                if (rs.getNumRecords() >= 5) {
                    byte[] b5 = rs.getRecord(5);
                    orientation = Integer.parseInt(new String(b5));
                }
                if (rs.getNumRecords() >= 6) {
                    simSlot = Integer.parseInt(new String(rs.getRecord(6)));
                }
                if (rs.getNumRecords() >= 7) {
                    networkBearer = Integer.parseInt(new String(rs.getRecord(7)));
                }
                if (rs.getNumRecords() >= 8) {
                    apnPreset = Integer.parseInt(new String(rs.getRecord(8)));
                }
                if (rs.getNumRecords() >= 9) {
                    customApn = new String(rs.getRecord(9));
                }
                if (rs.getNumRecords() >= 10) {
                    customProxy = new String(rs.getRecord(10));
                }
                if (rs.getNumRecords() >= 11) {
                    dataSaver = "1".equals(new String(rs.getRecord(11)));
                }
                if (rs.getNumRecords() >= 12) {
                    totalMobileBytes = Long.parseLong(new String(rs.getRecord(12)));
                }
            } else {
                saveSettings();
            }
        } catch (Exception e) {
            // Keep defaults
        } finally {
            closeRs(rs);
        }
    }

    public void saveSettings() {
        RecordStore rs = null;
        try {
            RecordStore.deleteRecordStore(RS_SETTINGS);
        } catch (Exception e) {}
        try {
            rs = RecordStore.openRecordStore(RS_SETTINGS, true);
            byte[] b1 = gatewayUrl.getBytes();
            byte[] b2 = (loadImages ? "1" : "0").getBytes();
            byte[] b3 = String.valueOf(fontSize).getBytes();
            byte[] b4 = String.valueOf(searchEngine).getBytes();
            byte[] b5 = String.valueOf(orientation).getBytes();
            byte[] b6 = String.valueOf(simSlot).getBytes();
            byte[] b7 = String.valueOf(networkBearer).getBytes();
            byte[] b8 = String.valueOf(apnPreset).getBytes();
            byte[] b9 = (customApn != null ? customApn : "").getBytes();
            byte[] b10 = (customProxy != null ? customProxy : "").getBytes();
            byte[] b11 = (dataSaver ? "1" : "0").getBytes();
            byte[] b12 = String.valueOf(totalMobileBytes).getBytes();
            rs.addRecord(b1, 0, b1.length);
            rs.addRecord(b2, 0, b2.length);
            rs.addRecord(b3, 0, b3.length);
            rs.addRecord(b4, 0, b4.length);
            rs.addRecord(b5, 0, b5.length);
            rs.addRecord(b6, 0, b6.length);
            rs.addRecord(b7, 0, b7.length);
            rs.addRecord(b8, 0, b8.length);
            rs.addRecord(b9, 0, b9.length);
            rs.addRecord(b10, 0, b10.length);
            rs.addRecord(b11, 0, b11.length);
            rs.addRecord(b12, 0, b12.length);
        } catch (Exception e) {
        } finally {
            closeRs(rs);
        }
    }

    private void addBookmarkMemory(String title, String url) {
        if (url != null && !bookmarkUrls.contains(url)) {
            bookmarkTitles.addElement(title);
            bookmarkUrls.addElement(url);
        }
    }

    private void loadBookmarks() {
        RecordStore rs = null;
        boolean needsSave = false;
        try {
            rs = RecordStore.openRecordStore(RS_BOOKMARKS, true);
            if (rs.getNumRecords() == 0) {
                // Populate default bookmarks in memory
                addBookmarkMemory("Robi Portal (Robi-INTERNET)", "http://wap.robi.com.bd");
                addBookmarkMemory("YouTube Video Search", "search:youtube");
                addBookmarkMemory("YouTube Subscriptions", "https://www.youtube.com/feed/subscriptions");
                addBookmarkMemory("Search https://www.youtube.com/", "https://www.youtube.com/search");
                addBookmarkMemory("YouTube Videos", "https://www.youtube.com");
                addBookmarkMemory("KamTape Video Search", "search:kamtape");
                addBookmarkMemory("KamTape Videos", "https://www.kamtape.com");
                addBookmarkMemory("Bing Search", "https://www.bing.com");
                addBookmarkMemory("FrogFind! (Retro Search)", "https://www.frogfind.com");
                addBookmarkMemory("Wikipedia Mobile", "https://en.wikipedia.org");
                addBookmarkMemory("Hacker News", "https://news.ycombinator.com");
                addBookmarkMemory("BBC News", "https://www.bbc.com/news");
                addBookmarkMemory("The Old Net", "https://theoldnet.com");
                addBookmarkMemory("Rebex Public Test FTP", "ftp://test.rebex.net");
                addBookmarkMemory("Sample Media Page", "http://127.0.0.1:8080/sample_media");
                needsSave = true;
            } else {
                RecordEnumeration re = rs.enumerateRecords(null, null, false);
                while (re.hasNextElement()) {
                    byte[] data = re.nextRecord();
                    String line = new String(data);
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        String bTitle = line.substring(0, tab);
                        String bUrl = line.substring(tab + 1);
                        if (!bookmarkUrls.contains(bUrl)) {
                            bookmarkTitles.addElement(bTitle);
                            bookmarkUrls.addElement(bUrl);
                        } else {
                            needsSave = true; // Clean duplicates from RMS
                        }
                    }
                }
                re.destroy();

                if (!bookmarkUrls.contains("http://wap.robi.com.bd")) {
                    addBookmarkMemory("Robi Portal (Robi-INTERNET)", "http://wap.robi.com.bd");
                    needsSave = true;
                }
                if (!bookmarkUrls.contains("https://www.frogfind.com")) {
                    addBookmarkMemory("FrogFind! (Retro Search)", "https://www.frogfind.com");
                    needsSave = true;
                }
                if (!bookmarkUrls.contains("search:kamtape")) {
                    addBookmarkMemory("KamTape Video Search", "search:kamtape");
                    needsSave = true;
                }
                if (!bookmarkUrls.contains("https://www.kamtape.com")) {
                    addBookmarkMemory("KamTape Videos", "https://www.kamtape.com");
                    needsSave = true;
                }
                if (!bookmarkUrls.contains("search:youtube")) {
                    addBookmarkMemory("YouTube Video Search", "search:youtube");
                    needsSave = true;
                }
                if (!bookmarkUrls.contains("https://www.youtube.com/search")) {
                    addBookmarkMemory("Search https://www.youtube.com/", "https://www.youtube.com/search");
                    needsSave = true;
                }
                if (!bookmarkUrls.contains("https://www.youtube.com/feed/subscriptions")) {
                    addBookmarkMemory("YouTube Subscriptions", "https://www.youtube.com/feed/subscriptions");
                    needsSave = true;
                }
                if (!bookmarkUrls.contains("https://www.youtube.com")) {
                    addBookmarkMemory("YouTube Videos", "https://www.youtube.com");
                    needsSave = true;
                }
                if (!bookmarkUrls.contains("ftp://test.rebex.net")) {
                    addBookmarkMemory("Rebex Public Test FTP", "ftp://test.rebex.net");
                    needsSave = true;
                }
            }
        } catch (Exception e) {
        } finally {
            closeRs(rs);
            rs = null;
        }

        if (needsSave) {
            saveBookmarks();
        }
    }

    private synchronized void saveBookmarks() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RS_BOOKMARKS, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            while (re.hasNextElement()) {
                int id = re.nextRecordId();
                rs.deleteRecord(id);
            }
            re.destroy();
            for (int i = 0; i < bookmarkTitles.size(); i++) {
                String line = bookmarkTitles.elementAt(i) + "\t" + bookmarkUrls.elementAt(i);
                byte[] b = line.getBytes();
                rs.addRecord(b, 0, b.length);
            }
        } catch (Exception e) {
        } finally {
            closeRs(rs);
        }
    }

    private void loadHistory() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RS_HISTORY, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            while (re.hasNextElement()) {
                byte[] data = re.nextRecord();
                String h = new String(data);
                if (!historyList.contains(h)) {
                    historyList.addElement(h);
                }
            }
            re.destroy();
        } catch (Exception e) {
        } finally {
            closeRs(rs);
        }
    }

    private synchronized void saveHistory() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RS_HISTORY, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            while (re.hasNextElement()) {
                int id = re.nextRecordId();
                rs.deleteRecord(id);
            }
            re.destroy();
            for (int i = 0; i < historyList.size(); i++) {
                byte[] b = ((String) historyList.elementAt(i)).getBytes();
                rs.addRecord(b, 0, b.length);
            }
        } catch (Exception e) {
        } finally {
            closeRs(rs);
        }
    }

    private void closeRs(RecordStore rs) {
        if (rs != null) {
            try { rs.closeRecordStore(); } catch (Exception e) {}
        }
    }
}
