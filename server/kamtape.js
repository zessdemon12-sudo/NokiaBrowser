/**
 * KamTape (kamtape.com) video handler and parser for Nokia J2ME 240x320
 */
const url = require('url');

function isKamTapeUrl(targetUrl) {
    try {
        const u = new URL(targetUrl);
        return u.hostname === 'kamtape.com' || u.hostname === 'www.kamtape.com';
    } catch (e) {
        return false;
    }
}

function parseKamTapePage(html, baseUrl, gatewayHost, decodeHtmlEntities) {
    const u = new URL(baseUrl);
    const pathname = u.pathname;
    const isWatch = pathname === '/watch' || pathname === '/watch.php';
    const isResults = pathname === '/results' || pathname === '/results.php' || pathname === '/search';
    const searchQuery = u.searchParams.get('search_query') || u.searchParams.get('q') || u.searchParams.get('search') || '';
    const videoId = u.searchParams.get('v') || '';

    const lines = [];

    if (isWatch && videoId) {
        // 1. Watch Page
        let title = (html.match(/<title[^>]*>([\s\S]*?)<\/title>/i) || [])[1] || 'KamTape Video';
        title = decodeHtmlEntities(title.replace(/^KamTape\s*-\s*/i, '').trim());

        // Description
        let desc = '';
        const descMatch = html.match(/id=["\x27]vidDescRemain["\x27][^>]*>([\s\S]*?)<\/span>/i) ||
                          html.match(/name=["\x27]description["\x27]\s+content=["\x27]([^"\x27]*)["\x27]/i) ||
                          html.match(/id=["\x27]vidDescDiv["\x27][^>]*>([\s\S]*?)<\/div>/i);
        if (descMatch) {
            desc = decodeHtmlEntities(descMatch[1].replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim());
        }

        // Uploader
        let uploader = '';
        const authorMatch = html.match(/href=["\x27]\/profile\?user=([^"\x27]+)["\x27][^>]*>([\s\S]*?)<\/a>/i) ||
                            html.match(/href=["\x27]\/user\/([^"\x27]+)["\x27][^>]*>([\s\S]*?)<\/a>/i);
        if (authorMatch) {
            uploader = decodeHtmlEntities(authorMatch[2].replace(/<[^>]+>/g, '').trim());
        }

        // Thumbnail
        const stillUrl = `http://www.kamtape.com/get_still?video_id=${videoId}`;
        const proxyThumb = `http://${gatewayHost}/image?url=${encodeURIComponent(stillUrl)}`;

        // Video Stream (KamTape webm=1 serves baseline MP4)
        const mp4Url = `https://www.kamtape.com/get_video?video_id=${videoId}&webm=1`;
        const threeGpUrl = `http://${gatewayHost}/video.3gp?url=${encodeURIComponent(mp4Url)}&id=${videoId}`;
        const threeGp144pUrl = `http://${gatewayHost}/video.3gp?url=${encodeURIComponent(mp4Url)}&id=${videoId}&res=144p`;
        const audioUrl = `http://${gatewayHost}/video_audio?url=${encodeURIComponent(mp4Url)}`;

        lines.push('META:TITLE=' + title + ' - KamTape');
        lines.push('META:URL=' + baseUrl);
        lines.push('META:HTTPS=1');
        lines.push('H1:' + title);
        lines.push('I:' + proxyThumb + '\t' + title);
        lines.push('V:' + threeGpUrl + '\t▶ Stream 3GP: ' + title);
        lines.push('L:' + threeGpUrl + '\t🎬 Launch in Nokia RealPlayer (3GP)');
        lines.push('V:' + threeGp144pUrl + '\t▶ Stream 3GP (144p QCIF Classic)');
        lines.push('A:' + audioUrl + '\t♫ Audio: ' + title);
        if (uploader) {
            lines.push('P:Uploader: ' + uploader);
        }
        if (desc) {
            lines.push('P:' + desc);
        }
        lines.push('HR:');
        lines.push('H2:More on KamTape');
        lines.push('L:search:kamtape\t🔍 Search KamTape Videos');
        lines.push('L:https://www.kamtape.com\t📺 KamTape Home (All Videos)');

        // Extract related videos
        const relatedRegex = /<a\b[^>]*href=["\x27](\/watch\?v=([a-zA-Z0-9_-]+))["\x27][^>]*>([\s\S]*?)<\/a>/gi;
        let rm;
        let relCount = 0;
        const seen = {};
        seen[videoId] = true;

        while ((rm = relatedRegex.exec(html)) !== null && relCount < 10) {
            const relId = rm[2];
            if (seen[relId]) continue;
            const relText = decodeHtmlEntities(rm[3].replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim());
            if (relText.length > 2 && !relText.toLowerCase().includes('sign up')) {
                seen[relId] = true;
                relCount++;
                lines.push('L:https://www.kamtape.com/watch?v=' + relId + '\t▶ ' + relText);
            }
        }

        return lines.join('\n');
    }

    // 2. Search Results / Home / Browse Page: Extract video feed
    let title = (html.match(/<title[^>]*>([\s\S]*?)<\/title>/i) || [])[1] || 'KamTape - Share Your World';
    title = decodeHtmlEntities(title.trim());

    if (isResults) {
        lines.push('META:TITLE=' + (searchQuery ? `KamTape: ${searchQuery}` : 'KamTape Video Search'));
        lines.push('META:URL=' + baseUrl);
        lines.push('META:HTTPS=1');
        lines.push('H1:KamTape Search');
        if (searchQuery) {
            lines.push('P:Results for: "' + searchQuery + '"');
        } else {
            lines.push('P:Search thousands of vintage videos:');
        }
        lines.push('L:search:kamtape\t🔍 ' + (searchQuery ? 'New Search Query' : 'Click to Type Search Query'));
        lines.push('L:https://www.kamtape.com\t📺 Browse Popular Videos');
        lines.push('HR:');
    } else {
        lines.push('META:TITLE=' + title);
        lines.push('META:URL=' + baseUrl);
        lines.push('META:HTTPS=1');
        lines.push('H1:KamTape Videos');
        lines.push('L:search:kamtape\t🔍 Search KamTape Videos (Click to Type)');
        lines.push('P:Watch retro video streams formatted for Nokia 240x320:');
        lines.push('H2:Popular Searches');
        lines.push('L:https://www.kamtape.com/results?search_query=Mario\t🔍 Mario');
        lines.push('L:https://www.kamtape.com/results?search_query=Sonic\t🔍 Sonic');
        lines.push('L:https://www.kamtape.com/results?search_query=Animation\t🔍 Animation');
        lines.push('L:https://www.kamtape.com/results?search_query=Music\t🔍 Music');
        lines.push('HR:');
    }

    let count = 0;
    const seenVideos = {};

    // First attempt: match rich vTable entries (used in search results & detailed browse)
    const vTableRegex = /<table\b[^>]*class=["\x27]vTable["\x27][^>]*>([\s\S]*?)<\/table>/gi;
    let tm;
    while ((tm = vTableRegex.exec(html)) !== null && count < 25) {
        const block = tm[1];
        const vIdMatch = block.match(/\/watch\?v=([a-zA-Z0-9_-]+)/);
        if (!vIdMatch) continue;
        const vId = vIdMatch[1];
        if (seenVideos[vId]) continue;
        seenVideos[vId] = true;

        const thumbMatch = block.match(/<img\b[^>]*src=["\x27](https?:\/\/[^"\x27]+)["\x27]/i);
        const thumbUrl = thumbMatch ? thumbMatch[1] : `http://www.kamtape.com/get_still?video_id=${vId}`;

        const titleMatch = block.match(/<div\b[^>]*class=["\x27]vtitle["\x27][^>]*>[\s\S]*?<a[^>]*>([\s\S]*?)<\/a>/i);
        let vTitle = titleMatch ? decodeHtmlEntities(titleMatch[1].replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()) : ('Video ' + vId);

        const runtimeMatch = block.match(/class=["\x27]runtime["\x27][^>]*>([\s\S]*?)<\/span>/i);
        const runtime = runtimeMatch ? runtimeMatch[1].trim() : '';

        const descMatch = block.match(/id=["\x27]BeginvidDesc[^"\x27]*["\x27][^>]*>([\s\S]*?)<\/span>/i);
        let desc = descMatch ? decodeHtmlEntities(descMatch[1].replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()) : '';
        if (desc.length > 70) desc = desc.substring(0, 68) + '...';

        const proxyThumb = `http://${gatewayHost}/image?url=${encodeURIComponent(thumbUrl)}`;
        const mp4Url = `https://www.kamtape.com/get_video?video_id=${vId}&webm=1`;
        const threeGpUrl = `http://${gatewayHost}/video.3gp?url=${encodeURIComponent(mp4Url)}&id=${vId}`;

        count++;
        lines.push('H2:' + vTitle + (runtime ? ` [${runtime}]` : ''));
        lines.push('I:' + proxyThumb + '\t' + vTitle);
        lines.push('V:' + threeGpUrl + '\t▶ Stream 3GP: ' + vTitle);
        lines.push('L:' + threeGpUrl + '\t🎬 Launch in Nokia RealPlayer (3GP)');
        if (desc) {
            lines.push('P:' + desc);
        }
        lines.push('L:https://www.kamtape.com/watch?v=' + vId + '\tDetails & Related');
        lines.push('HR:');
    }

    // Second attempt: Match video entries with thumbnails and titles
    if (count === 0) {
        const vRegex = /<a\b[^>]*href=["\x27]\/watch\?v=([a-zA-Z0-9_-]+)["\x27][^>]*>[\s\S]*?<img\b[^>]*src=["\x27]([^"\x27]+)["\x27][\s\S]*?<\/a>[\s\S]*?<div\b[^>]*class=["\x27]vtitle[^"\x27]*["\x27][^>]*>[\s\S]*?<a\b[^>]*href=["\x27]\/watch\?v=\1["\x27][^>]*>([\s\S]*?)<\/a>/gi;
        let m;
        while ((m = vRegex.exec(html)) !== null && count < 25) {
            const vId = m[1];
            if (seenVideos[vId]) continue;
            seenVideos[vId] = true;
            const thumbUrl = m[2];
            const vTitle = decodeHtmlEntities(m[3].replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()) || ('Video ' + vId);

            const proxyThumb = `http://${gatewayHost}/image?url=${encodeURIComponent(thumbUrl)}`;
            const mp4Url = `https://www.kamtape.com/get_video?video_id=${vId}&webm=1`;
            const threeGpUrl = `http://${gatewayHost}/video.3gp?url=${encodeURIComponent(mp4Url)}&id=${vId}`;

            count++;
            lines.push('H2:' + vTitle);
            lines.push('I:' + proxyThumb + '\t' + vTitle);
            lines.push('V:' + threeGpUrl + '\t▶ Stream 3GP: ' + vTitle);
            lines.push('L:' + threeGpUrl + '\t🎬 Launch in Nokia RealPlayer (3GP)');
            lines.push('L:https://www.kamtape.com/watch?v=' + vId + '\tDetails & Related');
            lines.push('HR:');
        }
    }

    // Third attempt: simple links fallback
    if (count === 0) {
        const linkRegex = /<a\b[^>]*href=["\x27]\/watch\?v=([a-zA-Z0-9_-]+)["\x27][^>]*>([\s\S]*?)<\/a>/gi;
        let lm;
        while ((lm = linkRegex.exec(html)) !== null && count < 25) {
            const vId = lm[1];
            if (seenVideos[vId]) continue;
            const text = decodeHtmlEntities(lm[2].replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim());
            if (text.length > 2 && !text.toLowerCase().includes('sign up')) {
                seenVideos[vId] = true;
                count++;
                const mp4Url = `https://www.kamtape.com/get_video?video_id=${vId}&webm=1`;
                const threeGpUrl = `http://${gatewayHost}/video.3gp?url=${encodeURIComponent(mp4Url)}&id=${vId}`;
                lines.push('H2:' + text);
                lines.push('V:' + threeGpUrl + '\t▶ Stream 3GP: ' + text);
                lines.push('L:' + threeGpUrl + '\t🎬 Launch in Nokia RealPlayer (3GP)');
                lines.push('L:https://www.kamtape.com/watch?v=' + vId + '\tWatch on KamTape');
                lines.push('HR:');
            }
        }
    }

    if (count === 0) {
        if (isResults && searchQuery) {
            lines.push('P:No videos found matching "' + searchQuery + '".');
            lines.push('L:search:kamtape\t🔍 Try Another Search');
            lines.push('HR:');
            lines.push('H2:Try Popular Searches');
            lines.push('L:https://www.kamtape.com/results?search_query=Mario\t🔍 Mario');
            lines.push('L:https://www.kamtape.com/results?search_query=Sonic\t🔍 Sonic');
            lines.push('L:https://www.kamtape.com/results?search_query=Animation\t🔍 Animation');
            lines.push('L:https://www.kamtape.com\t📺 Browse Popular Videos');
        } else if (isResults) {
            lines.push('P:Search KamTape retro video database:');
            lines.push('L:search:kamtape\t🔍 Enter Search Keywords...');
            lines.push('HR:');
            lines.push('H2:Popular Searches');
            lines.push('L:https://www.kamtape.com/results?search_query=Mario\t🔍 Mario');
            lines.push('L:https://www.kamtape.com/results?search_query=Sonic\t🔍 Sonic');
            lines.push('L:https://www.kamtape.com/results?search_query=Animation\t🔍 Animation');
            lines.push('L:https://www.kamtape.com/results?search_query=Music\t🔍 Music Videos');
            lines.push('L:https://www.kamtape.com\t📺 Browse Popular Videos');
        } else {
            lines.push('P:No videos detected on this page.');
            lines.push('L:search:kamtape\t🔍 Search KamTape Videos');
        }
    } else {
        lines.push('L:search:kamtape\t🔍 Search KamTape Videos');
        lines.push('L:https://www.kamtape.com\t📺 KamTape Home');
    }

    return lines.join('\n');
}

module.exports = {
    isKamTapeUrl,
    parseKamTapePage
};
