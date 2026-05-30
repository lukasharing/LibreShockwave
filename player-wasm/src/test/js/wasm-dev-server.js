#!/usr/bin/env node
'use strict';

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const net = require('net');
const path = require('path');

const root = path.resolve(process.argv[2] || path.resolve(__dirname, '../../../build/dist'));
const port = Number(process.argv[3] || 4173);

const MIME = {
    '.html': 'text/html',
    '.js': 'application/javascript',
    '.wasm': 'application/wasm',
    '.css': 'text/css',
    '.png': 'image/png',
    '.xml': 'application/xml',
    '.txt': 'text/plain'
};

const ISOLATION_HEADERS = {
    'Cross-Origin-Opener-Policy': 'same-origin',
    'Cross-Origin-Embedder-Policy': 'require-corp',
    'Cross-Origin-Resource-Policy': 'same-origin',
    'Access-Control-Allow-Origin': '*'
};

const repoRoot = path.resolve(__dirname, '../../../..');
const homeDir = process.env.HOME || process.env.USERPROFILE || '';

const ORIGINS_DEFAULT_HOTEL = String(process.env.LSW_ORIGINS_HOTEL || 'us').trim().toLowerCase();
const ORIGINS_GAMEDATA_MODE = String(process.env.LSW_ORIGINS_GAMEDATA_MODE || 'remote')
    .trim()
    .toLowerCase();
const ORIGINS_REMOTE_GAMEDATA_ENABLED = new Set(['remote', 'proxy', 'real'])
    .has(ORIGINS_GAMEDATA_MODE);
const ORIGINS_REMOTE_GAMEDATA_TIMEOUT_MS = Math.max(
    1000,
    Number(process.env.LSW_ORIGINS_GAMEDATA_TIMEOUT_MS || 10000) || 10000
);
const ORIGINS_HOTELS = new Map([
    ['us', {
        aliases: ['com', 'en', 'ous'],
        gameHost: 'game-ous.habbo.com',
        siteUrl: 'http://origins.habbo.com',
        gamedataHost: 'origins-gamedata.habbo.com'
    }],
    ['es', {
        aliases: ['oes'],
        gameHost: 'game-oes.habbo.com',
        siteUrl: 'http://origins.habbo.es',
        gamedataHost: 'origins-gamedata.habbo.es'
    }],
    ['br', {
        aliases: ['obr'],
        gameHost: 'game-obr.habbo.com',
        siteUrl: 'http://origins.habbo.com.br',
        gamedataHost: 'origins-gamedata.habbo.com.br'
    }],
    ['de', {
        aliases: ['od'],
        gameHost: 'game-od.habbo.com',
        siteUrl: 'http://origins.varoke.net',
        gamedataHost: 'origins-gamedata.varoke.net'
    }]
]);
const ORIGINS_HOTEL_ALIASES = new Map();
for (const [hotel, config] of ORIGINS_HOTELS.entries()) {
    ORIGINS_HOTEL_ALIASES.set(hotel, hotel);
    for (const alias of config.aliases || []) {
        ORIGINS_HOTEL_ALIASES.set(alias, hotel);
    }
}

const ORIGINS_LOCAL = {
    assetRoot: '/origins300/',
    remoteAssetRoot: '/origins-remote-assets/',
    gamedataRoots: ['/origins-gamedata/', '/origins300-gamedata/'],
    localProjectorParamsPath: '/origins300-gamedata/local_projector_params.js',
    connections: {
        info: { id: '#info' },
        mus: { id: '#mus' },
        room: { id: '#info' }
    }
};
const ORIGINS_GAMEDATA_ROUTES = new Map([
    ['external_variables/1', {
        localNames: ['external_variables.txt'],
        transform: withOriginsCompatibilityVariables
    }],
    ['external_texts/1', {
        localNames: ['external_texts.txt'],
        transform: withOriginsCompatibilityTexts
    }],
    ['furniture-data', {
        localNames: ['furniture-data', 'furnidata.txt'],
        generate: generateOriginsFurnitureDataSource,
        logPrefix: 'origins-sim'
    }],
    ['furnidata.txt', {
        localNames: ['furnidata.txt', 'furniture-data'],
        generate: generateOriginsFurnitureDataSource,
        logPrefix: 'origins-sim'
    }],
    ['product-data', {
        localNames: ['product-data', 'productdata.txt'],
        generate: generateOriginsProductDataSource,
        logPrefix: 'origins-sim'
    }],
    ['productdata.txt', {
        localNames: ['productdata.txt', 'product-data'],
        generate: generateOriginsProductDataSource,
        logPrefix: 'origins-sim'
    }],
    ['figuredata_xml/1', {
        localNames: ['figuredata.xml']
    }]
]);
const ORIGINS_AVAILABLE_BBCODES = '[' + [
    '"b":[#style:#fontStyle,#default:[#bold]]',
    '"i":[#style:#fontStyle,#default:[#italic]]',
    '"u":[#style:#fontStyle,#default:[#underline]]',
    '"color":[#style:#color]',
    '"c":[#style:#color]',
    '"size":[#style:#fontSize]',
    '"br":[#replace:"\\r"]',
    '"url":[#style:#url]'
].join(',') + ']';

