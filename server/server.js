/**
 * Modern Nokia J2ME Gateway & Media Transcoder Server
 * 
 * Provides:
 * 1. Modern HTTPS (TLS 1.2/1.3) bridging using native fetch with full ciphers/SNI.
 * 2. Mobile HTML reflow engine designed for 240x320 Nokia QVGA screen.
 * 3. Dedicated KamTape (kamtape.com) video playback integration.
 * 4. High-performance Video Frame Streamer for J2ME (OpenCV QVGA 240x180 at 8 FPS).
 * 5. Media detector & streaming proxy for J2ME MMAPI (Audio: MP3/AMR/AAC, Video: 3GP/MP4).
 * 6. Image proxying for 240px width constraints.
 * 7. Instant mobile search powered by Bing.
 * 8. Sample media hub for immediate testing on J2ME devices.
 */

const http = require('http');
const url = require('url');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { spawn } = require('child_process');
const kamtape = require('./kamtape');
const frogfind = require('./frogfind');
const youtube = require('./youtube');
const { execFile } = require('child_process');
const ftp = require('./ftp');

const directStreamUrlCache = new Map();
const inFlight3gpTranscodes = new Map();
async function resolveDirectVideoUrl(targetUrl) {
    if (!youtube.isYouTubeUrl(targetUrl)) return targetUrl;
    const vId = youtube.extractVideoId(targetUrl);
    if (vId && directStreamUrlCache.has(vId)) {
        const cached = directStreamUrlCache.get(vId);
        if (Date.now() - cached.timestamp < 20 * 60 * 1000) {
            return cached.url;
        }
    }
    const ytDlpPath = path.join(__dirname, '..', 'tools', 'yt-dlp');
    return new Promise((resolve) => {
        execFile(ytDlpPath, ['-g', '-f', '18/worst[ext=mp4]/bestvideo[height<=360]/best', targetUrl], (err, stdout) => {
            if (err || !stdout.trim()) {
                return resolve(targetUrl);
            }
            const directUrl = stdout.trim().split('\n')[0].trim();
            if (vId) {
                directStreamUrlCache.set(vId, { timestamp: Date.now(), url: directUrl });
            }
            resolve(directUrl);
        });
    });
}

// Allow connections to vintage HTTPS servers with legacy/misconfigured certificates
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

process.on('uncaughtException', (err) => {
    console.error('[Uncaught Exception]:', err && err.message ? err.message : err);
});
process.on('unhandledRejection', (reason) => {
    console.error('[Unhandled Rejection]:', reason);
});

const PORT = process.env.PORT || 8080;
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

function decodeHtmlEntities(str) {
    if (!str) return '';
    return str
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&#39;/g, "'")
        .replace(/&amp;/g, '&')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&nbsp;/g, ' ')
        .replace(/&#(\d+);/g, (match, dec) => {
            try { return String.fromCharCode(parseInt(dec, 10)); } catch (e) { return match; }
        })
        .replace(/&#x([0-9a-fA-F]+);/g, (match, hex) => {
            try { return String.fromCharCode(parseInt(hex, 16)); } catch (e) { return match; }
        });
}

function decodeBingUrl(rawUrl) {
    const match = rawUrl.match(/[?&]u=a1([a-zA-Z0-9_-]+)/);
    if (match) {
        try {
            let b64 = match[1].replace(/-/g, '+').replace(/_/g, '/');
            while (b64.length % 4) b64 += '=';
            return Buffer.from(b64, 'base64').toString('utf-8');
        } catch (e) {}
    }
    return rawUrl;
}

function extractImageInfo(imgTagOrAttrs, baseUrl) {
    if (!imgTagOrAttrs) return null;
    let src = '';
    const srcMatch = imgTagOrAttrs.match(/\bsrc=["']?([^"'\s>]+)["']?/i);
    const dataSrcMatch = imgTagOrAttrs.match(/\b(?:data-src|data-original|data-lazy-src|data-url)=["']?([^"'\s>]+)["']?/i);
    const srcsetMatch = imgTagOrAttrs.match(/\b(?:srcset|data-srcset)=["']?([^"'>]+)["']?/i);

    const isPlaceholder = (s) => !s || s.startsWith('data:') || s.includes('placeholder') || s.includes('spacer') || s.includes('blank.gif');

    let candidateFromSrcset = null;
    if (srcsetMatch) {
        const entries = srcsetMatch[1].split(',').map(s => s.trim().split(/\s+/));
        for (const entry of entries) {
            const u = entry[0];
            const w = entry[1];
            if (w && (w.endsWith('w') || w.endsWith('x'))) {
                const val = parseInt(w, 10);
                if (val >= 160 && val <= 480) {
                    candidateFromSrcset = u;
                    break;
                }
            }
        }
        if (!candidateFromSrcset && entries.length > 0 && !isPlaceholder(entries[0][0])) {
            candidateFromSrcset = entries[0][0];
        }
    }

    if (candidateFromSrcset && !isPlaceholder(candidateFromSrcset)) {
        src = candidateFromSrcset;
    } else if (srcMatch && !isPlaceholder(srcMatch[1])) {
        src = srcMatch[1];
    } else if (dataSrcMatch && !isPlaceholder(dataSrcMatch[1])) {
        src = dataSrcMatch[1];
    } else if (srcMatch && !srcMatch[1].startsWith('data:')) {
        src = srcMatch[1];
    }

    if (!src) return null;

    src = decodeHtmlEntities(src).trim();
    if (src.startsWith('//')) {
        src = 'https:' + src;
    }

    try {
        const absUrl = new URL(src, baseUrl).toString();
        let alt = '';
        const altMatch = imgTagOrAttrs.match(/\balt=["']?([^"']*)["']?/i);
        if (altMatch) {
            alt = decodeHtmlEntities(altMatch[1]).trim();
        }
        return { url: absUrl, alt: alt || 'Image' };
    } catch (e) {
        return null;
    }
}

