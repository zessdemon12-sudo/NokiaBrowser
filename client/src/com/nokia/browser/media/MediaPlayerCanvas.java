package com.nokia.browser.media;

import com.nokia.browser.storage.StorageManager;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VideoControl;
import javax.microedition.media.control.VolumeControl;
import java.io.DataInputStream;
import java.io.InputStream;

/**
 * Fullscreen 240x320 & 320x240 Multimedia Player for Nokia J2ME.
 * Supports:
 * 1. Video Frame Streamer (240x144 / 320x180 widescreen QVGA at 8 FPS) - Universal across MicroEmulator & Nokia HW.
 * 2. Native MMAPI Player (3GP Video & Audio) with hardware VideoControl acceleration.
 */
public class MediaPlayerCanvas extends Canvas implements PlayerListener, Runnable {

    private Display display;
    private Displayable returnScreen;
    private String mediaUrl;
    private String mediaTitle;
    private boolean isVideo;
    private StorageManager storage;

    private Player player;
    private VolumeControl volumeControl;
    private VideoControl videoControl;

    // Video Frame Streaming Engine
    private Image currentVideoFrame;
    private byte[] frameBuffer;
    private boolean running;
    private boolean isPlaying;
    private boolean isPaused;
    private boolean isFullscreenVideo;
    private String statusMessage;
    private int volumeLevel; // 0 to 100
    private long durationUs; // microseconds
    private long mediaTimeUs;

    // Software Rotation Offscreen Buffers (for 240x320 portrait devices rotated to landscape)
    private Image offscreenBuffer;
    private Graphics offscreenGraphics;

    private Thread videoStreamThread;
    private Thread animThread;
    private int animFrame;

    private HttpConnection streamConn;
    private DataInputStream streamDis;
    private String gatewayUrl;

    private Font fontSmallBold;
    private Font fontSmallPlain;

    public MediaPlayerCanvas(Display display, Displayable returnScreen, String mediaUrl, String mediaTitle, boolean isVideo) {
        this(display, returnScreen, mediaUrl, mediaTitle, isVideo, (StorageManager) null);
    }

    public MediaPlayerCanvas(Display display, Displayable returnScreen, String mediaUrl, String mediaTitle, boolean isVideo, String gatewayUrl) {
        this(display, returnScreen, mediaUrl, mediaTitle, isVideo, (StorageManager) null);
        if (gatewayUrl != null && gatewayUrl.length() > 0) {
            this.gatewayUrl = gatewayUrl;
        }
    }

    public MediaPlayerCanvas(Display display, Displayable returnScreen, String mediaUrl, String mediaTitle, boolean isVideo, StorageManager storage) {
        setFullScreenMode(true);
        this.display = display;
        this.returnScreen = returnScreen;
        this.mediaUrl = mediaUrl;
        this.mediaTitle = (mediaTitle != null && mediaTitle.length() > 0) ? mediaTitle : "Media Stream";
        this.isVideo = isVideo;
        this.storage = storage;
        this.gatewayUrl = (storage != null && storage.getGatewayUrl() != null) ? storage.getGatewayUrl() : "http://127.0.0.1:8080";

        this.fontSmallBold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        this.fontSmallPlain = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);

        this.currentVideoFrame = null;
        this.running = true;
        this.isPlaying = false;
        this.isPaused = false;
        this.isFullscreenVideo = false;
        this.statusMessage = "Connecting...";
        this.volumeLevel = 80;
        this.durationUs = -1;
        this.mediaTimeUs = 0;
        this.animFrame = 0;
        this.frameBuffer = new byte[32768];