const ORIGINS_FORCED_VARIABLES = new Map([
    ['external.variables.txt', `${ORIGINS_LOCAL.gamedataRoots[0]}external_variables/1`],
    ['external.texts.txt', `${ORIGINS_LOCAL.gamedataRoots[0]}external_texts/1`],
    ['external.figurepartlist.txt', `${ORIGINS_LOCAL.gamedataRoots[0]}figuredata_xml/1`],
    ['furnidata.load.url', `${ORIGINS_LOCAL.gamedataRoots[0]}furniture-data`],
    ['productdata.load.url', `${ORIGINS_LOCAL.gamedataRoots[0]}product-data`],
    ['available.bbcodes', ORIGINS_AVAILABLE_BBCODES],
    ['dynamic.download.url', `${ORIGINS_LOCAL.remoteAssetRoot}http/images.habbo.com/dcr/hof_furni/%revision%/`],
    ['connection.info.id', ORIGINS_LOCAL.connections.info.id],
    ['connection.mus.id', ORIGINS_LOCAL.connections.mus.id],
    ['connection.room.id', ORIGINS_LOCAL.connections.room.id],
    ['tutorial_active', '0'],
    ['steam.enabled', '0'],
    ['steam.mac', '0'],
    ['steam_link_active', '0'],
    ['cart_show_on_add', '1']
]);
const ORIGINS_REMOTE_ASSET_HOSTS = new Set([
    'images.habbo.com',
    'www.habbo.com',
    'origins-gamedata.habbo.com',
    'origins-gamedata.habbo.es',
    'origins-gamedata.habbo.com.br',
    'origins-gamedata.varoke.net'
]);
const ORIGINS_GAME_PORTS = new Set([40001, 40002]);
const ALLOW_REMOTE_TCP_WS = process.env.LSW_ALLOW_REMOTE_TCP_WS !== '0';

function parseTcpBridgeTarget(requestUrl) {
    const url = new URL(requestUrl, `http://127.0.0.1:${port}`);
    if (url.pathname !== '/tcp-ws' && url.pathname !== '/mus-ws') {
        throw new Error('unsupported websocket path');
    }
    const host = String(url.searchParams.get('host') || '').trim();
    const targetPort = Number(url.searchParams.get('port') || 0);
    const tracePackets = url.searchParams.get('traceMusPackets') === '1';
    if (!host || !Number.isInteger(targetPort) || targetPort <= 0 || targetPort > 65535) {
        throw new Error('missing target host/port');
    }
    return { host, port: targetPort, tracePackets };
}

function acceptKey(key) {
    return crypto
        .createHash('sha1')
        .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
        .digest('base64');
}

function encodeFrame(opcode, payload) {
    const data = Buffer.from(payload || []);
    let header;
    if (data.length < 126) {
        header = Buffer.from([0x80 | opcode, data.length]);
    } else if (data.length <= 0xffff) {
        header = Buffer.alloc(4);
        header[0] = 0x80 | opcode;
        header[1] = 126;
        header.writeUInt16BE(data.length, 2);
    } else {
        header = Buffer.alloc(10);
        header[0] = 0x80 | opcode;
        header[1] = 127;
        header.writeBigUInt64BE(BigInt(data.length), 2);
    }
    return Buffer.concat([header, data]);
}

function closeFrame(code, reason) {
    const reasonBytes = Buffer.from(String(reason || '').slice(0, 120), 'utf8');
    const payload = Buffer.alloc(2 + reasonBytes.length);
    payload.writeUInt16BE(code, 0);
    reasonBytes.copy(payload, 2);
    return encodeFrame(8, payload);
}

function previewBytes(buffer) {
    const bytes = Buffer.from(buffer || []);
    const limit = Math.min(bytes.length, 64);
    const hex = [];
    let ascii = '';
    for (let i = 0; i < limit; i++) {
        const b = bytes[i];
        hex.push(b.toString(16).padStart(2, '0'));
        ascii += b >= 32 && b < 127 ? String.fromCharCode(b) : '.';
    }
    if (bytes.length > limit) {
        hex.push('...');
        ascii += '...';
    }
    return `bytes=${bytes.length} hex=${hex.join(' ')} ascii=${JSON.stringify(ascii)}`;
}

function isLoopbackHost(host) {
    const value = String(host || '').toLowerCase();
    return value === 'localhost'
        || value === '127.0.0.1'
        || value === '::1'
        || value.endsWith('.localhost');
}

function isHabboOriginsRealProxyTarget(target) {
    return target
        && ORIGINS_GAME_PORTS.has(target.port)
        && isLoopbackHost(target.host);
}

