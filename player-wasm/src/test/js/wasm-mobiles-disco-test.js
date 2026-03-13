#!/usr/bin/env node
'use strict';

/**
 * Headless Chromium integration test for the Mobiles Disco DCR.
 *
 * Loads the Director 5.19 Mobiles Disco movie through the full
 *   shockwave-lib.js -> Web Worker -> WASM -> SoftwareRenderer -> canvas
 * pipeline, captures periodic PNG screenshots, and verifies that frames
 * render with non-trivial pixel content (splash/login screen reached).
 *
 * Usage (via Gradle):
 *   ./gradlew :player-wasm:runWasmMobilesDiscoTest
 *   ./gradlew :player-wasm:runWasmMobilesDiscoTest -PoutputDir=C:/tmp/mobiles-test
 *
 * Args: distPath dcrFile [outputDir]
 */

const puppeteer = require('puppeteer');
const http = require('http');
const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------
// Args
// ---------------------------------------------------------------------------
const distPath  = process.argv[2] || path.resolve(__dirname, '../../../build/dist');
const dcrFile   = process.argv[3] || 'C:/xampp/htdocs/mobiles/dcr_0519b_e/20000201_mobiles_disco.dcr';
const outputDir = process.argv[4] || path.resolve(process.cwd(), 'frames_mobiles_disco');

// Mobiles Disco has no external casts — the DCR directory serves as castDir
const castDir   = path.dirname(dcrFile);

const MAX_TICKS = 400;          // Max ticks before giving up
const CAPTURE_INTERVAL = 50;    // Capture a PNG every N ticks
const SPRITE_THRESHOLD = 3;     // Sprite count that indicates content is rendering

// ---------------------------------------------------------------------------
// Tiny HTTP server: serves dist/ files AND DCR files from castDir
// ---------------------------------------------------------------------------
const MIME = {
    '.html': 'text/html',
    '.js':   'application/javascript',
    '.wasm': 'application/wasm',
    '.css':  'text/css',
    '.png':  'image/png',
    '.dcr':  'application/x-director',
    '.cct':  'application/x-director',
    '.cst':  'application/x-director',
    '.txt':  'text/plain',
};

function createServer() {
    return new Promise((resolve) => {
        const server = http.createServer((req, res) => {
            const url = decodeURIComponent(req.url.split('?')[0]);

            // Try dist/ first
            let filePath = path.join(distPath, url);
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                return serveFile(res, filePath);
            }

            // Try castDir (for .dcr files)
            filePath = path.join(castDir, path.basename(url));
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                return serveFile(res, filePath);
            }

            // Try ancestor directories (for related assets)
            let ancestor = castDir;
            for (let i = 0; i < 3; i++) {
                ancestor = path.dirname(ancestor);
                const ancestorPath = path.join(ancestor, url);
                if (fs.existsSync(ancestorPath) && fs.statSync(ancestorPath).isFile()) {
                    return serveFile(res, ancestorPath);
                }
            }

            res.writeHead(404);
            res.end('Not found: ' + url);
        });

        server.listen(0, '127.0.0.1', () => {
            const port = server.address().port;
            resolve({ server, port });
        });
    });
}

function serveFile(res, filePath) {
    const ext = path.extname(filePath).toLowerCase();
    const mime = MIME[ext] || 'application/octet-stream';
    const data = fs.readFileSync(filePath);
    res.writeHead(200, {
        'Content-Type': mime,
        'Content-Length': data.length,
        'Access-Control-Allow-Origin': '*',
    });
    res.end(data);
}

// ---------------------------------------------------------------------------
// Canvas capture: extract pixel-perfect PNG directly from the canvas element
// ---------------------------------------------------------------------------
async function captureCanvas(page, filePath) {
    const dataUrl = await page.evaluate(() => {
        const canvas = document.getElementById('stage');
        return canvas ? canvas.toDataURL('image/png') : null;
    });
    if (!dataUrl) {
        console.error('  Warning: canvas not found for capture');
        return;
    }
    const base64 = dataUrl.replace(/^data:image\/png;base64,/, '');
    fs.writeFileSync(filePath, Buffer.from(base64, 'base64'));
}

/**
 * Sample a region of the canvas and return pixel statistics.
 * Used to detect whether content has actually rendered (non-blank canvas).
 */
