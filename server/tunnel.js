/**
 * Nokia Browser Cellular Tunnel Helper
 * 
 * Uses tools/bore to establish a zero-config, raw TCP plain HTTP tunnel
 * on bore.pub, allowing vintage Nokia devices on 2G EDGE cellular networks
 * to reach the local gateway server without SSL/TLS certificates or NAT issues.
 */

const { spawn, execSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const BORE_PATH = path.join(__dirname, '..', 'tools', 'bore');
const LOCAL_PORT = process.env.PORT || '8080';

if (!fs.existsSync(BORE_PATH)) {
    console.log('Downloading tools/bore client from github releases...');
    try {
        execSync(`mkdir -p "${path.dirname(BORE_PATH)}" && curl -sL https://github.com/ekzhang/bore/releases/download/v0.5.1/bore-v0.5.1-x86_64-unknown-linux-musl.tar.gz | tar -xz -C "${path.dirname(BORE_PATH)}" && chmod +x "${BORE_PATH}"`);
    } catch (e) {
        console.error('[Tunnel Error]: Failed to download tools/bore:', e.message);
        process.exit(1);
    }
}

console.log('Starting cellular tunnel to port ' + LOCAL_PORT + ' via bore.pub...');

const proc = spawn(BORE_PATH, ['local', LOCAL_PORT, '--to', 'bore.pub'], {
    stdio: ['ignore', 'pipe', 'pipe']
});

let assignedPort = null;

function handleOutput(data) {
    const text = data.toString();
    const m = text.match(/remote_port=(\d+)/) || text.match(/bore\.pub:(\d+)/);
    if (m && !assignedPort) {
        assignedPort = m[1];
        const publicUrl = `http://bore.pub:${assignedPort}`;
        console.log('\n' + '='.repeat(64));
        console.log(' 🌐 Nokia Browser Cellular Gateway Online');
        console.log('='.repeat(64));
        console.log(' Public Gateway URL:');
        console.log(`   ${publicUrl}`);
        console.log('\n 📱 Instructions for your Nokia Phone:');
        console.log('   1. Open Nokia Browser on your phone');
        console.log('   2. Select: Options -> Settings');
        console.log('   3. Change "Gateway URL:" to:');
        console.log(`      ${publicUrl}`);
        console.log('   4. Select OK to save and browse freely over cellular (EDGE)!');
        console.log('='.repeat(64) + '\n');
    }
}

proc.stdout.on('data', handleOutput);
proc.stderr.on('data', handleOutput);

proc.on('close', (code) => {
    console.log(`[Tunnel] Process exited with code ${code}`);
});

process.on('SIGINT', () => {
    proc.kill('SIGINT');
    process.exit(0);
});
process.on('SIGTERM', () => {
    proc.kill('SIGTERM');
    process.exit(0);
});
