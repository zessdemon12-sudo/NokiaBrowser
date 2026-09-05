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

    private int searchEngine = 0; // 0 = Bing, 1 = FrogFind, 2 = KamTape, 3 = YouTube

    public int getSearchEngine() { return searchEngine; }
    public void setSearchEngine(int engine) {
        this.searchEngine = engine;
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
            rs.addRecord(b1, 0, b1.length);
            rs.addRecord(b2, 0, b2.length);
            rs.addRecord(b3, 0, b3.length);
            rs.addRecord(b4, 0, b4.length);
        } catch (Exception e) {
        } finally {
            closeRs(rs);
        }
    }

    private void loadBookmarks() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RS_BOOKMARKS, true);
            if (rs.getNumRecords() == 0) {
                // Populate default bookmarks
                addBookmark("YouTube Video Search", "search:youtube");
                addBookmark("Search https://www.youtube.com/", "https://www.youtube.com/search");
                addBookmark("YouTube Videos", "https://www.youtube.com");
                addBookmark("KamTape Video Search", "search:kamtape");
                addBookmark("KamTape Videos", "https://www.kamtape.com");
                addBookmark("Bing Search", "https://www.bing.com");
                addBookmark("FrogFind! (Retro Search)", "https://www.frogfind.com");
                addBookmark("Wikipedia Mobile", "https://en.wikipedia.org");
                addBookmark("Hacker News", "https://news.ycombinator.com");
                addBookmark("BBC News", "https://www.bbc.com/news");
                addBookmark("The Old Net", "https://theoldnet.com");
                addBookmark("Sample Media Page", "http://127.0.0.1:8080/sample_media");
            } else {
                boolean hasFrogFind = false;
                boolean hasKamTape = false;
                boolean hasYouTube = false;
                RecordEnumeration re = rs.enumerateRecords(null, null, false);
                while (re.hasNextElement()) {
                    byte[] data = re.nextRecord();
                    String line = new String(data);
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        String bTitle = line.substring(0, tab);
                        String bUrl = line.substring(tab + 1);
                        bookmarkTitles.addElement(bTitle);
                        bookmarkUrls.addElement(bUrl);
                        if (bUrl.indexOf("frogfind.com") >= 0) {
                            hasFrogFind = true;
                        }
                        if (bUrl.indexOf("kamtape") >= 0) {
                            hasKamTape = true;
                        }
                        if (bUrl.indexOf("youtube") >= 0) {
                            hasYouTube = true;
                        }
                    }
                }
                re.destroy();
                if (!hasFrogFind) {
                    addBookmark("FrogFind! (Retro Search)", "https://www.frogfind.com");
                }
                if (!hasKamTape) {
                    addBookmark("KamTape Video Search", "search:kamtape");
                    addBookmark("KamTape Videos", "https://www.kamtape.com");
                }
                if (!hasYouTube) {
                    addBookmark("YouTube Video Search", "search:youtube");
                    addBookmark("Search https://www.youtube.com/", "https://www.youtube.com/search");
                    addBookmark("YouTube Videos", "https://www.youtube.com");
                }
            }
        } catch (Exception e) {
        } finally {
            closeRs(rs);
        }
    }

    private void saveBookmarks() {
        RecordStore rs = null;
        try {
            RecordStore.deleteRecordStore(RS_BOOKMARKS);
        } catch (Exception e) {}
        try {
            rs = RecordStore.openRecordStore(RS_BOOKMARKS, true);
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
                historyList.addElement(new String(data));
            }
            re.destroy();
        } catch (Exception e) {
        } finally {
            closeRs(rs);
        }
    }

    private void saveHistory() {
        RecordStore rs = null;
        try {
            RecordStore.deleteRecordStore(RS_HISTORY);
        } catch (Exception e) {}
        try {
            rs = RecordStore.openRecordStore(RS_HISTORY, true);
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