function parseAndReflowHtml(rawHtml, baseUrl, isHttps, options = {}) {
    const loadImages = options.img !== '0';
    let html = rawHtml;

    let title = 'Untitled Page';
    const titleMatch = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
    if (titleMatch) {
        title = decodeHtmlEntities(titleMatch[1].replace(/\s+/g, ' ').trim());
    }

    // Resolve base tag if present
    const baseMatch = html.match(/<base\b[^>]*href=["']([^"']+)["']/i);
    const effectiveBaseUrl = baseMatch ? new URL(baseMatch[1], baseUrl).toString() : baseUrl;

    html = html.replace(/<!--[\s\S]*?-->/g, '');
    html = html.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '');
    html = html.replace(/<style\b[^<]*(?:(?!<\/style>)<[^<]*)*<\/style>/gi, '');
    html = html.replace(/<svg\b[^<]*(?:(?!<\/svg>)<[^<]*)*<\/svg>/gi, '');
    html = html.replace(/<iframe\b[^<]*(?:(?!<\/iframe>)<[^<]*)*<\/iframe>/gi, '');
    html = html.replace(/<noscript\b[^<]*(?:(?!<\/noscript>)<[^<]*)*<\/noscript>/gi, '');

    const elements = [];
    const seenImages = new Set();
    const maxElements = 350;
    let count = 0;

    function addImage(imgTagOrAttrs) {
        if (!loadImages || count >= maxElements) return;
        const info = extractImageInfo(imgTagOrAttrs, effectiveBaseUrl);
        if (info && !seenImages.has(info.url)) {
            seenImages.add(info.url);
            elements.push({ type: 'img', url: info.url, alt: info.alt });
            count++;
        }
    }

    // Audio tags
    const audioRegex = /<audio\b([^>]*)>([\s\S]*?)<\/audio>/gi;
    let audioMatch;
    while ((audioMatch = audioRegex.exec(html)) !== null) {
        const audioAttrs = audioMatch[1];
        const audioBody = audioMatch[2];
        let src = '';
        const srcMatch = audioAttrs.match(/src=["']([^"']+)["']/i);
        if (srcMatch) src = srcMatch[1];
        if (!src) {
            const sourceMatch = audioBody.match(/<source\b[^>]*src=["']([^"']+)["']/i);
            if (sourceMatch) src = sourceMatch[1];
        }
        if (src) {
            try {
                const absSrc = new URL(src, effectiveBaseUrl).toString();
                elements.push({ type: 'audio', url: absSrc, title: 'Audio: ' + path.basename(absSrc.split('?')[0]) });
            } catch (e) {}
        }
    }

    // Video tags
    const videoRegex = /<video\b([^>]*)>([\s\S]*?)<\/video>/gi;
    let videoMatch;
    while ((videoMatch = videoRegex.exec(html)) !== null) {
        const videoAttrs = videoMatch[1];
        const videoBody = videoMatch[2];
        let src = '';
        const srcMatch = videoAttrs.match(/src=["']([^"']+)["']/i);
        if (srcMatch) src = srcMatch[1];
        if (!src) {
            const sourceMatch = videoBody.match(/<source\b[^>]*src=["']([^"']+)["']/i);
            if (sourceMatch) src = sourceMatch[1];
        }
        if (src) {
            try {
                const absSrc = new URL(src, effectiveBaseUrl).toString();
                elements.push({ type: 'video', url: absSrc, title: 'Video: ' + path.basename(absSrc.split('?')[0]) });
            } catch (e) {}
        }
    }

    // Structural body tags (void tags img and hr matched as self-closing)
    const tagRegex = /<(h[1-6]|p|blockquote|li|a|figure)\b([^>]*)>([\s\S]*?)<\/\1>|<(img|hr)\b([^>]*)>/gi;
    let match;

    while ((match = tagRegex.exec(html)) !== null && count < maxElements) {
        const tag = (match[1] || match[4] || '').toLowerCase();
        const attrs = match[2] || match[5] || '';
        const inner = match[3] || '';

        let cleanText = decodeHtmlEntities(inner.replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();

        if (tag.startsWith('h')) {
            if (cleanText.length > 0) {
                elements.push({ type: 'heading', level: parseInt(tag.substring(1), 10), text: cleanText });
                count++;
            }
        } else if (tag === 'figure') {
            const figImgs = inner.match(/<img\b[^>]*>/gi);
            if (figImgs) {
                for (let fi = 0; fi < figImgs.length; fi++) addImage(figImgs[fi]);
            }
            const figCap = inner.match(/<figcaption\b[^>]*>([\s\S]*?)<\/figcaption>/i);
            if (figCap) {
                const cap = decodeHtmlEntities(figCap[1].replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
                if (cap.length > 0) {
                    elements.push({ type: 'p', text: cap });
                    count++;
                }
            }
        } else if (tag === 'p' || tag === 'blockquote') {
            const pImgs = inner.match(/<img\b[^>]*>/gi);
            if (pImgs) {
                for (let pi = 0; pi < pImgs.length; pi++) addImage(pImgs[pi]);
            }
            if (cleanText.length > 0) {
                const linkMatch = inner.match(/<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/i);
                if (linkMatch && cleanText.length < 80) {
                    try {
                        const absLink = new URL(linkMatch[1], effectiveBaseUrl).toString();
                        const linkText = decodeHtmlEntities(linkMatch[2].replace(/<[^>]+>/g, '').trim()) || cleanText;
                        elements.push({ type: 'link', url: absLink, text: linkText });
                        count++;
                        continue;
                    } catch (e) {}
                }
                elements.push({ type: tag === 'blockquote' ? 'quote' : 'p', text: cleanText });
                count++;
            }
        } else if (tag === 'li') {
            const liImgs = inner.match(/<img\b[^>]*>/gi);
            if (liImgs) {
                for (let li = 0; li < liImgs.length; li++) addImage(liImgs[li]);
            }
            if (cleanText.length > 0) {
                elements.push({ type: 'li', text: cleanText });
                count++;
            }
        } else if (tag === 'a') {
            const aImgs = inner.match(/<img\b[^>]*>/gi);
            if (aImgs) {
                for (let ai = 0; ai < aImgs.length; ai++) addImage(aImgs[ai]);
            }
            const hrefMatch = attrs.match(/href=["']([^"']+)["']/i);
            if (hrefMatch) {
                const href = hrefMatch[1];
                if (!href.startsWith('javascript:') && !href.startsWith('#')) {
                    try {
                        const absUrl = new URL(href, effectiveBaseUrl).toString();
                        const lower = absUrl.toLowerCase();
                        if (lower.endsWith('.mp3') || lower.endsWith('.wav') || lower.endsWith('.aac') || lower.endsWith('.amr')) {
                            elements.push({ type: 'audio', url: absUrl, title: cleanText || 'Audio' });
                            count++;
                        } else if (lower.endsWith('.mp4') || lower.endsWith('.3gp') || lower.endsWith('.avi')) {
                            elements.push({ type: 'video', url: absUrl, title: cleanText || 'Video' });
                            count++;
                        } else {
                            const linkText = cleanText.length > 0 ? cleanText : (aImgs && elements.length > 0 && elements[elements.length - 1].type === 'img' ? `[${elements[elements.length - 1].alt || 'Image Link'}]` : '');
                            if (linkText.length > 0) {
                                elements.push({ type: 'link', url: absUrl, text: linkText });
                                count++;
                            }
                        }
                    } catch (e) {}
                }
            }
        } else if (tag === 'img') {
            addImage(match[0]);
        } else if (tag === 'hr') {
            elements.push({ type: 'hr' });
            count++;
        }
    }

    if (elements.length === 0) {
        const plainText = decodeHtmlEntities(html.replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
        if (plainText.length > 0) {
            for (let i = 0; i < plainText.length && count < maxElements; i += 200) {
                elements.push({ type: 'p', text: plainText.substring(i, Math.min(i + 200, plainText.length)) });
                count++;
            }
        } else {
            elements.push({ type: 'p', text: 'Page loaded, but no readable text content was detected.' });
        }
    }

    return {
        title: title,
        url: effectiveBaseUrl,
        isHttps: isHttps,
        elements: elements
    };
}

function formatPagePayload(pageData, gatewayHost) {
    const lines = [];
    lines.push('META:TITLE=' + (pageData.title || 'Untitled'));
    lines.push('META:URL=' + (pageData.url || ''));
    lines.push('META:HTTPS=' + (pageData.isHttps ? '1' : '0'));

    for (let i = 0; i < pageData.elements.length; i++) {
        const el = pageData.elements[i];
        if (el.type === 'heading') {
            lines.push('H' + el.level + ':' + el.text);
        } else if (el.type === 'p') {
            lines.push('P:' + el.text);
        } else if (el.type === 'quote') {
            lines.push('Q:' + el.text);
        } else if (el.type === 'li') {
            lines.push('LI:' + el.text);
        } else if (el.type === 'link') {
            lines.push('L:' + el.url + '\t' + el.text);
        } else if (el.type === 'img') {
            const proxyImgUrl = 'http://' + gatewayHost + '/image?url=' + encodeURIComponent(el.url);
            const cleanAlt = (el.alt || 'Image').replace(/[\t\r\n]+/g, ' ').trim();
            lines.push('I:' + proxyImgUrl + '\t' + cleanAlt);
        } else if (el.type === 'audio') {
            const proxyAudioUrl = el.url.startsWith('http://' + gatewayHost) ? el.url : ('http://' + gatewayHost + '/media?url=' + encodeURIComponent(el.url));
            lines.push('A:' + proxyAudioUrl + '\t' + (el.title || 'Audio'));
        } else if (el.type === 'video') {
            const proxyVideoUrl = el.url.startsWith('http://' + gatewayHost) ? el.url : ('http://' + gatewayHost + '/media?url=' + encodeURIComponent(el.url));
            lines.push('V:' + proxyVideoUrl + '\t' + (el.title || 'Video'));
        } else if (el.type === 'hr') {
            lines.push('HR:');
        }
    }
    return lines.join('\n');
}

/**
 * Default Search Engine: Bing
 */
async function handleSearch(query, res, gatewayHost) {
    const searchUrl = 'https://www.bing.com/search?q=' + encodeURIComponent(query);
    try {
        const resp = await fetch(searchUrl, {
            headers: {
                'User-Agent': USER_AGENT,
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
            }
        });
        const html = await resp.text();

        const lines = [];
        lines.push('META:TITLE=Bing: ' + query);
        lines.push('META:URL=' + searchUrl);
        lines.push('META:HTTPS=1');
        lines.push('H1:Bing Search');
        lines.push('P:Results for: ' + query);
        lines.push('HR:');

        const algoRegex = /<li\b[^>]*class=["'][^"']*b_algo[^"']*["'][^>]*>([\s\S]*?)<\/li>/gi;
        let match;
        let resultCount = 0;

        while ((match = algoRegex.exec(html)) !== null && resultCount < 20) {
            const itemHtml = match[1];
            const linkMatch = itemHtml.match(/<h2\b[^>]*><a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a><\/h2>/i);
            const pMatch = itemHtml.match(/<p\b[^>]*>([\s\S]*?)<\/p>/i);

            if (linkMatch) {
                resultCount++;
                const rawLink = decodeHtmlEntities(linkMatch[1]);
                const directUrl = decodeBingUrl(rawLink);
                const title = decodeHtmlEntities(linkMatch[2].replace(/<[^>]+>/g, ' ').trim());
                const snippet = pMatch ? decodeHtmlEntities(pMatch[1].replace(/<[^>]+>/g, ' ').trim()) : '';

                lines.push('H2:' + title);
                lines.push('L:' + directUrl + '\tOpen: ' + (title.length > 25 ? title.substring(0, 22) + '...' : title));
                if (snippet) {
                    lines.push('P:' + snippet);
                }
                lines.push('HR:');
            }
        }

        if (resultCount === 0) {
            const parsed = parseAndReflowHtml(html, searchUrl, true, { img: '0' });
            parsed.title = 'Bing: ' + query;
            const payload = formatPagePayload(parsed, gatewayHost);
            res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
            return res.end(payload);
        }

        lines.push('P:Powered by Bing');
        const payload = lines.join('\n');
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(payload);

    } catch (e) {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('META:TITLE=Bing Search Error\nP:Failed to perform search: ' + e.message);
    }
}

/**
 * Handle Media Proxy (Direct audio/video streaming)
 */
async function handleMediaProxy(req, res, targetUrl) {
    try {
        const headers = {
            'User-Agent': USER_AGENT,
            'Accept': '*/*'
        };
        if (req.headers['range']) {
            headers['Range'] = req.headers['range'];
        }

        const mediaResp = await fetch(targetUrl, {
            headers: headers
        });

        const outHeaders = {
            'Content-Type': mediaResp.headers.get('content-type') || 'application/octet-stream',
            'Accept-Ranges': 'bytes',
            'Access-Control-Allow-Origin': '*'
        };
        if (mediaResp.headers.has('content-length')) {
            outHeaders['Content-Length'] = mediaResp.headers.get('content-length');
        }
        if (mediaResp.headers.has('content-range')) {
            outHeaders['Content-Range'] = mediaResp.headers.get('content-range');
        }

        res.writeHead(mediaResp.status, outHeaders);
        const reader = mediaResp.body.getReader();
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            res.write(value);
        }
        res.end();
    } catch (err) {
        console.error('Media proxy error:', err.message);
        if (!res.headersSent) {
            res.writeHead(502, { 'Content-Type': 'text/plain' });
            res.end('Failed to stream media: ' + err.message);
        }
    }
}

/**
 * Handle 3GP Streaming (Transcodes KamTape video to 3GPP for Nokia S40/S60)
 */
async function handle3gpStream(req, res, targetUrl, gatewayHost) {
    try {
        let reqUrl;
        try {
            reqUrl = new URL(req.url, `http://${gatewayHost}`);
        } catch (e) {
            reqUrl = new URL(targetUrl, `http://${gatewayHost}`);
        }
        const rawRes = (reqUrl.searchParams.get('res') || reqUrl.searchParams.get('q') || '240p').toLowerCase();
        let resProfile = '240p';
        if (rawRes === '144p' || rawRes === 'qcif') {
            resProfile = '144p';
        } else {
            resProfile = '240p'; // default: 240p QVGA (authentic Nokia screen resolution)
        }

        let videoUrl = targetUrl;
        if (videoUrl.includes('/media?url=')) {
            try {
                const u = new URL(videoUrl);
                videoUrl = u.searchParams.get('url') || videoUrl;
            } catch (e) {}
        }
        if (videoUrl.includes('kamtape.com/get_video') && !videoUrl.includes('webm=')) {
            videoUrl += (videoUrl.includes('?') ? '&' : '?') + 'webm=1';
        }

        let cacheBase = null;
        if (youtube.isYouTubeUrl(videoUrl)) {
            const vId = youtube.extractVideoId(videoUrl);
            cacheBase = 'yt_' + (vId || Buffer.from(videoUrl).toString('hex').substring(0, 16));
        } else {
            try {
                const u = new URL(videoUrl);
                cacheBase = u.searchParams.get('video_id') || u.searchParams.get('v');
            } catch (e) {}
            if (!cacheBase) {
                cacheBase = Buffer.from(videoUrl).toString('hex').substring(0, 16);
            }
        }

        const cacheKey = `${cacheBase}_${resProfile}`;
        const cacheDir = path.join(__dirname, 'cache', '3gp');
        if (!fs.existsSync(cacheDir)) {
            fs.mkdirSync(cacheDir, { recursive: true });
        }
        const cachedFilePath = path.join(cacheDir, `${cacheKey}.3gp`);

        // If cached file already exists and is complete (> 1KB), serve with range support
        if (fs.existsSync(cachedFilePath) && fs.statSync(cachedFilePath).size > 1000) {
            return serveStatic3gp(req, res, cachedFilePath, cacheKey);
        }

        console.log(`[3GP Streamer] Transcoding video to 3GP (${resProfile}) on the fly: ${videoUrl}`);
        const ffmpegPath = path.join(__dirname, '..', 'tools', 'ffmpeg');

        let vcodec, vbitrate, fps, acodec, audioArgs, videoFilter, sizeArgs;
        if (resProfile === '144p') {
            vcodec = 'h263';
            vbitrate = '128k';
            fps = '15';
            acodec = 'libopencore_amrnb';
            audioArgs = ['-ar', '8000', '-ac', '1', '-b:a', '12.2k'];
            sizeArgs = ['-s', '176x144'];
            videoFilter = null;
        } else {
            // 240p QVGA profile (default: up to 240p, MPEG-4 Simple Profile + AAC stereo in 3GP)
            vcodec = 'mpeg4';
            vbitrate = '350k';
            fps = null; // Native video framerate to ensure 1.0x normal real-time speed
            acodec = 'aac';
            audioArgs = ['-ar', '44100', '-ac', '2', '-b:a', '64k'];
            sizeArgs = null;
            videoFilter = "scale='min(320,iw)':min'(240,ih)':force_original_aspect_ratio=decrease,scale=trunc(iw/2)*2:trunc(ih/2)*2";
        }

        const isYt = youtube.isYouTubeUrl(videoUrl);

        if (isYt) {
            if (inFlight3gpTranscodes.has(cacheKey)) {
                console.log(`[3GP Streamer] Waiting for in-flight transcode of ${cacheKey}`);
                await inFlight3gpTranscodes.get(cacheKey);
                if (fs.existsSync(cachedFilePath) && fs.statSync(cachedFilePath).size > 1000) {
                    return serveStatic3gp(req, res, cachedFilePath, cacheKey);
                }
            }

            const transcodePromise = (async () => {
                const ytDlpPath = path.join(__dirname, '..', 'tools', 'yt-dlp');
                const tmpV = path.join(cacheDir, `${cacheKey}_tmp_v_${Date.now()}.mp4`);
                const tmpA = path.join(cacheDir, `${cacheKey}_tmp_a_${Date.now()}.m4a`);
                const tmp3gp = path.join(cacheDir, `${cacheKey}_tmp_${Date.now()}.3gp`);

                const ytFormat = resProfile === '144p'
                    ? 'bestvideo[height<=144]/worstvideo/worst'
                    : 'bestvideo[height<=240]/worstvideo[height<=144]/worst';

                console.log(`[3GP Streamer] Downloading YouTube streams for ${cacheKey} (${ytFormat})...`);
                await Promise.all([
                    new Promise((resolve, reject) => {
                        const p = spawn(ytDlpPath, ['-f', ytFormat, '-o', tmpV, videoUrl]);
                        p.on('close', (c) => c === 0 ? resolve() : reject(new Error(`yt-dlp video exited ${c}`)));
                        p.on('error', reject);
                    }),
                    new Promise((resolve, reject) => {
                        const p = spawn(ytDlpPath, ['-f', 'bestaudio/best/worst', '-o', tmpA, videoUrl]);
                        p.on('close', (c) => c === 0 ? resolve() : reject(new Error(`yt-dlp audio exited ${c}`)));
                        p.on('error', reject);
                    })
                ]);

                console.log(`[3GP Streamer] Transcoding to 3GP (${resProfile}) for ${cacheKey}...`);
                const ffmpegArgs = [
                    '-y',
                    '-i', tmpV,
                    '-i', tmpA,
                    '-c:v', vcodec,
                    '-b:v', vbitrate
                ];
                if (fps) {
                    ffmpegArgs.push('-r', fps);
                }
                if (videoFilter) {
                    ffmpegArgs.push('-vf', videoFilter);
                } else if (sizeArgs) {
                    ffmpegArgs.push(...sizeArgs);
                }
                ffmpegArgs.push(
                    '-c:a', acodec,
                    ...audioArgs,
                    '-shortest',
                    '-movflags', '+faststart',
                    tmp3gp
                );

                await new Promise((resolve, reject) => {
                    const ffmpeg = spawn(ffmpegPath, ffmpegArgs);
                    ffmpeg.on('close', (c) => {
                        if (c === 0) {
                            try {
                                fs.renameSync(tmp3gp, cachedFilePath);
                            } catch (e) {}
                            resolve();
                        } else {
                            try { fs.unlinkSync(tmp3gp); } catch (e) {}
                            reject(new Error(`ffmpeg exited ${c}`));
                        }
                    });
                    ffmpeg.on('error', (err) => {
                        try { fs.unlinkSync(tmp3gp); } catch (e) {}
                        reject(err);
                    });
                });

                try { fs.unlinkSync(tmpV); } catch (e) {}
                try { fs.unlinkSync(tmpA); } catch (e) {}
            })();

            inFlight3gpTranscodes.set(cacheKey, transcodePromise);
            try {
                await transcodePromise;
            } finally {
                inFlight3gpTranscodes.delete(cacheKey);
            }

            if (fs.existsSync(cachedFilePath) && fs.statSync(cachedFilePath).size > 1000) {
                return serveStatic3gp(req, res, cachedFilePath, cacheKey);
            } else {
                throw new Error('Failed to generate 3GP file');
            }
        }

        const mediaResp = await fetch(videoUrl, {
            headers: { 'User-Agent': USER_AGENT }
        });

        if (!mediaResp.ok) {
            res.writeHead(502, { 'Content-Type': 'text/plain' });
            return res.end('Failed to fetch remote video: ' + mediaResp.statusText);
        }

        // Spawn ffmpeg to transcode to authentic 3GP (240p QVGA MPEG-4+AAC or 144p H.263+AMR)
        const ffmpegArgs = [
            '-y',
            '-i', 'pipe:0',
            '-c:v', vcodec,
            '-b:v', vbitrate
        ];
        if (fps) {
            ffmpegArgs.push('-r', fps);
        }
        if (videoFilter) {
            ffmpegArgs.push('-vf', videoFilter);
        } else if (sizeArgs) {
            ffmpegArgs.push(...sizeArgs);
        }
        ffmpegArgs.push(
            '-c:a', acodec,
            ...audioArgs,
            '-shortest',
            '-movflags', 'frag_keyframe+empty_moov+default_base_moof',
            '-f', '3gp',
            'pipe:1'
        );

        const ffmpeg = spawn(ffmpegPath, ffmpegArgs);

        res.writeHead(200, {
            'Content-Type': 'video/3gpp',
            'Content-Disposition': 'inline; filename="' + cacheKey + '.3gp"',
            'Cache-Control': 'no-cache',
            'Connection': 'close',
            'Access-Control-Allow-Origin': '*'
        });

        const tmp3gp = path.join(cacheDir, `${cacheKey}_tmp_${Date.now()}.3gp`);
        const cacheWriter = fs.createWriteStream(tmp3gp);
        cacheWriter.on('error', () => {});

        ffmpeg.stdout.pipe(res);
        ffmpeg.stdout.pipe(cacheWriter);

        ffmpeg.on('close', (code) => {
            try { ffmpeg.stdout.unpipe(cacheWriter); } catch (e) {}
            if (code === 0) {
                try {
                    fs.renameSync(tmp3gp, cachedFilePath);
                } catch (e) {}
            } else {
                try { fs.unlinkSync(tmp3gp); } catch (e) {}
            }
        });

        ffmpeg.stdin.on('error', () => {});
        ffmpeg.stdout.on('error', () => {});

        const reader = mediaResp.body.getReader();
        (async () => {
            try {
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    if (!ffmpeg.stdin.destroyed && ffmpeg.stdin.writable) {
                        ffmpeg.stdin.write(value);
                    }
                }
                if (!ffmpeg.stdin.destroyed) {
                    ffmpeg.stdin.end();
                }
            } catch (err) {
                try { ffmpeg.stdin.destroy(); } catch (e) {}
            }
        })();

        req.on('close', () => {
            try { ffmpeg.stdout.unpipe(res); } catch (e) {}
            try { ffmpeg.stdout.unpipe(cacheWriter); } catch (e) {}
            try { ffmpeg.kill(); } catch (e) {}
            try { cacheWriter.end(); } catch (e) {}
            try { fs.unlinkSync(tmp3gp); } catch (e) {}
        });

    } catch (err) {
        console.error('3GP stream error:', err.message);
        if (!res.headersSent) {
            res.writeHead(500, { 'Content-Type': 'text/plain' });
            res.end('3GP stream error: ' + err.message);
        }
    }
}

function serveStatic3gp(req, res, filePath, cacheKey) {
    const stat = fs.statSync(filePath);
    const fileSize = stat.size;
    const range = req.headers.range;

    if (range) {
        const parts = range.replace(/bytes=/, "").split("-");
        const start = parseInt(parts[0], 10);
        const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
        const chunksize = (end - start) + 1;
        const file = fs.createReadStream(filePath, { start, end });

        res.writeHead(206, {
            'Content-Range': `bytes ${start}-${end}/${fileSize}`,
            'Accept-Ranges': 'bytes',
            'Content-Length': chunksize,
            'Content-Type': 'video/3gpp',
            'Content-Disposition': `inline; filename="${cacheKey}.3gp"`,
            'Access-Control-Allow-Origin': '*'
        });
        file.pipe(res);
    } else {
        res.writeHead(200, {
            'Content-Length': fileSize,
            'Accept-Ranges': 'bytes',
            'Content-Type': 'video/3gpp',
            'Content-Disposition': `inline; filename="${cacheKey}.3gp"`,
            'Access-Control-Allow-Origin': '*'
        });
        fs.createReadStream(filePath).pipe(res);
    }
}

function makeWavHeader(sampleRate, channels, bitsPerSample, totalDataBytes) {
    const buffer = Buffer.alloc(44);
    const totalSize = totalDataBytes > 0 ? 36 + totalDataBytes : 0x7FFFFFF0;
    const dataSize = totalDataBytes > 0 ? totalDataBytes : 0x7FFFFFF0;
    buffer.write('RIFF', 0);
    buffer.writeUInt32LE(totalSize, 4);
    buffer.write('WAVEfmt ', 8);
    buffer.writeUInt32LE(16, 16);
    buffer.writeUInt16LE(1, 20); // PCM format
    buffer.writeUInt16LE(channels, 22);
    buffer.writeUInt32LE(sampleRate, 24);
    buffer.writeUInt32LE(sampleRate * channels * (bitsPerSample / 8), 28);
    buffer.writeUInt16LE(channels * (bitsPerSample / 8), 32);
    buffer.writeUInt16LE(bitsPerSample, 34);
    buffer.write('data', 36);
    buffer.writeUInt32LE(dataSize, 40);
    return buffer;
}

const imageMemoryCache = new Map();
const MAX_MEM_IMAGE_CACHE = 60;

function transcodeSvgToPng(svgBuffer, maxWidth) {
    return new Promise((resolve, reject) => {
        const py = spawn('python3', ['-c', `
import cairosvg, sys
data = sys.stdin.buffer.read()
png = cairosvg.svg2png(bytestring=data, output_width=${maxWidth})
sys.stdout.buffer.write(png)
`]);
        const chunks = [];
        py.stdout.on('data', c => chunks.push(c));
        py.on('close', code => {
            const out = Buffer.concat(chunks);
            if (code === 0 && out.length > 50) {
                resolve(out);
            } else {
                reject(new Error('cairosvg failed with exit code ' + code));
            }
        });
        py.on('error', err => reject(err));
        py.stdin.write(svgBuffer);
        py.stdin.end();
    });
}

function transcodeWithPillow(inputBuffer, maxWidth) {
    return new Promise((resolve, reject) => {
        const py = spawn('python3', ['-c', `
from PIL import Image
import sys, io
try:
    img = Image.open(io.BytesIO(sys.stdin.buffer.read()))
    if img.mode not in ('RGB', 'RGBA', 'L'):
        img = img.convert('RGBA')
    w, h = img.size
    if w > ${maxWidth}:
        new_h = max(1, int(h * ${maxWidth} / w))
        img = img.resize((${maxWidth}, new_h), Image.Resampling.LANCZOS)
    out = io.BytesIO()
    img.save(out, format='PNG', optimize=True)
    sys.stdout.buffer.write(out.getvalue())
except Exception as e:
    sys.exit(1)
`]);
        const chunks = [];
        py.stdout.on('data', c => chunks.push(c));
        py.on('close', code => {
            const out = Buffer.concat(chunks);
            if (code === 0 && out.length > 50) {
                resolve(out);
            } else {
                reject(new Error('Pillow failed with exit code ' + code));
            }
        });
        py.on('error', err => reject(err));
        py.stdin.write(inputBuffer);
        py.stdin.end();
    });
}

/**
 * Handle Image Proxying & Transcoding
 * Converts any image format (WebP, AVIF, JPEG, PNG, GIF, SVG) to a 220px-wide PNG
 * optimized for Nokia 240x320 QVGA screen and J2ME memory constraints.
 */
async function handleImageProxy(res, targetUrl, maxWidth = 220) {
    try {
        let cleanUrl = decodeHtmlEntities(targetUrl).trim();
        if (!cleanUrl.startsWith('http://') && !cleanUrl.startsWith('https://')) {
            if (cleanUrl.startsWith('//')) {
                cleanUrl = 'https:' + cleanUrl;
            } else {
                cleanUrl = 'https://' + cleanUrl;
            }
        }

        const cacheSuffix = (maxWidth !== 220) ? `_w${maxWidth}` : '';
        const cacheKey = crypto.createHash('sha256').update(cleanUrl + cacheSuffix).digest('hex').substring(0, 32);

        // 1. Fast in-memory cache lookup (< 1ms latency)
        if (imageMemoryCache.has(cacheKey)) {
            const memBuf = imageMemoryCache.get(cacheKey);
            res.writeHead(200, {
                'Content-Type': 'image/png',
                'Content-Length': memBuf.length,
                'Cache-Control': 'public, max-age=86400',
                'Access-Control-Allow-Origin': '*'
            });
            return res.end(memBuf);
        }

        const cacheDir = path.join(__dirname, 'cache', 'images');
        if (!fs.existsSync(cacheDir)) {
            fs.mkdirSync(cacheDir, { recursive: true });
        }
        const cachedPath = path.join(cacheDir, `${cacheKey}.png`);

        // 2. Disk cache lookup
        if (fs.existsSync(cachedPath) && fs.statSync(cachedPath).size > 100) {
            const diskBuf = fs.readFileSync(cachedPath);
            if (imageMemoryCache.size >= MAX_MEM_IMAGE_CACHE) {
                const firstKey = imageMemoryCache.keys().next().value;
                imageMemoryCache.delete(firstKey);
            }
            imageMemoryCache.set(cacheKey, diskBuf);

            res.writeHead(200, {
                'Content-Type': 'image/png',
                'Content-Length': diskBuf.length,
                'Cache-Control': 'public, max-age=86400',
                'Access-Control-Allow-Origin': '*'
            });
            return res.end(diskBuf);
        }

        let origin = '';
        try { origin = new URL(cleanUrl).origin; } catch (e) {}

        console.log(`[Image Proxy] Fetching: ${cleanUrl}`);
        let resp = null;
        try {
            resp = await fetch(cleanUrl, {
                headers: {
                    'User-Agent': USER_AGENT,
                    'Accept': 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
                    'Referer': origin || cleanUrl
                }
            });
        } catch (fetchErr) {
            console.warn(`[Image Proxy] Initial fetch failed for ${cleanUrl}:`, fetchErr.message);
        }

        // YouTube fallback retry logic
        if (!resp || !resp.ok) {
            if (cleanUrl.includes('youtube.com') || cleanUrl.includes('ytimg.com') || cleanUrl.includes('youtu.be')) {
                const ytMatch = cleanUrl.match(/\/vi(?:_webp)?\/([a-zA-Z0-9_-]{11})\//);
                if (ytMatch && ytMatch[1]) {
                    const ytId = ytMatch[1];
                    const ytFallbacks = [
                        `https://i.ytimg.com/vi/${ytId}/mqdefault.jpg`,
                        `https://i.ytimg.com/vi/${ytId}/hqdefault.jpg`,
                        `https://i.ytimg.com/vi/${ytId}/default.jpg`
                    ];
                    for (const fbUrl of ytFallbacks) {
                        if (fbUrl !== cleanUrl) {
                            try {
                                console.log(`[Image Proxy] YouTube fallback retry: ${fbUrl}`);
                                const fbResp = await fetch(fbUrl, {
                                    headers: {
                                        'User-Agent': USER_AGENT,
                                        'Accept': 'image/jpeg,image/*;q=0.8',
                                        'Referer': 'https://www.youtube.com/'
                                    }
                                });
                                if (fbResp.ok) {
                                    resp = fbResp;
                                    cleanUrl = fbUrl;
                                    break;
                                }
                            } catch (e) {}
                        }
                    }
                }
            }
        }

        // KamTape fallback retry logic
        if (!resp || !resp.ok) {
            if (cleanUrl.includes('kamtape.com') || cleanUrl.includes('get_still')) {
                const ktMatch = cleanUrl.match(/\/vi\/([a-zA-Z0-9_-]+)\//) || cleanUrl.match(/[?&]video_id=([a-zA-Z0-9_-]+)/);
                if (ktMatch && ktMatch[1]) {
                    const ktId = ktMatch[1];
                    const ktFallbacks = [
                        `https://www.kamtape.com/get_still?video_id=${ktId}`,
                        `https://v37.kamtape.com/vi/${ktId}/2.jpg`,
                        `https://v37.kamtape.com/vi/${ktId}/0.jpg`
                    ];
                    for (const fbUrl of ktFallbacks) {
                        if (fbUrl !== cleanUrl) {
                            try {
                                console.log(`[Image Proxy] KamTape fallback retry: ${fbUrl}`);
                                const fbResp = await fetch(fbUrl, {
                                    headers: {
                                        'User-Agent': USER_AGENT,
                                        'Accept': 'image/jpeg,image/png,image/*;q=0.8',
                                        'Referer': 'https://www.kamtape.com/'
                                    }
                                });
                                if (fbResp.ok) {
                                    resp = fbResp;
                                    cleanUrl = fbUrl;
                                    break;
                                }
                            } catch (e) {}
                        }
                    }
                }
            }
        }

        if (!resp || !resp.ok) {
            throw new Error(`Upstream returned ${resp ? resp.status : 'fetch failed'}`);
        }

        const inputBuffer = Buffer.from(await resp.arrayBuffer());
        if (inputBuffer.length === 0) {
            throw new Error('Empty image received');
        }

        const contentType = (resp.headers.get('content-type') || '').toLowerCase();
        const isSvg = contentType.includes('svg') ||
                      cleanUrl.toLowerCase().split('?')[0].endsWith('.svg') ||
                      inputBuffer.slice(0, 100).toString('utf8').includes('<svg');

        let pngBuf = null;

        // Vector SVG path: transcode via cairosvg
        if (isSvg) {
            try {
                pngBuf = await transcodeSvgToPng(inputBuffer, maxWidth);
            } catch (svgErr) {
                console.warn('[Image Proxy] cairosvg transcode failed:', svgErr.message);
            }
        }

        // Raster path: transcode via FFmpeg
        if (!pngBuf) {
            pngBuf = await new Promise((resolve) => {
                const ffmpegPath = path.join(__dirname, '..', 'tools', 'ffmpeg');
                const scaleFilter = `scale='min(${maxWidth},iw)':-1`;
                const ffmpeg = spawn(ffmpegPath, [
                    '-y',
                    '-i', 'pipe:0',
                    '-vf', scaleFilter,
                    '-vframes', '1',
                    '-f', 'image2',
                    '-c:v', 'png',
                    '-compression_level', '9',
                    '-pred', 'mixed',
                    'pipe:1'
                ]);

                const chunks = [];
                ffmpeg.stdout.on('data', c => chunks.push(c));
                ffmpeg.stdin.on('error', () => {});
                ffmpeg.stdout.on('error', () => {});
                ffmpeg.on('close', (code) => {
                    const out = Buffer.concat(chunks);
                    if (out.length > 50) {
                        resolve(out);
                    } else {
                        resolve(null);
                    }
                });
                ffmpeg.stdin.write(inputBuffer);
                ffmpeg.stdin.end();
            });
        }

        // Fallback: transcode via Pillow
        if (!pngBuf) {
            try {
                pngBuf = await transcodeWithPillow(inputBuffer, maxWidth);
            } catch (pilErr) {
                console.warn('[Image Proxy] Pillow transcode failed:', pilErr.message);
            }
        }

        // Save and serve PNG
        if (pngBuf && pngBuf.length > 50) {
            try { fs.writeFileSync(cachedPath, pngBuf); } catch (e) {}
            if (imageMemoryCache.size >= MAX_MEM_IMAGE_CACHE) {
                const firstKey = imageMemoryCache.keys().next().value;
                imageMemoryCache.delete(firstKey);
            }
            imageMemoryCache.set(cacheKey, pngBuf);

            res.writeHead(200, {
                'Content-Type': 'image/png',
                'Content-Length': pngBuf.length,
                'Cache-Control': 'public, max-age=86400',
                'Access-Control-Allow-Origin': '*'
            });
            return res.end(pngBuf);
        }

        // Ultimate fallback: if original was PNG or JPEG, serve it directly
        if (contentType.includes('png') || contentType.includes('jpeg') || contentType.includes('jpg')) {
            res.writeHead(200, {
                'Content-Type': contentType,
                'Content-Length': inputBuffer.length,
                'Cache-Control': 'public, max-age=86400',
                'Access-Control-Allow-Origin': '*'
            });
            return res.end(inputBuffer);
        }

        throw new Error('Image could not be transcoded to PNG');

    } catch (e) {
        console.error('[Image Proxy Error]', e.message);
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('Image not available');
    }
}

const server = http.createServer(async (req, res) => {
    const parsedUrl = new URL(req.url, 'http://' + (req.headers.host || '127.0.0.1:8080'));
    const pathname = parsedUrl.pathname;
    const gatewayHost = req.headers.host || ('127.0.0.1:' + PORT);

    const nokiaSim = req.headers['x-nokia-sim'];
    const nokiaBearer = req.headers['x-nokia-bearer'];
    const nokiaOp = req.headers['x-nokia-operator'];
    const nokiaApn = req.headers['x-nokia-apn'];
    const nokiaDataSaver = req.headers['x-nokia-data-saver'] === '1';
    const nokiaSignal = req.headers['x-nokia-signal'];

    if (nokiaBearer || nokiaSim) {
        console.log(`[${new Date().toISOString().substring(11, 19)}] ${req.method} ${pathname} [Cellular: SIM ${nokiaSim || '1'} | ${nokiaBearer || 'GPRS'} | ${nokiaOp || 'Nokia Mobile'} | ${nokiaApn || 'internet'} | Saver: ${nokiaDataSaver ? 'ON' : 'OFF'} | Sig: ${nokiaSignal || 4}/4]`);
    } else {
        console.log(`[${new Date().toISOString().substring(11, 19)}] ${req.method} ${pathname}`);
    }

    // Health check
    if (pathname === '/' || pathname === '/status' || pathname === '/health') {
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end('Nokia J2ME Modern Gateway & Media Transcoder\nScreen: 240x320 QVGA\nDefault Search: Bing\nKamTape Video: Supported\nStatus: Online\nPort: ' + PORT);
    }

    // Static Files
    if (pathname.startsWith('/static/')) {
        const filename = pathname.substring(8);
        const filePath = path.join(__dirname, 'public', filename);
        if (fs.existsSync(filePath)) {
            let mime = 'application/octet-stream';
            if (filename.endsWith('.wav')) mime = 'audio/x-wav';
            else if (filename.endsWith('.mp3')) mime = 'audio/mpeg';
            else if (filename.endsWith('.mp4')) mime = 'video/mp4';
            else if (filename.endsWith('.3gp')) mime = 'video/3gpp';
            else if (filename.endsWith('.png')) mime = 'image/png';
            else if (filename.endsWith('.gif')) mime = 'image/gif';

            const stat = fs.statSync(filePath);
            res.writeHead(200, {
                'Content-Type': mime,
                'Content-Length': stat.size,
                'Accept-Ranges': 'bytes',
                'Access-Control-Allow-Origin': '*'
            });
            return fs.createReadStream(filePath).pipe(res);
        } else {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            return res.end('Static file not found');
        }
    }

    // Video Frame Streamer Engine (240x180 QVGA at 8 FPS)
    if (pathname === '/video_stream') {
        let videoUrl = parsedUrl.searchParams.get('url');
        const startSec = parsedUrl.searchParams.get('t') || '0';
        const fps = parsedUrl.searchParams.get('fps') || '8';
        const maxW = parsedUrl.searchParams.get('max_w') || '240';
        const maxH = parsedUrl.searchParams.get('max_h') || '144';

        if (!videoUrl) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            return res.end('Missing url parameter');
        }

        videoUrl = decodeURIComponent(videoUrl);
        console.log(`[video_stream] Streaming from ${videoUrl} starting at ${startSec}s (${maxW}x${maxH} @ ${fps}fps)`);

        const isDataSaver = req.headers['x-nokia-data-saver'] === '1' || parsedUrl.searchParams.get('ds') === '1';
        const qv = isDataSaver ? '9' : '7';
        const reqFps = parseFloat(fps) || (isDataSaver ? 8.0 : 12.0);
        const targetFps = Math.min(15.0, Math.max(5.0, reqFps));
        const frameIntervalMs = Math.max(40, Math.round(1000.0 / targetFps));

        const ffmpegPath = path.join(__dirname, '..', 'tools', 'ffmpeg');
        const ffmpegArgs = [
            '-y',
            '-threads', '2',
            '-fflags', 'nobuffer+fastseek',
            '-flags', 'low_delay'
        ];
        const sSec = parseFloat(startSec) || 0;
        if (sSec > 0) {
            ffmpegArgs.push('-ss', startSec);
        }
        ffmpegArgs.push(
            '-i', 'pipe:0',
            '-vf', `scale=${maxW}:${maxH}:force_original_aspect_ratio=decrease,pad=${maxW}:${maxH}:(ow-iw)/2:(oh-ih)/2,fps=${targetFps}`,
            '-q:v', qv,
            '-f', 'image2pipe',
            '-vcodec', 'mjpeg',
            'pipe:1'
        );

        const ffmpeg = spawn(ffmpegPath, ffmpegArgs);

        res.writeHead(200, {
            'Content-Type': 'application/octet-stream',
            'Cache-Control': 'no-cache',
            'Connection': 'close',
            'Access-Control-Allow-Origin': '*'
        });

        // Write 8-byte Stream Header: Magic 'NVID' (4 bytes) + Total duration ms (4 bytes uint32BE)
        const streamHdr = Buffer.alloc(8);
        streamHdr.write('NVID', 0);
        let durMs = 0;
        const durParam = parsedUrl.searchParams.get('dur');
        if (durParam) {
            durMs = Math.round(parseFloat(durParam) * 1000);
        } else {
            const vidId = parsedUrl.searchParams.get('id') || (youtube.isYouTubeUrl(videoUrl) ? youtube.extractVideoId(videoUrl) : null);
            if (vidId) {
                const dSec = youtube.getVideoDuration(vidId);
                if (dSec > 0) {
                    durMs = Math.round(dSec * 1000);
                }
            }
        }
        if (durMs === 0 && youtube.isYouTubeUrl(videoUrl)) {
            const vidId = parsedUrl.searchParams.get('id') || youtube.extractVideoId(videoUrl);
            if (vidId) {
                try {
                    const info = await Promise.race([
                        youtube.getVideoInfo(vidId),
                        new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), 1500))
                    ]);
                    if (info && info.durationSec > 0) {
                        durMs = Math.round(info.durationSec * 1000);
                    }
                } catch (e) {}
            }
        }
        streamHdr.writeUInt32BE(durMs, 4);
        res.write(streamHdr);

        let imgBuffer = Buffer.alloc(0);
        let frameCount = 0;

        const frameQueue = [];
        let timer = null;
        let ffmpegClosed = false;

        function pumpFrame() {
            if (res.writableEnded || !res.writable) {
                if (timer) clearInterval(timer);
                return;
            }
            if (frameQueue.length > 0) {
                const item = frameQueue.shift();
                res.write(item.frameHeader);
                res.write(item.frameData);

                // Resume FFmpeg if queue has drained below 4 frames
                if (frameQueue.length < 4 && !ffmpegClosed && ffmpeg.stdout.isPaused()) {
                    ffmpeg.stdout.resume();
                }
            } else if (ffmpegClosed) {
                if (timer) clearInterval(timer);
                if (!res.writableEnded) {
                    const eos = Buffer.alloc(4);
                    eos.writeInt32BE(0, 0);
                    res.write(eos);
                    res.end();
                }
            }
        }

        // Send frames at target interval (12 fps = 83ms)
        timer = setInterval(pumpFrame, frameIntervalMs);

        ffmpeg.stdout.on('data', (chunk) => {
            imgBuffer = Buffer.concat([imgBuffer, chunk]);

            while (imgBuffer.length > 0) {
                const soi = imgBuffer.indexOf(Buffer.from([0xFF, 0xD8]));
                if (soi === -1) {
                    imgBuffer = Buffer.alloc(0);
                    break;
                }
                if (soi > 0) {
                    imgBuffer = imgBuffer.subarray(soi);
                }

                const eoi = imgBuffer.indexOf(Buffer.from([0xFF, 0xD9]));
                if (eoi === -1) {
                    break;
                }

                const frameLen = eoi + 2;
                const frameData = imgBuffer.subarray(0, frameLen);
                imgBuffer = imgBuffer.subarray(frameLen);

                // Frame Protocol: [4 bytes length uint32BE][4 bytes curMs timestamp uint32BE][JPEG bytes]
                const frameHeader = Buffer.alloc(8);
                frameHeader.writeUInt32BE(frameLen, 0);
                const curMs = Math.round((sSec * 1000) + (frameCount * (1000.0 / targetFps)));
                frameHeader.writeUInt32BE(curMs, 4);
                frameCount++;

                frameQueue.push({ frameHeader, frameData });

                // Burst send initial 3 frames immediately so client begins decoding right away
                if (frameCount <= 3) {
                    pumpFrame();
                }

                // Pause FFmpeg if queue has 8 frames buffered (~650ms cushion)
                if (frameQueue.length >= 8 && !ffmpeg.stdout.isPaused()) {
                    ffmpeg.stdout.pause();
                }
            }
        });

        ffmpeg.stdin.on('error', () => {});
        ffmpeg.stdout.on('error', () => {});
        ffmpeg.stderr.on('data', (d) => {});

        req.on('close', () => {
            if (timer) clearInterval(timer);
            try { ffmpeg.kill(); } catch (e) {}
        });
        ffmpeg.on('close', () => {
            ffmpegClosed = true;
            if (frameQueue.length === 0) {
                pumpFrame();
            }
        });

        // Feed input to ffmpeg
        if (youtube.isYouTubeUrl(videoUrl)) {
            const ytDlpPath = path.join(__dirname, '..', 'tools', 'yt-dlp');
            const ytProc = spawn(ytDlpPath, [
                '-o', '-',
                '-f', 'bestvideo[height<=240]/bestvideo[height<=360]/worstvideo/160/133/278/18/worst',
                '--no-warnings',
                videoUrl
            ]);
            ytProc.stdout.pipe(ffmpeg.stdin);
            ytProc.stdout.on('error', () => {});
            ytProc.on('error', (err) => console.error('[yt-dlp error]:', err.message));
            req.on('close', () => {
                try { ytProc.kill(); } catch (e) {}
            });
            return;
        }

        if (videoUrl.startsWith('http://') || videoUrl.startsWith('https://')) {
            if (videoUrl.includes('/static/')) {
                const staticPath = path.join(__dirname, 'public', videoUrl.substring(videoUrl.indexOf('/static/') + 8));
                if (fs.existsSync(staticPath)) {
                    fs.createReadStream(staticPath).pipe(ffmpeg.stdin);
                    return;
                }
            }

            fetch(videoUrl, {
                headers: { 'User-Agent': USER_AGENT }
            }).then(resp => {
                if (!resp.ok) {
                    try { ffmpeg.kill(); } catch (e) {}
                    if (!res.headersSent) {
                        res.writeHead(502, { 'Content-Type': 'text/plain' });
                        res.end('Remote video fetch failed');
                    }
                    return;
                }
                const reader = resp.body.getReader();
                (async () => {
                    try {
                        while (true) {
                            const { done, value } = await reader.read();
                            if (done) break;
                            if (!ffmpeg.stdin.destroyed && ffmpeg.stdin.writable) {
                                ffmpeg.stdin.write(Buffer.from(value));
                            }
                        }
                    } catch (e) {
                    } finally {
                        try { ffmpeg.stdin.end(); } catch (e) {}
                    }
                })();
            }).catch(err => {
                try { ffmpeg.kill(); } catch (e) {}
                if (!res.headersSent) {
                    res.writeHead(500, { 'Content-Type': 'text/plain' });
                    res.end('Proxy streaming error: ' + err.message);
                }
            });
            return;
        }

        // Local video file
        if (fs.existsSync(videoUrl)) {
            fs.createReadStream(videoUrl).pipe(ffmpeg.stdin);
            return;
        }

        res.writeHead(404, { 'Content-Type': 'text/plain' });
        return res.end('Video resource not found');
    }

    // Synchronized Audio Streamer for Video Player (48k MP3 or 16kHz WAV)
    if (pathname === '/video_audio') {
        let videoUrl = parsedUrl.searchParams.get('url');
        const startSec = parsedUrl.searchParams.get('t') || '0';
        const format = (parsedUrl.searchParams.get('format') || 'mp3').toLowerCase();
        const isMp3 = (format === 'mp3');
        const contentType = isMp3 ? 'audio/mpeg' : 'audio/x-wav';
        const ext = isMp3 ? 'mp3' : 'wav';

        if (!videoUrl) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            return res.end('Missing url parameter');
        }

        videoUrl = decodeURIComponent(videoUrl);

        let cacheKey = null;
        if (youtube.isYouTubeUrl(videoUrl)) {
            const vId = youtube.extractVideoId(videoUrl);
            cacheKey = 'yt_' + (vId || crypto.createHash('sha256').update(videoUrl).digest('hex').substring(0, 16));
        } else {
            try {
                const u = new URL(videoUrl);
                cacheKey = u.searchParams.get('video_id') || u.searchParams.get('v');
            } catch (e) {}
            if (!cacheKey) {
                cacheKey = crypto.createHash('sha256').update(videoUrl).digest('hex').substring(0, 16);
            }
        }

        const cacheDir = path.join(__dirname, 'cache', 'audio');
        if (!fs.existsSync(cacheDir)) {
            fs.mkdirSync(cacheDir, { recursive: true });
        }
        const cachedFilePath = path.join(cacheDir, `${cacheKey}.${ext}`);
        const sSec = parseFloat(startSec) || 0;

        if (req.method === 'HEAD') {
            res.writeHead(200, {
                'Content-Type': contentType,
                'Cache-Control': 'no-cache',
                'Connection': 'close',
                'Access-Control-Allow-Origin': '*'
            });
            return res.end();
        }

        // If cached audio already exists and is complete (> 1000 bytes)
        if (fs.existsSync(cachedFilePath) && fs.statSync(cachedFilePath).size > 1000) {
            const stat = fs.statSync(cachedFilePath);
            const totalFileSize = stat.size;

            if (isMp3) {
                const byteOffset = Math.floor(sSec * 6000);
                if (byteOffset < totalFileSize) {
                    const remainingData = totalFileSize - byteOffset;
                    res.writeHead(200, {
                        'Content-Type': 'audio/mpeg',
                        'Content-Length': remainingData.toString(),
                        'Cache-Control': 'no-cache',
                        'Connection': 'close',
                        'Access-Control-Allow-Origin': '*'
                    });
                    return fs.createReadStream(cachedFilePath, { start: byteOffset }).pipe(res);
                }
            } else {
                const byteOffset = 44 + Math.floor(sSec * 32000);
                if (byteOffset < totalFileSize) {
                    const remainingData = totalFileSize - byteOffset;
                    res.writeHead(200, {
                        'Content-Type': 'audio/x-wav',
                        'Content-Length': (44 + remainingData).toString(),
                        'Cache-Control': 'no-cache',
                        'Connection': 'close',
                        'Access-Control-Allow-Origin': '*'
                    });
                    const wavHdr = makeWavHeader(16000, 1, 16, remainingData);
                    res.write(wavHdr);
                    return fs.createReadStream(cachedFilePath, { start: byteOffset }).pipe(res);
                }
            }
        }

        console.log(`[video_audio] Streaming ${format.toUpperCase()} audio from ${videoUrl} starting at ${startSec}s`);

        // Transcode audio on the fly with FFmpeg
        const ffmpegPath = path.join(__dirname, '..', 'tools', 'ffmpeg');
        const ffmpegArgs = ['-y'];
        if (sSec > 0) {
            ffmpegArgs.push('-ss', startSec);
        }

        if (isMp3) {
            ffmpegArgs.push(
                '-i', 'pipe:0',
                '-vn',
                '-acodec', 'libmp3lame',
                '-b:a', '48k',
                '-ar', '22050',
                '-ac', '1',
                '-f', 'mp3',
                'pipe:1'
            );
        } else {
            ffmpegArgs.push(
                '-i', 'pipe:0',
                '-vn',
                '-acodec', 'pcm_s16le',
                '-ar', '16000',
                '-ac', '1',
                '-f', 's16le',
                'pipe:1'
            );
        }

        const ffmpeg = spawn(ffmpegPath, ffmpegArgs);

        res.writeHead(200, {
            'Content-Type': contentType,
            'Cache-Control': 'no-cache',
            'Connection': 'close',
            'Access-Control-Allow-Origin': '*'
        });

        if (!isMp3) {
            const wavHdr = makeWavHeader(16000, 1, 16, 0x7FFFFFF0);
            res.write(wavHdr);
        }

        const tempCachePath = cachedFilePath + '.tmp';
        let cacheWriter = null;
        if (sSec === 0) {
            try {
                if (fs.existsSync(tempCachePath)) fs.unlinkSync(tempCachePath);
                cacheWriter = fs.createWriteStream(tempCachePath);
                cacheWriter.on('error', () => {});
                if (!isMp3) {
                    const wavHdr = makeWavHeader(16000, 1, 16, 0x7FFFFFF0);
                    cacheWriter.write(wavHdr);
                }
            } catch (e) {}
        }

        ffmpeg.stdout.pipe(res);
        if (cacheWriter) {
            ffmpeg.stdout.pipe(cacheWriter);
        }

        ffmpeg.stdin.on('error', () => {});
        ffmpeg.stdout.on('error', () => {});
        ffmpeg.stderr.on('data', (d) => {});

        req.on('close', () => {
            try { ffmpeg.stdout.unpipe(res); } catch (e) {}
            if (cacheWriter) {
                try {
                    ffmpeg.stdout.unpipe(cacheWriter);
                    cacheWriter.end();
                    if (fs.existsSync(tempCachePath)) fs.unlinkSync(tempCachePath);
                } catch (e) {}
            }
            try { ffmpeg.kill(); } catch (e) {}
        });
        ffmpeg.on('close', (code) => {
            if (cacheWriter) {
                try { ffmpeg.stdout.unpipe(cacheWriter); } catch (e) {}
                cacheWriter.end(() => {
                    if (code === 0) {
                        try {
                            fs.renameSync(tempCachePath, cachedFilePath);
                        } catch (e) {}
                    } else {
                        try {
                            if (fs.existsSync(tempCachePath)) fs.unlinkSync(tempCachePath);
                        } catch (e) {}
                    }
                });
            }
            if (!res.writableEnded) res.end();
        });

        // Feed input to ffmpeg
        if (youtube.isYouTubeUrl(videoUrl)) {
            const ytDlpPath = path.join(__dirname, '..', 'tools', 'yt-dlp');
            const ytAudioProc = spawn(ytDlpPath, [
                '-o', '-',
                '-f', 'ba/140/251/bestaudio/worst',
                '--no-warnings',
                videoUrl
            ]);
            ytAudioProc.stdout.pipe(ffmpeg.stdin);
            ytAudioProc.stdout.on('error', () => {});
            ytAudioProc.on('error', (err) => console.error('[yt-dlp audio error]:', err.message));
            req.on('close', () => {
                try { ytAudioProc.kill(); } catch (e) {}
            });
            return;
        }

        if (videoUrl.startsWith('http://') || videoUrl.startsWith('https://')) {
            if (videoUrl.includes('/static/')) {
                const staticPath = path.join(__dirname, 'public', videoUrl.substring(videoUrl.indexOf('/static/') + 8));
                if (fs.existsSync(staticPath)) {
                    fs.createReadStream(staticPath).pipe(ffmpeg.stdin);
                    return;
                }
            }

            fetch(videoUrl, {
                headers: { 'User-Agent': USER_AGENT }
            }).then(resp => {
                if (!resp.ok) {
                    try { ffmpeg.kill(); } catch (e) {}
                    if (!res.headersSent) {
                        res.writeHead(502, { 'Content-Type': 'text/plain' });
                        res.end('Remote video fetch failed');
                    }
                    return;
                }
                const reader = resp.body.getReader();
                (async () => {
                    try {
                        while (true) {
                            const { done, value } = await reader.read();
                            if (done) break;
                            if (!ffmpeg.stdin.destroyed && ffmpeg.stdin.writable) {
                                ffmpeg.stdin.write(value);
                            }
                        }
                        if (!ffmpeg.stdin.destroyed) {
                            ffmpeg.stdin.end();
                        }
                    } catch (err) {
                        try { ffmpeg.stdin.destroy(); } catch (e) {}
                    }
                })();
            }).catch(err => {
                console.error('[video_audio] Fetch error:', err.message);
                try { ffmpeg.kill(); } catch (e) {}
            });
        } else {
            let localPath = videoUrl;
            if (!path.isAbsolute(localPath)) {
                localPath = path.join(__dirname, '..', localPath);
            }
            if (fs.existsSync(localPath)) {
                fs.createReadStream(localPath).pipe(ffmpeg.stdin);
            } else {
                try { ffmpeg.kill(); } catch (e) {}
                if (!res.headersSent) {
                    res.writeHead(404, { 'Content-Type': 'text/plain' });
                    res.end('File not found');
                }
            }
        }
        return;
    }

    // Sample Media Page for J2ME Device Showcase
    if (pathname === '/sample_media') {
        const payload = [
            'META:TITLE=Media Showcase (240x320)',
            'META:URL=http://' + gatewayHost + '/sample_media',
            'META:HTTPS=0',
            'H1:Media & Video Test',
            'P:Testing Mobile Media API (MMAPI) video playback on Nokia J2ME 240x320.',
            'H2:Featured KamTape Video',
            'V:http://' + gatewayHost + '/video.3gp?url=https%3A%2F%2Fwww.kamtape.com%2Fget_video%3Fvideo_id%3DIV0P5qK75H8%26webm%3D1\t▶ Stream 3GP Video: Mega Man X',
            'L:http://' + gatewayHost + '/video.3gp?url=https%3A%2F%2Fwww.kamtape.com%2Fget_video%3Fvideo_id%3DIV0P5qK75H8%26webm%3D1\t🎬 Launch in Nokia RealPlayer (3GP)',
            'V:http://' + gatewayHost + '/video.3gp?url=https%3A%2F%2Fwww.kamtape.com%2Fget_video%3Fvideo_id%3DIV0P5qK75H8%26webm%3D1&res=144p\t▶ Stream 3GP (144p Classic Nokia)',
            'A:http://' + gatewayHost + '/media?url=https%3A%2F%2Fwww.kamtape.com%2Fget_video%3Fvideo_id%3DIV0P5qK75H8%26webm%3D1\t♫ Audio: Mega Man X (KamTape)',
            'L:search:kamtape\t🔍 Search KamTape Retro Videos',
            'L:https://www.kamtape.com\tBrowse All KamTape Videos',
            'HR:',
            'H2:Featured YouTube Video',
            'V:http://' + gatewayHost + '/video.3gp?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DiGw5FlQXmrU&id=iGw5FlQXmrU\t▶ Stream 3GP Video: Nokia 6300 Ad',
            'L:http://' + gatewayHost + '/video.3gp?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DiGw5FlQXmrU&id=iGw5FlQXmrU\t🎬 Launch in Nokia RealPlayer (3GP)',
            'V:http://' + gatewayHost + '/video.3gp?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DiGw5FlQXmrU&id=iGw5FlQXmrU&res=144p\t▶ Stream 3GP (144p Classic Nokia)',
            'A:http://' + gatewayHost + '/media?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DiGw5FlQXmrU\t♫ Audio: Nokia 6300 Ad (YouTube)',
            'L:https://www.youtube.com/watch?v=iGw5FlQXmrU\tWatch on YouTube',
            'L:https://www.youtube.com/search\t🔍 Search https://www.youtube.com/',
            'HR:',
            'H2:Audio Ringtone & Radio',
            'A:http://' + gatewayHost + '/static/nokia_tune.wav\tNokia Ringtone (WAV)',
            'A:https://stream.radioparadise.com/mp3-128\tRadio Paradise (Live MP3)',
            'HR:',
            'H2:Popular Links',
            'L:https://www.youtube.com/search\t🔍 Search https://www.youtube.com/',
            'L:search:youtube\t🔍 YouTube Video Search',
            'L:https://www.youtube.com\tYouTube Mobile',
            'L:search:kamtape\t🔍 KamTape Video Search',
            'L:https://www.kamtape.com\tKamTape.com Videos',
            'L:https://www.bing.com\tBing Search',
            'L:https://en.wikipedia.org/wiki/Nokia\tWikipedia: Nokia',
            'HR:',
            'H2:FTP & Web Archives',
            'L:ftp://test.rebex.net\t📁 Rebex Public Test FTP',
            'L:http://info.cern.ch\t🌐 First Website Ever (CERN HTTP)',
            'L:http://textfiles.com\t📜 TextFiles.com (Retro HTTP Archive)'
        ].join('\n');

        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end(payload);
    }

    // Web Page Gateway
    if (pathname === '/page') {
        let targetUrl = parsedUrl.searchParams.get('url');
        if (!targetUrl) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            return res.end('META:TITLE=Error\nP:Missing url parameter');
        }

        if (targetUrl.includes('/sample_media')) {
            const redirectUrl = 'http://' + gatewayHost + '/sample_media';
            res.writeHead(302, { 'Location': redirectUrl });
            return res.end();
        }

        let autoPrefixedHttps = false;
        if (ftp.isFtpUrl(targetUrl)) {
            return ftp.handleFtpRequest(targetUrl, req, res, gatewayHost);
        }

        if (!targetUrl.startsWith('http://') && !targetUrl.startsWith('https://') && !targetUrl.startsWith('ftp://')) {
            if (targetUrl === 'frogfind' || targetUrl.startsWith('frogfind/')) {
                targetUrl = 'https://www.frogfind.com' + targetUrl.substring(8);
            } else if (targetUrl === 'kamtape' || targetUrl.startsWith('kamtape/')) {
                targetUrl = 'https://www.kamtape.com' + targetUrl.substring(7);
            } else if (targetUrl === 'kamtape.com' || targetUrl.startsWith('kamtape.com/')) {
                targetUrl = 'https://www.' + targetUrl;
            } else if (targetUrl === 'subs' || targetUrl === 'subscriptions' || targetUrl === 'feed' || targetUrl === 'feed/subscriptions') {
                targetUrl = 'https://www.youtube.com/feed/subscriptions';
            } else if (targetUrl === 'channels' || targetUrl === 'feed/channels') {
                targetUrl = 'https://www.youtube.com/feed/channels';
            } else if (targetUrl === 'youtube' || targetUrl.startsWith('youtube/')) {
                targetUrl = 'https://www.youtube.com' + targetUrl.substring(7);
            } else if (targetUrl === 'youtube.com' || targetUrl.startsWith('youtube.com/')) {
                targetUrl = 'https://www.' + targetUrl;
            } else if (targetUrl === 'm.youtube.com' || targetUrl.startsWith('m.youtube.com/')) {
                targetUrl = 'https://' + targetUrl;
            } else if (targetUrl === 'www.youtube.com' || targetUrl.startsWith('www.youtube.com/')) {
                targetUrl = 'https://' + targetUrl;
            } else if (targetUrl.startsWith('search:youtube') || targetUrl.startsWith('search youtube') ||
                       targetUrl.startsWith('search www.youtube.com') || targetUrl.startsWith('search https://www.youtube.com') ||
                       targetUrl.startsWith('search http://www.youtube.com') || targetUrl.startsWith('search:https://www.youtube.com') ||
                       targetUrl.startsWith('search:http://www.youtube.com') || targetUrl.startsWith('search:www.youtube.com') ||
                       targetUrl.startsWith('search https://youtube.com') || targetUrl.startsWith('search:https://youtube.com') ||
                       targetUrl.startsWith('search youtube.com') || targetUrl.startsWith('search:youtube.com')) {
                const q = targetUrl.replace(/^search:?(\s*https?:\/\/)?(\s*(?:www\.)?youtube\.com)?\/?\s*/i, '').trim();
                const ytUrl = q ? ('https://www.youtube.com/results?search_query=' + encodeURIComponent(q)) : 'https://www.youtube.com/search';
                return youtube.handleYouTubeRequest(ytUrl, res, gatewayHost, decodeHtmlEntities);
            } else if (targetUrl.startsWith('search:kamtape') || targetUrl.startsWith('search kamtape') ||
                       targetUrl.startsWith('search www.kamtape.com') || targetUrl.startsWith('search https://www.kamtape.com') ||
                       targetUrl.startsWith('search http://www.kamtape.com') || targetUrl.startsWith('search:https://www.kamtape.com') ||
                       targetUrl.startsWith('search:http://www.kamtape.com') || targetUrl.startsWith('search:www.kamtape.com') ||
                       targetUrl.startsWith('search kamtape.com') || targetUrl.startsWith('search:kamtape.com')) {
                const q = targetUrl.replace(/^search:?(\s*https?:\/\/)?(\s*(?:www\.)?kamtape\.com)?\/?\s*/i, '').trim();
                const kamUrl = q ? ('https://www.kamtape.com/results?search_query=' + encodeURIComponent(q)) : 'https://www.kamtape.com/results';
                return handleKamTapeRequest(kamUrl, res, gatewayHost);
            } else if (targetUrl.startsWith('search:frogfind') || targetUrl.startsWith('search frogfind')) {
                const q = targetUrl.replace(/^search:?(\s*frogfind)?\s*/i, '').trim();
                const frogUrl = q ? ('https://www.frogfind.com/?q=' + encodeURIComponent(q)) : 'https://www.frogfind.com';
                return frogfind.handleFrogFindPage(frogUrl, res, gatewayHost, decodeHtmlEntities, parseAndReflowHtml, formatPagePayload);
            } else if (targetUrl.startsWith('search:robi') || targetUrl.startsWith('search robi')) {
                targetUrl = 'http://wap.robi.com.bd';
            } else if (targetUrl === 'robi' || targetUrl.startsWith('robi/') ||
                       targetUrl === 'robi-internet' || targetUrl.startsWith('robi-internet/') ||
                       targetUrl === 'robi-inernet' || targetUrl.startsWith('robi-inernet/') ||
                       targetUrl === 'robi internet' || targetUrl.startsWith('robi internet/') ||
                       targetUrl === 'wap.robi.com.bd' || targetUrl.startsWith('wap.robi.com.bd/') ||
                       targetUrl === 'robi.com.bd' || targetUrl.startsWith('robi.com.bd/')) {
                const sub = targetUrl.includes('/') ? targetUrl.substring(targetUrl.indexOf('/')) : '';
                targetUrl = 'http://wap.robi.com.bd' + sub;
            } else if (targetUrl.startsWith('ftp.') || targetUrl.includes('.ftp.')) {
                targetUrl = 'ftp://' + targetUrl;
            } else {
                autoPrefixedHttps = true;
                targetUrl = 'https://' + targetUrl;
            }
        }

        // Check again in case prefixing created an FTP url
        if (ftp.isFtpUrl(targetUrl)) {
            return ftp.handleFtpRequest(targetUrl, req, res, gatewayHost);
        }

        // Special Robi-INTERNET mobile WAP portal integration (Robi Axiata)
        if (targetUrl.includes('wap.robi.com.bd') || targetUrl.includes('robi.com.bd') ||
            targetUrl.includes('robi-internet') || targetUrl.includes('robi-inernet')) {
            return handleRobiPortalRequest(targetUrl, req, res, gatewayHost);
        }

        // Special FrogFind search & reader proxy integration
        if (frogfind.isFrogFindUrl(targetUrl)) {
            return frogfind.handleFrogFindPage(targetUrl, res, gatewayHost, decodeHtmlEntities, parseAndReflowHtml, formatPagePayload);
        }

        // Special KamTape video integration
        if (kamtape.isKamTapeUrl(targetUrl)) {
            return handleKamTapeRequest(targetUrl, res, gatewayHost);
        }

        // Special YouTube video integration
        if (youtube.isYouTubeUrl(targetUrl)) {
            return youtube.handleYouTubeRequest(targetUrl, res, gatewayHost, decodeHtmlEntities);
        }

        // General Web Page (HTTP/HTTPS with automatic fallback)
        try {
            console.log('Fetching web page:', targetUrl);
            let resp;
            const httpsController = new AbortController();
            const httpsTimeout = setTimeout(() => httpsController.abort(), 4000);
            try {
                resp = await fetch(targetUrl, {
                    headers: {
                        'User-Agent': USER_AGENT,
                        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
                    },
                    signal: httpsController.signal
                });
                clearTimeout(httpsTimeout);
            } catch (fetchErr) {
                clearTimeout(httpsTimeout);
                if (autoPrefixedHttps && targetUrl.startsWith('https://')) {
                    const fallbackHttp = 'http://' + targetUrl.substring(8);
                    console.log('HTTPS connection failed, retrying over HTTP:', fallbackHttp);
                    const httpController = new AbortController();
                    const httpTimeout = setTimeout(() => httpController.abort(), 8000);
                    resp = await fetch(fallbackHttp, {
                        headers: {
                            'User-Agent': USER_AGENT,
                            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
                        },
                        signal: httpController.signal
                    });
                    clearTimeout(httpTimeout);
                } else {
                    throw fetchErr;
                }
            }

            const html = await resp.text();
            const finalUrl = resp.url || targetUrl;
            const isHttps = finalUrl.startsWith('https://');

            const parsed = parseAndReflowHtml(html, finalUrl, isHttps, {
                img: parsedUrl.searchParams.get('img')
            });
            const payload = formatPagePayload(parsed, gatewayHost);

            res.writeHead(200, {
                'Content-Type': 'text/plain; charset=utf-8',
                'Cache-Control': 'no-cache',
                'Access-Control-Allow-Origin': '*'
            });
            return res.end(payload);
        } catch (e) {
            console.error('Page fetch error:', e.message);
            res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
            return res.end('META:TITLE=Page Error\nMETA:HTTPS=0\nH1:Connection Error\nP:Could not load ' + targetUrl + '\nP:Reason: ' + e.message + '\nHR:\nL:http://' + gatewayHost + '/\tGateway Home');
        }
    }

    // FTP Direct Binary Download
    if (pathname === '/ftp_download') {
        const downloadUrl = parsedUrl.searchParams.get('url');
        if (!downloadUrl) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            return res.end('Missing url parameter');
        }
        return ftp.handleFtpDownload(req, res, downloadUrl);
    }

    // 3GP Mobile Video Streamer (Nokia S40/S60 Hardware Format)
    if (pathname === '/video.3gp' || pathname === '/media_3gp') {
        const videoUrl = parsedUrl.searchParams.get('url');
        if (!videoUrl) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            return res.end('Missing video url parameter');
        }
        return handle3gpStream(req, res, videoUrl, gatewayHost);
    }

    // Media Streaming (Always 3GP for video, handleMediaProxy for audio/static)
    if (pathname === '/media') {
        const mediaUrl = parsedUrl.searchParams.get('url');
        if (!mediaUrl) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            return res.end('Missing media url');
        }
        if (youtube.isYouTubeUrl(mediaUrl) || mediaUrl.includes('kamtape.com') || mediaUrl.match(/\.(mp4|webm|mkv|flv|avi|mov|3gp)$/i)) {
            return handle3gpStream(req, res, mediaUrl, gatewayHost);
        }
        return handleMediaProxy(req, res, mediaUrl);
    }

    // Image Proxy
    if (pathname === '/image') {
        const imgUrl = parsedUrl.searchParams.get('url');
        let maxW = parseInt(parsedUrl.searchParams.get('w') || '220');
        if (nokiaDataSaver || nokiaBearer === 'G' || nokiaBearer === 'E') {
            maxW = Math.min(maxW, 160);
        }
        if (!imgUrl) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            return res.end('Missing image url');
        }
        return handleImageProxy(res, imgUrl, maxW);
    }

    // Search (Bing, FrogFind, or KamTape)
    if (pathname === '/search') {
        const q = parsedUrl.searchParams.get('q') || '';
        const lq = q.trim().toLowerCase();
        if (lq === 'subs' || lq === 'subscriptions' || lq === 'yt subs' || lq === 'youtube subs' || lq === 'feed') {
            return youtube.handleYouTubeRequest('https://www.youtube.com/feed/subscriptions', res, gatewayHost, decodeHtmlEntities);
        }
        if (lq === 'channels' || lq === 'feed/channels') {
            return youtube.handleYouTubeRequest('https://www.youtube.com/feed/channels', res, gatewayHost, decodeHtmlEntities);
        }
        const engine = parsedUrl.searchParams.get('engine') || 'bing';
        if (engine === 'frogfind') {
            const frogUrl = q ? ('https://www.frogfind.com/?q=' + encodeURIComponent(q)) : 'https://www.frogfind.com';
            return frogfind.handleFrogFindPage(frogUrl, res, gatewayHost, decodeHtmlEntities, parseAndReflowHtml, formatPagePayload);
        }
        if (engine === 'kamtape') {
            const kamUrl = q ? ('https://www.kamtape.com/results?search_query=' + encodeURIComponent(q)) : 'https://www.kamtape.com';
            return handleKamTapeRequest(kamUrl, res, gatewayHost);
        }
        if (engine === 'youtube' || engine === 'https://www.youtube.com/' || engine === 'https://www.youtube.com' || engine === 'www.youtube.com' || engine === 'youtube.com') {
            const ytUrl = q ? ('https://www.youtube.com/results?search_query=' + encodeURIComponent(q)) : 'https://www.youtube.com/search';
            return youtube.handleYouTubeRequest(ytUrl, res, gatewayHost, decodeHtmlEntities);
        }
        if (!q) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            return res.end('META:TITLE=Bing Search\nP:Please enter search keywords.');
        }
        return handleSearch(q, res, gatewayHost);
    }

    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
});