async function getCanvasStats(page) {
    return page.evaluate(() => {
        const canvas = document.getElementById('stage');
        if (!canvas) return null;
        const ctx = canvas.getContext('2d');
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const data = imageData.data;
        const pixelCount = canvas.width * canvas.height;
        let nonBlack = 0;
        let nonWhite = 0;
        const colorBuckets = new Set();

        for (let i = 0; i < data.length; i += 16) { // Sample every 4th pixel
            const r = data[i], g = data[i + 1], b = data[i + 2];
            if (r > 10 || g > 10 || b > 10) nonBlack++;
            if (r < 245 || g < 245 || b < 245) nonWhite++;
            // Quantize to 32-level buckets for color variety
            const bucket = (Math.floor(r / 8) << 10) | (Math.floor(g / 8) << 5) | Math.floor(b / 8);
            colorBuckets.add(bucket);
        }
        const sampled = Math.ceil(data.length / 16);
        return {
            width: canvas.width,
            height: canvas.height,
            nonBlackPct: (100 * nonBlack / sampled).toFixed(1),
            nonWhitePct: (100 * nonWhite / sampled).toFixed(1),
            colorVariety: colorBuckets.size
        };
    });
}

// ---------------------------------------------------------------------------
// Main test
// ---------------------------------------------------------------------------
async function main() {
    console.log('=== WASM Mobiles Disco Chromium Test ===');
    console.log('Dist:    ', distPath);
    console.log('DCR:     ', dcrFile);
    console.log('CastDir: ', castDir);
    console.log('Output:  ', outputDir);

    // Verify dist exists
    if (!fs.existsSync(path.join(distPath, 'libreshockwave.js'))) {
        console.error('FAIL: libreshockwave.js not found in dist. Run assembleWasm first.');
        process.exit(1);
    }

    // Verify DCR exists
    if (!fs.existsSync(dcrFile)) {
        console.error('FAIL: DCR file not found: ' + dcrFile);
        process.exit(1);
    }

    // Create output directory
    fs.mkdirSync(outputDir, { recursive: true });

    // Start server
    const { server, port } = await createServer();
    const baseUrl = `http://127.0.0.1:${port}`;
    console.log('Server:  ', baseUrl);

    const dcrFileName = path.basename(dcrFile);

    let browser;
    try {
        // Launch headless Chromium
        browser = await puppeteer.launch({
            headless: true,
            args: ['--no-sandbox', '--disable-setuid-sandbox'],
        });

        const page = await browser.newPage();

        // Collect console output
        const logs = [];
        const errors = [];
        page.on('console', msg => {
            const text = msg.text();
            logs.push(text);
            if (text.includes('[W]') || text.includes('[LS]') || text.includes('[TEST]')) {
                console.log('  ' + text);
            }
        });
        page.on('pageerror', err => {
            errors.push(err.message);
            console.error('  PAGE ERROR: ' + err.message);
        });

        // Build a minimal HTML page that uses the bundled libreshockwave.js
        // Mobiles Disco is a self-contained DCR (no external casts, no sw params)
        const html = `<!DOCTYPE html>
<html><body>
<canvas id="stage" width="640" height="480"></canvas>
<script src="${baseUrl}/libreshockwave.js"></script>
<script>
    var _testState = { tick: 0, maxSprites: 0, frame: 0, done: false, error: null, debugLogs: [] };

    var player = LibreShockwave.create('stage', {
        basePath: '${baseUrl}/',
        autoplay: true,
        onLoad: function(info) {
            console.log('[TEST] Movie loaded: ' + info.width + 'x' + info.height +
                        ' frames=' + info.frameCount + ' tempo=' + info.tempo);
        },
        onFrame: function(frame, total) {
            _testState.tick++;
            _testState.frame = frame;
        },
        onError: function(msg) {
            _testState.error = msg;
            console.error('[TEST] Error: ' + msg);
        },
        onDebugLog: function(log) {
            _testState.debugLogs.push(log);
            // Print all non-empty lines for debugging
            var lines = log.split('\\n');
            for (var i = 0; i < lines.length; i++) {
                var l = lines[i].trim();
                if (l) console.log('[WASM] ' + l);
            }
        }
    });

    player.load('${baseUrl}/${dcrFileName}');
    // Enable debug AFTER load so WASM engine exists
    setTimeout(function() { player.setDebugPlayback(true); }, 1000);
</script>
</body></html>`;

        // Write test HTML and navigate
        const htmlPath = path.join(distPath, '_test_mobiles.html');
        fs.writeFileSync(htmlPath, html);

        await page.goto(`${baseUrl}/_test_mobiles.html`, { waitUntil: 'domcontentloaded' });
        console.log('Page loaded, waiting for playback...');

        // Poll tick count and capture frames
        let maxSprites = 0;
        let lastFrame = 0;
        let captures = 0;
        let contentRendered = false;
        const startTime = Date.now();

        for (let i = 0; i < MAX_TICKS; i++) {
            await new Promise(r => setTimeout(r, 100));

            // Read test state from page
            const state = await page.evaluate(() => {
                var s = window._testState || {};
                var sc = 0;
                try { sc = window.player ? (window.player._lastSpriteCount || 0) : 0; } catch(e) {}
                return {
                    tick: s.tick || 0,
                    frame: s.frame || 0,
                    error: s.error,
                    done: s.done,
                    spriteCount: sc,
                };
            });

            if (state.error) {
                console.error('Error from player: ' + state.error);
            }

            lastFrame = state.frame;

            // Update max sprite count
            if (state.spriteCount > maxSprites) {
                maxSprites = state.spriteCount;
                console.log(`  [tick ~${state.tick}] sprites=${maxSprites} frame=${lastFrame}`);
            }

            // Log debug info periodically
            if (i === 30 || i === 100 || i === 200) {
                const stats = await getCanvasStats(page);
                console.log(`  [debug tick=${state.tick}] spriteCount=${state.spriteCount} frame=${lastFrame} ` +
                    `maxSprites=${maxSprites}` +
                    (stats ? ` canvas: ${stats.nonBlackPct}% nonBlack, colors=${stats.colorVariety}` : ''));
            }

            // Capture PNG periodically
            if (i > 0 && i % CAPTURE_INTERVAL === 0) {
                const pngPath = path.join(outputDir, `frame_${String(i).padStart(4, '0')}.png`);
                await captureCanvas(page, pngPath);
                captures++;
                console.log(`  Captured ${pngPath} (tick ~${state.tick}, frame ${lastFrame})`);
            }

            // Check if content has rendered (sprites visible + canvas has color variety)
            if (maxSprites >= SPRITE_THRESHOLD && !contentRendered) {
                const stats = await getCanvasStats(page);
                if (stats && stats.colorVariety >= 10) {
                    contentRendered = true;
                    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
                    console.log(`  Content rendered! (${maxSprites} sprites, ${stats.colorVariety} colors in ${elapsed}s)`);

                    // Capture the content frame
                    const pngPath = path.join(outputDir, 'content_rendered.png');
                    await captureCanvas(page, pngPath);
                    captures++;
                    console.log(`  Captured ${pngPath}`);
                }
            }

            // If content reached, run a few more polls for additional captures then exit
            if (contentRendered && i > 80) {
                for (let j = 0; j < 3; j++) {
                    await new Promise(r => setTimeout(r, 2000));
                    const sprState = await page.evaluate(() => {
                        let sc = 0;
                        try { sc = window.player ? (window.player._lastSpriteCount || 0) : 0; } catch(e) {}
                        return sc;
                    });
                    if (sprState > maxSprites) {
                        maxSprites = sprState;
                        console.log(`  [final capture] sprites=${maxSprites}`);
                    }
                    const pngPath = path.join(outputDir, `frame_final_${j}.png`);
                    await captureCanvas(page, pngPath);
                    captures++;
                    console.log(`  Captured ${pngPath}`);
                }
                break;
            }
        }

        // Final capture + stats
        const finalPng = path.join(outputDir, 'frame_final.png');
        await captureCanvas(page, finalPng);
        captures++;

        const finalStats = await getCanvasStats(page);

        // Clean up temp HTML
        try { fs.unlinkSync(htmlPath); } catch(e) {}

        // Results
        console.log('\n--- Results ---');
        console.log('Max sprites:    ', maxSprites);
        console.log('Last frame:     ', lastFrame);
        console.log('Captures:       ', captures);
        console.log('Page errors:    ', errors.length);
        if (finalStats) {
            console.log('Canvas:          ' + finalStats.width + 'x' + finalStats.height);
            console.log('  Non-black:     ' + finalStats.nonBlackPct + '%');
            console.log('  Color variety: ' + finalStats.colorVariety);
        }
        console.log('Output dir:     ', outputDir);
        console.log('Elapsed:        ', ((Date.now() - startTime) / 1000).toFixed(1) + 's');

        if (errors.length > 0) {
            console.log('\nPage errors:');
            errors.forEach(e => console.log('  ' + e));
        }

        // Dump all accumulated WASM debug logs
        const allDebugLogs = await page.evaluate(() => window._testState.debugLogs || []);
        if (allDebugLogs.length > 0) {
            console.log('\n--- WASM Debug Logs ---');
            for (const log of allDebugLogs) {
                // Print first 200 lines max
                const lines = log.split('\n').filter(l => l.trim());
                for (const line of lines) {
                    console.log('  ' + line);
                }
            }
        }

        // Pass criteria: sprites rendered and canvas has meaningful content
        if (maxSprites >= SPRITE_THRESHOLD && lastFrame > 0 &&
            finalStats && finalStats.colorVariety >= 5) {
            console.log('\nPASS (maxSprites=' + maxSprites + ', frame=' + lastFrame +
                        ', colors=' + finalStats.colorVariety + ')');
        } else {
            console.log('\nFAIL: maxSprites=' + maxSprites + ', frame=' + lastFrame +
                        ', colors=' + (finalStats ? finalStats.colorVariety : 0));
            process.exitCode = 1;
        }

    } finally {
        if (browser) await browser.close();
        server.close();
    }
}

main().catch(err => {
    console.error('FATAL:', err);
    process.exit(1);
});
