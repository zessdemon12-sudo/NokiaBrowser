/**
 * Nokia Browser Cellular Tunnel Helper
 * 
 * Uses localtunnel to establish an HTTP Port 80 public tunnel with fixed
 * subdomain 'nokia-robi', allowing vintage Nokia devices on 2G EDGE cellular
 * networks (including ROBI-WAP 2.0 proxy 10.16.18.77:9028 and Robi-INTERNET)
 * to connect on standard Port 80 without port restrictions or TLS negotiation issues.
 */

const { spawn } = require('child_process');

const LOCAL_PORT = process.env.PORT || '8080';
const SUBDOMAIN = process.env.TUNNEL_SUBDOMAIN || 'robi-nokia-wap';

function runTunnel() {
    console.log(`Starting cellular tunnel on port ${LOCAL_PORT} (subdomain: ${SUBDOMAIN})...`);
    const proc = spawn('npx', ['--yes', 'localtunnel', '--port', LOCAL_PORT, '--subdomain', SUBDOMAIN], {
        stdio: ['ignore', 'pipe', 'pipe']
    });

    let announced = false;
    function checkOutput(chunk) {
        const text = chunk.toString();
        const m = text.match(/https?:\/\/([^\s]+)/);
        if (m && !announced) {
            announced = true;
            const url = 'http://' + m[1].replace('https://', '');
            console.log('\n' + '='.repeat(64));
            console.log(' 🌐 Nokia Browser Cellular Gateway Online (Port 80 / WAP 2.0)');
            console.log('='.repeat(64));
            console.log(' Public Gateway URL:');
            console.log(`   ${url}`);
            console.log('\n 📱 Instructions for your Nokia Phone:');
            console.log('   Works on BOTH ROBI-WAP 2.0 and Robi-INTERNET!');
            console.log('   1. Open Nokia Browser on your phone');
            console.log('   2. Select: Options -> Settings');
            console.log('   3. Set "Gateway URL:" to:');
            console.log(`      ${url}`);
            console.log('   4. Select OK to save and browse freely!');
            console.log('='.repeat(64) + '\n');
        }
    }

    proc.stdout.on('data', checkOutput);
    proc.stderr.on('data', checkOutput);

    proc.on('close', (code) => {
        console.log(`[Tunnel] Exited with code ${code}, restarting in 2s...`);
        setTimeout(runTunnel, 2000);
    });
}

runTunnel();
