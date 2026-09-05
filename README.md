# Nokia Web - Modern J2ME Browser (240x320)

A high-performance modern web browser engineered specifically for vintage **Nokia J2ME** (Series 40 and Symbian S60) devices with a **240x320 QVGA** screen, featuring **Modern HTTPS (TLS 1.2/1.3)** support and **Multimedia (Audio/Video/Image)** streaming.

---

## Key Features

- **Pixel-Perfect 240x320 QVGA Rendering:** Tailored layout engine with automatic word wrapping, heading hierarchy, blockquotes, lists, and focus ring navigation.
- **Modern HTTPS (TLS 1.2 & 1.3):** Transparently loads modern encrypted websites (Wikipedia, Bing, Hacker News, news portals, GitHub) that standard vintage J2ME devices cannot connect to directly.
- **Multimedia Support (MMAPI):**
  - **Audio Player:** Dedicated 240x320 audio player canvas with retro equalizer visualizer, elapsed/total time, volume slider (0-100%), and playback controls (MP3, WAV, AMR, AAC).
  - **Video Player:** 3GP and MP4 video playback support with MMAPI `VideoControl` integration.
- **Nokia Keypad & D-Pad Navigation:**
  - **D-Pad Up / Down:** Jump between links and media cards or smooth scroll.
  - **D-Pad Center / Key 5:** Open selected link or launch media player.
  - **Key 1 / 7:** Fast Page Up / Page Down.
  - **Key 3 / 9:** Jump directly to Top / Bottom of page.
  - **Key 2 / 8:** Line scroll up / down.
  - **Key 4 / 6:** History Back / Forward.
  - **Key 0:** Instant Bookmarks dialog.
  - **Key \*:** Toggle Fullscreen mode (maximizes 240x320 content).
  - **Key #:** Quick URL entry & Search bar.
  - **Left Softkey:** Options Menu.
  - **Right Softkey:** Back / Exit.
- **Persistent Storage (RMS):** Bookmarks, history, and user settings saved to phone memory across restarts.
- **Ultra-Lightweight Footprint:** The compiled client JAR is only **~34 KB**, preserving maximum Java heap RAM for smooth page rendering.

---

## Architecture

```
+-------------------------------------------------------------------+
|               Nokia J2ME Phone (240 x 320 Screen)                 |
|                                                                   |
|  +-------------------------------------------------------------+  |
|  | NokiaBrowser.jar (MIDP 2.0 / CLDC 1.1)                      |  |
|  |                                                             |  |
|  |  +-------------------+  +--------------------------------+  |  |
|  |  | BrowserCanvas     |  | MediaPlayerCanvas (MMAPI)      |  |  |
|  |  | - 240x320 QVGA    |  | - Audio (MP3, AMR, AAC, WAV)   |  |  |
|  |  | - D-Pad & Keypad  |  | - Video (3GP, MP4 baseline)    |  |  |
|  |  | - Box/Flow layout |  | - Equalizer & Volume HUD       |  |  |
|  |  +---------+---------+  +---------------+----------------+  |  |
|  |            |                            |                   |  |
|  |  +---------v----------------------------v----------------+  |  |
|  |  | NetworkManager & Storage (RMS Bookmarks/History)      |  |  |
|  |  +--------------------------+----------------------------+  |  |
|  +-----------------------------|-------------------------------+  |
+--------------------------------|----------------------------------+
                                 | HTTP / Socket Request
                                 v
+-------------------------------------------------------------------+
|               Modern Gateway & Media Transcoder Server            |
|               (Node.js / Express Backend)                         |
|                                                                   |
|  +------------------+  +-------------------+  +----------------+  |
|  | HTTPS/TLS 1.3    |  | HTML Reflow Engine|  | Media Extractor|  |
|  | - TLS 1.2/1.3    |  | - 240px Reflow    |  | - Audio stream |  |
|  | - Modern ciphers |  | - Strips JS bloat |  | - 3GP / MP4    |  |
|  | - SNI & Certs    |  | - Downscales img  |  | - MMAPI stream |  |
|  +--------+---------+  +---------+---------+  +--------+-------+  |
+-----------|----------------------|---------------------|----------+
            |                      |                     |
            +----------------------v---------------------+
                        Modern Web (HTTPS)
            (Wikipedia, News, Reddit, YouTube audio, Search)
```

---

## Quick Start

### 1. Start the Gateway Server
```bash
./run_server.sh
```
The server runs on port 8080 (or `PORT=...`).

### 2. Test in Emulator (MicroEmulator 240x320)
```bash
./run_emulator.sh
```

