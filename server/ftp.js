/**
 * Pure Node.js RFC 959 FTP Client & Reflow Adapter for Nokia J2ME (240x320 QVGA)
 * Provides directory browsing, text file viewing, and media streaming over FTP.
 * (MIT License)
 */

const net = require("net");

const DEFAULT_FTP_PORT = 21;
const DEFAULT_TIMEOUT_MS = 10000;

/**
 * Checks if target URL matches FTP scheme or hostname
 */
function isFtpUrl(targetUrl) {
    if (!targetUrl) return false;
    const lower = targetUrl.toLowerCase().trim();
    if (lower.startsWith("ftp://") || lower.startsWith("ftp:") || lower.startsWith("ftp ")) return true;
    if (lower.startsWith("ftp.") && !lower.includes("://")) return true;
    return false;
}

/**
 * Parses FTP URL into connection parameters and normalized path
 */
function parseFtpUrl(rawUrl) {
    let clean = rawUrl.trim();
    if (clean.startsWith("ftp ")) {
        clean = clean.substring(4).trim();
    }
    if (clean.startsWith("ftp:")) {
        clean = clean.substring(4).trim();
        while (clean.startsWith("/")) clean = clean.substring(1);
        clean = "ftp://" + clean;
    }
    if (!clean.startsWith("ftp://")) {
        clean = "ftp://" + clean;
    }

    try {
        const u = new URL(clean);
        return {
            host: u.hostname,
            port: u.port ? parseInt(u.port, 10) : DEFAULT_FTP_PORT,
            user: decodeURIComponent(u.username || "anonymous"),
            pass: decodeURIComponent(u.password || "nokia@browser.com"),
            path: decodeURIComponent(u.pathname || "/") || "/"
        };
    } catch (e) {
        const m = clean.match(/^ftp:\/\/(?:([^:@]+)(?::([^@]+))?@)?([^\/:]+)(?::(\d+))?(\/.*)?$/i);
        if (m) {
            return {
                host: m[3],
                port: m[4] ? parseInt(m[4], 10) : DEFAULT_FTP_PORT,
                user: m[1] || "anonymous",
                pass: m[2] || "nokia@browser.com",
                path: m[5] || "/"
            };
        }
        return { host: clean.replace(/^ftp:\/\//i, ""), port: DEFAULT_FTP_PORT, user: "anonymous", pass: "nokia@browser.com", path: "/" };
    }
}

/**
 * Connects to FTP control socket and handles RFC 959 multi-line command/response flow
 */
class FtpClient {
    constructor(host, port, timeoutMs = DEFAULT_TIMEOUT_MS) {
        this.host = host;
        this.port = port || DEFAULT_FTP_PORT;
        this.timeoutMs = timeoutMs;
        this.socket = null;
        this.buffer = "";
    }

    connect() {
        return new Promise((resolve, reject) => {
            let timer = null;
            const sock = net.createConnection({ port: this.port, host: this.host, family: 4 }, () => {
                // Connected, waiting for 220 banner
            });

            timer = setTimeout(() => {
                sock.destroy();
                reject(new Error(`Connection to ${this.host}:${this.port} timed out (${this.timeoutMs}ms)`));
            }, this.timeoutMs);

            this.socket = sock;

            const onData = (chunk) => {
                this.buffer += chunk.toString();
                const resp = this._extractResponse();
                if (resp) {
                    sock.removeListener("data", onData);
                    clearTimeout(timer);
                    if (resp.code === 220) {
                        resolve(resp);
                    } else {
                        reject(new Error(`FTP server error on greeting: ${resp.raw}`));
                    }
                }
            };

            sock.on("data", onData);
            sock.on("error", (err) => {
                clearTimeout(timer);
                reject(err);
            });
        });
    }

    _extractResponse() {
        // RFC 959: reply is complete when a line starts with 3 digits followed by a space
        const lines = this.buffer.split(/\r?\n/);
        for (let i = 0; i < lines.length; i++) {
            const l = lines[i];
            const m = l.match(/^(\d{3})\s(.*)$/);
            if (m) {
                const code = parseInt(m[1], 10);
                const fullText = lines.slice(0, i + 1).join("\n");
                this.buffer = lines.slice(i + 1).join("\n");
                return { code, text: m[2], raw: fullText };
            }
        }
        return null;
    }

    command(cmd) {
        return new Promise((resolve, reject) => {
            if (!this.socket || this.socket.destroyed) {
                return reject(new Error("FTP socket closed"));
            }

            let timer = setTimeout(() => {
                reject(new Error(`FTP command '${cmd}' timed out`));
            }, this.timeoutMs);

            const onData = (chunk) => {
                this.buffer += chunk.toString();
                const resp = this._extractResponse();
                if (resp) {
                    this.socket.removeListener("data", onData);
                    clearTimeout(timer);
                    resolve(resp);
                }
            };

            this.socket.on("data", onData);
            this.socket.write(cmd + "\r\n");
        });
    }

    async login(user, pass) {
        const uResp = await this.command(`USER ${user}`);
        if (uResp.code === 230) {
            return uResp;
        }
        if (uResp.code === 331) {
            const pResp = await this.command(`PASS ${pass}`);
            if (pResp.code === 230 || pResp.code === 202) {
                return pResp;
            }
            throw new Error(`FTP Login Failed: ${pResp.text}`);
        }
        throw new Error(`FTP USER Failed: ${uResp.text}`);
    }

    async pasv() {
        const pResp = await this.command("PASV");
        if (pResp.code !== 227) {
            throw new Error(`PASV command failed: ${pResp.text}`);
        }
        const m = pResp.text.match(/\((\d+),(\d+),(\d+),(\d+),(\d+),(\d+)\)/);
        if (!m) {
            throw new Error(`Could not parse PASV response: ${pResp.text}`);
        }
        let pasvHost = `${m[1]}.${m[2]}.${m[3]}.${m[4]}`;
        const pasvPort = parseInt(m[5], 10) * 256 + parseInt(m[6], 10);

        if (pasvHost.startsWith("10.") || pasvHost.startsWith("192.168.") || pasvHost.startsWith("172.16.") || pasvHost === "127.0.0.1") {
            pasvHost = this.host;
        }

        return { host: pasvHost, port: pasvPort };
    }

    close() {
        try {
            if (this.socket && !this.socket.destroyed) {
                this.socket.write("QUIT\r\n");
                this.socket.end();
            }
        } catch (e) {}
    }
}

/**
 * Format bytes into human-readable representation
 */
function formatSize(bytes) {
    if (bytes === 0 || bytes === undefined || isNaN(bytes)) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    if (i === 0) return `${bytes} B`;
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

/**
 * Parses directory entry line in Unix, Windows IIS, or MLSD formats
 */
function parseFtpListingLine(line) {
    line = line.trim();
    if (!line) return null;

    // 1. MLSD format
    if (line.includes("type=") && line.includes(";")) {
        const parts = line.split(";");
        const name = parts[parts.length - 1].trim();
        if (name === "." || name === "..") return null;
        const isDir = line.includes("type=dir") || line.includes("type=cdir") || line.includes("type=pdir");
        let size = 0;
        const sizePart = parts.find(p => p.toLowerCase().startsWith("size="));
        if (sizePart) size = parseInt(sizePart.substring(5), 10) || 0;
        return { name, isDir, size };
    }

    // 2. Windows IIS format: MM-DD-YY HH:MM(AM|PM) <DIR>|size name
    const winMatch = line.match(/^(\d{2}-\d{2}-\d{2,4}\s+\d{2}:\d{2}(?:AM|PM))\s+(<DIR>|\d+)\s+(.+)$/i);
    if (winMatch) {
        const name = winMatch[3].trim();
        if (name === "." || name === "..") return null;
        const isDir = winMatch[2].toUpperCase() === "<DIR>";
        const size = isDir ? 0 : (parseInt(winMatch[2], 10) || 0);
        return { name, isDir, size };
    }

    // 3. Standard Unix format: drwxr-xr-x 2 demo users 0 Mar 31 2023 pub
    const unixMatch = line.match(/^([bcdlps\-])[rwx\-]{9}\+?\s+\d+\s+\S+\s+\S+\s+(\d+)\s+[A-Za-z]{3}\s+\d+\s+[\d:]+\s+(.+)$/);
    if (unixMatch) {
        let name = unixMatch[3].trim();
        if (name === "." || name === "..") return null;
        if (name.includes(" -> ")) name = name.split(" -> ")[0].trim();
        const isDir = unixMatch[1] === "d" || unixMatch[1] === "l";
        const size = parseInt(unixMatch[2], 10) || 0;
        return { name, isDir, size };
    }

    // 4. Flexible whitespace fallback
    const parts = line.split(/\s+/);
    if (parts.length >= 9 && (line.startsWith("d") || line.startsWith("-") || line.startsWith("l"))) {
        const name = parts.slice(8).join(" ");
        if (name === "." || name === "..") return null;
        const isDir = line.startsWith("d") || line.startsWith("l");
        const size = parseInt(parts[4], 10) || 0;
        return { name, isDir, size };
    }

    return null;
}

function isTextFile(filename) {
    return /\.(txt|log|md|nfo|c|h|cpp|hpp|java|py|js|ts|html|htm|xml|json|csv|sh|bat|conf|ini|cfg|me|1st|doc|rtf|diff|patch|properties|mf)$/i.test(filename);
}

function isImageFile(filename) {
    return /\.(png|jpg|jpeg|gif|bmp|webp|ico|svg)$/i.test(filename);
}

function isMediaFile(filename) {
    return /\.(mp3|wav|aac|ogg|3gp|mp4|webm|mkv|flv|avi|mov)$/i.test(filename);
}

/**
 * Fetches directory listing or file over FTP
 */
async function fetchFtp(ftpInfo) {
    const client = new FtpClient(ftpInfo.host, ftpInfo.port);
    try {
        await client.connect();
        await client.login(ftpInfo.user, ftpInfo.pass);

        const targetPath = ftpInfo.path || "/";
        const isLikelyDir = targetPath.endsWith("/") || !targetPath.split("/").pop().includes(".");

        if (isLikelyDir) {
            try {
                if (targetPath !== "/") {
                    const cwdResp = await client.command(`CWD ${targetPath}`);
                    if (cwdResp.code >= 400) throw new Error(cwdResp.text);
                }
                await client.command("TYPE A");
                const dataTarget = await client.pasv();

                const dataSock = net.createConnection({ port: dataTarget.port, host: dataTarget.host, family: 4 });
                let listOutput = "";
                dataSock.on("data", d => listOutput += d.toString());

                const dataPromise = new Promise((resolve, reject) => {
                    dataSock.on("end", () => resolve(listOutput));
                    dataSock.on("error", reject);
                });

                const listCmdResp = await client.command("LIST");
                if (listCmdResp.code >= 400 && listCmdResp.code !== 150 && listCmdResp.code !== 125) {
                    throw new Error(`LIST failed: ${listCmdResp.text}`);
                }

                const rawList = await dataPromise;
                client.close();

                return {
                    type: "directory",
                    path: targetPath,
                    raw: rawList
                };
            } catch (dirErr) {
                // If CWD failed, try RETR
            }
        }

        // Retrieve file (RETR)
        await client.command("TYPE I");
        const dataTarget = await client.pasv();
        const dataSock = net.createConnection({ port: dataTarget.port, host: dataTarget.host, family: 4 });

        const chunks = [];
        dataSock.on("data", d => chunks.push(d));

        const dataPromise = new Promise((resolve, reject) => {
            dataSock.on("end", () => resolve(Buffer.concat(chunks)));
            dataSock.on("error", reject);
        });

        const retrResp = await client.command(`RETR ${targetPath}`);
        if (retrResp.code >= 400 && retrResp.code !== 150 && retrResp.code !== 125) {
            throw new Error(`File retrieval failed: ${retrResp.text}`);
        }

        const fileBuffer = await dataPromise;
        client.close();

        return {
            type: "file",
            path: targetPath,
            buffer: fileBuffer
        };

    } catch (err) {
        client.close();
        throw err;
    }
}

/**
 * Handles FTP URL request from /page?url=ftp://...
 */
async function handleFtpRequest(targetUrl, req, res, gatewayHost) {
    try {
        const ftpInfo = parseFtpUrl(targetUrl);
        const result = await fetchFtp(ftpInfo);

        const canonicalPath = ftpInfo.path.startsWith("/") ? ftpInfo.path : ("/" + ftpInfo.path);
        const canonicalUrl = `ftp://${ftpInfo.host}${canonicalPath}`;

        if (result.type === "directory") {
            const rawLines = result.raw.split(/\r?\n/);
            const items = [];
            for (const l of rawLines) {
                const item = parseFtpListingLine(l);
                if (item) items.push(item);
            }

            items.sort((a, b) => {
                if (a.isDir && !b.isDir) return -1;
                if (!a.isDir && b.isDir) return 1;
                return a.name.localeCompare(b.name);
            });

            const lines = [];
            lines.push(`META:TITLE=FTP: ${ftpInfo.host}${canonicalPath}`);
            lines.push(`META:URL=${canonicalUrl}`);
            lines.push("META:HTTPS=0");

            lines.push("H1:FTP Directory");
            lines.push(`P:📁 Server: ftp://${ftpInfo.host}${canonicalPath}`);
            lines.push(`P:Total items: ${items.length}`);
            lines.push("HR:");

            if (canonicalPath !== "/" && canonicalPath !== "") {
                const segments = canonicalPath.replace(/\/+$/, "").split("/");
                segments.pop();
                const parentPath = segments.join("/") || "/";
                lines.push(`L:ftp://${ftpInfo.host}${parentPath}\t⬆️ [..] Parent Directory`);
            }

            if (items.length === 0) {
                lines.push("P:(Empty directory)");
            } else {
                for (const item of items) {
                    const itemSubPath = canonicalPath.endsWith("/") ? `${canonicalPath}${item.name}` : `${canonicalPath}/${item.name}`;
                    const itemUrl = `ftp://${ftpInfo.host}${itemSubPath}${item.isDir ? "/" : ""}`;
                    if (item.isDir) {
                        lines.push(`L:${itemUrl}\t📁 ${item.name}/`);
                    } else {
                        lines.push(`L:${itemUrl}\t📄 ${item.name} (${formatSize(item.size)})`);
                    }
                }
            }

            lines.push("HR:");
            lines.push(`L:${canonicalUrl}\t🔄 Reload Directory`);
            lines.push(`L:http://${gatewayHost}/sample_media\t⭐ Sample Media Showcase`);
            lines.push(`L:http://${gatewayHost}/\t🌐 Gateway Home`);

            res.writeHead(200, {
                "Content-Type": "text/plain; charset=utf-8",
                "X-Nokia-Protocol": "FTP"
            });
            return res.end(lines.join("\n"));

        } else if (result.type === "file") {
            const filename = canonicalPath.split("/").pop() || "file";
            const size = result.buffer.length;

            if (isTextFile(filename)) {
                const text = result.buffer.toString("utf-8");
                const rawLines = text.split(/\r?\n/);
                const lines = [];

                lines.push(`META:TITLE=FTP: ${filename}`);
                lines.push(`META:URL=${canonicalUrl}`);
                lines.push("META:HTTPS=0");

                lines.push(`H1:${filename}`);
                lines.push(`P:📁 Location: ftp://${ftpInfo.host}${canonicalPath}`);
                lines.push(`P:📏 Size: ${formatSize(size)} (${rawLines.length} lines)`);
                lines.push("HR:");

                for (let i = 0; i < rawLines.length; i++) {
                    const l = rawLines[i].trimEnd();
                    if (l.trim().length > 0) {
                        lines.push("P:" + l);
                    }
                }

                lines.push("HR:");
                const parentDir = canonicalPath.substring(0, canonicalPath.lastIndexOf("/")) || "/";
                lines.push(`L:ftp://${ftpInfo.host}${parentDir}\t⬆️ Back to Directory`);
                lines.push(`L:http://${gatewayHost}/ftp_download?url=${encodeURIComponent(canonicalUrl)}\t💾 Download File (${formatSize(size)})`);

                res.writeHead(200, {
                    "Content-Type": "text/plain; charset=utf-8",
                    "X-Nokia-Protocol": "FTP"
                });
                return res.end(lines.join("\n"));
            }

            if (isImageFile(filename)) {
                const lines = [];
                lines.push(`META:TITLE=FTP: ${filename}`);
                lines.push(`META:URL=${canonicalUrl}`);
                lines.push("META:HTTPS=0");
                lines.push(`H1:${filename}`);
                lines.push(`IMG:http://${gatewayHost}/image?url=${encodeURIComponent(canonicalUrl)}`);
                lines.push(`P:📏 Size: ${formatSize(size)}`);
                lines.push("HR:");
                const parentDir = canonicalPath.substring(0, canonicalPath.lastIndexOf("/")) || "/";
                lines.push(`L:ftp://${ftpInfo.host}${parentDir}\t⬆️ Back to Directory`);
                lines.push(`L:http://${gatewayHost}/ftp_download?url=${encodeURIComponent(canonicalUrl)}\t💾 Download Image`);

                res.writeHead(200, {
                    "Content-Type": "text/plain; charset=utf-8",
                    "X-Nokia-Protocol": "FTP"
                });
                return res.end(lines.join("\n"));
            }

            if (isMediaFile(filename)) {
                const lines = [];
                lines.push(`META:TITLE=FTP: ${filename}`);
                lines.push(`META:URL=${canonicalUrl}`);
                lines.push("META:HTTPS=0");
                lines.push(`H1:${filename}`);
                lines.push(`P:🎵 Media File: ${filename}`);
                lines.push(`P:📏 File Size: ${formatSize(size)}`);
                lines.push("HR:");
                lines.push(`L:http://${gatewayHost}/media?url=${encodeURIComponent(canonicalUrl)}\t▶️ Play in Media Player`);
                lines.push(`L:http://${gatewayHost}/ftp_download?url=${encodeURIComponent(canonicalUrl)}\t💾 Download Media File`);
                const parentDir = canonicalPath.substring(0, canonicalPath.lastIndexOf("/")) || "/";
                lines.push(`L:ftp://${ftpInfo.host}${parentDir}\t⬆️ Back to Directory`);

                res.writeHead(200, {
                    "Content-Type": "text/plain; charset=utf-8",
                    "X-Nokia-Protocol": "FTP"
                });
                return res.end(lines.join("\n"));
            }

            const lines = [];
            lines.push(`META:TITLE=FTP: ${filename}`);
            lines.push(`META:URL=${canonicalUrl}`);
            lines.push("META:HTTPS=0");
            lines.push(`H1:${filename}`);
            lines.push(`P:📦 Binary File: ${filename}`);
            lines.push(`P:📏 File Size: ${formatSize(size)}`);
            lines.push("HR:");
            lines.push(`L:http://${gatewayHost}/ftp_download?url=${encodeURIComponent(canonicalUrl)}\t💾 Download File (${formatSize(size)})`);
            const parentDir = canonicalPath.substring(0, canonicalPath.lastIndexOf("/")) || "/";
            lines.push(`L:ftp://${ftpInfo.host}${parentDir}\t⬆️ Back to Directory`);

            res.writeHead(200, {
                "Content-Type": "text/plain; charset=utf-8",
                "X-Nokia-Protocol": "FTP"
            });
            return res.end(lines.join("\n"));
        }

    } catch (err) {
        console.error("FTP Handler Error:", err.message);
        res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
        res.end(`META:TITLE=FTP Error\nMETA:HTTPS=0\nH1:FTP Connection Error\nP:Could not load ${targetUrl}\nP:Reason: ${err.message}\nHR:\nL:http://${gatewayHost}/\t🌐 Gateway Home`);
    }
}

/**
 * Handles direct binary file downloads from FTP via HTTP streaming
 */
async function handleFtpDownload(req, res, targetUrl) {
    try {
        const ftpInfo = parseFtpUrl(targetUrl);
        const result = await fetchFtp(ftpInfo);
        if (result.type !== "file") {
            res.writeHead(400, { "Content-Type": "text/plain" });
            return res.end("Target URL is not a downloadable file");
        }

        const filename = (ftpInfo.path.split("/").pop() || "download").replace(/[^a-zA-Z0-9_\.\-]/g, "_");
        res.writeHead(200, {
            "Content-Type": "application/octet-stream",
            "Content-Disposition": `attachment; filename="${filename}"`,
            "Content-Length": result.buffer.length
        });
        return res.end(result.buffer);
    } catch (err) {
        console.error("FTP Download Error:", err.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        return res.end("FTP Download Error: " + err.message);
    }
}

module.exports = {
    isFtpUrl,
    parseFtpUrl,
    fetchFtp,
    handleFtpRequest,
    handleFtpDownload,
    formatSize
};
