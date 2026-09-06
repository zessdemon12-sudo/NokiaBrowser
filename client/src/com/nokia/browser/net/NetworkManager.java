package com.nokia.browser.net;

import com.nokia.browser.model.PageElement;
import com.nokia.browser.model.WebPage;
import com.nokia.browser.storage.StorageManager;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.lcdui.Image;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Handles communication with the Modern Gateway over HTTP.
 */
public class NetworkManager {

    public interface NetworkCallback {
        void onLoading(String message);
        void onPageLoaded(WebPage page);
        void onError(String error);
    }

    private StorageManager storage;
    private SimManager simManager;

    public NetworkManager(StorageManager storage) {
        this.storage = storage;
        this.simManager = new SimManager(storage);
    }

    public SimManager getSimManager() {
        return simManager;
    }

    /**
     * Load a URL asynchronously
     */
    public void loadUrl(final String targetUrl, final NetworkCallback callback) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                executeLoad(targetUrl, callback);
            }
        });
        t.start();
    }

    /**
     * Perform mobile search asynchronously
     */
    public void search(final String query, final NetworkCallback callback) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                String gateway = storage.getGatewayUrl();
                int eng = storage.getSearchEngine();
                String engine;
                if (eng == 1) engine = "frogfind";
                else if (eng == 2) engine = "kamtape";
                else if (eng == 3) engine = "youtube";
                else engine = "bing";
                String encodedQ = urlEncode(query);
                String fullUrl = gateway + "/search?engine=" + engine + "&q=" + encodedQ;
                executeFetch(fullUrl, targetUrlTitle(query), callback);
            }
        });
        t.start();
    }

    private void executeLoad(String targetUrl, NetworkCallback callback) {
        String gateway = storage.getGatewayUrl();
        String imgParam = storage.isLoadImages() ? "1" : "0";
        String encoded = urlEncode(targetUrl);
        String requestUrl = gateway + "/page?url=" + encoded + "&img=" + imgParam;

        executeFetch(requestUrl, targetUrl, callback);
    }

    private void setSafeHeader(HttpConnection conn, String key, String val) {
        if (conn == null || val == null || val.length() == 0) return;
        try {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < val.length(); i++) {
                char c = val.charAt(i);
                if (c >= 32 && c <= 126) sb.append(c);
            }
            if (sb.length() > 0) conn.setRequestProperty(key, sb.toString());
        } catch (Throwable t) {}
    }

    private void applyCellularHeaders(HttpConnection conn) {
        setSafeHeader(conn, "User-Agent", "Nokia6300/2.0 (07.21) Profile/MIDP-2.0 Configuration/CLDC-1.1 (SIM; " + simManager.getBearerBadge() + ")");
        setSafeHeader(conn, "Bypass-Tunnel-Reminder", "1");
        setSafeHeader(conn, "X-Nokia-SIM", String.valueOf(simManager.getActiveSim() + 1));
        setSafeHeader(conn, "X-Nokia-Bearer", simManager.getBearerBadge());
        setSafeHeader(conn, "X-Nokia-Operator", simManager.getDetectedOperator());
        setSafeHeader(conn, "X-Nokia-APN", simManager.getApnName());
        setSafeHeader(conn, "X-Nokia-Signal", String.valueOf(simManager.getSignalBars()));
        if (simManager.isDataSaver()) {
            setSafeHeader(conn, "X-Nokia-Data-Saver", "1");
        }
    }

    private void executeFetch(String requestUrl, String originalUrl, NetworkCallback callback) {
        HttpConnection conn = null;
        InputStream is = null;
        boolean success = false;
        Exception lastException = null;

        for (int attempt = 1; attempt <= 2 && !success; attempt++) {
            try {
                if (attempt == 1) {
                    callback.onLoading("Connecting (" + simManager.getSimBadge() + ": " + simManager.getBearerBadge() + ")...");
                } else {
                    callback.onLoading("Retrying on " + simManager.getSimBadge() + "...");
                    try { Thread.sleep(400); } catch (Exception ex) {}
                }

                conn = (HttpConnection) Connector.open(requestUrl, Connector.READ, false);
                conn.setRequestMethod(HttpConnection.GET);
                applyCellularHeaders(conn);

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpConnection.HTTP_OK) {
                    callback.onError("HTTP Error: " + responseCode);
                    return;
                }

                callback.onLoading("Receiving page (" + simManager.getBearerBadge() + ")...");
                is = conn.openInputStream();

                WebPage page = new WebPage();
                page.url = originalUrl;

                // Read lines using 2048-byte block buffer to eliminate per-byte socket syscalls
                byte[] netBuf = new byte[2048];
                ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);
                int bytesRead;
                while ((bytesRead = is.read(netBuf)) != -1) {
                    simManager.recordBytes(bytesRead);
                    for (int i = 0; i < bytesRead; i++) {
                        byte b = netBuf[i];
                        if (b == '\n') {
                            byte[] lineBytes = lineBuffer.toByteArray();
                            lineBuffer.reset();
                            String line = decodeUtf8(lineBytes).trim();
                            if (line.length() > 0) {
                                parseLine(line, page);
                            }
                        } else if (b != '\r') {
                            lineBuffer.write(b);
                        }
                    }
                }

                if (lineBuffer.size() > 0) {
                    String line = decodeUtf8(lineBuffer.toByteArray()).trim();
                    if (line.length() > 0) {
                        parseLine(line, page);
                    }
                }

                // Record to history
                storage.addHistory(page.url);

                success = true;
                callback.onPageLoaded(page);

            } catch (Exception e) {
                lastException = e;
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Exception e) {}
                    is = null;
                }
                if (conn != null) {
                    try { conn.close(); } catch (Exception e) {}
                    conn = null;
                }
            }
        }

        if (!success && lastException != null) {
            String msg = lastException.getMessage();
            if (msg == null || msg.length() == 0) msg = lastException.getClass().getName();
            String gw = storage.getGatewayUrl();
            if (gw != null && (gw.indexOf("127.0.0.1") >= 0 || gw.indexOf("localhost") >= 0)) {
                callback.onError("Loopback (127.0.0.1):\nSet Server in Settings");
            } else if (msg.indexOf("53") >= 0 || msg.indexOf("HTTP") >= 0 || msg.indexOf("refused") >= 0) {
                callback.onError("Gateway unreachable:\n" + gw);
            } else {
                callback.onError("Cellular error (" + simManager.getBearerBadge() + "):\n" + msg);
            }
        }
    }

    private void parseLine(String line, WebPage page) {
        if (line.startsWith("META:TITLE=")) {
            page.title = line.substring(11);
        } else if (line.startsWith("META:URL=")) {
            page.url = line.substring(9);
        } else if (line.startsWith("META:HTTPS=")) {
            page.isHttps = "1".equals(line.substring(11));
        } else if (line.startsWith("H") && line.length() > 2 && line.charAt(2) == ':') {
            int level = line.charAt(1) - '0';
            String text = line.substring(3);
            page.addElement(new PageElement(PageElement.TYPE_HEADING, text, null, level, null));
        } else if (line.startsWith("P:")) {
            page.addElement(new PageElement(PageElement.TYPE_PARAGRAPH, line.substring(2), null, 0, null));
        } else if (line.startsWith("Q:")) {
            page.addElement(new PageElement(PageElement.TYPE_QUOTE, line.substring(2), null, 0, null));
        } else if (line.startsWith("LI:")) {
            page.addElement(new PageElement(PageElement.TYPE_LIST_ITEM, line.substring(3), null, 0, null));
        } else if (line.startsWith("L:")) {
            String content = line.substring(2);
            int tab = content.indexOf('\t');
            if (tab > 0) {
                String lUrl = content.substring(0, tab);
                String lText = content.substring(tab + 1);
                page.addElement(new PageElement(PageElement.TYPE_LINK, lText, lUrl, 0, null));
            } else {
                page.addElement(new PageElement(PageElement.TYPE_LINK, content, content, 0, null));
            }
        } else if (line.startsWith("I:")) {
            String content = line.substring(2);
            int tab = content.indexOf('\t');
            String iUrl = tab > 0 ? content.substring(0, tab) : content;
            String iAlt = tab > 0 ? content.substring(tab + 1) : "Image";
            page.addElement(new PageElement(PageElement.TYPE_IMAGE, null, iUrl, 0, iAlt));
        } else if (line.startsWith("A:")) {
            String content = line.substring(2);
            int tab = content.indexOf('\t');
            String aUrl = tab > 0 ? content.substring(0, tab) : content;
            String aTitle = tab > 0 ? content.substring(tab + 1) : "Audio Stream";
            page.addElement(new PageElement(PageElement.TYPE_AUDIO, aTitle, aUrl, 0, null));
        } else if (line.startsWith("V:")) {
            String content = line.substring(2);
            int tab = content.indexOf('\t');
            String vUrl = tab > 0 ? content.substring(0, tab) : content;
            String vTitle = tab > 0 ? content.substring(tab + 1) : "Video Stream";
            page.addElement(new PageElement(PageElement.TYPE_VIDEO, vTitle, vUrl, 0, null));
        } else if (line.startsWith("HR:")) {
            page.addElement(new PageElement(PageElement.TYPE_HR, null, null, 0, null));
        }
    }

    /**
     * Download image bytes and create Image object safely for MIDP 2.0
     */
    public Image fetchImage(String imageUrl) {
        if (imageUrl == null || imageUrl.length() == 0) return null;
        HttpConnection conn = null;
        InputStream is = null;
        ByteArrayOutputStream baos = null;
        try {
            conn = (HttpConnection) Connector.open(imageUrl, Connector.READ, false);
            conn.setRequestMethod(HttpConnection.GET);
            applyCellularHeaders(conn);
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpConnection.HTTP_OK) {
                return null;
            }
            is = conn.openInputStream();
            baos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) != -1) {
                simManager.recordBytes(n);
                baos.write(buf, 0, n);
            }
            byte[] data = baos.toByteArray();
            if (data.length > 0) {
                return Image.createImage(data, 0, data.length);
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (baos != null) {
                try { baos.close(); } catch (Exception e) {}
            }
            if (is != null) {
                try { is.close(); } catch (Exception e) {}
            }
            if (conn != null) {
                try { conn.close(); } catch (Exception e) {}
            }
        }
    }

    private String targetUrlTitle(String q) {
        return "Search: " + q;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            return new String(bytes);
        }
    }

    public static String urlEncode(String s) {
        if (s == null) return "";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append("%20");
            } else {
                int code = (int) c;
                sb.append('%');
                String hex = Integer.toHexString(code).toUpperCase();
                if (hex.length() < 2) sb.append('0');
                sb.append(hex);
            }
        }
        return sb.toString();
    }
}