### 3. Build JAR and JAD from Source
```bash
./build.sh
```
Produces:
- `build/NokiaBrowser.jar` (~34 KB)
- `build/NokiaBrowser.jad`

---

## Installing on Real Nokia Phones (e.g. Nokia 6300, 2700, C2-01, E51)

1. Connect your Nokia phone via USB (Data Storage mode) or Bluetooth.
2. Transfer both `NokiaBrowser.jar` and `NokiaBrowser.jad` to the `Applications` or `Memory Card` folder.
3. On the phone, select `NokiaBrowser` and press **Install** or **Launch**.
4. In the browser menu:
   - Press **Left Softkey (Options)** -> **Settings**.
   - Set **Gateway URL** to your computer's local Wi-Fi IP (e.g. `http://192.168.1.100:8080`) or your public VPS URL.
   - Press **OK** to save.

---

## Media Testing
The Gateway includes a built-in media showcase:
- Open URL: `http://127.0.0.1:8080/sample_media`
- Includes the iconic Nokia Tune (WAV), live radio streams (MP3), and sample MP4 video.
- Use key `5` / Fire to start playback. Control volume with `2` (Up) and `8` (Down), and seek with `4` (Rev) and `6` (Fwd).

---

## KamTape Video & 3GP Playback (www.kamtape.com)

Dedicated integration for streaming retro video content from **KamTape** (`www.kamtape.com`):
- **Authentic 3GP Streaming (`video/3gpp`):** Real-time transcoding pipeline on the gateway converting KamTape video streams to standard mobile **3GP (H.263 176x144, 15 FPS, AMR-NB 8kHz mono audio)** optimized specifically for vintage Nokia Series 40 and Symbian S60 DSP hardware decoders.
- **Hardware Acceleration & VideoControl:** Supported via J2ME MMAPI `VideoControl` for in-browser hardware rendering, plus native launch in the phone's standalone RealPlayer via `MIDlet.platformRequest()`.
- **Universal Video Frame Streamer (240x180 QVGA @ 8 FPS):** High-compatibility streaming engine that decodes and resizes video frames on the gateway into optimized 240x180 JPEG frames, rendering via standard LCDUI `Graphics.drawImage()`. Works on 100% of J2ME runtimes (MicroEmulator on PC and real phones).
- **Browse Videos:** Navigate to `https://www.kamtape.com` to see featured videos with thumbnails and direct play links.
- **Watch Page (`/watch?v=...`):** Automatically extracts video details, uploader, description, thumbnail still, and presents direct playback choices:
  - `[▶ Video] Play 3GP (Nokia)`: Hardware-accelerated 3GP in the browser.
  - `[Link] Launch in Nokia RealPlayer`: Fullscreen playback in Nokia's native system video player.
  - `[▶ Video] Play Video (Stream)`: Universal frame streamer.
  - `[♫ Audio] Audio Track`: Bandwidth-friendly audio playback.
- **Bookmark:** Added to default bookmarks (`0` key $\rightarrow$ **KamTape Videos**).

---

## FrogFind! Support (https://www.frogfind.com)

First-class support for **FrogFind** (`https://www.frogfind.com`), Action Retro's search engine and readability proxy specifically engineered for vintage computers and legacy browsers:
- **Iconic Mascot & Retro UI:** Loads the original FrogFind mascot logo (`/static/frogfind.gif`, 174x80 QVGA fit), title, and vintage search shortcuts.
- **Search Queries:** Entering `https://www.frogfind.com/?q=<term>` or selecting the "Leap with FrogFind" search prompt performs resilient web search, returning clean, numbered search results with titles, snippets, direct links, and FrogFind Reader links.
- **FrogFind Reader Mode (`/read.php?a=<url>`):** Automatically fetches the target webpage over modern HTTPS, strips away heavy styling, JavaScript, and trackers through our gateway's 240px mobile reflow engine, and provides clean, fast reading with "🐸 Back to FrogFind Search" navigation.
- **Instant Shortcuts:** Type `frogfind` directly into the URL bar (`#` key) to open the FrogFind home page immediately.
- **Bookmark:** Added to default bookmarks (`0` key $\rightarrow$ **FrogFind! (Retro Search)**).
- **Browser Menu Option:** Select **Left Softkey (Options)** $\rightarrow$ **FrogFind Retro Search** for quick search prompt.
- **Default Search Engine Toggle:** In **Settings**, easily toggle your browser's default search engine between **Bing Search** and **FrogFind! (Retro)**.

---

## License

This project is open source and available under the [MIT License](LICENSE).