        startPlayback();
    }

    public boolean isLandscape() {
        if (storage != null) {
            int o = storage.getOrientation();
            if (o == StorageManager.ORIENTATION_LANDSCAPE) return true;
            if (o == StorageManager.ORIENTATION_PORTRAIT) return false;
        }
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
        repaint();
    }

    private void startPlayback() {
        if (isVideo) {
            // Start video frame streaming thread
            videoStreamThread = new Thread(new Runnable() {
                public void run() {
                    runVideoFrameStreamer(0);
                }
            });
            videoStreamThread.start();
        }

        // Start audio playback thread (companion audio for video, or standalone audio)
        Thread audioThread = new Thread(new Runnable() {
            public void run() {
                runAudioPlayer(0);
            }
        });
        audioThread.start();

        // Animation thread for UI updates
        animThread = new Thread(this);
        animThread.start();
    }

    /**
     * Synchronized Audio Player:
     * Plays companion audio stream (/video_audio) for video, or standalone audio.
     * Universal across MicroEmulator (PC) and real Nokia J2ME MMAPI devices.
     */
    private void runAudioPlayer(long startSec) {
        closeAudioPlayer();
        String audioUrl = buildAudioUrl(mediaUrl, startSec);
        try {
            // 1. Try URL locator with Manager.createPlayer
            try {
                player = Manager.createPlayer(audioUrl);
            } catch (Throwable t) {
                player = null;
            }

            // 2. Fallback: Open HttpConnection stream for Nokia MMAPI
            if (player == null && (audioUrl.startsWith("http://") || audioUrl.startsWith("https://"))) {
                try {
                    HttpConnection conn = (HttpConnection) Connector.open(audioUrl, Connector.READ, true);
                    conn.setRequestMethod(HttpConnection.GET);
                    conn.setRequestProperty("User-Agent", "Nokia6300/J2ME");
                    InputStream is = conn.openInputStream();
                    player = Manager.createPlayer(is, "audio/x-wav");
                } catch (Throwable t) {
                    player = null;
                }
            }

            if (player != null) {
                player.addPlayerListener(this);
                player.realize();

                try {
                    volumeControl = (VolumeControl) player.getControl("VolumeControl");
                    if (volumeControl != null) {
                        volumeControl.setLevel(volumeLevel);
                    }
                } catch (Throwable t) {}

                player.prefetch();
                if (durationUs <= 0) {
                    long dur = player.getDuration();
                    if (dur > 0) durationUs = dur;
                }
                player.start();
                if (!isVideo) {
                    isPlaying = true;
                    statusMessage = "Playing";
                }
            } else {
                if (!isVideo) {
                    statusMessage = "Audio playback failed";
                }
            }
        } catch (Throwable t) {
            if (!isVideo) {
                statusMessage = "Audio playback failed";
            }
        }
        repaint();
    }

    private void closeAudioPlayer() {
        if (player != null) {
            try {
                player.stop();
                player.close();
            } catch (Throwable t) {}
            player = null;
        }
        volumeControl = null;
    }

    /**
     * Universal Video Frame Streamer:
     * Connects to Gateway's /video_stream endpoint and decodes 240x180 QVGA frames.
     */
    private void runVideoFrameStreamer(long startSec) {
        String streamUrl = buildStreamUrl(mediaUrl, startSec);
        try {
            statusMessage = "Buffering video...";
            repaint();

            streamConn = (HttpConnection) Connector.open(streamUrl, Connector.READ, true);
            streamConn.setRequestMethod(HttpConnection.GET);
            streamConn.setRequestProperty("User-Agent", "Nokia6300/J2ME");

            streamDis = streamConn.openDataInputStream();

            // Read magic: 'NVID'
            byte[] magic = new byte[4];
            streamDis.readFully(magic);
            String magicStr = new String(magic);

            if (!"NVID".equals(magicStr)) {
                statusMessage = "Stream error: Invalid format";
                repaint();
                return;
            }

            int durMs = streamDis.readInt();
            if (durMs > 0) {
                durationUs = (long) durMs * 1000L;
            }

            isPlaying = true;
            isPaused = false;
            statusMessage = "Playing";
            repaint();

            while (running) {
                if (isPaused) {
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                    continue;
                }

                int len = streamDis.readInt();
                if (len <= 0) {
                    statusMessage = "Finished";
                    isPlaying = false;
                    repaint();
                    break;
                }

                int curMs = streamDis.readInt();
                if (len > frameBuffer.length) {
                    frameBuffer = new byte[Math.max(len, frameBuffer.length * 2)];
                }
                streamDis.readFully(frameBuffer, 0, len);

                try {
                    Image frame = Image.createImage(frameBuffer, 0, len);
                    this.currentVideoFrame = frame;
                    this.mediaTimeUs = (long) curMs * 1000L;
                    // Efficient dirty region repaint for 240x144 video viewport
                    repaint(0, 44, getWidth(), 144);
                } catch (Throwable t) {
                    // Frame decode skip
                }
            }

        } catch (Exception e) {
            if (running) {
                statusMessage = "Stream ended";
                isPlaying = false;
                repaint();
            }
        } finally {
            closeStreamConnection();
        }
    }

    private String buildStreamUrl(String rawUrl, long startSec) {
        String base;
        if (rawUrl.indexOf("/media?url=") >= 0) {
            base = replaceString(rawUrl, "/media?url=", "/video_stream?url=");
        } else if (rawUrl.indexOf("/video.3gp?url=") >= 0) {
            base = replaceString(rawUrl, "/video.3gp?url=", "/video_stream?url=");
        } else if (rawUrl.indexOf("/media_3gp?url=") >= 0) {
            base = replaceString(rawUrl, "/media_3gp?url=", "/video_stream?url=");
        } else if (rawUrl.indexOf("/video_stream?url=") >= 0) {
            base = rawUrl;
        } else if (rawUrl.startsWith("/")) {
            base = gatewayUrl + rawUrl;
        } else if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            base = gatewayUrl + "/video_stream?url=" + com.nokia.browser.net.NetworkManager.urlEncode(rawUrl);
        } else {
            base = gatewayUrl + "/video_stream?url=" + rawUrl;
        }
        char sep = (base.indexOf('?') >= 0) ? '&' : '?';
        String sizeParam = isLandscape() ? "&max_w=320&max_h=180" : "&max_w=240&max_h=144";
        return base + sep + "t=" + startSec + "&fps=8" + sizeParam;
    }

    private String buildAudioUrl(String rawUrl, long startSec) {
        String base;
        if (rawUrl.indexOf("/media?url=") >= 0) {
            base = replaceString(rawUrl, "/media?url=", "/video_audio?url=");
        } else if (rawUrl.indexOf("/video.3gp?url=") >= 0) {
            base = replaceString(rawUrl, "/video.3gp?url=", "/video_audio?url=");
        } else if (rawUrl.indexOf("/media_3gp?url=") >= 0) {
            base = replaceString(rawUrl, "/media_3gp?url=", "/video_audio?url=");
        } else if (rawUrl.indexOf("/video_stream?url=") >= 0) {
            base = replaceString(rawUrl, "/video_stream?url=", "/video_audio?url=");
        } else if (rawUrl.indexOf("/video_audio?url=") >= 0) {
            base = rawUrl;
        } else if (!isVideo && (rawUrl.endsWith(".wav") || rawUrl.endsWith(".mp3"))) {
            if (rawUrl.startsWith("/")) base = gatewayUrl + rawUrl;
            else base = rawUrl;
        } else if (rawUrl.startsWith("/")) {
            base = gatewayUrl + "/video_audio?url=" + com.nokia.browser.net.NetworkManager.urlEncode(rawUrl);
        } else if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            base = gatewayUrl + "/video_audio?url=" + com.nokia.browser.net.NetworkManager.urlEncode(rawUrl);
        } else {
            base = gatewayUrl + "/video_audio?url=" + rawUrl;
        }
        char sep = (base.indexOf('?') >= 0) ? '&' : '?';
        return base + sep + "t=" + startSec;
    }

    private static String replaceString(String source, String target, String replacement) {
        int idx = source.indexOf(target);
        if (idx >= 0) {
            return source.substring(0, idx) + replacement + source.substring(idx + target.length());
        }
        return source;
    }

    private void closeStreamConnection() {
        if (streamDis != null) {
            try { streamDis.close(); } catch (Exception e) {}
            streamDis = null;
        }
        if (streamConn != null) {
            try { streamConn.close(); } catch (Exception e) {}
            streamConn = null;
        }
    }

    public void playerUpdate(Player player, String event, Object eventData) {
        if (PlayerListener.STARTED.equals(event)) {
            if (!isVideo) isPlaying = true;
        } else if (PlayerListener.STOPPED.equals(event)) {
            if (!isVideo) isPlaying = false;
        } else if (PlayerListener.END_OF_MEDIA.equals(event)) {
            if (!isVideo) {
                isPlaying = false;
                statusMessage = "Finished";
            }
        }
        repaint();
    }

    public void run() {
        while (running) {
            if (isVideo) {
                // When playing video, UI only needs progress bar updates at 1 Hz
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                if (running && isPlaying && !isPaused) {
                    repaint(16, 210, getWidth() - 32, 28);
                }
            } else {
                // Audio equalizer animation at ~7 FPS
                animFrame = (animFrame + 1) % 360;
                if (player != null && isPlaying) {
                    try {
                        mediaTimeUs = player.getMediaTime();
                    } catch (Exception e) {}
                }
                repaint(20, 50, getWidth() - 40, 180);
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {}
            }
        }
    }

    public void stopAndClose() {
        running = false;
        closeStreamConnection();
        closeAudioPlayer();

        if (videoControl != null) {
            try {
                videoControl.setVisible(false);
            } catch (Exception e) {}
            videoControl = null;
        }
        display.setCurrent(returnScreen);
    }

    protected void keyPressed(int keyCode) {
        int gameAction = 0;
        try {
            gameAction = getGameAction(keyCode);
        } catch (Exception e) {}

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

        if (keyCode == -6 || keyCode == '7') {
            stopAndClose();
            return;
        }

        if (keyCode == -7) {
            stopAndClose();
            return;
        }

        // Star (*): Toggle Fullscreen Video Mode
        if (keyCode == '*' && isVideo) {
            isFullscreenVideo = !isFullscreenVideo;
            repaint();
            return;
        }

        // Toggle Play / Pause
        if (gameAction == FIRE || keyCode == '5' || keyCode == -5 || keyCode == 10) {
            togglePlayPause();
            return;
        }

        // Volume Up
        if (gameAction == UP || keyCode == '2' || keyCode == -1) {
            adjustVolume(10);
            return;
        }

        // Volume Down
        if (gameAction == DOWN || keyCode == '8' || keyCode == -2) {
            adjustVolume(-10);
            return;
        }

        // Seek -5s
        if (gameAction == LEFT || keyCode == '4' || keyCode == -3) {
            seekRelative(-5);
            return;
        }

        // Seek +5s
        if (gameAction == RIGHT || keyCode == '6' || keyCode == -4) {
            seekRelative(5);
            return;
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

        // Bottom Softkeys Bar
        if (ly >= lh - 25) {
            if (lx > lw / 3 && lx < (lw * 2) / 3 && isVideo) {
                isFullscreenVideo = !isFullscreenVideo;
                repaint();
            } else {
                stopAndClose();
            }
            return;
        }

        // Tap on video area toggles Play / Pause
        if (isVideo) {
            togglePlayPause();
            return;
        }

        // Tap on volume bar area
        if (ly >= lh - 85 && ly <= lh - 55) {
            if (lx < lw / 2) adjustVolume(-10);
            else adjustVolume(10);
            return;
        }
    }

    private void togglePlayPause() {
        if (isVideo) {
            isPaused = !isPaused;
            isPlaying = !isPaused;
            statusMessage = isPaused ? "Paused" : "Playing";
            if (videoControl != null) {
                try {
                    videoControl.setVisible(!isPaused);
                } catch (Exception e) {}
            }
            if (player != null) {
                try {
                    if (isPaused) player.stop();
                    else player.start();
                } catch (Exception e) {}
            }
        } else {
            if (player != null) {
                try {
                    if (isPlaying) {
                        player.stop();
                        isPlaying = false;
                        statusMessage = "Paused";
                    } else {
                        player.start();
                        isPlaying = true;
                        statusMessage = "Playing";
                    }
                } catch (Exception e) {}
            }
        }
        repaint();
    }

    private void adjustVolume(int delta) {
        volumeLevel += delta;
        if (volumeLevel > 100) volumeLevel = 100;
        if (volumeLevel < 0) volumeLevel = 0;
        if (volumeControl != null) {
            try {
                volumeControl.setLevel(volumeLevel);
            } catch (Exception e) {}
        }
        repaint();
    }

    private void seekRelative(int deltaSec) {
        long currentSec = mediaTimeUs / 1000000L;
        long targetSec = currentSec + deltaSec;
        if (targetSec < 0) targetSec = 0;

        if (isVideo) {
            // Reconnect frame streamer at new second
            closeStreamConnection();
            final long sSec = targetSec;
            Thread seekThread = new Thread(new Runnable() {
                public void run() {
                    runVideoFrameStreamer(sSec);
                }
            });
            seekThread.start();

            // Reconnect companion audio player at new second
            Thread audioSeekThread = new Thread(new Runnable() {
                public void run() {
                    runAudioPlayer(sSec);
                }
            });
            audioSeekThread.start();
        } else {
            // Pure audio seek
            final long sSec = targetSec;
            Thread audioSeekThread = new Thread(new Runnable() {
                public void run() {
                    runAudioPlayer(sSec);
                }
            });
            audioSeekThread.start();
        }
        repaint();
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
        // Background
        g.setColor(0x0F172A);
        g.fillRect(0, 0, w, h);

        // 1. Fullscreen Video Mode
        if (isFullscreenVideo && isVideo) {
            if (currentVideoFrame != null) {
                int imgW = currentVideoFrame.getWidth();
                int imgH = currentVideoFrame.getHeight();
                int imgX = (w - imgW) / 2;
                int imgY = (h - imgH) / 2;
                g.drawImage(currentVideoFrame, imgX, imgY, Graphics.TOP | Graphics.LEFT);
            } else if (videoControl == null) {
                g.setColor(0x38BDF8);
                g.setFont(fontSmallBold);
                g.drawString("[ Buffering Fullscreen Video... ]", w / 2, h / 2 - 8, Graphics.HCENTER | Graphics.TOP);
            }

            if (isPaused) {
                g.setColor(0x000000);
                g.fillRect(w / 2 - 90, h - 26, 180, 20);
                g.setColor(0xFBBF24);
                g.setFont(fontSmallBold);
                g.drawString("[ PAUSED | 5: Play | *: Exit ]", w / 2, h - 23, Graphics.HCENTER | Graphics.TOP);
            }
            return;
        }

        boolean land = (w >= 300);

        if (land) {
            // Widescreen Landscape Layout (320x240)
            // Header Bar (y: 0..18)
            g.setColor(0x1E293B);
            g.fillRect(0, 0, w, 18);
            g.setColor(0x38BDF8);
            g.setFont(fontSmallBold);
            String hdr = isVideo ? "▶ Video" : "♫ Audio";
            g.drawString(hdr, 6, 2, Graphics.TOP | Graphics.LEFT);

            g.setColor(0xF8FAFC);
            g.setFont(fontSmallPlain);
            String dispTitle = mediaTitle;
            if (dispTitle.length() > 36) dispTitle = dispTitle.substring(0, 33) + "...";
            g.drawString(dispTitle, 60, 2, Graphics.TOP | Graphics.LEFT);

            // Viewport (y: 20..162)
            int vidY = 20;
            int vidH = 142;
            if (isVideo) {
                if (currentVideoFrame != null) {
                    int imgW = currentVideoFrame.getWidth();
                    int imgH = currentVideoFrame.getHeight();
                    int imgX = (w - imgW) / 2;
                    int imgY = vidY + (vidH - imgH) / 2;
                    g.drawImage(currentVideoFrame, imgX, imgY, Graphics.TOP | Graphics.LEFT);
                } else if (videoControl == null) {
                    g.setColor(0x1E293B);
                    g.fillRect((w - 248) / 2, vidY, 248, vidH);
                    g.setColor(0x334155);
                    g.drawRect((w - 248) / 2, vidY, 247, vidH);
                    g.setColor(0x38BDF8);
                    g.setFont(fontSmallBold);
                    g.drawString("[ Buffering 3GP / Video... ]", w / 2, vidY + 58, Graphics.HCENTER | Graphics.TOP);
                }
            } else {
                drawEqualizer(g, 20, vidY + 6, w - 40, vidH - 12);
            }

            // Status & Time indicators (y: 165)
            g.setColor(isPlaying ? 0x4ADE80 : 0xFBBF24);
            g.setFont(fontSmallBold);
            g.drawString("Status: " + statusMessage, 12, 165, Graphics.TOP | Graphics.LEFT);

            g.setColor(0x94A3B8);
            g.setFont(fontSmallPlain);
            String curTimeStr = formatTime(mediaTimeUs);
            String durTimeStr = durationUs > 0 ? formatTime(durationUs) : "--:--";
            g.drawString(curTimeStr + " / " + durTimeStr, w - 12, 165, Graphics.TOP | Graphics.RIGHT);

            // Progress Bar (y: 179)
            int pbX = 12;
            int pbY = 179;
            int pbW = w - 24;
            int pbH = 5;
            g.setColor(0x334155);
            g.fillRoundRect(pbX, pbY, pbW, pbH, 4, 4);
            if (durationUs > 0) {
                int fillW = (int) ((mediaTimeUs * pbW) / durationUs);
                if (fillW > pbW) fillW = pbW;
                g.setColor(0x38BDF8);
                g.fillRoundRect(pbX, pbY, fillW, pbH, 4, 4);
            }

            // Controls & Volume Bar (y: 191)
            g.setColor(0xE2E8F0);
            g.setFont(fontSmallPlain);
            g.drawString("Vol: " + volumeLevel + "%", 12, 191, Graphics.TOP | Graphics.LEFT);
            int volW = 50;
            g.setColor(0x334155);
            g.fillRect(68, 195, volW, 5);
            g.setColor(0x22C55E);
            g.fillRect(68, 195, (volumeLevel * volW) / 100, 5);

            g.setColor(0x64748B);
            g.drawString("5: Play | 4/6: Seek | *: Fullscreen", w - 12, 191, Graphics.TOP | Graphics.RIGHT);

            // Bottom Softkeys Bar (y: 218..240)
            int footY = h - 22;
            g.setColor(0x1E293B);
            g.fillRect(0, footY, w, 22);
            g.setColor(0xF8FAFC);
            g.setFont(fontSmallBold);
            g.drawString("Stop", 6, footY + 3, Graphics.TOP | Graphics.LEFT);
            if (isVideo) {
                g.setColor(0x38BDF8);
                g.drawString("Fullscreen (*)", w / 2, footY + 3, Graphics.HCENTER | Graphics.TOP);
            }
            g.setColor(0xF8FAFC);
            g.drawString("Back", w - 6, footY + 3, Graphics.TOP | Graphics.RIGHT);

        } else {
            // Original Portrait Layout (240x320)
            // Header Bar (y: 0..24)
            g.setColor(0x1E293B);
            g.fillRect(0, 0, w, 24);
            g.setColor(0x38BDF8);
            g.setFont(fontSmallBold);
            String hdr = isVideo ? "▶ KamTape 3GP/Video" : "♫ Audio Player";
            g.drawString(hdr, 6, 4, Graphics.TOP | Graphics.LEFT);

            // Title
            g.setColor(0xF8FAFC);
            g.setFont(fontSmallPlain);
            String dispTitle = mediaTitle;
            if (dispTitle.length() > 28) {
                dispTitle = dispTitle.substring(0, 25) + "...";
            }
            g.drawString(dispTitle, 6, 28, Graphics.TOP | Graphics.LEFT);

            // Visual Display Area (y: 44..188)
            if (isVideo) {
                if (currentVideoFrame != null) {
                    int imgW = currentVideoFrame.getWidth();
                    int imgH = currentVideoFrame.getHeight();
                    int imgX = (w - imgW) / 2;
                    int imgY = 44 + (144 - imgH) / 2;
                    g.drawImage(currentVideoFrame, imgX, imgY, Graphics.TOP | Graphics.LEFT);
                } else if (videoControl == null) {
                    g.setColor(0x1E293B);
                    g.fillRect(0, 44, w, 144);
                    g.setColor(0x334155);
                    g.drawRect(0, 44, w - 1, 144);
                    g.setColor(0x38BDF8);
                    g.setFont(fontSmallBold);
                    g.drawString("[ Buffering 3GP / Video... ]", w / 2, 105, Graphics.HCENTER | Graphics.TOP);
                }
            } else {
                drawEqualizer(g, 20, 50, w - 40, 120);
            }

            // Status Message
            g.setColor(isPlaying ? 0x4ADE80 : 0xFBBF24);
            g.setFont(fontSmallBold);
            g.drawString("Status: " + statusMessage, w / 2, 194, Graphics.HCENTER | Graphics.TOP);

            // Progress Bar (y: 212)
            int pbX = 16;
            int pbY = 212;
            int pbW = w - 32;
            int pbH = 6;
            g.setColor(0x334155);
            g.fillRoundRect(pbX, pbY, pbW, pbH, 4, 4);

            if (durationUs > 0) {
                int fillW = (int) ((mediaTimeUs * pbW) / durationUs);
                if (fillW > pbW) fillW = pbW;
                g.setColor(0x38BDF8);
                g.fillRoundRect(pbX, pbY, fillW, pbH, 4, 4);
            }

            // Time indicators
            g.setColor(0x94A3B8);
            g.setFont(fontSmallPlain);
            String curTimeStr = formatTime(mediaTimeUs);
            String durTimeStr = durationUs > 0 ? formatTime(durationUs) : "--:--";
            g.drawString(curTimeStr, pbX, pbY + 8, Graphics.TOP | Graphics.LEFT);
            g.drawString(durTimeStr, pbX + pbW, pbY + 8, Graphics.TOP | Graphics.RIGHT);

            // Controls Bar
            g.setColor(0xE2E8F0);
            g.drawString("Vol: " + volumeLevel + "%", 16, 244, Graphics.TOP | Graphics.LEFT);
            int volW = 60;
            g.setColor(0x334155);
            g.fillRect(80, 248, volW, 5);
            g.setColor(0x22C55E);
            g.fillRect(80, 248, (volumeLevel * volW) / 100, 5);

            // Controls Help
            g.setColor(0x64748B);
            g.setFont(fontSmallPlain);
            g.drawString("5: Play/Pause | 4/6: Seek | *: Fullscreen", w / 2, 270, Graphics.HCENTER | Graphics.TOP);

            // Bottom Softkeys Bar
            g.setColor(0x1E293B);
            g.fillRect(0, 298, w, 22);
            g.setColor(0xF8FAFC);
            g.setFont(fontSmallBold);
            g.drawString("Stop", 6, 302, Graphics.TOP | Graphics.LEFT);
            g.drawString("Back", w - 6, 302, Graphics.TOP | Graphics.RIGHT);
        }
    }

    private void drawEqualizer(Graphics g, int x, int y, int width, int height) {
        int numBars = 9;
        int barW = (width - (numBars - 1) * 4) / numBars;

        for (int i = 0; i < numBars; i++) {
            int bx = x + i * (barW + 4);
            int barHeight;
            if (isPlaying) {
                int angle = (animFrame * 12 + i * 40) % 360;
                int sinVal = (int) (Math.sin(angle * 3.14159 / 180.0) * 100);
                barHeight = 15 + Math.abs(sinVal) * (height - 20) / 100;
            } else {
                barHeight = 6;
            }

            int by = y + height - barHeight;

            g.setColor(0x22C55E);
            g.fillRect(bx, by, barW, barHeight);

            g.setColor(0xEF4444);
            g.fillRect(bx, y + height - barHeight - 4, barW, 2);
        }
    }

    private String formatTime(long us) {
        long totalSec = us / 1000000L;
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        String m = String.valueOf(min);
        String s = String.valueOf(sec);
        if (s.length() < 2) s = "0" + s;
        return m + ":" + s;
    }
}
