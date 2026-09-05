/**
 * FrogFind (frogfind.com) Search & Reader Handler for Nokia J2ME (240x320 QVGA)
 * Created by Action Retro (Sean) - Vintage search engine & readability proxy.
 */

const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

function isFrogFindUrl(targetUrl) {
    try {
        const u = new URL(targetUrl);
        const host = u.hostname.toLowerCase();
        return host === 'frogfind.com' || host === 'www.frogfind.com';
    } catch (e) {
        return false;
    }
}

function decodeBingUrl(rawUrl, decodeHtmlEntities) {
    let clean = decodeHtmlEntities ? decodeHtmlEntities(rawUrl) : rawUrl;
    if (clean.includes('bing.com/ck/a?')) {
        try {
            const u = new URL(clean);
            const uParam = u.searchParams.get('u');
            if (uParam && uParam.startsWith('a1')) {
                let b64 = uParam.substring(2).replace(/-/g, '+').replace(/_/g, '/');
                while (b64.length % 4) b64 += '=';
                return Buffer.from(b64, 'base64').toString('utf-8');
            }
        } catch (e) {}
    }
    return clean;
}

async function handleFrogFindPage(targetUrl, res, gatewayHost, decodeHtmlEntities, parseAndReflowHtml, formatPagePayload) {
    try {
        const u = new URL(targetUrl);
        const pathname = u.pathname;
        const query = u.searchParams.get('q');
        const articleUrl = u.searchParams.get('a');

        // 1. Reader Mode: /read.php?a=<targetUrl>
        if (pathname === '/read.php' || pathname === '/read') {
            if (!articleUrl) {
                res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
                return res.end('META:TITLE=FrogFind Reader Error\nH1:Missing Article URL\nP:Please specify an article URL parameter "a".');
            }

            let cleanArticleUrl = articleUrl.trim();
            if (!cleanArticleUrl.startsWith('http://') && !cleanArticleUrl.startsWith('https://')) {
                cleanArticleUrl = 'https://' + cleanArticleUrl;
            }

            console.log(`[FrogFind Reader] Fetching article: ${cleanArticleUrl}`);
            const resp = await fetch(cleanArticleUrl, {
                headers: {
                    'User-Agent': USER_AGENT,
                    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
                }
            });

            if (!resp.ok) {
                res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
                return res.end(`META:TITLE=FrogFind Reader Error\nH1:Error Fetching Article\nP:Could not load ${cleanArticleUrl} (Status ${resp.status})\nHR:\nL:https://www.frogfind.com\t🐸 Back to FrogFind Search`);
            }

            const html = await resp.text();
            const parsed = parseAndReflowHtml(html, cleanArticleUrl, cleanArticleUrl.startsWith('https://'), { img: '1' });
            
            // Format in FrogFind Reader Style
            const lines = [];
            lines.push('META:TITLE=FrogFind Reader: ' + (parsed.title || 'Article'));
            lines.push('META:URL=' + targetUrl);
            lines.push('META:HTTPS=1');
            lines.push('L:https://www.frogfind.com\t🐸 Back to FrogFind Search');
            lines.push('H1:' + (parsed.title || 'FrogFind Reader'));
            lines.push('L:' + cleanArticleUrl + '\t🌐 Open Original Webpage');
            lines.push('HR:');

            // Append article elements from parsed payload
            const rawPayload = formatPagePayload(parsed, gatewayHost);
            const rawLines = rawPayload.split('\n');
            for (let i = 0; i < rawLines.length; i++) {
                const l = rawLines[i];
                if (l.startsWith('META:')) continue;
                lines.push(l);
            }

            lines.push('HR:');
            lines.push('L:https://www.frogfind.com\t🐸 Back to FrogFind Home');

            res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
            return res.end(lines.join('\n'));
        }

        // 2. Search Results: /?q=<query> or /search?q=<query>
        if (query && query.trim().length > 0) {
            const cleanQ = query.trim();
            console.log(`[FrogFind Search] Searching for: ${cleanQ}`);
            const searchUrl = 'https://www.bing.com/search?q=' + encodeURIComponent(cleanQ);

            const resp = await fetch(searchUrl, {
                headers: {
                    'User-Agent': USER_AGENT,
                    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
                }
            });
            const html = await resp.text();

            const lines = [];
            lines.push('META:TITLE=FrogFind: ' + cleanQ);
            lines.push('META:URL=' + targetUrl);
            lines.push('META:HTTPS=1');
            lines.push('H1:FrogFind!');
            lines.push('P:Search Results for: ' + cleanQ);
            lines.push('L:search:frogfind\t🐸 Leap Again (New Search)');
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
                    const rawLink = linkMatch[1];
                    const directUrl = decodeBingUrl(rawLink, decodeHtmlEntities);
                    const title = decodeHtmlEntities(linkMatch[2].replace(/<[^>]+>/g, ' ').trim());
                    const snippet = pMatch ? decodeHtmlEntities(pMatch[1].replace(/<[^>]+>/g, ' ').trim()) : '';

                    let shortTitle = title;
                    if (shortTitle.length > 24) shortTitle = shortTitle.substring(0, 21) + '...';

                    let displayHost = directUrl;
                    try { displayHost = new URL(directUrl).hostname; } catch(e) {}

                    lines.push('H2:' + title);
                    lines.push(`L:https://www.frogfind.com/read.php?a=${encodeURIComponent(directUrl)}\t📖 Read in FrogFind: ${shortTitle}`);
                    lines.push(`L:${directUrl}\t🌐 Visit: ${displayHost}`);
                    if (snippet) {
                        lines.push('P:' + snippet);
                    }
                    lines.push('HR:');
                }
            }

            // Secondary fallback if b_algo wasn't matched
            if (resultCount === 0) {
                const anyH2Regex = /<h2\b[^>]*><a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a><\/h2>/gi;
                while ((match = anyH2Regex.exec(html)) !== null && resultCount < 15) {
                    const rawLink = match[1];
                    if (rawLink.includes('bing.com/search')) continue;
                    const directUrl = decodeBingUrl(rawLink, decodeHtmlEntities);
                    const title = decodeHtmlEntities(match[2].replace(/<[^>]+>/g, ' ').trim());
                    if (!title) continue;
                    resultCount++;
                    let shortTitle = title;
                    if (shortTitle.length > 24) shortTitle = shortTitle.substring(0, 21) + '...';
                    let displayHost = directUrl;
                    try { displayHost = new URL(directUrl).hostname; } catch(e) {}
                    lines.push('H2:' + title);
                    lines.push(`L:https://www.frogfind.com/read.php?a=${encodeURIComponent(directUrl)}\t📖 Read in FrogFind: ${shortTitle}`);
                    lines.push(`L:${directUrl}\t🌐 Visit: ${displayHost}`);
                    lines.push('HR:');
                }
            }

            if (resultCount === 0) {
                lines.push('P:No results found. Ribbbit :(');
                lines.push('L:search:frogfind\t🐸 Try Another Search');
            } else {
                lines.push('P:The Search Engine for Vintage Computers');
                lines.push('P:Created by Action Retro | Formatted for Nokia 240x320');
            }

            res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
            return res.end(lines.join('\n'));
        }

        // 3. FrogFind Homepage (/)
        const proxyLogo = `http://${gatewayHost}/static/frogfind.gif`;

        const lines = [];
        lines.push('META:TITLE=FrogFind! - Vintage Search');
        lines.push('META:URL=' + targetUrl);
        lines.push('META:HTTPS=1');
        lines.push('H1:FrogFind!');
        lines.push('P:The Search Engine for Vintage Computers');
        lines.push(`I:${proxyLogo}\tFrogFind Mascot`);
        lines.push(`L:search:frogfind\t🔍 Leap to: Search with FrogFind`);
        lines.push('HR:');

        lines.push('H2:Popular Vintage Searches');
        lines.push('L:https://www.frogfind.com/?q=Nokia+6300\t🔍 Search: Nokia 6300 Phone');
        lines.push('L:https://www.frogfind.com/?q=Retro+Computing\t🔍 Search: Retro Computing');
        lines.push('L:https://www.frogfind.com/?q=Action+Retro\t🔍 Search: Action Retro');
        lines.push('L:https://www.frogfind.com/?q=Vintage+Macintosh\t🔍 Search: Vintage Macintosh');
        lines.push('L:https://www.frogfind.com/?q=KamTape+Videos\t🔍 Search: KamTape Retro Videos');
        lines.push('HR:');

        lines.push('H2:About FrogFind');
        lines.push('P:FrogFind is an ad-free, lightweight search engine created by Sean (Action Retro) on YouTube.');
        lines.push('P:It strips away JavaScript, heavy CSS, and tracker bloat to deliver fast text for classic computers and Nokia mobile phones.');
        lines.push('L:https://youtube.com/ActionRetro\t📺 Action Retro on YouTube');
        lines.push('L:https://www.kamtape.com\t▶ KamTape Videos');
        lines.push('L:https://www.bing.com\t🔍 Switch to Bing Search');

        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end(lines.join('\n'));

    } catch (e) {
        console.error('FrogFind handler error:', e.message);
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end('META:TITLE=FrogFind Error\nH1:FrogFind Error\nP:' + e.message);
    }
}

module.exports = {
    isFrogFindUrl,
    handleFrogFindPage
};