function originsRealBridgeTarget(target) {
    const hotel = originsCurrentHotel();
    const config = ORIGINS_HOTELS.get(hotel) || ORIGINS_HOTELS.get('us');
    return {
        ...target,
        proxyHost: target.host,
        proxyPort: target.port,
        host: config.gameHost,
        port: target.port === 40002 ? 40002 : 40001,
        originsHotel: hotel
    };
}

function originsHotelKey(value) {
    return ORIGINS_HOTEL_ALIASES.get(String(value || '').toLowerCase()) || 'us';
}

function originsCurrentHotel() {
    return originsHotelKey(ORIGINS_DEFAULT_HOTEL);
}

function originsCurrentHotelConfig() {
    return ORIGINS_HOTELS.get(originsCurrentHotel()) || ORIGINS_HOTELS.get('us');
}

function originsRealConnectionParams() {
    const hotel = originsCurrentHotel();
    const config = originsCurrentHotelConfig();
    return {
        'origins.connectionMode': 'real',
        'origins.hotel': hotel,
        'origins.real.info.host': config.gameHost,
        'origins.real.info.port': '40001',
        'origins.real.mus.host': config.gameHost,
        'origins.real.mus.port': '40002',
        'connection.info.host': config.gameHost,
        'connection.info.port': '40001',
        'connection.mus.host': config.gameHost,
        'connection.mus.port': '40002',
        'site.url': config.siteUrl,
        'url.prefix': config.siteUrl.endsWith('/') ? config.siteUrl : `${config.siteUrl}/`,
        'external.variables.txt': `${ORIGINS_LOCAL.gamedataRoots[0]}external_variables/1`,
        'external.texts.txt': `${ORIGINS_LOCAL.gamedataRoots[0]}external_texts/1`
    };
}

