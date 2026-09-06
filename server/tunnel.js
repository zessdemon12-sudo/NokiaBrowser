/**
 * Nokia Browser Cellular Tunnel Helper
 * 
 * Uses localtunnel to establish an HTTP Port 80 public tunnel with fixed
 * subdomain 'nokia-robi', allowing vintage Nokia devices on 2G EDGE cellular
 * networks (including ROBI-WAP 2.0 proxy 10.16.18.77:9028 and Robi-INTERNET)
 * to connect on standard Port 80 without port restrictions or TLS negotiation issues.
 */

const localtunnel = require('localtunnel');

const LOCAL_PORT = parseInt(process.env.PORT || '8080', 10);
const SUBDOMAIN = process.env.TUNNEL_SUBDOMAIN || 'robi-nokia-wap';

async function startTunnel() {
    try {
        console.log(`Starting cellular tunnel on port ${LOCAL_PORT} (subdomain: ${SUBDOMAIN})...`);
        const tunnel = await localtunnel({ port: LOCAL_PORT, subdomain: SUBDOMAIN });

        const url = tunnel.url.replace('https://', 'http://');
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

        tunnel.on('close', () => {
            console.log('[Tunnel] Tunnel closed, reconnecting in 3s...');
            setTimeout(startTunnel, 3000);
        });

        tunnel.on('error', (err) => {
            console.error('[Tunnel Error]:', err.message);
        });
    } catch (e) {
        console.error('[Tunnel Init Error]:', e.message);
        setTimeout(startTunnel, 5000);
    }
}

startTunnel();
