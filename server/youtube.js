/**
 * YouTube (youtube.com) handler, search, and page reflower for Nokia J2ME 240x320
 */
const { execFile } = require('child_process');
const path = require('path');

const YT_DLP_PATH = path.join(__dirname, '..', 'tools', 'yt-dlp');

// In-memory caches to make repeated searches and watch pages instantaneous
const searchCache = new Map(); // query -> { timestamp, data }
const videoInfoCache = new Map(); // videoId -> { timestamp, data }
const CACHE_TTL_MS = 10 * 60 * 1000; // 10 minutes

function isYouTubeUrl(targetUrl) {
    try {
        const u = new URL(targetUrl);
        const host = u.hostname.toLowerCase();
        return host === 'youtube.com' ||
               host === 'www.youtube.com' ||
               host === 'm.youtube.com' ||
               host === 'youtu.be' ||
               host.endsWith('.youtube.com');
    } catch (e) {
        return false;
    }
}

function extractVideoId(targetUrl) {
    try {
        const u = new URL(targetUrl);
        if (u.hostname === 'youtu.be') {
            const id = u.pathname.substring(1).split(/[?#&]/)[0];
            if (id && id.length === 11 && !id.startsWith('UC')) return id;
        }
        if (u.searchParams.has('v')) {
            const id = u.searchParams.get('v');
            if (id && id.length === 11 && !id.startsWith('UC')) return id;
        }
        const m = u.pathname.match(/\/(?:embed|v|shorts)\/([a-zA-Z0-9_-]{11})(?:[?#&]|$)/);
        if (m) return m[1];
    } catch (e) {}
    const directMatch = targetUrl.match(/[?&]v=([a-zA-Z0-9_-]{11})(?:[&#]|$)/) || targetUrl.match(/youtu\.be\/([a-zA-Z0-9_-]{11})(?:[?#&]|$)/);
    return directMatch ? directMatch[1] : null;
}

function formatDuration(sec) {
    if (!sec || isNaN(sec) || sec <= 0) return '';
    const s = Math.floor(sec);
    const m = Math.floor(s / 60);
    const remS = s % 60;
    const padS = remS < 10 ? '0' + remS : remS;
    if (m < 60) {
        return `${m}:${padS}`;
    }
    const h = Math.floor(m / 60);
    const remM = m % 60;
    const padM = remM < 10 ? '0' + remM : remM;
    return `${h}:${padM}:${padS}`;
}

function formatViews(views) {
    if (!views || isNaN(views)) return '';
    const v = parseInt(views, 10);
    if (v >= 1000000) {
        return (v / 1000000).toFixed(1).replace(/\.0$/, '') + 'M views';
    }
    if (v >= 1000) {
        return (v / 1000).toFixed(1).replace(/\.0$/, '') + 'K views';
    }
    return v + ' views';
}

function runYtDlp(args, timeoutMs = 12000) {
    return new Promise((resolve, reject) => {
        execFile(YT_DLP_PATH, args, { maxBuffer: 10 * 1024 * 1024, timeout: timeoutMs }, (err, stdout, stderr) => {
            if (err) {
                return reject(new Error(stderr || err.message));
            }
            resolve(stdout);
        });
    });
}

async function searchYouTube(query, count = 15) {
    const cleanQ = query.trim();
    const cacheKey = cleanQ.toLowerCase();
    const now = Date.now();

    if (searchCache.has(cacheKey)) {
        const entry = searchCache.get(cacheKey);
        if (now - entry.timestamp < CACHE_TTL_MS) {
            return entry.data;
        }
    }

    try {
        const stdout = await runYtDlp([
            '--dump-json',
            `ytsearch${count}:${cleanQ}`,
            '--flat-playlist',
            '--no-warnings'
        ]);

        const lines = stdout.trim().split('\n');
        const results = [];
        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trim();
            if (!line) continue;
            try {
                const item = JSON.parse(line);
                const id = item.id;
                if (!id || id.length !== 11 || id.startsWith('UC') || item._type === 'channel' || item._type === 'playlist') {
                    continue;
                }
                const title = item.title || `Video ${id}`;
                const duration = item.duration_string || formatDuration(item.duration);
                const channel = item.channel || item.uploader || '';
                const views = formatViews(item.view_count);
                let desc = item.description || '';
                if (desc.length > 75) desc = desc.substring(0, 72) + '...';

                let thumb = `https://i.ytimg.com/vi/${id}/hqdefault.jpg`;
                if (item.thumbnails && item.thumbnails.length > 0) {
                    thumb = item.thumbnails[0].url;
                }

                results.push({
                    id,
                    title,
                    duration,
                    channel,
                    views,
                    desc,
                    thumb
                });
            } catch (e) {}
        }

        searchCache.set(cacheKey, { timestamp: now, data: results });
        return results;
    } catch (e) {
        console.error('[YouTube Search Error]:', e.message);
        return [];
    }
}

async function getVideoInfo(videoId) {
    const now = Date.now();
    if (videoInfoCache.has(videoId)) {
        const entry = videoInfoCache.get(videoId);
        if (now - entry.timestamp < CACHE_TTL_MS) {
            return entry.data;
        }
    }

    try {
        const stdout = await runYtDlp([
            '--dump-json',
            `https://www.youtube.com/watch?v=${videoId}`,
            '--no-playlist',
            '--no-warnings'
        ], 15000);

        const data = JSON.parse(stdout.trim());
        const info = {
            id: videoId,
            title: data.title || `YouTube Video ${videoId}`,
            channel: data.channel || data.uploader || 'YouTube Creator',
            views: formatViews(data.view_count),
            duration: data.duration_string || formatDuration(data.duration),
            durationSec: data.duration || 0,
            uploadDate: data.upload_date ? `${data.upload_date.substring(0,4)}-${data.upload_date.substring(4,6)}-${data.upload_date.substring(6,8)}` : '',
            desc: data.description || '',
            thumb: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`
        };

        videoInfoCache.set(videoId, { timestamp: now, data: info });
        return info;
    } catch (e) {
        console.error('[YouTube Video Info Error]:', e.message);
        return {
            id: videoId,
            title: `YouTube Video (${videoId})`,
            channel: 'YouTube',
            views: '',
            duration: '',
            uploadDate: '',
            desc: '',
            thumb: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`
        };
    }
}

async function handleYouTubeRequest(targetUrl, res, gatewayHost, decodeHtmlEntities) {
    try {
        const u = new URL(targetUrl);
        const pathname = u.pathname;
        const videoId = extractVideoId(targetUrl);
        const searchQuery = u.searchParams.get('search_query') || u.searchParams.get('q') || '';
        const isSearch = pathname === '/results' || pathname === '/search' || (u.searchParams.has('search_query'));

        // 1. Watch Page
        if (videoId && (pathname === '/watch' || u.hostname === 'youtu.be' || pathname.startsWith('/shorts/') || pathname.startsWith('/embed/'))) {
            return await handleWatchPage(videoId, targetUrl, res, gatewayHost, decodeHtmlEntities);
        }

        // 2. Search Results Page
        if (isSearch) {
            return await handleSearchPage(searchQuery, targetUrl, res, gatewayHost);
        }

        // 3. YouTube Mobile Homepage
        return await handleHomePage(targetUrl, res, gatewayHost);

    } catch (e) {
        console.error('[YouTube Request Error]:', e.message);
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`META:TITLE=YouTube Error\nH1:YouTube Error\nP:${e.message}\nHR:\nL:search:youtube\t🔍 Search YouTube\nL:https://www.youtube.com\t📺 YouTube Home`);
    }
}

async function handleWatchPage(videoId, originalUrl, res, gatewayHost, decodeHtmlEntities) {
    const info = await getVideoInfo(videoId);
    const lines = [];

    const cleanTitle = decodeHtmlEntities ? decodeHtmlEntities(info.title) : info.title;
    const cleanDesc = (decodeHtmlEntities ? decodeHtmlEntities(info.desc) : info.desc)
        .replace(/<[^>]+>/g, ' ')
        .replace(/https?:\/\/[^\s]+/g, '')
        .replace(/\s+/g, ' ')
        .trim();

    const watchUrl = originalUrl && originalUrl.startsWith('http') ? originalUrl : `https://www.youtube.com/watch?v=${videoId}`;
    const proxyThumb = `http://${gatewayHost}/image?url=${encodeURIComponent(info.thumb)}`;
    const threeGpUrl = `http://${gatewayHost}/video.3gp?url=${encodeURIComponent(watchUrl)}&id=${videoId}`;
    const threeGp144pUrl = `http://${gatewayHost}/video.3gp?url=${encodeURIComponent(watchUrl)}&id=${videoId}&res=144p`;
    const durParam = info.durationSec ? `&dur=${info.durationSec}` : '';
    const audioMedia = `http://${gatewayHost}/video_audio?url=${encodeURIComponent(watchUrl)}${durParam}`;

    lines.push('META:TITLE=' + cleanTitle + ' - YouTube');
    lines.push('META:URL=' + watchUrl);
    lines.push('META:HTTPS=1');
    lines.push('H1:' + cleanTitle);
    lines.push('I:' + proxyThumb + '\t' + cleanTitle);
    lines.push('V:' + threeGpUrl + '\t▶ Stream 3GP: ' + cleanTitle);
    lines.push('L:' + threeGpUrl + '\t🎬 Launch in Nokia RealPlayer (3GP)');
    lines.push('V:' + threeGp144pUrl + '\t▶ Stream 3GP (144p QCIF Classic)');
    lines.push('A:' + audioMedia + '\t♫ Audio: ' + cleanTitle);

    let metaLine = 'Channel: ' + info.channel;
    if (info.views) metaLine += ' • ' + info.views;
    if (info.duration) metaLine += ' • ' + info.duration;
    if (info.uploadDate) metaLine += ' • ' + info.uploadDate;
    lines.push('P:' + metaLine);

    if (cleanDesc) {
        let shortDesc = cleanDesc;
        if (shortDesc.length > 250) {
            shortDesc = shortDesc.substring(0, 245) + '...';
        }
        lines.push('P:' + shortDesc);
    }

    lines.push('HR:');
    lines.push('H2:More on YouTube');
    lines.push('L:https://www.youtube.com/search\t🔍 Search https://www.youtube.com/');
    lines.push('L:https://www.youtube.com\t📺 YouTube Home');

    res.writeHead(200, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Access-Control-Allow-Origin': '*'
    });
    return res.end(lines.join('\n'));
}

async function handleSearchPage(query, originalUrl, res, gatewayHost) {
    const lines = [];
    const cleanQ = (query || '').trim();

    lines.push('META:TITLE=' + (cleanQ ? `YouTube: ${cleanQ}` : 'YouTube Search'));
    lines.push('META:URL=' + originalUrl);
    lines.push('META:HTTPS=1');
    lines.push('H1:YouTube Search');

    if (cleanQ) {
        lines.push('P:Results for: "' + cleanQ + '"');
    } else {
        lines.push('P:Search millions of videos on YouTube:');
    }

    lines.push('L:https://www.youtube.com/search\t🔍 Search https://www.youtube.com/ (Click to Type)');
    lines.push('L:search:youtube\t🔍 Quick Search Dialog');
    lines.push('L:https://www.youtube.com\t📺 Browse YouTube Home');
    lines.push('HR:');

    if (!cleanQ) {
        lines.push('H2:Popular Topics');
        lines.push('L:https://www.youtube.com/results?search_query=Trending\t🔍 Trending Videos');
        lines.push('L:https://www.youtube.com/results?search_query=Music\t🔍 Music');
        lines.push('L:https://www.youtube.com/results?search_query=Gaming\t🔍 Gaming');
        lines.push('L:https://www.youtube.com/results?search_query=Retro+Tech\t🔍 Retro Tech & Phones');
        lines.push('L:https://www.youtube.com/results?search_query=Nokia\t🔍 Nokia Vintage Phones');
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end(lines.join('\n'));
    }

    const videos = await searchYouTube(cleanQ, 15);

    if (videos.length === 0) {
        lines.push('P:No videos found matching "' + cleanQ + '".');
        lines.push('L:search:youtube\t🔍 Try Another Search');
        lines.push('HR:');
        lines.push('H2:Popular Searches');
        lines.push('L:https://www.youtube.com/results?search_query=Nokia+6300\t🔍 Nokia 6300');
        lines.push('L:https://www.youtube.com/results?search_query=Retro+Computing\t🔍 Retro Computing');
        lines.push('L:https://www.youtube.com/results?search_query=Action+Retro\t🔍 Action Retro');
    } else {
        for (let i = 0; i < videos.length; i++) {
            const v = videos[i];
            const watchUrl = `https://www.youtube.com/watch?v=${v.id}`;
            const proxyThumb = `http://${gatewayHost}/image?url=${encodeURIComponent(v.thumb)}`;
            const threeGpUrl = `http://${gatewayHost}/video.3gp?url=${encodeURIComponent(watchUrl)}&id=${v.id}`;

            lines.push('H2:' + v.title + (v.duration ? ` [${v.duration}]` : ''));
            lines.push('I:' + proxyThumb + '\t' + v.title);
            lines.push('V:' + threeGpUrl + '\t▶ Stream 3GP: ' + v.title);
            lines.push('L:' + threeGpUrl + '\t🎬 Launch in Nokia RealPlayer (3GP)');
            if (v.channel || v.views) {
                lines.push('P:' + (v.channel ? v.channel : '') + (v.views ? ' • ' + v.views : ''));
            }
            if (v.desc) {
                lines.push('P:' + v.desc);
            }
            lines.push('L:https://www.youtube.com/watch?v=' + v.id + '\tDetails & Related');
            lines.push('HR:');
        }
        lines.push('L:search:youtube\t🔍 Search YouTube');
        lines.push('L:https://www.youtube.com\t📺 YouTube Home');
    }

    res.writeHead(200, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Access-Control-Allow-Origin': '*'
    });
    return res.end(lines.join('\n'));
}

async function handleHomePage(originalUrl, res, gatewayHost) {
    const lines = [];

    lines.push('META:TITLE=YouTube Mobile');
    lines.push('META:URL=' + originalUrl);
    lines.push('META:HTTPS=1');
    lines.push('H1:YouTube Mobile');
    lines.push('L:https://www.youtube.com/search\t🔍 Search https://www.youtube.com/ (Click to Type)');
    lines.push('L:search:youtube\t🔍 Quick Search Dialog');
    lines.push('P:Watch YouTube videos formatted for Nokia 240x320:');

    lines.push('H2:Popular Topics');
    lines.push('L:https://www.youtube.com/results?search_query=Trending\t🔍 Trending Videos');
    lines.push('L:https://www.youtube.com/results?search_query=Music\t🔍 Music');
    lines.push('L:https://www.youtube.com/results?search_query=Gaming\t🔍 Gaming');
    lines.push('L:https://www.youtube.com/results?search_query=Retro+Tech\t🔍 Retro Tech & Phones');
    lines.push('L:https://www.youtube.com/results?search_query=Nokia\t🔍 Nokia Vintage Phones');
    lines.push('HR:');

    lines.push('H2:Featured Videos');
    const videos = await searchYouTube('Trending', 8);
    if (videos.length > 0) {
        for (let i = 0; i < videos.length; i++) {
            const v = videos[i];
            const watchUrl = `https://www.youtube.com/watch?v=${v.id}`;
            const proxyThumb = `http://${gatewayHost}/image?url=${encodeURIComponent(v.thumb)}`;
            const threeGpUrl = `http://${gatewayHost}/video.3gp?url=${encodeURIComponent(watchUrl)}&id=${v.id}`;

            lines.push('H2:' + v.title + (v.duration ? ` [${v.duration}]` : ''));
            lines.push('I:' + proxyThumb + '\t' + v.title);
            lines.push('V:' + threeGpUrl + '\t▶ Stream 3GP: ' + v.title);
            lines.push('L:' + threeGpUrl + '\t🎬 Launch in Nokia RealPlayer (3GP)');
            if (v.channel || v.views) {
                lines.push('P:' + (v.channel ? v.channel : '') + (v.views ? ' • ' + v.views : ''));
            }
            lines.push('L:https://www.youtube.com/watch?v=' + v.id + '\tDetails & Related');
            lines.push('HR:');
        }
    }

    lines.push('L:https://www.youtube.com/search\t🔍 Search https://www.youtube.com/');
    lines.push('L:search:youtube\t🔍 Quick Search Dialog');
    lines.push('L:https://www.kamtape.com\t📺 KamTape Retro Videos');
    lines.push('L:https://www.frogfind.com\t🐸 FrogFind Search');

    res.writeHead(200, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Access-Control-Allow-Origin': '*'
    });
    return res.end(lines.join('\n'));
}

module.exports = {
    isYouTubeUrl,
    extractVideoId,
    searchYouTube,
    getVideoInfo,
    handleYouTubeRequest
};