function decodeFrames(buffer) {
    const frames = [];
    let offset = 0;
    while (offset + 2 <= buffer.length) {
        const first = buffer[offset];
        const second = buffer[offset + 1];
        const opcode = first & 0x0f;
        const masked = (second & 0x80) !== 0;
        let length = second & 0x7f;
        let headerLength = 2;
        if (length === 126) {
            if (offset + 4 > buffer.length) break;
            length = buffer.readUInt16BE(offset + 2);
            headerLength = 4;
        } else if (length === 127) {
            if (offset + 10 > buffer.length) break;
            const bigLength = buffer.readBigUInt64BE(offset + 2);
            if (bigLength > BigInt(Number.MAX_SAFE_INTEGER)) {
                throw new Error('oversized websocket frame');
            }
            length = Number(bigLength);
            headerLength = 10;
        }
        const maskLength = masked ? 4 : 0;
        const frameEnd = offset + headerLength + maskLength + length;
        if (frameEnd > buffer.length) break;

        const payload = Buffer.from(buffer.subarray(offset + headerLength + maskLength, frameEnd));
        if (masked) {
            const mask = buffer.subarray(offset + headerLength, offset + headerLength + 4);
            for (let i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        frames.push({ opcode, payload });
        offset = frameEnd;
    }
    return { frames, rest: buffer.subarray(offset) };
}

function closeSocket(socket) {
    if (socket && !socket.destroyed) {
        socket.destroy();
    }
}

function handleTcpBridgeUpgrade(req, socket, head) {
    let target;
    try {
        target = parseTcpBridgeTarget(req.url);
    } catch (err) {
        socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
        socket.destroy();
        return;
    }

    const key = req.headers['sec-websocket-key'];
    if (!key) {
        socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
        socket.destroy();
        return;
    }

    if (isHabboOriginsRealProxyTarget(target)) {
        target = originsRealBridgeTarget(target);
    }
    if (!ALLOW_REMOTE_TCP_WS && !isLoopbackHost(target.host)) {
        socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
        socket.destroy();
        console.warn(`[tcp-ws] blocked remote target ${target.host}:${target.port}; set LSW_ALLOW_REMOTE_TCP_WS=1 to allow forwarding`);
        return;
    }

    let pending = Buffer.from(head || []);
    let terminalSent = false;
    const targetSocket = net.createConnection({ host: target.host, port: target.port });

    function closeBoth() {
        closeSocket(socket);
        closeSocket(targetSocket);
    }

    function sendCloseToClient(code, reason) {
        if (terminalSent || socket.destroyed) {
            return;
        }
        terminalSent = true;
        socket.end(closeFrame(code, reason));
    }

    targetSocket.on('connect', () => {
        console.log(`[tcp-ws] ${socket.remoteAddress}:${socket.remotePort} -> ${target.host}:${target.port}`
            + (target.proxyHost ? ` via ${target.proxyHost}:${target.proxyPort}` : '')
            + (target.tracePackets ? ' trace=1' : ''));
    });
    targetSocket.on('data', data => {
        if (target.tracePackets) {
            console.log(`[tcp-ws] <- target ${target.host}:${target.port} ${previewBytes(data)}`);
        }
        if (!socket.destroyed) {
            socket.write(encodeFrame(2, data));
        }
    });
    targetSocket.on('error', err => {
        const reason = `target ${target.host}:${target.port} error: ${err.message}`;
        console.error(`[tcp-ws] ${reason}`);
        sendCloseToClient(1011, reason);
        closeSocket(targetSocket);
    });
    targetSocket.on('close', hadError => {
        const reason = `target ${target.host}:${target.port} close hadError=${hadError}`;
        console.log(`[tcp-ws] ${reason}`);
        sendCloseToClient(hadError ? 1011 : 1000, reason);
    });

    socket.write([
        'HTTP/1.1 101 Switching Protocols',
        'Upgrade: websocket',
        'Connection: Upgrade',
        `Sec-WebSocket-Accept: ${acceptKey(key)}`,
        '',
        ''
    ].join('\r\n'));

    function processData(chunk) {
        try {
            pending = Buffer.concat([pending, chunk]);
            const decoded = decodeFrames(pending);
            pending = decoded.rest;
            for (const frame of decoded.frames) {
                if (frame.opcode === 8) {
                    closeBoth();
                    return;
                }
                if (frame.opcode === 9) {
                    socket.write(encodeFrame(10, frame.payload));
                    continue;
                }
                if ((frame.opcode === 0 || frame.opcode === 1 || frame.opcode === 2) && !targetSocket.destroyed) {
                    if (target.tracePackets) {
                        console.log(`[tcp-ws] -> target ${target.host}:${target.port} ${previewBytes(frame.payload)}`);
                    }
                    targetSocket.write(frame.payload);
                }
            }
        } catch (err) {
            console.error(`[tcp-ws] client frame error: ${err.message}`);
            closeBoth();
        }
    }

    if (pending.length > 0) {
        processData(Buffer.alloc(0));
    }
    socket.on('data', processData);
    socket.on('error', closeBoth);
    socket.on('close', () => closeSocket(targetSocket));
}

function serveFile(res, filePath) {
    const data = fs.readFileSync(filePath);
    const ext = path.extname(filePath).toLowerCase();
    const devCacheHeaders = ['.html', '.js', '.wasm'].includes(ext)
        ? {
            'Cache-Control': 'no-store, max-age=0',
            'Pragma': 'no-cache',
            'Expires': '0'
        }
        : {};
    res.writeHead(200, {
        ...ISOLATION_HEADERS,
        ...devCacheHeaders,
        'Content-Type': MIME[ext] || 'application/octet-stream',
        'Content-Length': data.length
    });
    res.end(data);
}

function serveText(res, text) {
    const data = Buffer.from(text || '', 'utf8');
    res.writeHead(200, {
        ...ISOLATION_HEADERS,
        'Cache-Control': 'no-store, max-age=0',
        'Pragma': 'no-cache',
        'Expires': '0',
        'Content-Type': 'text/plain; charset=utf-8',
        'Content-Length': data.length
    });
    res.end(data);
}

function serveBinary(res, data, contentType) {
    const bytes = Buffer.from(data || []);
    res.writeHead(200, {
        ...ISOLATION_HEADERS,
        'Cache-Control': 'no-store, max-age=0',
        'Pragma': 'no-cache',
        'Expires': '0',
        'Content-Type': contentType || 'application/octet-stream',
        'Content-Length': bytes.length
    });
    res.end(bytes);
}

function serveJavaScript(res, text) {
    const data = Buffer.from(text || '', 'utf8');
    res.writeHead(200, {
        ...ISOLATION_HEADERS,
        'Cache-Control': 'no-store, max-age=0',
        'Pragma': 'no-cache',
        'Expires': '0',
        'Content-Type': 'application/javascript; charset=utf-8',
        'Content-Length': data.length
    });
    res.end(data);
}

function serveError(res, status, message) {
    const text = String(message || 'error');
    const data = Buffer.from(text, 'utf8');
    res.writeHead(status, {
        ...ISOLATION_HEADERS,
        'Cache-Control': 'no-store, max-age=0',
        'Pragma': 'no-cache',
        'Expires': '0',
        'Content-Type': 'text/plain; charset=utf-8',
        'Content-Length': data.length
    });
    res.end(data);
}

function serveOriginsLocalProjectorParams(res) {
    const params = originsRealConnectionParams();
    const json = JSON.stringify(params);
    serveJavaScript(res,
        `window.__lsLocalProjectorParams = ${json};\n`
        + 'window.__lsOriginsLocalProjectorParams = window.__lsLocalProjectorParams;\n');
}

function serveOriginsAsset(res, urlPath) {
    const localText = originsLocalAssetText(urlPath);
    if (localText !== null) {
        console.log(`[origins-local] serve ${urlPath} (${Buffer.byteLength(localText, 'utf8')} bytes)`);
        serveText(res, localText);
        return true;
    }
    const filePath = resolveOriginsAssetPath(urlPath);
    if (!filePath) {
        return false;
    }
    serveFile(res, filePath);
    return true;
}

async function serveOriginsRemoteAsset(res, requestUrl) {
    const target = parseOriginsRemoteAssetTarget(requestUrl);
    if (!target) {
        serveError(res, 404, 'remote asset not found');
        return;
    }

    const localFallback = resolveOriginsLocalAssetByFileName(path.basename(target.pathname));
    if (localFallback) {
        console.log(`[origins-asset-proxy] serve ${target.url} from ${localFallback}`);
        serveFile(res, localFallback);
        return;
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), ORIGINS_REMOTE_GAMEDATA_TIMEOUT_MS);
    try {
        const response = await fetch(target.url, { signal: controller.signal });
        if (!response.ok) {
            console.warn(`[origins-asset-proxy] remote ${target.url} -> ${response.status}`);
            serveError(res, response.status, `remote asset not available: ${target.url}`);
            return;
        }
        const contentType = response.headers.get('content-type') || remoteAssetContentType(target.pathname);
        const bytes = Buffer.from(await response.arrayBuffer());
        console.log(`[origins-asset-proxy] serve ${target.url} (${bytes.length} bytes)`);
        serveBinary(res, bytes, contentType);
    } catch (err) {
        console.warn(`[origins-asset-proxy] remote ${target.url} failed: ${err.message}`);
        serveError(res, 502, `remote asset fetch failed: ${target.url}`);
    } finally {
        clearTimeout(timeout);
    }
}

function parseOriginsRemoteAssetTarget(requestUrl) {
    const url = new URL(requestUrl, `http://127.0.0.1:${port}`);
    if (!url.pathname.startsWith(ORIGINS_LOCAL.remoteAssetRoot)) {
        return null;
    }
    const relative = url.pathname.slice(ORIGINS_LOCAL.remoteAssetRoot.length);
    const parts = relative.split('/');
    if (parts.length < 3) {
        return null;
    }
    const protocol = parts.shift();
    const host = parts.shift();
    if (!['http', 'https'].includes(protocol) || !ORIGINS_REMOTE_ASSET_HOSTS.has(host)) {
        return null;
    }
    const pathname = `/${parts.join('/')}`;
    return {
        url: `${protocol}://${host}${pathname}${url.search}`,
        pathname
    };
}

function remoteAssetContentType(pathname) {
    const ext = path.extname(pathname || '').toLowerCase();
    return MIME[ext] || 'application/octet-stream';
}

function resolveOriginsLocalAssetByFileName(fileName) {
    for (const dir of localOriginsRuntimeDirs()) {
        const resolved = resolveOriginsAssetByFileName(dir, fileName);
        if (resolved) {
            return resolved;
        }
    }
    return null;
}

function originsLocalAssetText(urlPath) {
    const relative = urlPath.slice(ORIGINS_LOCAL.assetRoot.length);
    if (relative === 'steam_build.txt') {
        return '0\n';
    }
    return null;
}

function resolveOriginsAssetPath(urlPath) {
    const relative = urlPath.slice(ORIGINS_LOCAL.assetRoot.length);
    if (!relative || relative.includes('..') || path.isAbsolute(relative)) {
        return null;
    }
    for (const dir of localOriginsRuntimeDirs()) {
        const direct = path.join(dir, relative);
        if (fs.existsSync(direct) && fs.statSync(direct).isFile()) {
            return direct;
        }
        if (relative.toLowerCase().endsWith('.cst')) {
            const cct = path.join(dir, `${relative.slice(0, -4)}.cct`);
            if (fs.existsSync(cct) && fs.statSync(cct).isFile()) {
                return cct;
            }
        }
        const byName = resolveOriginsAssetByFileName(dir, path.basename(relative));
        if (byName) {
            return byName;
        }
    }
    return null;
}

function resolveOriginsAssetByFileName(dir, fileName) {
    if (!fileName || fileName.includes('..') || path.isAbsolute(fileName)) {
        return null;
    }
    const direct = path.join(dir, fileName);
    if (fs.existsSync(direct) && fs.statSync(direct).isFile()) {
        return direct;
    }
    if (fileName.toLowerCase().endsWith('.cst')) {
        const cct = path.join(dir, `${fileName.slice(0, -4)}.cct`);
        if (fs.existsSync(cct) && fs.statSync(cct).isFile()) {
            return cct;
        }
    }
    return null;
}

function localOriginsRuntimeDirs() {
    const dirs = [];
    if (homeDir) {
        const launcherShockwave = path.join(homeDir, 'Library/Application Support/Habbo Launcher/downloads/shockwave');
        if (fs.existsSync(launcherShockwave) && fs.statSync(launcherShockwave).isDirectory()) {
            for (const entry of fs.readdirSync(launcherShockwave, { withFileTypes: true })
                .filter(item => item.isDirectory())
                .map(item => item.name)
                .sort((a, b) => Number(b) - Number(a) || b.localeCompare(a))) {
                dirs.push(path.join(
                    launcherShockwave,
                    entry,
                    'Habbo.app/Contents/SharedSupport/prefix/drive_c/Program Files (x86)/Habbo Hotel'
                ));
            }
        }
        dirs.push(path.join(
            homeDir,
            'Library/Application Support/Steam/steamapps/common/Habbo Hotel Origins/Habbo.app/Contents/SharedSupport/prefix/drive_c/Program Files (x86)/Habbo Hotel'
        ));
    }
    dirs.push(path.join(repoRoot, 'temp/habbo_origins_src/archive/unprotected_client_dump_20_06_2024'));
    return dirs.filter(dir => fs.existsSync(path.join(dir, 'habbo.dcr')));
}

function originsGamedataRelativePath(urlPath) {
    for (const rootPath of ORIGINS_LOCAL.gamedataRoots) {
        if (urlPath.startsWith(rootPath)) {
            const relative = urlPath.slice(rootPath.length);
            if (!relative || relative.includes('..') || path.isAbsolute(relative)) {
                return null;
            }
            return relative;
        }
    }
    return null;
}

function isOriginsGamedataPath(urlPath) {
    return originsGamedataRelativePath(urlPath) !== null;
}

function originsGamedataRoute(urlPath) {
    const relative = originsGamedataRelativePath(urlPath);
    if (!relative) {
        return null;
    }
    const route = ORIGINS_GAMEDATA_ROUTES.get(relative);
    if (route) {
        return { relative, route };
    }
    return { relative, route: { localNames: [relative] } };
}

async function serveOriginsGamedata(res, urlPath) {
    const match = originsGamedataRoute(urlPath);
    if (!match) {
        serveError(res, 404, 'Not found');
        return;
    }
    const loaded = await loadOriginsGamedataSource(match);
    if (!loaded) {
        serveError(res, 404, `origins gamedata not found: ${match.relative}`);
        return;
    }
    const text = match.route.transform ? match.route.transform(loaded.text) : loaded.text;
    const logPrefix = match.route.logPrefix || 'origins-gamedata';
    console.log(`[${logPrefix}] serve ${match.relative} from ${loaded.source} (${Buffer.byteLength(text, 'utf8')} bytes)`);
    serveText(res, text);
}

async function loadOriginsGamedataSource(match) {
    if (!match) {
        return null;
    }
    let loaded = null;
    if (ORIGINS_REMOTE_GAMEDATA_ENABLED) {
        loaded = await fetchOriginsGamedataText(match.relative);
    }
    if (!loaded) {
        loaded = loadOriginsGamedataText(match.route.localNames);
    }
    if (!loaded && match.route.generate) {
        loaded = await match.route.generate();
    }
    return loaded;
}

async function fetchOriginsGamedataText(relative) {
    const config = originsCurrentHotelConfig();
    const url = `http://${config.gamedataHost}/${relative}`;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), ORIGINS_REMOTE_GAMEDATA_TIMEOUT_MS);
    try {
        const response = await fetch(url, { signal: controller.signal });
        if (!response.ok) {
            console.warn(`[origins-gamedata] remote ${url} -> ${response.status}`);
            return null;
        }
        return {
            source: url,
            text: await response.text()
        };
    } catch (err) {
        console.warn(`[origins-gamedata] remote ${url} failed: ${err.message}`);
        return null;
    } finally {
        clearTimeout(timeout);
    }
}