async function handleKamTapeRequest(targetUrl, res, gatewayHost) {
    try {
        console.log('Fetching KamTape video page:', targetUrl);
        const resp = await fetch(targetUrl, {
            headers: {
                'User-Agent': USER_AGENT,
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
            }
        });
        const html = await resp.text();
        const finalUrl = resp.url || targetUrl;
        const payload = kamtape.parseKamTapePage(html, finalUrl, gatewayHost, decodeHtmlEntities);

        res.writeHead(200, {
            'Content-Type': 'text/plain; charset=utf-8',
            'Cache-Control': 'no-cache',
            'Access-Control-Allow-Origin': '*'
        });
        return res.end(payload);
    } catch (e) {
        console.error('KamTape error:', e.message);
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end('META:TITLE=KamTape Error\nH1:Video Load Error\nP:' + e.message);
    }
}

function handleRobiPortalRequest(targetUrl, req, res, gatewayHost) {
    const sim = req.headers['x-nokia-sim'] || 'SIM 1';
    const bearer = req.headers['x-nokia-bearer'] || 'EDGE (2.5G)';
    const operator = req.headers['x-nokia-operator'] || 'Robi Axiata';
    const apn = req.headers['x-nokia-apn'] || 'INTERNET';
    const signal = req.headers['x-nokia-signal'] || '4/4';

    let title = 'Robi-INTERNET WAP Portal';
    const lines = [];

    // Parse sub-path if any
    let path = '';
    try {
        const u = new URL(targetUrl);
        path = u.pathname;
    } catch (e) {
        path = '/';
    }

    if (path.startsWith('/packs/daily')) {
        title = 'Robi 1 Day Social Pack';
        lines.push('META:TITLE=' + title);
        lines.push('META:URL=' + targetUrl);
        lines.push('META:HTTPS=0');
        lines.push('H1:Robi 1 Day Social Pack');
        lines.push('P:Volume: 50 MB High Speed Internet');
        lines.push('P:Price: ৳9 (Inclusive of SD, VAT & SC)');
        lines.push('P:Validity: 24 Hours from activation');
        lines.push('P:Dial Code: *123*050#');
        lines.push('P:Network: 2G / 3G / 3.5G HSDPA');
        lines.push('HR:');
        lines.push('L:http://wap.robi.com.bd/packs\t<< Back to Internet Packs');
        lines.push('L:http://wap.robi.com.bd\t<< Robi Home');
    } else if (path.startsWith('/packs/weekly')) {
        title = 'Robi 7 Days Unlimited Pack';
        lines.push('META:TITLE=' + title);
        lines.push('META:URL=' + targetUrl);
        lines.push('META:HTTPS=0');
        lines.push('H1:Robi 7 Days Unlimited Pack');
        lines.push('P:Volume: 1 GB Data + 250 MB 3G Bonus');
        lines.push('P:Price: ৳49 (All Inclusive)');
        lines.push('P:Validity: 7 Calendar Days');
        lines.push('P:Dial Code: *123*049#');
        lines.push('P:APN Profile: Robi-INTERNET (INTERNET)');
        lines.push('HR:');
        lines.push('L:http://wap.robi.com.bd/packs\t<< Back to Internet Packs');
        lines.push('L:http://wap.robi.com.bd\t<< Robi Home');
    } else if (path.startsWith('/packs/monthly')) {
        title = 'Robi 30 Days Power Net';
        lines.push('META:TITLE=' + title);
        lines.push('META:URL=' + targetUrl);
        lines.push('META:HTTPS=0');
        lines.push('H1:Robi 30 Days Power Net');
        lines.push('P:Volume: 5 GB High Speed Data');
        lines.push('P:Price: ৳199 (All Inclusive)');
        lines.push('P:Validity: 30 Calendar Days');
        lines.push('P:Dial Code: *123*199#');
        lines.push('P:Auto-renewal: Available (*123*199*1#)');
        lines.push('HR:');
        lines.push('L:http://wap.robi.com.bd/packs\t<< Back to Internet Packs');
        lines.push('L:http://wap.robi.com.bd\t<< Robi Home');
    } else if (path.startsWith('/packs')) {
        title = 'Robi Internet & Data Packs';
        lines.push('META:TITLE=' + title);
        lines.push('META:URL=' + targetUrl);
        lines.push('META:HTTPS=0');
        lines.push('H1:Robi Internet & Data Packs');
        lines.push('P:Choose an internet package for your Nokia device:');
        lines.push('L:http://wap.robi.com.bd/packs/daily\t1. 1 Day Social Pack (50 MB - ৳9)');
        lines.push('L:http://wap.robi.com.bd/packs/weekly\t2. 7 Days Unlimited Pack (1 GB - ৳49)');
        lines.push('L:http://wap.robi.com.bd/packs/monthly\t3. 30 Days Power Net (5 GB - ৳199)');
        lines.push('L:http://wap.robi.com.bd/account\t4. Check Balance & Data MB (*222# / *3#)');
        lines.push('HR:');
        lines.push('L:http://wap.robi.com.bd\t<< Robi Home');
    } else if (path.startsWith('/account')) {
        title = 'Robi Account & Balance Services';
        lines.push('META:TITLE=' + title);
        lines.push('META:URL=' + targetUrl);
        lines.push('META:HTTPS=0');
        lines.push('H1:Robi Account Services');
        lines.push('P:Main Balance: ৳ 48.75 (Valid until 31/12/2026)');
        lines.push('P:Mobile Data: 850 MB remaining on Robi-INTERNET');
        lines.push('P:Emergency Balance: Eligible for up to ৳30 (*123*007#)');
        lines.push('P:Active Profile: ' + apn + ' (Proxy: 10.16.18.77:8080)');
        lines.push('P:SIM Status: ' + sim + ' connected to ' + operator);
        lines.push('HR:');
        lines.push('H2:USSD Quick Codes');
        lines.push('LI:Check Balance: *222#');
        lines.push('LI:Check Internet MB: *3#');
        lines.push('LI:Check Minute Balance: *222*2#');
        lines.push('LI:Know My Number: *140*2*4#');
        lines.push('LI:Jhotpot Emergency: *123*007#');
        lines.push('HR:');
        lines.push('L:http://wap.robi.com.bd\t<< Robi Home');
    } else {
        // Main Robi WAP Portal
        lines.push('META:TITLE=Robi-INTERNET WAP Portal');
        lines.push('META:URL=http://wap.robi.com.bd');
        lines.push('META:HTTPS=0');
        lines.push('H1:Robi WAP Portal (BD)');
        lines.push('P:Welcome to Robi Axiata WAP mobile services on Nokia J2ME.');
        lines.push('P:📶 Network: ' + operator + ' [' + bearer + '] | Signal: ' + signal);
        lines.push('P:📡 Cellular APN: ' + apn + ' (Proxy: 10.16.18.77:8080)');
        lines.push('HR:');
        lines.push('H2:Robi Internet Packs');
        lines.push('L:http://wap.robi.com.bd/packs/daily\t• 1 Day Social Pack (50 MB - ৳9)');
        lines.push('L:http://wap.robi.com.bd/packs/weekly\t• 7 Days Unlimited Pack (1 GB - ৳49)');
        lines.push('L:http://wap.robi.com.bd/packs/monthly\t• 30 Days Power Net (5 GB - ৳199)');
        lines.push('L:http://wap.robi.com.bd/packs\t• View All Internet Packages...');
        lines.push('HR:');
        lines.push('H2:Account & USSD Services');
        lines.push('L:http://wap.robi.com.bd/account\t• Check Balance & Internet MB (*222# / *3#)');
        lines.push('P:Emergency Balance: Dial *123*007# (৳10-৳30)');
        lines.push('P:My Robi Number: Dial *140*2*4#');
        lines.push('HR:');
        lines.push('H2:WAP Media & Entertainment');
        lines.push('A:http://' + gatewayHost + '/static/nokia_tune.wav\t♫ Robi GoonGoon Caller Tune (WAV)');
        lines.push('V:http://' + gatewayHost + '/video.3gp?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DiGw5FlQXmrU&id=iGw5FlQXmrU\t▶ Robi 3.5G Mobile TV (3GP Stream)');
        lines.push('L:search:youtube\t🔍 Search YouTube Videos');
        lines.push('L:search:kamtape\t🔍 Search KamTape Retro Clips');
        lines.push('HR:');
        lines.push('H2:Robi-INTERNET APN Settings');
        lines.push('P:• Profile Name: Robi-INTERNET');
        lines.push('P:• APN: INTERNET');
        lines.push('P:• WAP Gateway: 10.16.18.77');
        lines.push('P:• Port: 8080');
        lines.push('P:• MCC: 470, MNC: 02 (Bangladesh)');
        lines.push('HR:');
        lines.push('H2:Popular Web Links');
        lines.push('L:https://www.bing.com\tBing Search');
        lines.push('L:https://www.frogfind.com\tFrogFind! Search');
        lines.push('L:https://en.wikipedia.org\tWikipedia Mobile');
        lines.push('L:https://news.ycombinator.com\tHacker News');
        lines.push('L:https://www.bbc.com/news\tBBC News');
    }

    const payload = lines.join('\n');
    res.writeHead(200, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Cache-Control': 'no-cache',
        'X-Nokia-Robi-Portal': '1'
    });
    res.end(payload);
}

server.listen(PORT, '0.0.0.0', () => {
    console.log(`====================================================`);
    console.log(` Nokia J2ME Modern Gateway Server running on port ${PORT}`);
    console.log(` Target screen: 240x320 QVGA (Nokia S40 / S60)`);
    console.log(` HTTPS TLS 1.3 / 1.2: Enabled`);
    console.log(` Default Search: Bing (https://www.bing.com)`);
    console.log(` FrogFind Retro Search: Enabled (https://www.frogfind.com)`);
    console.log(` KamTape Video Playback & Search: Enabled (www.kamtape.com)`);
    console.log(` YouTube Video Playback & Search: Enabled (www.youtube.com)`);
    console.log(` Video Frame Streaming: /video_stream Enabled`);
    console.log(` Video Audio Streaming: /video_audio Enabled`);
    console.log(` Media Streaming (Audio/Video): Enabled`);
    console.log(` Sample Media Showcase: http://localhost:${PORT}/sample_media`);
    console.log(` Robi-INTERNET WAP Portal: http://wap.robi.com.bd (via Gateway)`);
    console.log(`====================================================`);
});