function loadOriginsGamedataText(localNames) {
    for (const name of localNames || []) {
        const local = readLocalOriginsGamedataText(name);
        if (local) {
            return local;
        }
    }
    return null;
}

function readLocalOriginsGamedataText(name) {
    for (const dir of localOriginsGamedataDirs()) {
        const file = path.join(dir, name);
        if (fs.existsSync(file) && fs.statSync(file).isFile()) {
            return {
                source: file,
                text: fs.readFileSync(file, 'utf8')
            };
        }
    }
    return null;
}

function localOriginsGamedataDirs() {
    const dirs = [
        path.join(root, 'origins300-gamedata'),
        path.join(repoRoot, 'temp/origins_official_gamedata_com'),
        path.join(repoRoot, 'temp/origins_official_gamedata_es')
    ];
    if (homeDir) {
        const diffVersionsDir = path.join(homeDir, 'Library/Application Support/Habbo Origins Diff/data/versions');
        dirs.push(...localVersionDirs(diffVersionsDir));
    }
    return dirs;
}

function localVersionDirs(parentDir) {
    if (!fs.existsSync(parentDir) || !fs.statSync(parentDir).isDirectory()) {
        return [];
    }
    return fs.readdirSync(parentDir, { withFileTypes: true })
        .filter(entry => entry.isDirectory())
        .map(entry => entry.name)
        .sort((a, b) => Number(b) - Number(a) || b.localeCompare(a))
        .map(name => path.join(parentDir, name));
}

function withOriginsCompatibilityVariables(text) {
    const lines = String(text || '').split(/\r?\n/);
    const out = [];
    const keys = new Set();
    for (const line of lines) {
        if (!line) continue;
        const eq = line.indexOf('=');
        if (eq <= 0) {
            out.push(line);
            continue;
        }
        const key = line.slice(0, eq);
        const value = line.slice(eq + 1);
        keys.add(key);
        if (ORIGINS_FORCED_VARIABLES.has(key)) {
            out.push(`${key}=${ORIGINS_FORCED_VARIABLES.get(key)}`);
        } else {
            out.push(`${key}=${rewriteOriginsVariableValue(key, value)}`);
        }
    }
    for (const [key, value] of ORIGINS_FORCED_VARIABLES.entries()) {
        if (!keys.has(key)) {
            out.push(`${key}=${value}`);
        }
    }
    return `${out.join('\r\n')}\r\n`;
}

function withOriginsCompatibilityTexts(text) {
    const lines = String(text || '').split(/\r?\n/).filter(line => line.length > 0);
    const keys = new Set(lines.map(line => {
        const eq = line.indexOf('=');
        return eq > 0 ? line.slice(0, eq) : null;
    }).filter(Boolean));
    const compatibility = new Map([
        ['server_clock_name', 'Habbo Time:'],
        ['player_profile', 'Habbo Profile'],
        ['profile_hide_elements_btn', 'Hide'],
        ['skill_shopping_cart', 'Shopping Cart']
    ]);
    for (const [key, value] of compatibility.entries()) {
        if (!keys.has(key)) {
            lines.push(`${key}=${value}`);
        }
    }
    return `${lines.join('\r\n')}\r\n`;
}

async function generateOriginsFurnitureDataSource() {
    const texts = await loadOriginsCompatibilityTextsSource();
    if (!texts) {
        return null;
    }
    return {
        source: `generated from ${texts.source}`,
        text: generateOriginsFurnitureData(texts.text)
    };
}

async function generateOriginsProductDataSource() {
    const texts = await loadOriginsCompatibilityTextsSource();
    if (!texts) {
        return null;
    }
    return {
        source: `generated from ${texts.source}`,
        text: generateOriginsProductData(texts.text)
    };
}

async function loadOriginsCompatibilityTextsSource() {
    const route = ORIGINS_GAMEDATA_ROUTES.get('external_texts/1');
    const loaded = await loadOriginsGamedataSource({ relative: 'external_texts/1', route });
    if (!loaded) {
        return null;
    }
    return {
        source: loaded.source,
        text: withOriginsCompatibilityTexts(loaded.text)
    };
}

function generateOriginsFurnitureData(externalTexts) {
    const entries = collectOriginsFurnitureTextEntries(externalTexts);
    const records = [];
    let classId = 1;
    for (const [className, entry] of entries.entries()) {
        records.push(originsFurnitureRecord('s', classId, className, entry));
        records.push(originsFurnitureRecord('e', classId, className, entry));
        classId++;
    }
    return `${chunkLingoListRecords(records, 32).join('\r\n')}\r\n`;
}

function generateOriginsProductData(externalTexts) {
    const entries = collectOriginsFurnitureTextEntries(externalTexts);
    const records = [];
    for (const [className, entry] of entries.entries()) {
        records.push([
            quoteLingoString(className),
            quoteLingoString(entry.name || className),
            quoteLingoString(entry.desc || ''),
            quoteLingoString('')
        ]);
    }
    return `${chunkLingoListRecords(records, 64).join('\r\n')}\r\n`;
}

function collectOriginsFurnitureTextEntries(externalTexts) {
    const entries = new Map();
    for (const line of String(externalTexts || '').split(/\r?\n/)) {
        const eq = line.indexOf('=');
        if (eq <= 0) {
            continue;
        }
        const key = line.slice(0, eq);
        const value = line.slice(eq + 1);
        let className = '';
        let prop = '';
        if (key.startsWith('furni_') && key.endsWith('_name')) {
            className = key.slice('furni_'.length, -'_name'.length);
            prop = 'name';
        } else if (key.startsWith('furni_') && key.endsWith('_desc')) {
            className = key.slice('furni_'.length, -'_desc'.length);
            prop = 'desc';
        }
        if (!className) {
            continue;
        }
        const entry = entries.get(className) || { name: '', desc: '' };
        entry[prop] = value;
        entries.set(className, entry);
    }
    return new Map([...entries.entries()]
        .filter(([, entry]) => entry.name || entry.desc)
        .sort((a, b) => a[0].localeCompare(b[0])));
}

function originsFurnitureRecord(type, classId, className, entry) {
    return [
        quoteLingoString(type),
        quoteLingoString(String(classId)),
        quoteLingoString(className),
        quoteLingoString('0'),
        quoteLingoString('0'),
        quoteLingoString('1'),
        quoteLingoString('1'),
        '[]',
        quoteLingoString(entry.name || className),
        quoteLingoString(entry.desc || '')
    ];
}

function chunkLingoListRecords(records, chunkSize) {
    const lines = [];
    for (let i = 0; i < records.length; i += chunkSize) {
        const chunk = records.slice(i, i + chunkSize);
        lines.push(`[${chunk.map(record => `[${record.join(',')}]`).join(',')}]`);
    }
    return lines.length > 0 ? lines : ['[]'];
}

function quoteLingoString(value) {
    return `"${String(value || '')
        .replace(/[\r\n]+/g, ' ')
        .replace(/"/g, "'")}"`;
}

function rewriteOriginsVariableValue(key, value) {
    if (key === 'furnidata.load.url' || key === 'productdata.load.url'
            || key === 'external.variables.txt' || key === 'external.texts.txt'
            || key === 'external.figurepartlist.txt') {
        return rewriteOriginsGamedataUrl(value);
    }
    if (shouldProxyOriginsVariableUrl(key)) {
        return rewriteRemoteUrlToLocalProxy(value);
    }
    return value;
}

function shouldProxyOriginsVariableUrl(key) {
    return key === 'dynamic.download.url'
        || key === 'flash.dynamic.download.url'
        || key === 'sound.download.url'
        || key === 'external.override.texts.txt'
        || key === 'image.library.url'
        || key === 'image.library.url.server';
}

function rewriteOriginsGamedataUrl(value) {
    return String(value || '')
        .replace(/^https?:\/\/origins-gamedata\.habbo\.com\//i, ORIGINS_LOCAL.gamedataRoots[0])
        .replace(/^https?:\/\/origins-gamedata\.habbo\.es\//i, ORIGINS_LOCAL.gamedataRoots[0])
        .replace(/^https?:\/\/origins-gamedata\.habbo\.com\.br\//i, ORIGINS_LOCAL.gamedataRoots[0])
        .replace(/^https?:\/\/origins-gamedata\.varoke\.net\//i, ORIGINS_LOCAL.gamedataRoots[0]);
}

function rewriteRemoteUrlToLocalProxy(value) {
    const text = String(value || '');
    if (!text) {
        return text;
    }
    try {
        const absolute = text.startsWith('//') ? `http:${text}` : text;
        const url = new URL(absolute);
        if (!ORIGINS_REMOTE_ASSET_HOSTS.has(url.hostname)) {
            return text;
        }
        const protocol = url.protocol === 'https:' ? 'https' : 'http';
        return `${ORIGINS_LOCAL.remoteAssetRoot}${protocol}/${url.hostname}${url.pathname}${url.search}`;
    } catch {
        return text;
    }
}

async function handleHttpRequest(req, res) {
    const urlPath = decodeURIComponent(req.url.split('?')[0]);
    if (urlPath === ORIGINS_LOCAL.localProjectorParamsPath) {
        serveOriginsLocalProjectorParams(res);
        return;
    }
    if (urlPath.startsWith(ORIGINS_LOCAL.assetRoot) && serveOriginsAsset(res, urlPath)) {
        return;
    }
    if (urlPath.startsWith(ORIGINS_LOCAL.remoteAssetRoot)) {
        await serveOriginsRemoteAsset(res, req.url);
        return;
    }
    if (isOriginsGamedataPath(urlPath)) {
        await serveOriginsGamedata(res, urlPath);
        return;
    }
    const relPath = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
    const filePath = path.join(root, relPath);
    if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
        serveFile(res, filePath);
        return;
    }
    res.writeHead(404, ISOLATION_HEADERS);
    res.end('Not found');
}

const server = http.createServer((req, res) => {
    handleHttpRequest(req, res).catch(err => {
        console.error(`[http] ${req.url} failed: ${err.stack || err.message}`);
        if (!res.headersSent) {
            serveError(res, 500, 'Internal server error');
        } else {
            res.end();
        }
    });
});

server.on('upgrade', handleTcpBridgeUpgrade);

server.listen(port, '127.0.0.1', () => {
    const params = originsRealConnectionParams();
    console.log(`LibreShockwave WASM server: http://127.0.0.1:${port}`);
    console.log(`Serving: ${root}`);
    console.log(`Origins connection mode: ${params['origins.connectionMode']} hotel=${params['origins.hotel']} info=${params['connection.info.host']}:${params['connection.info.port']} mus=${params['connection.mus.host']}:${params['connection.mus.port']}`);
    console.log(`Origins gamedata mode: ${ORIGINS_REMOTE_GAMEDATA_ENABLED ? 'remote' : 'local'}`);
    console.log(`Remote TCP forwarding: ${ALLOW_REMOTE_TCP_WS ? 'enabled' : 'disabled'}`);
    console.log(`TCP WebSocket bridge: ws://127.0.0.1:${port}/tcp-ws?host={host}&port={port}`);
    console.log(`MUS alias: ws://127.0.0.1:${port}/mus-ws?host={host}&port={port}`);
});
