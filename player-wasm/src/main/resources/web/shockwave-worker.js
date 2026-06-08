'use strict';

// Suppress noisy worker console output by default, but still relay the logs that
// matter for startup/network failures to the main thread console.
function _relayWorkerLog(type, args) {
    var msg = Array.prototype.slice.call(args).join(' ');
    if (!msg) return;
    if (type === 'error') {
        self.postMessage({ type: 'debugLog', msg: msg });
        return;
    }
    if (!_debugLogsEnabled) {
        return;
    }
    var isInputTrace = msg.indexOf('[MouseHit]') >= 0
            || msg.indexOf('[InputDispatch]') >= 0
            || msg.indexOf('[TextFocus]') >= 0
            || msg.indexOf('[KeyDispatch]') >= 0
            || msg.indexOf('[HitTest]') >= 0;
    if (isInputTrace && !_traceInputEvents) {
        return;
    }
    var isUiTrace = msg.indexOf('[EH]') >= 0
            || msg.indexOf('[EventHandler]') >= 0
            || msg.indexOf('[EventBroker]') >= 0
            || msg.indexOf('[UICall]') >= 0
            || msg.indexOf('[EventDispatcher]') >= 0
            || msg.indexOf('[FrameActor]') >= 0
            || msg.indexOf('[DEBUG') >= 0;
    if (isUiTrace && !_traceUiEvents) {
        return;
    }
    if (msg.indexOf('[WORKER]') >= 0
            || msg.indexOf('[TRACE]') >= 0
            || msg.indexOf('Lingo call stack:') >= 0
            || msg.indexOf('[Player]') >= 0
            || msg.indexOf('[CastLib]') >= 0
            || msg.indexOf('[CastLoad]') >= 0
            || msg.indexOf('[NetManager]') >= 0
            || msg.indexOf('[MUS]') >= 0
            || msg.indexOf('[MUSBridge]') >= 0
            || msg.indexOf('[MultiuserXtra]') >= 0
            || msg.indexOf('[BobbaXtra]') >= 0
            || msg.indexOf('[QueuedNet]') >= 0
            || msg.indexOf('[FetchOrder]') >= 0
            || isInputTrace
            || isUiTrace) {
        self.postMessage({ type: 'debugLog', msg: msg });
    }
}
console.log = function() { _relayWorkerLog('log', arguments); };
console.warn = function() { _relayWorkerLog('warn', arguments); };
console.error = function() { _relayWorkerLog('error', arguments); };

function _musDebug(msg) {
    if (_debugLogsEnabled) {
        self.postMessage({ type: 'debugLog', msg: '[MUS] ' + msg });
    }
}

function _musPacketDebug(msg) {
    if (_debugLogsEnabled && _musTracePackets) {
        self.postMessage({ type: 'debugLog', msg: '[MUS] ' + msg });
    }
}

function _musFrameDebug(msg) {
    if (_debugLogsEnabled && _musTraceFrames) {
        self.postMessage({ type: 'debugLog', msg: '[MUS] ' + msg });
    }
}

function _buildLookupSet(values) {
    var set = Object.create(null);
    if (!Array.isArray(values)) return set;
    for (var i = 0; i < values.length; i++) {
        var key = String(values[i] || '').trim().toLowerCase();
        if (key) set[key] = true;
    }
    return set;
}

function _musPreview(data) {
    if (!_debugLogsEnabled || data == null) return '';
    var bytes = data instanceof Uint8Array ? data : _binaryStringToBytes(String(data));
    var hex = [];
    var text = '';
    var limit = Math.min(bytes.length, 64);
    for (var i = 0; i < limit; i++) {
        var b = bytes[i];
        hex.push((b < 16 ? '0' : '') + b.toString(16));
        text += (b >= 32 && b < 127) ? String.fromCharCode(b) : '.';
    }
    if (bytes.length > limit) {
        hex.push('...');
        text += '...';
    }
    return ' hex=' + hex.join(' ') + ' ascii=' + JSON.stringify(text);
}

/**
 * LibreShockwave Web Worker - runs the WASM engine off the main thread.
 *
 * Message protocol (main -> worker):
 *   {type:'init',    basePath}
 *   {type:'loadMovie', data:ArrayBuffer, basePath}
 *   {type:'setParam',  key, value}
 *   {type:'clearParams'}
 *   {type:'preloadCasts'}
 *   {type:'play'|'pause'|'stop'}
 *   {type:'tick'}
 *   {type:'goToFrame', frame}
 *   {type:'stepForward'}
 *   {type:'stepBackward'}
 *   {type:'mouseMove',  x, y}
 *   {type:'mouseDown',  x, y, button}  (button: 0=left, 2=right)
 *   {type:'mouseUp',    x, y, button}
 *   {type:'keyDown',    keyCode, key, modifiers}  (modifiers: 1=shift,2=ctrl,4=alt)
 *   {type:'keyUp',      keyCode, key, modifiers}
 *
 * Message protocol (worker -> main):
 *   {type:'ready'}
 *   {type:'movieLoaded',  info:{width,height,frameCount,tempo} | null}
 *   {type:'castsDone'}
 *   {type:'frame', playing, enginePlaying, tempo, lastFrame, frameCount,
 *                  rgba:Uint8ClampedArray, width, height}
 *   {type:'error', msg}
 */

var _e = null;          // WasmEngine instance
var _isTicking = false; // guard against overlapping ticks
var _pageProtocol = ''; // page protocol from main thread (e.g. 'https:')
var _debugLogsEnabled = false;
var _traceInputEvents = false;
var _traceUiEvents = false;
var _musTracePackets = false;
var _musTraceFrames = false;
var _musWebSocketUrl = ''; // optional browser WebSocket URL override for Multiuser Xtra
var _musSecureHosts = Object.create(null); // optional host allow-list for default wss:// Multiuser sockets
var STARTUP_FETCH_DELIVERY_MAX = 32;
var STARTUP_FETCH_DELIVERY_BUDGET_MS = 32;
var STARTUP_JPEG_DELIVERY_MAX = 8;
var STARTUP_ASYNC_IDLE_DELAY_MS = 2;
var SKIP_RENDER_FETCH_DELIVERY_MAX = 24;
var SKIP_RENDER_FETCH_DELIVERY_BUDGET_MS = 24;
var SKIP_RENDER_JPEG_DELIVERY_MAX = 6;
var VISIBLE_FETCH_DELIVERY_MAX = 8;
var VISIBLE_FETCH_DELIVERY_BUDGET_MS = 8;
var VISIBLE_JPEG_DELIVERY_MAX = 2;
var IDLE_PREFETCH_FIRE_MAX = 1;
// --- Multiuser Xtra WebSocket connections ---
var _musSockets = {};      // instanceId -> WebSocket
var _musInbound = {};      // instanceId -> [Uint8Array] (raw MUS TCP chunks)
var _musPendingSends = {}; // instanceId -> [Uint8Array] waiting for WebSocket.OPEN
var _musSocketUrls = {};   // instanceId -> resolved WebSocket URL
var _musOutstandingSends = {}; // instanceId -> latest send sequence awaiting inbound bytes
var _musSendSeq = 0;
var _musRequestPumpActive = false;

var _musConnected = {};    // instanceId -> true (pending connect notifications)
var _musDisconnected = {}; // instanceId -> true (pending disconnect notifications)
var _musErrors = {};       // instanceId -> errorCode
var _musDisconnectDetails = {}; // instanceId -> {code, reason, wasClean, url}
var _musErrorDetails = {};      // instanceId -> string

function _musClearInstanceState(instId) {
    delete _musConnected[instId];
    delete _musDisconnected[instId];
    delete _musErrors[instId];
    delete _musDisconnectDetails[instId];
    delete _musErrorDetails[instId];
    delete _musInbound[instId];
    delete _musPendingSends[instId];
    delete _musSocketUrls[instId];
    delete _musOutstandingSends[instId];
}

function _musCloseAllSockets() {
    for (var instId in _musSockets) {
        var ws = _musSockets[instId];
        if (!ws) continue;
        try {
            ws.onopen = null;
            ws.onmessage = null;
            ws.onerror = null;
            ws.onclose = null;
            ws.close();
        } catch(e) {}
    }
    _musSockets = {};
    _musInbound = {};
    _musPendingSends = {};
    _musConnected = {};
    _musDisconnected = {};
    _musErrors = {};
    _musDisconnectDetails = {};
    _musErrorDetails = {};
    _musSocketUrls = {};
    _musOutstandingSends = {};
}

// --- Non-blocking fetch delivery queue ---
var _fetchQueue = [];   // [{taskId, data: ArrayBuffer}] or [{taskId, error: number}]
var _fetchInFlight = 0;
var _regularFetchInFlight = 0;
var _prefetchFetchInFlight = 0;
var _fetchInFlightByKey = {};
var _fetchResponseCache = {};
var _fetchResponseCacheOwners = {};
var _fetchOrderSeq = 0;
var _deferredPrefetchRequests = [];
var _jpegDecodeQueue = []; // [{id, width, height, data: Uint8Array}]
var _jpegDecodeInFlight = {}; // id -> true
var _jpegDecodeSeq = 0; // increments per movie load to ignore stale async decodes
var _networkSeq = 0;    // increments per movie load to ignore stale async fetches
var _sharedFrameBytes = null;
var _sharedFrameControl = null;
var _sharedFrameCapacity = 0;
var _sharedFrameSeq = 0;

// --- Cross-origin fetch relay (main thread fetches on behalf of worker) ---
var _fetchRelayMap = {};  // relayId -> { engine, taskId, url, fallbacks }
var _fetchRelayCounter = 0;

function _fetchOrderLog(msg) {
    if (_debugLogsEnabled) {
        console.log('[FetchOrder] ' + msg);
    }
}

// --- Fetch with timeout (prevents hanging requests on mobile) ---
function _fetchWithTimeout(url, opts, timeoutMs) {
    timeoutMs = timeoutMs || 30000;
    var controller = new AbortController();
    var timer = setTimeout(function() { controller.abort(); }, timeoutMs);
    opts = opts || {};
    if (opts.cache === undefined && _isLocalDevUrl(url)) {
        opts.cache = 'no-store';
    }
    opts.signal = controller.signal;
    return fetch(url, opts).finally(function() { clearTimeout(timer); });
}

function _isLocalDevUrl(url) {
    try {
        var u = new URL(String(url), self.location && self.location.href ? self.location.href : undefined);
        return u.hostname === '127.0.0.1' || u.hostname === 'localhost' || u.hostname === '::1';
    } catch (e) {
        return false;
    }
}

function _refreshTempoCache() {
    if (!_e || !_e.exports) return;
    try {
        _e._lastTempo = _e.exports.getTempo();
        _e._clearEx();
    } catch (tempoErr) {}
}

function _flushWasmDiagnostics() {
    if (!_e || !_e.exports) return;

    if (_debugLogsEnabled) {
        try {
            var logLen = _e.exports.getDebugLog(); _e._clearEx();
            if (logLen > 0) {
                var logAddr = _e.exports.getStringBufferAddress(); _e._clearEx();
                var logMsg = _e._readString(logAddr, logLen);
                if (logMsg) {
                    self.postMessage({ type: 'debugLog', msg: logMsg });
                }
            }
        } catch (logErr) {}
    }

    _relayLastError();

    _drainGotoNetPages();
}

function _sleep(ms) {
    return new Promise(function(resolve) { setTimeout(resolve, ms); });
}

async function _drainStartupFetches(timeoutMs) {
    if (!_e || !_e.exports) return { delivered: 0, fired: 0, timedOut: false };

    var deadline = performance.now() + timeoutMs;
    var deliveredTotal = 0;
    var firedTotal = 0;
    var timedOut = false;

    while (true) {
        try {
            deliveredTotal += _e.deliverQueuedResults({
                maxResults: STARTUP_FETCH_DELIVERY_MAX,
                budgetMs: STARTUP_FETCH_DELIVERY_BUDGET_MS
            });
        } catch (deliverErr) {
            console.error(_formatWorkerError('[WORKER] startup deliver error', deliverErr));
            break;
        }
        try {
            deliveredTotal += _e.deliverJpegDecodeResults(STARTUP_JPEG_DELIVERY_MAX);
            _e.pumpJpegDecodeRequests();
        } catch (jpegErr) {
            console.error(_formatWorkerError('[WORKER] startup JPEG pump error', jpegErr));
            break;
        }

        try {
            firedTotal += _e.pumpNetworkFire();
        } catch (pumpErr) {
            console.error(_formatWorkerError('[WORKER] startup pump error', pumpErr));
            break;
        }

        if (!_hasStartupAsyncWork()) {
            break;
        }
        if (performance.now() >= deadline) {
            timedOut = true;
            break;
        }
        await _sleep(STARTUP_ASYNC_IDLE_DELAY_MS);
    }

    try {
        deliveredTotal += _e.deliverQueuedResults({
            maxResults: STARTUP_FETCH_DELIVERY_MAX,
            budgetMs: STARTUP_FETCH_DELIVERY_BUDGET_MS
        });
        deliveredTotal += _e.deliverJpegDecodeResults(STARTUP_JPEG_DELIVERY_MAX);
        _e.pumpJpegDecodeRequests();
    } catch (finalDeliverErr) {
        console.error(_formatWorkerError('[WORKER] startup final deliver error', finalDeliverErr));
    }

    return { delivered: deliveredTotal, fired: firedTotal, timedOut: timedOut };
}

async function _drainRequiredStartupFetches(label) {
    var deliveredTotal = 0;
    var firedTotal = 0;
    var startedAt = performance.now();
    var lastLogAt = startedAt;

    while (true) {
        var drained = await _drainStartupFetches(1000);
        deliveredTotal += drained.delivered;
        firedTotal += drained.fired;

        if (!_hasStartupAsyncWork()) {
            break;
        }

        var now = performance.now();
        if (now - lastLogAt >= 5000) {
            console.log('[WORKER] ' + label + ' waiting for startup fetches: '
                    + 'inFlight=' + _fetchInFlight
                    + ' queued=' + _fetchQueue.length
                    + ' jpegInFlight=' + Object.keys(_jpegDecodeInFlight).length
                    + ' jpegQueued=' + _jpegDecodeQueue.length
                    + ' jpegPending=' + _pendingJpegDecodeCount()
                    + ' elapsed=' + Math.round(now - startedAt) + 'ms');
            lastLogAt = now;
        }
        await _sleep(STARTUP_ASYNC_IDLE_DELAY_MS);
    }

    return {
        delivered: deliveredTotal,
        fired: firedTotal,
        timedOut: false,
        totalMs: Math.round(performance.now() - startedAt)
    };
}

function _hasStartupAsyncWork() {
    return _fetchInFlight > 0
        || _fetchQueue.length > 0
        || _jpegDecodeQueue.length > 0
        || Object.keys(_jpegDecodeInFlight).length > 0
        || _pendingJpegDecodeCount() > 0;
}

function _pendingJpegDecodeCount() {
    if (!_e || !_e.exports || _e._wasmDead) return 0;
    try {
        var count = _e.exports.getPendingJpegDecodeCount();
        _e._clearEx();
        return count || 0;
    } catch (e) {
        return 0;
    }
}

function _relayLastError() {
    var errMsg = _readLastError();
    if (errMsg) {
        self.postMessage({ type: 'error', msg: errMsg });
    }
    return errMsg;
}

function _readLastError() {
    if (!_e || !_e.exports) return;
    try {
        var errLen = _e.exports.getLastError(); _e._clearEx();
        if (errLen <= 0) {
            return;
        }
        var errAddr = _e.exports.getStringBufferAddress(); _e._clearEx();
        var errMsg = _e._readString(errAddr, errLen);
        return errMsg || '';
    } catch (errRead) {}
}

function _consumeScriptErrorPauseRequest() {
    if (!_e || !_e.exports || !_e.exports.consumeScriptErrorPauseRequest) return false;
    try {
        var shouldPause = _e.exports.consumeScriptErrorPauseRequest(); _e._clearEx();
        if (shouldPause) {
            _e.playing = false;
            if (_debugLogsEnabled) {
                self.postMessage({
                    type: 'debugLog',
                    msg: '[WORKER] auto-paused on script error before pumping disconnect/network requests\n'
                });
            }
            return true;
        }
    } catch (pauseErr) {}
    return false;
}

function _formatWorkerError(prefix, err) {
    var msg = prefix + ': ' + err;
    if (_e && _e._lastRenderStage && prefix.indexOf('render') >= 0) {
        msg += '\nrenderStage=' + _e._lastRenderStage;
    }
    if (_e && _e.exports && prefix.indexOf('render') >= 0 && _e.exports.getRenderPipelineStage) {
        try {
            var stageLen = _e.exports.getRenderPipelineStage();
            _e._clearEx();
            if (stageLen > 0) {
                var stageAddr = _e.exports.getStringBufferAddress();
                _e._clearEx();
                msg += '\npipelineStage=' + _e._readString(stageAddr, stageLen);
            }
        } catch (stageErr) {}
    }
    if (err && err.stack) {
        msg += '\n' + err.stack;
    }
    var lastError = _readLastError();
    return lastError ? msg + '\n' + lastError : msg;
}

function _drainGotoNetPages() {
    if (!_e || !_e.exports) return;
    try {
        while (true) {
            var packed = _e.exports.readNextGotoNetPage(); _e._clearEx();
            if (!packed) break;
            var urlLen = (packed >>> 16) & 0xFFFF;
            var targetLen = packed & 0xFFFF;
            var strAddr = _e.exports.getStringBufferAddress(); _e._clearEx();
            var url = urlLen > 0
                ? new TextDecoder().decode(new Uint8Array(_e._mem(), strAddr, urlLen))
                : '';
            var target = targetLen > 0
                ? new TextDecoder().decode(new Uint8Array(_e._mem(), strAddr + urlLen, targetLen))
                : '';
            self.postMessage({ type: 'gotoNetPage', url: url, target: target });
        }
    } catch (navErr) {}
    _drainGotoNetMovies();
}

function _drainGotoNetMovies() {
    if (!_e || !_e.exports) return;
    try {
        while (true) {
            var urlLen = _e.exports.readNextGotoNetMovie(); _e._clearEx();
            if (!urlLen) break;
            var strAddr = _e.exports.getStringBufferAddress(); _e._clearEx();
            var url = new TextDecoder().decode(new Uint8Array(_e._mem(), strAddr, urlLen));
            self.postMessage({ type: 'gotoNetMovie', url: url });
        }
    } catch (navErr) {}
}

function _pumpAfterInputEvent(label) {
    _drainGotoNetPages();
    if (!_e || _e._wasmDead) return;
    try {
        _e.pumpNetworkFire();
    } catch (netErr) {
        console.error(_formatWorkerError('[WORKER] input network pump error' + (label ? ' ' + label : ''), netErr));
    }
    try {
        _e.pumpMusRequests();
    } catch (musErr) {
        console.error(_formatWorkerError('[WORKER] input MUS pump error' + (label ? ' ' + label : ''), musErr));
    }
}

function _pendingMusEventSummary() {
    var inboundInstances = 0;
    var inboundMessages = 0;
    var inboundBytes = 0;
    for (var mid in _musInbound) {
        var msgs = _musInbound[mid];
        if (!msgs || msgs.length === 0) continue;
        inboundInstances++;
        inboundMessages += msgs.length;
        for (var i = 0; i < msgs.length; i++) {
            inboundBytes += msgs[i] ? msgs[i].length : 0;
        }
    }
    var connected = Object.keys(_musConnected).length;
    var errors = Object.keys(_musErrors).length;
    var disconnected = Object.keys(_musDisconnected).length;
    if (!inboundMessages && !connected && !errors && !disconnected) {
        return '';
    }
    return 'inboundInstances=' + inboundInstances
        + ' inboundMessages=' + inboundMessages
        + ' inboundBytes=' + inboundBytes
        + ' connected=' + connected
        + ' errors=' + errors
        + ' disconnected=' + disconnected;
}

function _drainMusBeforeInputEvent(label) {
    // Director input handlers run in their authored event turn. Network data may
    // arrive between browser events, but invoking Multiuser callbacks here can
    // interleave server messages with mouse/key handlers and shift encrypted
    // sends by one input boundary. WebSocket events are staged at frame start
    // and requests produced by this input are pumped after the handler returns.
}

// ============================================================
// WasmEngine - mirrors the main-thread version but without Canvas
// ============================================================

function WasmEngine() {
    this.teavm    = null;
    this.exports  = null;
    this.playing  = false;
    this._lastTempo      = 15;
    this._lastFrame      = 0;
    this._lastFrameCount = 0;
}

WasmEngine.prototype._mem = function() { return this.teavm.memory.buffer; };

WasmEngine.prototype._readString = function(addr, len) {
    return new TextDecoder().decode(new Uint8Array(this._mem(), addr, len));
};
WasmEngine.prototype._readBytes = function(addr, len) {
    var copy = new Uint8Array(len);
    copy.set(new Uint8Array(this._mem(), addr, len));
    return copy;
};

function _binaryStringToBytes(value) {
    var bytes = new Uint8Array(value.length);
    for (var i = 0; i < value.length; i++) {
        bytes[i] = value.charCodeAt(i) & 0xff;
    }
    return bytes;
}

WasmEngine.prototype._writeBytes = function(addr, bytes, maxLen) {
    new Uint8Array(this._mem(), addr, maxLen).set(bytes.subarray(0, Math.min(bytes.length, maxLen)));
};

WasmEngine.prototype._clearEx = function() {
    var ex = this.teavm.instance && this.teavm.instance.exports;
    if (ex && ex.teavm_catchException) ex.teavm_catchException();
};

WasmEngine.prototype.loadMovie = function(bytes, basePath) {
    var bp = new TextEncoder().encode(basePath || '');
    this._writeBytes(this.exports.getStringBufferAddress(), bp, 4096);
    var bufAddr = this.exports.allocateBuffer(bytes.length);
    new Uint8Array(this._mem(), bufAddr, bytes.length).set(bytes);
    var result = this.exports.loadMovie(bytes.length, bp.length);
    this._clearEx();
    if (result === 0) return null;
    var w = (result >> 16) & 0xFFFF, h = result & 0xFFFF;
    this._lastFrameCount = this.exports.getFrameCount();
    this._lastTempo      = this.exports.getTempo();
    return { width: w, height: h, frameCount: this._lastFrameCount, tempo: this._lastTempo };
};

WasmEngine.prototype.setExternalParam = function(key, value) {
    var kb = new TextEncoder().encode(key), vb = new TextEncoder().encode(value);
    var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
    sbuf.set(kb); sbuf.set(vb, kb.length);
    this.exports.setExternalParam(kb.length, vb.length);
    this._clearEx();
};

WasmEngine.prototype.clearExternalParams = function() {
    this.exports.clearExternalParams(); this._clearEx();
};

WasmEngine.prototype.setInitialBuiltinVariable = function(key, value) {
    if (!this.exports.setInitialBuiltinVariable) return;
    var kb = new TextEncoder().encode(key), vb = new TextEncoder().encode(String(value == null ? '' : value));
    var totalLen = kb.length + vb.length;
    var strAddr = this.exports.ensureStringBufferCapacity
        ? this.exports.ensureStringBufferCapacity(totalLen)
        : this.exports.getStringBufferAddress();
    this._clearEx();
    var sbuf = new Uint8Array(this._mem(), strAddr, totalLen);
    sbuf.set(kb); sbuf.set(vb, kb.length);
    this.exports.setInitialBuiltinVariable(kb.length, vb.length);
    this._clearEx();
};

WasmEngine.prototype.clearInitialBuiltinVariables = function() {
    if (!this.exports.clearInitialBuiltinVariables) return;
    this.exports.clearInitialBuiltinVariables();
    this._clearEx();
};

WasmEngine.prototype.seedNetCache = function(url, data) {
    if (!this.exports.seedNetCache || !data) return;
    var ub = new TextEncoder().encode(url || '');
    var bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    var totalLen = ub.length + bytes.length;
    var strAddr = this.exports.ensureStringBufferCapacity
        ? this.exports.ensureStringBufferCapacity(totalLen)
        : this.exports.getStringBufferAddress();
    this._clearEx();
    var sbuf = new Uint8Array(this._mem(), strAddr, totalLen);
    sbuf.set(ub, 0);
    sbuf.set(bytes, ub.length);
    this.exports.seedNetCache(ub.length, bytes.length);
    this._clearEx();
};

WasmEngine.prototype.setFastMovieClockEnabled = function(enabled, multiplier) {
    if (this.exports.setFastMovieClockEnabled) {
        this.exports.setFastMovieClockEnabled(enabled ? 1 : 0, multiplier || 1);
        this._clearEx();
    }
};

WasmEngine.prototype.setRunMode = function(value) {
    var vb = new TextEncoder().encode(value || '');
    var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
    sbuf.set(vb);
    this.exports.setRunMode(vb.length);
    this._clearEx();
};

WasmEngine.prototype.setPropListSetAtByKeyCompatibility = function(enabled) {
    if (this.exports.setPropListSetAtByKeyCompatibility) {
        this.exports.setPropListSetAtByKeyCompatibility(enabled ? 1 : 0);
        this._clearEx();
    }
};

WasmEngine.prototype.addTraceHandler = function(name) {
    var nb = new TextEncoder().encode(name);
    var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
    sbuf.set(nb);
    this.exports.addTraceHandler(nb.length);
    this._clearEx();
};

WasmEngine.prototype.removeTraceHandler = function(name) {
    var nb = new TextEncoder().encode(name);
    var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
    sbuf.set(nb);
    this.exports.removeTraceHandler(nb.length);
    this._clearEx();
};

WasmEngine.prototype.clearTraceHandlers = function() {
    this.exports.clearTraceHandlers();
    this._clearEx();
};

WasmEngine.prototype.preloadCasts = function() {
    var n = this.exports.preloadCasts(); this._clearEx(); return n;
};

WasmEngine.prototype.prefetchExternalCasts = function() {
    if (!this.exports.prefetchExternalCasts) return 0;
    var n = this.exports.prefetchExternalCasts(); this._clearEx(); return n;
};

WasmEngine.prototype.tick = function() {
    var r = this.exports.tick(); this._clearEx(); return r !== 0;
};

/**
 * Render the current frame into an RGBA buffer via SoftwareRenderer (WASM-side).
 * Returns { w, h, rgba: Uint8ClampedArray } or null on failure.
 */
WasmEngine.prototype.renderFrame = function() {
    this._lastRenderStage = 'render';
    var len = this.exports.render(this._lastFrame || 0); this._clearEx();
    if (len <= 0) return null;

    // Address.ofData() points at TeaVM-managed memory. Copy the frame bytes
    // before any further Java export can allocate or trigger GC and move it.
    this._lastRenderStage = 'getRenderBufferAddress';
    var ptr = this.exports.getRenderBufferAddress();
    this._lastRenderStage = 'copyRenderBuffer';
    var memory = this._mem();
    if (ptr === 0) return null;
    if (ptr < 0 || ptr + len > memory.byteLength) {
        console.error('[WORKER] render buffer out of bounds: ptr=' + ptr + ' len=' + len
            + ' memory=' + memory.byteLength);
        return null;
    }
    var frameBytes = new Uint8ClampedArray(len);
    frameBytes.set(new Uint8ClampedArray(memory, ptr, len));

    this._lastRenderStage = 'getRenderBufferWidth';
    var w = this.exports.getRenderBufferWidth(); this._clearEx();
    this._lastRenderStage = 'getRenderBufferHeight';
    var h = this.exports.getRenderBufferHeight(); this._clearEx();
    if (w <= 0 || h <= 0) return null;

    this._lastRenderStage = 'validateRenderBuffer';
    var expectedLen = w * h * 4;
    if (len < expectedLen) {
        console.error('[WORKER] render buffer too small: ptr=' + ptr + ' len=' + len
            + ' expected=' + expectedLen + ' stage=' + w + 'x' + h);
        return null;
    }
    var rgba = frameBytes.length === expectedLen ? frameBytes : frameBytes.slice(0, expectedLen);

    // Update cached frame metadata after the raw pixel copy is complete.
    this._lastRenderStage = 'getFrameMetadata';
    this._lastFrame      = this.exports.getCurrentFrame();
    this._lastFrameCount = this.exports.getFrameCount();

    this._lastRenderStage = 'done';
    return { w: w, h: h, rgba: rgba };
};

// --- Input forwarding ---

WasmEngine.prototype.mouseMove = function(x, y) {
    this.exports.mouseMove(x, y); this._clearEx();
};

WasmEngine.prototype.mouseDown = function(x, y, button) {
    this.exports.mouseDown(x, y, button); this._clearEx();
};

WasmEngine.prototype.mouseUp = function(x, y, button) {
    this.exports.mouseUp(x, y, button); this._clearEx();
};

WasmEngine.prototype.inspectStageAt = function(x, y) {
    if (!this.exports.inspectStageAt) return '';
    var len = this.exports.inspectStageAt(x, y); this._clearEx();
    if (!len) return '';
    var strAddr = this.exports.getStringBufferAddress(); this._clearEx();
    return this._readString(strAddr, len);
};

WasmEngine.prototype.keyDown = function(browserKeyCode, keyChar, modifiers) {
    var kb = new TextEncoder().encode(keyChar || '');
    if (kb.length > 0) {
        var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
        sbuf.set(kb);
    }
    this.exports.keyDown(browserKeyCode, kb.length, modifiers); this._clearEx();
};

WasmEngine.prototype.keyUp = function(browserKeyCode, keyChar, modifiers) {
    var kb = new TextEncoder().encode(keyChar || '');
    if (kb.length > 0) {
        var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
        sbuf.set(kb);
    }
    this.exports.keyUp(browserKeyCode, kb.length, modifiers); this._clearEx();
};

WasmEngine.prototype.pasteText = function(text) {
    var kb = new TextEncoder().encode(text || '');
    if (kb.length > 0) {
        var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 65536);
        sbuf.set(kb);
    }
    this.exports.pasteText(kb.length); this._clearEx();
};

WasmEngine.prototype._drainRequests = function() {
    var count = this.exports.getPendingFetchCount(); this._clearEx();
    if (count === 0) return null;
    var reqs = [];
    var strAddr = this.exports.getStringBufferAddress();
    for (var i = 0; i < count; i++) {
        var taskId = this.exports.getPendingFetchTaskId(i); this._clearEx();
        var urlLen = this.exports.getPendingFetchUrl(i); this._clearEx();
        var url = this._readString(strAddr, urlLen);
        var method = this.exports.getPendingFetchMethod(i); this._clearEx();
        var postData = null;
        if (method === 1) {
            var pdLen = this.exports.getPendingFetchPostData(i); this._clearEx();
            if (pdLen > 0) postData = this._readString(strAddr, pdLen);
        }
        var fbCount = this.exports.getPendingFetchFallbackCount(i); this._clearEx();
        var fallbacks = [];
        for (var j = 0; j < fbCount; j++) {
            var fbLen = this.exports.getPendingFetchFallbackUrl(i, j); this._clearEx();
            fallbacks.push(this._readString(strAddr, fbLen));
        }
        var internalPrefetch = false;
        if (this.exports.isPendingFetchInternalPrefetch) {
            internalPrefetch = this.exports.isPendingFetchInternalPrefetch(i) !== 0;
            this._clearEx();
        }
        reqs.push({taskId: taskId, url: url, method: method === 1 ? 'POST' : 'GET',
                   postData: postData, fallbacks: fallbacks, internalPrefetch: internalPrefetch});
    }
    this.exports.drainPendingFetches(); this._clearEx();
    return reqs;
};

/**
 * Detect if a URL points to a cast file (.cct or .cst).
 */

WasmEngine.prototype._deliverResult = function(taskId, arrayBuffer, url) {
    // Deliver all fetch results uniformly; cast file detection and caching
    // is handled on the Java side in deliverFetchResult.
    try {
        var bytes = new Uint8Array(arrayBuffer);
        var addr  = this.exports.allocateNetBuffer(bytes.length); this._clearEx();
        new Uint8Array(this._mem(), addr, bytes.length).set(bytes);
        var urlBytes = new TextEncoder().encode(String(url || ''));
        var strAddr;
        if (this.exports.ensureStringBufferCapacity) {
            strAddr = this.exports.ensureStringBufferCapacity(urlBytes.length); this._clearEx();
        } else {
            strAddr = this.exports.getStringBufferAddress(); this._clearEx();
        }
        if (urlBytes.length > 0) {
            new Uint8Array(this._mem(), strAddr, urlBytes.length).set(urlBytes);
        }
        this.exports.deliverFetchResult(taskId, urlBytes.length, bytes.length);
        this._clearEx();
    } catch (e) {
        console.error('[WORKER] deliverFetchResult error for taskId=' + taskId + ': ' + e);
        this._clearEx();
    }
};

WasmEngine.prototype._deliverPrefetchResult = function(requestedUrl, arrayBuffer, completedUrl) {
    try {
        var bytes = new Uint8Array(arrayBuffer);
        var addr  = this.exports.allocateNetBuffer(bytes.length); this._clearEx();
        new Uint8Array(this._mem(), addr, bytes.length).set(bytes);

        var requestedBytes = new TextEncoder().encode(String(requestedUrl || ''));
        var completedBytes = new TextEncoder().encode(String(completedUrl || ''));
        var strLen = requestedBytes.length + completedBytes.length;
        var strAddr;
        if (this.exports.ensureStringBufferCapacity) {
            strAddr = this.exports.ensureStringBufferCapacity(strLen); this._clearEx();
        } else {
            strAddr = this.exports.getStringBufferAddress(); this._clearEx();
        }
        var strView = new Uint8Array(this._mem(), strAddr, strLen);
        if (requestedBytes.length > 0) {
            strView.set(requestedBytes, 0);
        }
        if (completedBytes.length > 0) {
            strView.set(completedBytes, requestedBytes.length);
        }
        this.exports.deliverPrefetchResult(requestedBytes.length, completedBytes.length, bytes.length);
        this._clearEx();
    } catch (e) {
        console.error('[WORKER] deliverPrefetchResult error for url=' + requestedUrl + ': ' + e);
        this._clearEx();
    }
};

WasmEngine.prototype._deliverStatus = function(taskId, arrayBuffer, url) {
    try {
        var text = String(url || '');
        var bytes = new TextEncoder().encode(text);
        var addr;
        if (this.exports.ensureStringBufferCapacity) {
            addr = this.exports.ensureStringBufferCapacity(bytes.length); this._clearEx();
        } else {
            addr = this.exports.getStringBufferAddress(); this._clearEx();
        }
        if (bytes.length > 0) {
            new Uint8Array(this._mem(), addr, bytes.length).set(bytes);
        }
        var byteCount = arrayBuffer ? arrayBuffer.byteLength : 0;
        this.exports.deliverFetchStatus(taskId, bytes.length, byteCount);
        this._clearEx();
    } catch (e) {
        console.error('[WORKER] deliverFetchStatus error for taskId=' + taskId + ': ' + e);
        this._clearEx();
    }
};

WasmEngine.prototype._wasmDead = false;



WasmEngine.prototype._deliverError = function(taskId, status) {
    this.exports.deliverFetchError(taskId, status || 0); this._clearEx();
};

/**
 * Legacy helper kept for callers that still ask to "collect" requests.
 * It now only starts the fetches and returns immediately; completed responses
 * are delivered later by deliverQueuedResults().
 */
WasmEngine.prototype.pumpNetworkCollect = function() {
    this.pumpNetworkFire();
    return [];
};

// ============================================================
// Non-blocking fetch pipeline (used during tick for async I/O)
// ============================================================

/**
 * Ask the main thread to perform a fetch that the worker should not perform
 * directly. Resolves asynchronously; never blocks the worker tick loop.
 */
WasmEngine.prototype._relayFetch = function(url, method, postData) {
    return new Promise(function(resolve, reject) {
        var relayId = ++_fetchRelayCounter;
        _fetchRelayMap[relayId] = {
            url: url,
            resolve: resolve,
            reject: reject
        };
        self.postMessage({
            type: 'fetchRelay',
            relayId: relayId,
            url: url,
            method: method || 'GET',
            postData: postData || null
        });
    });
};

WasmEngine.prototype._fetchOnce = function(url, method, postData) {
    if (_isCrossOrigin(url)) {
        return this._relayFetch(url, method, postData);
    }
    var opts = {};
    if (method === 'POST') {
        opts.method = 'POST';
        opts.body = postData || null;
        opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    }
    return _fetchWithTimeout(url, opts, 30000)
        .then(function(r) {
            if (!r.ok) throw { status: r.status };
            return r.arrayBuffer();
        });
};

WasmEngine.prototype._fetchWithFallbacks = async function(taskId, url, method, postData, fallbacks) {
    var urls = [url];
    if (fallbacks) {
        for (var i = 0; i < fallbacks.length; i++) urls.push(fallbacks[i]);
    }
    for (var j = 0; j < urls.length; j++) {
        var tryUrl = urls[j];
        _fetchOrderLog('attempt task=' + taskId
            + ' index=' + j
            + ' url=' + tryUrl);
        console.log('[WORKER] fetch: ' + tryUrl + (j === 0 && urls.length > 1 ? ' (+' + (urls.length - 1) + ' fallbacks)' : ''));
        try {
            var buf = await this._fetchOnce(tryUrl, method, postData);
            if (_isImportableImageUrl(tryUrl)) {
                buf = await _decodeImageForImport(buf, tryUrl);
            }
            _fetchOrderLog('complete task=' + taskId
                + ' url=' + tryUrl
                + ' bytes=' + buf.byteLength);
            console.log('[WORKER] fetch OK: ' + tryUrl + ' (' + buf.byteLength + ' bytes)');
            return { data: buf, url: tryUrl, status: 200 };
        } catch (e) {
            _fetchOrderLog('error task=' + taskId
                + ' url=' + tryUrl
                + ' status=' + ((e && e.status) || 'network'));
            console.warn('[WORKER] fetch ERR: ' + tryUrl + ' status=' + ((e && e.status) || 'network'));
        }
    }
    return { error: true, status: 0 };
};

function _isImportableImageUrl(url) {
    if (!url) return false;
    var lower = String(url).toLowerCase();
    var qi = lower.indexOf('?');
    if (qi >= 0) lower = lower.substring(0, qi);
    return lower.endsWith('.gif') || lower.endsWith('.png')
        || lower.endsWith('.jpg') || lower.endsWith('.jpeg');
}

async function _decodeImageForImport(arrayBuffer, url) {
    if (typeof createImageBitmap !== 'function' || typeof OffscreenCanvas === 'undefined') {
        return arrayBuffer;
    }
    try {
        var blob = new Blob([arrayBuffer]);
        var bitmap = await createImageBitmap(blob);
        var canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
        var ctx = canvas.getContext('2d');
        ctx.drawImage(bitmap, 0, 0);
        if (bitmap.close) bitmap.close();
        var rgba = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        var out = new Uint8Array(12 + rgba.length);
        out[0] = 0x4c; out[1] = 0x53; out[2] = 0x57; out[3] = 0x49; // LSWI
        _writeU32BE(out, 4, canvas.width);
        _writeU32BE(out, 8, canvas.height);
        out.set(rgba, 12);
        return out.buffer;
    } catch (e) {
        console.warn('[WORKER] image decode skipped for ' + url + ': ' + e);
        return arrayBuffer;
    }
}

async function _decodeImageToRgba(arrayBuffer) {
    if (typeof createImageBitmap !== 'function' || typeof OffscreenCanvas === 'undefined') {
        return null;
    }
    var blob = new Blob([arrayBuffer], { type: 'image/jpeg' });
    var bitmap = await createImageBitmap(blob);
    var canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    var ctx = canvas.getContext('2d');
    ctx.drawImage(bitmap, 0, 0);
    if (bitmap.close) bitmap.close();
    var rgba = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
    var copy = new Uint8Array(rgba.length);
    copy.set(rgba);
    return { width: canvas.width, height: canvas.height, data: copy };
}

function _writeU32BE(bytes, offset, value) {
    bytes[offset] = (value >>> 24) & 0xFF;
    bytes[offset + 1] = (value >>> 16) & 0xFF;
    bytes[offset + 2] = (value >>> 8) & 0xFF;
    bytes[offset + 3] = value & 0xFF;
}

/**
 * Deliver queued fetch results into WASM with an event-loop budget.
 * Cast file results are prioritized, but still rate-limited: parsing a cast can
 * execute substantial Lingo-side work, so delivering many in one tick blocks
 * rendering, input and multiuser keepalives.
 * @return number of results delivered
 */
WasmEngine.prototype.deliverQueuedResults = function(options) {
    if (_fetchQueue.length === 0) return 0;
    var limit;
    var budgetMs = 0;
    if (typeof options === 'object' && options !== null) {
        limit = options.maxResults === undefined ? 1 : Math.max(1, options.maxResults | 0);
        budgetMs = Math.max(0, Number(options.budgetMs) || 0);
    } else {
        limit = options === undefined ? 1 : Math.max(1, options | 0);
    }
    var deadline = budgetMs > 0 ? performance.now() + budgetMs : 0;
    var delivered = 0;
    while (_fetchQueue.length > 0 && delivered < limit) {
        var index = 0;
        var bestPriority = 999;
        for (var i = 0; i < _fetchQueue.length; i++) {
            var queued = _fetchQueue[i];
            var priority = _queuedFetchDeliveryPriority(queued);
            if (priority < bestPriority) {
                index = i;
                bestPriority = priority;
                if (priority === 0) break;
            }
        }
        var item = _fetchQueue.splice(index, 1)[0];
        _fetchOrderLog('deliver seq=' + (item.fetchSeq || 0)
            + ' task=' + item.taskId
            + ' type=' + (item.data !== undefined ? 'data' : 'error')
            + ' url=' + (item.url || '<none>')
            + ' priority=' + bestPriority
            + ' remaining=' + _fetchQueue.length);
        if (item.data !== undefined) {
            if (item.internalPrefetch) {
                this._deliverPrefetchResult(item.requestedUrl || item.url, item.data, item.url);
            } else if (item.url && _isCastFileUrl(item.url) && !this._shouldDeliverFetchData(item.taskId, item.url)) {
                this._deliverStatus(item.taskId, item.data, item.url);
            } else {
                this._deliverResult(item.taskId, item.data, item.url);
            }
        } else if (!item.internalPrefetch) {
            this._deliverError(item.taskId, item.error);
        }
        delivered++;
        if (deadline > 0 && performance.now() >= deadline) {
            break;
        }
    }
    if (delivered > 0) _flushWasmDiagnostics();
    return delivered;
};

function _queuedFetchDeliveryPriority(item) {
    if (!item) return 3;
    if (item.data === undefined) return 0;
    if (item.url && _isCastFileUrl(item.url)) {
        return _isPlaceholderCastUrl(item.url) ? 2 : 0;
    }
    return 1;
}

WasmEngine.prototype.deliverJpegDecodeResults = function(maxResults) {
    if (_jpegDecodeQueue.length === 0) return 0;
    var limit = maxResults === undefined ? 1 : Math.max(1, maxResults | 0);
    var delivered = 0;
    while (_jpegDecodeQueue.length > 0 && delivered < limit) {
        var item = _jpegDecodeQueue.shift();
        try {
            if (item.data.length > 0) {
                var addr = this.exports.allocateNetBuffer(item.data.length); this._clearEx();
                new Uint8Array(this._mem(), addr, item.data.length).set(item.data);
            }
            this.exports.deliverJpegDecodeResult(item.id, item.width, item.height, item.data.length);
            this._clearEx();
            delivered++;
        } catch (e) {
            console.error('[WORKER] deliverJpegDecodeResult error id=' + item.id + ': ' + e);
            this._clearEx();
        }
    }
    return delivered;
};

WasmEngine.prototype.pumpJpegDecodeRequests = function() {
    if (this._wasmDead) return;
    var count;
    try {
        count = this.exports.getPendingJpegDecodeCount(); this._clearEx();
    } catch (e) { return; }
    if (count === 0) return;

    var engine = this;
    var seq = _jpegDecodeSeq;
    for (var i = 0; i < count; i++) {
        var id = this.exports.getPendingJpegDecodeId(i); this._clearEx();
        if (!id || _jpegDecodeInFlight[id]) continue;
        var len = this.exports.getPendingJpegDecodeData(id); this._clearEx();
        var addr = this.exports.getPendingJpegDecodeDataAddress(); this._clearEx();
        if (!len || !addr) continue;
        var bytes = new Uint8Array(len);
        bytes.set(new Uint8Array(this._mem(), addr, len));
        _jpegDecodeInFlight[id] = true;
        (function(decodeId, data) {
            _decodeImageToRgba(data.buffer).then(function(decoded) {
                if (seq !== _jpegDecodeSeq) return;
                if (decoded) {
                    _jpegDecodeQueue.push({
                        id: decodeId,
                        width: decoded.width,
                        height: decoded.height,
                        data: decoded.data
                    });
                } else {
                    _jpegDecodeQueue.push({
                        id: decodeId,
                        width: 0,
                        height: 0,
                        data: new Uint8Array(0)
                    });
                }
            }).catch(function(e) {
                if (seq !== _jpegDecodeSeq) return;
                console.warn('[WORKER] embedded JPEG decode failed id=' + decodeId + ': ' + e);
                _jpegDecodeQueue.push({
                    id: decodeId,
                    width: 0,
                    height: 0,
                    data: new Uint8Array(0)
                });
            }).finally(function() {
                if (seq !== _jpegDecodeSeq) return;
                delete _jpegDecodeInFlight[decodeId];
            });
        })(id, bytes);
    }
};

function _isCastFileUrl(url) {
    if (!url) return false;
    var lower = url.toLowerCase();
    var qi = lower.indexOf('?');
    if (qi > 0) lower = lower.substring(0, qi);
    return lower.endsWith('.cct') || lower.endsWith('.cst');
}

function _isPlaceholderCastUrl(url) {
    if (!url) return false;
    var lower = url.toLowerCase();
    var qi = lower.indexOf('?');
    if (qi > 0) lower = lower.substring(0, qi);
    var slash = lower.lastIndexOf('/');
    var fileName = slash >= 0 ? lower.substring(slash + 1) : lower;
    return fileName === 'empty.cct' || fileName === 'empty.cst';
}

function _fetchCacheKeys(url) {
    var keys = [];
    if (!url) return keys;
    try {
        var parsed = new URL(url, self.location.href);
        var absolute = parsed.href;
        keys.push(absolute);
        if (parsed.search) {
            return keys;
        }
        var path = parsed.pathname || '';
        var fileName = path.substring(path.lastIndexOf('/') + 1);
        if (fileName) {
            keys.push(fileName.toLowerCase());
            var dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                keys.push(fileName.substring(0, dot).toLowerCase());
            }
        }
    } catch(e) {
        keys.push(String(url).toLowerCase());
    }
    return keys;
}

function _rememberFetchResponse(url, data) {
    if (!url || !data) return;
    var keys = _fetchCacheKeys(url);
    if (keys.length === 0) return;
    var canonical = keys[0];
    for (var i = 0; i < keys.length; i++) {
        var key = keys[i];
        if (i > 0) {
            var owner = _fetchResponseCacheOwners[key];
            if (owner && owner !== canonical) {
                _fetchResponseCacheOwners[key] = '<ambiguous>';
                delete _fetchResponseCache[key];
                continue;
            }
            if (owner === '<ambiguous>') {
                continue;
            }
            _fetchResponseCacheOwners[key] = canonical;
        }
        _fetchResponseCache[keys[i]] = data;
    }
}

function _cachedFetchResponse(url, fallbacks) {
    var candidates = [url];
    if (fallbacks && fallbacks.length) {
        for (var i = 0; i < fallbacks.length; i++) candidates.push(fallbacks[i]);
    }
    for (var c = 0; c < candidates.length; c++) {
        var keys = _fetchCacheKeys(candidates[c]);
        for (var k = 0; k < keys.length; k++) {
            var data = _fetchResponseCache[keys[k]];
            if (data) {
                var owner = _fetchResponseCacheOwners[keys[k]];
                var cachedUrl = owner && owner !== '<ambiguous>' ? owner : keys[k];
                return { data: data.slice(0), url: cachedUrl };
            }
        }
    }
    return null;
}

WasmEngine.prototype._shouldDeliverFetchData = function(taskId, url) {
    if (!url || !_isCastFileUrl(url) || !this.exports.shouldDeliverFetchData) {
        return true;
    }
    try {
        var bytes = new TextEncoder().encode(String(url));
        var addr;
        if (this.exports.ensureStringBufferCapacity) {
            addr = this.exports.ensureStringBufferCapacity(bytes.length); this._clearEx();
        } else {
            addr = this.exports.getStringBufferAddress(); this._clearEx();
        }
        if (bytes.length > 0) {
            new Uint8Array(this._mem(), addr, bytes.length).set(bytes);
        }
        var result = this.exports.shouldDeliverFetchData(taskId, bytes.length); this._clearEx();
        return result !== 0;
    } catch(e) {
        this._clearEx();
        return true;
    }
};

/**
 * Drain pending requests from WASM and fire them all non-blocking.
 * Checks JS-side cache first to avoid duplicate fetches.
 * @return number of requests fired
 */
WasmEngine.prototype.pumpNetworkFire = function() {
    var reqs = this._drainRequests() || [];
    if (_deferredPrefetchRequests.length > 0) {
        reqs = _deferredPrefetchRequests.concat(reqs);
        _deferredPrefetchRequests = [];
    }
    if (reqs.length === 0) return 0;

    var regularReqs = [];
    var prefetchReqs = [];
    for (var r = 0; r < reqs.length; r++) {
        if (reqs[r] && reqs[r].internalPrefetch) {
            prefetchReqs.push(reqs[r]);
        } else {
            regularReqs.push(reqs[r]);
        }
    }
    if (regularReqs.length > 0 || _regularFetchInFlight > 0 || _hasRegularQueuedFetchResult()) {
        if (prefetchReqs.length > 0) {
            _deferredPrefetchRequests = prefetchReqs.concat(_deferredPrefetchRequests);
        }
        reqs = regularReqs;
    } else {
        reqs = prefetchReqs.slice(0, IDLE_PREFETCH_FIRE_MAX);
        if (prefetchReqs.length > reqs.length) {
            _deferredPrefetchRequests = prefetchReqs.slice(reqs.length).concat(_deferredPrefetchRequests);
        }
    }
    if (reqs.length === 0) return 0;

    var seq = _networkSeq;
    var engine = this;
    for (var i = 0; i < reqs.length; i++) {
        let req = reqs[i];
        let fetchSeq = ++_fetchOrderSeq;
        _fetchOrderLog('request seq=' + fetchSeq
            + ' task=' + req.taskId
            + ' method=' + (req.method || 'GET')
            + ' url=' + req.url
            + ' fallbacks=' + ((req.fallbacks && req.fallbacks.length) || 0));
        if ((req.method || 'GET') === 'GET') {
            let cached = _cachedFetchResponse(req.url, req.fallbacks || []);
            if (cached && cached.data) {
                _fetchOrderLog('queue-cache seq=' + fetchSeq
                    + ' task=' + req.taskId
                    + ' url=' + (cached.url || req.url)
                    + ' bytes=' + cached.data.byteLength);
                _fetchQueue.push({
                    taskId: req.taskId,
                    data: cached.data,
                    url: cached.url || req.url,
                    requestedUrl: req.url,
                    fetchSeq: fetchSeq,
                    internalPrefetch: !!req.internalPrefetch
                });
                continue;
            }
        }
        let fetchKey = String(req.method || 'GET') + '\n' + String(req.url || '') + '\n' + String(req.postData || '');
        let pendingFetch = _fetchInFlightByKey[fetchKey];
        if (!pendingFetch) {
            _fetchInFlight++;
            if (req.internalPrefetch) {
                _prefetchFetchInFlight++;
            } else {
                _regularFetchInFlight++;
            }
            _fetchOrderLog('start seq=' + fetchSeq
                + ' task=' + req.taskId
                + ' url=' + req.url
                + ' inFlight=' + _fetchInFlight);
            pendingFetch = engine._fetchWithFallbacks(req.taskId, req.url, req.method, req.postData, req.fallbacks || [])
                .finally(function() {
                    if (_fetchInFlightByKey[fetchKey] === pendingFetch) {
                        delete _fetchInFlightByKey[fetchKey];
                    }
                    _fetchInFlight = Math.max(0, _fetchInFlight - 1);
                    if (req.internalPrefetch) {
                        _prefetchFetchInFlight = Math.max(0, _prefetchFetchInFlight - 1);
                    } else {
                        _regularFetchInFlight = Math.max(0, _regularFetchInFlight - 1);
                    }
                });
            _fetchInFlightByKey[fetchKey] = pendingFetch;
        } else {
            _fetchOrderLog('reuse-inflight seq=' + fetchSeq
                + ' task=' + req.taskId
                + ' url=' + req.url);
        }
        pendingFetch
            .then(function(result) {
                if (seq !== _networkSeq) return;
                if (result && result.data) {
                    if (_isCastFileUrl(result.url || req.url)) {
                        _rememberFetchResponse(result.url || req.url, result.data);
                    }
                    _fetchOrderLog('queue-result seq=' + fetchSeq
                        + ' task=' + req.taskId
                        + ' url=' + (result.url || req.url)
                        + ' bytes=' + result.data.byteLength
                        + ' queueBefore=' + _fetchQueue.length);
                    _fetchQueue.push({
                        taskId: req.taskId,
                        data: result.data,
                        url: result.url || req.url,
                        requestedUrl: req.url,
                        fetchSeq: fetchSeq,
                        internalPrefetch: !!req.internalPrefetch
                    });
                } else {
                    _fetchOrderLog('queue-error seq=' + fetchSeq
                        + ' task=' + req.taskId
                        + ' url=' + req.url
                        + ' status=' + (result && result.status ? result.status : 0)
                        + ' queueBefore=' + _fetchQueue.length);
                    _fetchQueue.push({
                        taskId: req.taskId,
                        error: result && result.status ? result.status : 0,
                        fetchSeq: fetchSeq,
                        internalPrefetch: !!req.internalPrefetch
                    });
                }
            })
            .catch(function(e) {
                if (seq !== _networkSeq) return;
                _fetchOrderLog('queue-throw seq=' + fetchSeq
                    + ' task=' + req.taskId
                    + ' url=' + req.url
                    + ' status=' + (e && e.status ? e.status : 0)
                    + ' queueBefore=' + _fetchQueue.length);
                _fetchQueue.push({
                    taskId: req.taskId,
                    error: e && e.status ? e.status : 0,
                    fetchSeq: fetchSeq,
                    internalPrefetch: !!req.internalPrefetch
                });
            });
    }
    return reqs.length;
};

function _hasRegularQueuedFetchResult() {
    for (var i = 0; i < _fetchQueue.length; i++) {
        if (!_fetchQueue[i] || !_fetchQueue[i].internalPrefetch) {
            return true;
        }
    }
    return false;
}

// ============================================================
// Multiuser Xtra - WebSocket bridge
// ============================================================

/**
 * Drain pending MUS requests from WASM and act on them:
 *   type 0 = connect -> open WebSocket
 *   type 1 = send    -> send on existing WebSocket
 *   type 2 = disconnect -> close WebSocket
 *
 * By default the WebSocket URL is built from the Lingo host/port, but
 * websocket.mode can force ws:// or wss:// from the player options.
 */
WasmEngine.prototype.pumpMusRequests = function() {
    if (this._wasmDead) return;
    if (_musRequestPumpActive) {
        _musDebug('pump requests skipped reentrant');
        return;
    }
    _musRequestPumpActive = true;
    var useSnapshotDrain = false;
    var snapshotDrainOpen = false;
    try {
        useSnapshotDrain = !!this.exports.beginMusPendingDrain;
        var drainedAny = false;
        var maxRounds = 8;
        for (var round = 1; round <= maxRounds; round++) {
            var count;
            if (useSnapshotDrain) {
                count = this.exports.beginMusPendingDrain(); this._clearEx();
                snapshotDrainOpen = true;
            } else {
                count = this.exports.getMusPendingCount(); this._clearEx();
            }
            if (count === 0) {
                if (snapshotDrainOpen && this.exports.finishMusPendingDrain) {
                    this.exports.finishMusPendingDrain(); this._clearEx();
                    snapshotDrainOpen = false;
                }
                break;
            }
            drainedAny = true;
            _musDebug('pump requests round=' + round + ' count=' + count);

            for (var i = 0; i < count; i++) {
                var type = this.exports.getMusPendingType(i); this._clearEx();
                var instId = this.exports.getMusPendingInstanceId(i); this._clearEx();
                var requestId = 0;
                if (this.exports.getMusPendingRequestId) {
                    requestId = this.exports.getMusPendingRequestId(i); this._clearEx();
                }

                if (type === 0) {
                    // CONNECT
                    var hostLen = this.exports.getMusPendingHost(i); this._clearEx();
                    var strAddr = this.exports.getStringBufferAddress(); this._clearEx();
                    var host = this._readString(strAddr, hostLen);
                    var port = this.exports.getMusPendingPort(i); this._clearEx();

                    var wsUrl = _buildMusWebSocketUrl(host, port, instId);
                    _musClearInstanceState(instId);
                    _musDebug('connect request id=' + requestId + ' instance=' + instId
                        + ' target=' + host + ':' + port + ' url=' + wsUrl);
                    this._musConnect(instId, wsUrl);

                } else if (type === 1) {
                    // SEND - raw content bytes (must be binary frame for websockify)
                    var dataLen = this.exports.getMusPendingSendData(i); this._clearEx();
                    var sendAddr = this.exports.getStringBufferAddress(); this._clearEx();
                    var data = this._readBytes(sendAddr, dataLen);
                    _musSendOrQueue(this, instId, data, dataLen, requestId, round);

                } else if (type === 2) {
                    // DISCONNECT
                    _musClearInstanceState(instId);
                    var ws2 = _musSockets[instId];
                    if (ws2) {
                        _musDebug('disconnect request id=' + requestId + ' instance=' + instId);
                        ws2.onopen = null;
                        ws2.onmessage = null;
                        ws2.onerror = null;
                        ws2.onclose = null; // prevent double-notification
                        ws2.close();
                        delete _musSockets[instId];
                    }
                }
            }

            if (useSnapshotDrain && this.exports.finishMusPendingDrain) {
                this.exports.finishMusPendingDrain(); this._clearEx();
                snapshotDrainOpen = false;
            } else {
                this.exports.drainMusPending(); this._clearEx();
                break;
            }
        }
        if (drainedAny && useSnapshotDrain) {
            var remaining = this.exports.getMusPendingCount(); this._clearEx();
            if (remaining > 0) {
                _musDebug('pump requests stopped with pending=' + remaining);
            }
        }
    } catch(e) {
        this._clearEx();
        throw e;
    } finally {
        if (snapshotDrainOpen && this.exports.finishMusPendingDrain) {
            try { this.exports.finishMusPendingDrain(); this._clearEx(); }
            catch(finishErr) { this._clearEx(); }
        }
        _musRequestPumpActive = false;
    }
};

function _musSendOrQueue(engine, instId, data, dataLen, requestId, drainRound) {
    var ws = _musSockets[instId];
    if (ws && ws.readyState === WebSocket.OPEN) {
        _musDebug('send request=' + (requestId || 0)
            + ' round=' + (drainRound || 0)
            + ' instance=' + instId + ' bytes=' + dataLen
            + ' url=' + (_musSocketUrls[instId] || '<unknown>')
            + ' readyState=' + ws.readyState);
        _musPacketDebug('send instance=' + instId + ' bytes=' + dataLen + _musPreview(data));
        ws.send(data.byteOffset === 0 && data.byteLength === data.buffer.byteLength
            ? data.buffer
            : data.slice().buffer);
        _musTrackOutstandingSend(instId, dataLen);
        return;
    }

    if (!ws || ws.readyState !== WebSocket.CONNECTING) {
        var readyState = ws ? ws.readyState : 'missing';
        var terminalKnown = _musDisconnected[instId] || _musErrors[instId] !== undefined
            || (ws && (ws.readyState === WebSocket.CLOSING || ws.readyState === WebSocket.CLOSED));
        _musDebug((terminalKnown ? 'send ignored after terminal state instance=' : 'send dropped instance=') + instId
            + ' readyState=' + (ws ? ws.readyState : 'missing')
            + ' bytes=' + dataLen
            + ' url=' + (_musSocketUrls[instId] || '<unknown>'));
        if (terminalKnown) {
            return;
        }
        if (_musErrors[instId] === undefined) {
            _musErrors[instId] = -2;
            _musErrorDetails[instId] = 'send dropped: socket readyState=' + readyState;
        }
        return;
    }

    var queue = _musPendingSends[instId];
    if (!queue) {
        queue = [];
        _musPendingSends[instId] = queue;
    }
    if (queue.length >= 64) {
        queue.shift();
    }
    queue.push(data.slice ? data.slice() : new Uint8Array(data));
    _musDebug('send queued instance=' + instId
        + ' readyState=' + (ws ? ws.readyState : 'missing')
        + ' queued=' + queue.length
        + ' bytes=' + dataLen
        + ' url=' + (_musSocketUrls[instId] || '<unknown>'));
}

function _musFlushQueuedSends(instId) {
    var ws = _musSockets[instId];
    var queue = _musPendingSends[instId];
    if (!ws || ws.readyState !== WebSocket.OPEN || !queue || queue.length === 0) {
        return;
    }
    _musDebug('flush queued sends instance=' + instId + ' count=' + queue.length);
    while (queue.length > 0 && ws.readyState === WebSocket.OPEN) {
        var data = queue.shift();
        _musDebug('send queued instance=' + instId + ' bytes=' + data.length
            + ' url=' + (_musSocketUrls[instId] || '<unknown>')
            + ' readyState=' + ws.readyState);
        _musPacketDebug('send instance=' + instId + ' bytes=' + data.length + _musPreview(data));
        ws.send(data.byteOffset === 0 && data.byteLength === data.buffer.byteLength
            ? data.buffer
            : data.slice().buffer);
        _musTrackOutstandingSend(instId, data.length);
    }
    if (queue.length === 0) {
        delete _musPendingSends[instId];
    }
}

function _musTrackOutstandingSend(instId, dataLen) {
    if (!_debugLogsEnabled) return;
    var seq = ++_musSendSeq;
    _musOutstandingSends[instId] = seq;
    var url = _musSocketUrls[instId] || '<unknown>';
    setTimeout(function() {
        if (_musOutstandingSends[instId] !== seq) {
            return;
        }
        var ws = _musSockets[instId];
        _musDebug('no inbound after send instance=' + instId
            + ' ms=2000 bytes=' + dataLen
            + ' readyState=' + (ws ? ws.readyState : 'missing')
            + ' url=' + url);
    }, 2000);
}

function _buildMusWebSocketUrl(host, port, instId) {
    var hostString = String(host || '').trim();
    var portString = String(port || '').trim();
    var wsUrl;
    if (_musWebSocketUrl) {
        wsUrl = _musWebSocketUrl
            .replace(/\{host\}/g, encodeURIComponent(hostString))
            .replace(/\{port\}/g, encodeURIComponent(portString));
        return _appendMusBridgeParams(wsUrl, instId);
    }
    if (/^wss?:\/\//i.test(hostString)) {
        try {
            var url = new URL(hostString);
            if (!url.port && port) url.port = String(port);
            return _appendMusBridgeParams(url.href, instId);
        } catch(e) {
            return _appendMusBridgeParams(hostString, instId);
        }
    }
    var protocol = _shouldUseSecureMusWebSocket(host, port) ? 'wss' : 'ws';
    wsUrl = protocol + '://' + hostString + ':' + portString;
    return _appendMusBridgeParams(wsUrl, instId);
}

function _appendMusBridgeParams(wsUrl, instId) {
    var params = [];
    if (instId !== undefined && instId !== null) {
        params.push(['lswInstanceId', String(instId)]);
    }
    if (_musTracePackets) {
        params.push(['traceMusPackets', '1']);
    }
    if (_musTraceFrames) {
        params.push(['traceMusFrames', '1']);
    }
    if (params.length === 0) {
        return wsUrl;
    }
    try {
        var url = new URL(wsUrl);
        for (var i = 0; i < params.length; i++) {
            url.searchParams.set(params[i][0], params[i][1]);
        }
        return url.href;
    } catch(e) {
        var suffix = params.map(function(pair) {
            return encodeURIComponent(pair[0]) + '=' + encodeURIComponent(pair[1]);
        }).join('&');
        return wsUrl + (wsUrl.indexOf('?') >= 0 ? '&' : '?') + suffix;
    }
}

function _shouldUseSecureMusWebSocket(host, port) {
    var forcedMode = String((_params && _params['websocket.mode']) || '').toLowerCase();
    if (forcedMode === 'wss') {
        return true;
    }
    if (forcedMode === 'ws') {
        return false;
    }
    var normalizedPort = String(port || '');
    if (_pageProtocol === 'https:' || normalizedPort === '443') {
        return true;
    }
    var normalizedHost = String(host || '').toLowerCase();
    return !!_musSecureHosts[normalizedHost];
}

/**
 * Open a WebSocket and wire up event handlers.
 * Messages are staged from socket events; the Director-facing Multiuser Xtra
 * callback is pumped by the normal movie tick.
 */
WasmEngine.prototype._musConnect = function(instId, wsUrl, notifyConnected) {
    if (notifyConnected === undefined) {
        notifyConnected = true;
    }
    // Close any existing connection for this instance
    if (_musSockets[instId]) {
        var oldSocket = _musSockets[instId];
        oldSocket.onopen = null;
        oldSocket.onmessage = null;
        oldSocket.onerror = null;
        oldSocket.onclose = null;
        oldSocket.close();
    }

    var ws;
    try {
        ws = new WebSocket(wsUrl);
    } catch(e) {
        _musDebug('WebSocket constructor failed instance=' + instId + ' url=' + wsUrl + ' error=' + e.message);
        _musErrors[instId] = -3; // connection refused
        _musErrorDetails[instId] = 'constructor failed url=' + wsUrl + ' error=' + (e && e.message ? e.message : e);
        return;
    }
    ws.binaryType = 'arraybuffer';
    _musSockets[instId] = ws;
    _musSocketUrls[instId] = wsUrl;

    ws.onopen = function() {
        _musDebug('open instance=' + instId + ' url=' + wsUrl);
        _musFrameDebug('frame open instance=' + instId + ' url=' + wsUrl);
        delete _musDisconnected[instId];
        delete _musDisconnectDetails[instId];
        if (notifyConnected) {
            _musConnected[instId] = true;
        }
        _musFlushQueuedSends(instId);
        _musDebug('staged event instance=' + instId + ' type=open');
    };

    ws.onmessage = function(evt) {
        // Receive raw message content
        if (!_musInbound[instId]) _musInbound[instId] = [];
        var data;
        if (typeof evt.data === 'string') {
            data = _binaryStringToBytes(evt.data);
        } else {
            data = new Uint8Array(evt.data);
        }
        delete _musOutstandingSends[instId];
        _musDebug('message instance=' + instId + ' bytes=' + data.length
            + ' url=' + wsUrl);
        _musPacketDebug('message instance=' + instId + ' bytes=' + data.length + _musPreview(data));
        _musFrameDebug('frame message instance=' + instId + ' bytes=' + data.length);
        _musInbound[instId].push(data);
        _musDebug('staged event instance=' + instId + ' type=message');
    };

    ws.onclose = function(evt) {
        var closeDetail = {
            code: evt && evt.code ? evt.code : 0,
            reason: evt && evt.reason ? evt.reason : '',
            wasClean: !!(evt && evt.wasClean),
            url: wsUrl
        };
        _musDebug('close instance=' + instId + ' code=' + closeDetail.code
            + ' reason=' + closeDetail.reason
            + ' wasClean=' + closeDetail.wasClean
            + ' url=' + wsUrl);
        _musFrameDebug('frame close instance=' + instId + ' code=' + closeDetail.code
            + ' wasClean=' + closeDetail.wasClean);
        delete _musSockets[instId];
        delete _musPendingSends[instId];
        delete _musSocketUrls[instId];
        delete _musOutstandingSends[instId];
        _musDisconnected[instId] = true;
        _musDisconnectDetails[instId] = closeDetail;
        _musDebug('staged event instance=' + instId + ' type=close');
    };

    ws.onerror = function(evt) {
        _musDebug('error instance=' + instId + ' url=' + wsUrl);
        _musErrors[instId] = -2; // network error
        _musErrorDetails[instId] = 'websocket error url=' + wsUrl
            + (evt && evt.message ? ' message=' + evt.message : '');
        _musDebug('staged event instance=' + instId + ' type=error');
    };
};

WasmEngine.prototype._writeStringForExport = function(text) {
    var bytes = new TextEncoder().encode(text || '');
    var addr;
    if (this.exports.ensureStringBufferCapacity) {
        addr = this.exports.ensureStringBufferCapacity(bytes.length); this._clearEx();
    } else {
        addr = this.exports.getStringBufferAddress(); this._clearEx();
    }
    new Uint8Array(this._mem(), addr, bytes.length).set(bytes);
    return bytes.length;
};

/**
 * Pump audio commands from WASM and send to main thread for Web Audio playback.
 * Audio must play on the main thread because Web Workers don't have AudioContext.
 */
WasmEngine.prototype.pumpAudioCommands = function() {
    if (this._wasmDead) return;
    var count;
    try {
        count = this.exports.getAudioPendingCount(); this._clearEx();
    } catch(e) { return; }
    if (count === 0) return;

    var strAddr = this.exports.getStringBufferAddress(); this._clearEx();

    for (var i = 0; i < count; i++) {
        var actionLen = this.exports.getAudioPendingAction(i); this._clearEx();
        var action = this._readString(strAddr, actionLen);
        var channel = this.exports.getAudioPendingChannel(i); this._clearEx();

        if (action === 'play') {
            var fmtLen = this.exports.getAudioPendingFormat(i); this._clearEx();
            var format = this._readString(strAddr, fmtLen);
            var loopCount = this.exports.getAudioPendingLoopCount(i); this._clearEx();
            var volume = this.exports.getAudioPendingVolume(i); this._clearEx();
            var dataLen = this.exports.getAudioPendingData(i); this._clearEx();

            if (dataLen > 0) {
                var dataAddr = this.exports.getAudioBufferAddress(); this._clearEx();
                var audioData = new Uint8Array(dataLen);
                audioData.set(new Uint8Array(this._mem(), dataAddr, dataLen));

                self.postMessage({
                    type: 'audio',
                    action: 'play',
                    channel: channel,
                    format: format,
                    loopCount: loopCount,
                    volume: volume,
                    data: audioData.buffer
                }, [audioData.buffer]);
            }
        } else if (action === 'stop') {
            self.postMessage({ type: 'audio', action: 'stop', channel: channel });
        } else if (action === 'volume') {
            var vol = this.exports.getAudioPendingVolume(i); this._clearEx();
            self.postMessage({ type: 'audio', action: 'volume', channel: channel, volume: vol });
        }
    }

    this.exports.drainAudioPending(); this._clearEx();
};

/**
 * Deliver queued MUS events to WASM.
 *
 * WebSocket error/close events can be posted after the final data frame for the
 * same TCP stream. Director scripts expect to process that data before seeing a
 * terminal network condition, because handling the data may intentionally close
 * or replace the connection. Keep terminal events behind same-instance inbound
 * data for one tick so authored cleanup can run first.
 */
WasmEngine.prototype.deliverMusEvents = function() {
    if (this._wasmDead) return;

    // Deliver connect notifications
    for (var id in _musConnected) {
        try {
            this.exports.musDeliverConnected(parseInt(id)); this._clearEx();
        } catch(e) {}
    }
    _musConnected = {};

    // Deliver messages
    var deliveredInboundFor = {};
    for (var mid in _musInbound) {
        var msgs = _musInbound[mid];
        var iid = parseInt(mid);
        for (var i = 0; i < msgs.length; i++) {
            var msgBytes = msgs[i];
            var len = msgBytes.length;
            var strAddr;
            try {
                if (this.exports.ensureStringBufferCapacity) {
                    strAddr = this.exports.ensureStringBufferCapacity(len); this._clearEx();
                } else {
                    strAddr = this.exports.getStringBufferAddress(); this._clearEx();
                }
            } catch(e) { return; }
            new Uint8Array(this._mem(), strAddr, len).set(msgBytes);
            try {
                _musDebug('deliver-to-wasm instance=' + iid + ' bytes=' + len
                    + ' index=' + (i + 1) + '/' + msgs.length);
                this.exports.musDeliverMessage(iid, len); this._clearEx();
            } catch(e) {
                self.postMessage({type:'error', msg:'[MUS] musDeliverMessage error: ' + e});
            }
        }
        if (msgs.length > 0) {
            deliveredInboundFor[mid] = true;
        }
    }
    _musInbound = {};

    // Deliver error notifications after data. Defer terminal events for
    // instances that delivered data this tick; pumpMusRequests() may clear them
    // if the script closes/reconnects while handling that data.
    var remainingErrors = {};
    var remainingErrorDetails = {};
    var terminalDeliveredFor = {};
    for (var eid in _musErrors) {
        if (deliveredInboundFor[eid]) {
            remainingErrors[eid] = _musErrors[eid];
            if (_musErrorDetails[eid]) {
                remainingErrorDetails[eid] = _musErrorDetails[eid];
            }
            continue;
        }
        try {
            var errorDetail = _musErrorDetails[eid] || '';
            if (this.exports.musDeliverErrorDetail && errorDetail) {
                var errLen = this._writeStringForExport(errorDetail);
                this.exports.musDeliverErrorDetail(parseInt(eid), _musErrors[eid], errLen); this._clearEx();
            } else {
                this.exports.musDeliverError(parseInt(eid), _musErrors[eid]); this._clearEx();
            }
            terminalDeliveredFor[eid] = true;
        } catch(e) {}
    }
    _musErrors = remainingErrors;
    _musErrorDetails = remainingErrorDetails;

    // Deliver disconnect notifications
    var remainingDisconnected = {};
    var remainingDisconnectDetails = {};
    for (var did in _musDisconnected) {
        if (deliveredInboundFor[did]) {
            remainingDisconnected[did] = true;
            if (_musDisconnectDetails[did]) {
                remainingDisconnectDetails[did] = _musDisconnectDetails[did];
            }
            continue;
        }
        if (terminalDeliveredFor[did]) {
            continue;
        }
        try {
            var detail = _musDisconnectDetails[did] || {};
            var reason = 'close code=' + (detail.code || 0)
                + ' wasClean=' + (detail.wasClean ? 'true' : 'false')
                + (detail.reason ? ' reason=' + detail.reason : '')
                + (detail.url ? ' url=' + detail.url : '');
            if (this.exports.musDeliverDisconnectedDetail) {
                var reasonLen = this._writeStringForExport(reason);
                this.exports.musDeliverDisconnectedDetail(parseInt(did), detail.code || 0,
                        detail.wasClean ? 1 : 0, reasonLen); this._clearEx();
            } else {
                this.exports.musDeliverDisconnected(parseInt(did)); this._clearEx();
            }
        } catch(e) {}
    }
    _musDisconnected = remainingDisconnected;
    _musDisconnectDetails = remainingDisconnectDetails;
};

// ============================================================
// URL helpers
// ============================================================

/**
 * Returns true if the URL is cross-origin relative to this worker.
 * Cross-origin fetches from a Web Worker can hang in Chrome when
 * the host:port differs from the page origin. We relay those through
 * the main thread, which has no such limitation.
 */
function _isCrossOrigin(url) {
    try {
        return new URL(url, self.location.href).origin !== self.location.origin;
    } catch(e) { return false; }
}

// ============================================================
// Message handler
// ============================================================

self.onmessage = async function(e) {
    var msg = e.data;
    try {
        switch (msg.type) {

            case 'init': {
                _pageProtocol = msg.pageProtocol || '';
                if (msg.sharedFrameBuffer && msg.sharedFrameControl && typeof Atomics === 'object') {
                    _sharedFrameBytes = new Uint8ClampedArray(msg.sharedFrameBuffer);
                    _sharedFrameControl = new Int32Array(msg.sharedFrameControl);
                    _sharedFrameCapacity = msg.sharedFrameCapacity || _sharedFrameBytes.length;
                }
                _musWebSocketUrl = msg.musWebSocketUrl ? String(msg.musWebSocketUrl) : '';
                _musSecureHosts = _buildLookupSet(msg.musSecureHosts);
                _musTracePackets = !!msg.traceMusPackets;
                _musTraceFrames = !!msg.traceMusFrames;
                _traceInputEvents = !!msg.traceInputEvents;
                _traceUiEvents = !!msg.traceUiEvents;
                var cacheBustSuffix = msg.cacheBust
                    ? '?v=' + encodeURIComponent(String(msg.cacheBust))
                    : '';
                // Fallback: detect protocol from worker's own location
                // (e.g. blob:https://... when loaded as a blob URL worker)
                if (!_pageProtocol && self.location && self.location.href) {
                    _pageProtocol = (self.location.href.indexOf('https:') !== -1) ? 'https:' : '';
                }
                // importScripts is synchronous; TeaVM.wasm.load is async
                importScripts(msg.basePath + 'player-wasm.wasm-runtime.js' + cacheBustSuffix);
                var instance = await TeaVM.wasm.load(msg.basePath + 'player-wasm.wasm' + cacheBustSuffix);
                await instance.main([]);
                _e = new WasmEngine();
                _e.teavm   = instance;
                _e.exports = instance.instance.exports;
                if (_e.exports.setBobbaMachineSeed) {
                    var machineSeedLen = _e._writeStringForExport(msg.machineSeed || '');
                    _e.exports.setBobbaMachineSeed(machineSeedLen);
                    _e._clearEx();
                }
                if (_e.exports.setVmHandlerTimeoutMs) {
                    _e.exports.setVmHandlerTimeoutMs(Math.max(0, Number(msg.vmHandlerTimeoutMs) || 0));
                }
                if (_e.exports.setPauseOnScriptErrorEnabled) {
                    _e.exports.setPauseOnScriptErrorEnabled(msg.pauseOnScriptError ? 1 : 0);
                }
                if (_e.exports.setPauseOnAuthoredMajorEnabled) {
                    _e.exports.setPauseOnAuthoredMajorEnabled(msg.pauseOnAuthoredMajor ? 1 : 0);
                }
                if (_e.exports.setPropertyTraceEnabled) {
                    _e.exports.setPropertyTraceEnabled(msg.traceProperties ? 1 : 0);
                }
                if (_e.exports.setMusTraceEnabled) {
                    _e.exports.setMusTraceEnabled(_musTracePackets ? 1 : 0);
                }
                _e.setPropListSetAtByKeyCompatibility(!!msg.compatPropListSetAtByKey);
                self.postMessage({ type: 'ready' });
                break;
            }

            case 'loadMovie': {
                _networkSeq++;
                _fetchQueue = [];
                _fetchInFlight = 0;
                _regularFetchInFlight = 0;
                _prefetchFetchInFlight = 0;
                _fetchInFlightByKey = {};
                _fetchResponseCache = {};
                _fetchResponseCacheOwners = {};
                _fetchOrderSeq = 0;
                _deferredPrefetchRequests = [];
                _musCloseAllSockets();
                _jpegDecodeSeq++;
                _jpegDecodeQueue = [];
                _jpegDecodeInFlight = {};
                var info = _e.loadMovie(new Uint8Array(msg.data), msg.basePath);
                _e.playing  = false;
                _flushWasmDiagnostics();
                self.postMessage({ type: 'movieLoaded', info: info });
                break;
            }

            case 'setParam':
                _e.setExternalParam(msg.key, msg.value);
                break;

            case 'setInitialBuiltinVariable':
                _e.setInitialBuiltinVariable(msg.key, msg.value);
                break;

            case 'clearInitialBuiltinVariables':
                _e.clearInitialBuiltinVariables();
                break;

            case 'seedNetCache':
                _e.seedNetCache(msg.url, msg.data);
                break;

            case 'setDebugPlayback':
                _debugLogsEnabled = !!msg.enabled;
                if (msg.traceInputEvents !== undefined) {
                    _traceInputEvents = !!msg.traceInputEvents;
                }
                if (msg.traceUiEvents !== undefined) {
                    _traceUiEvents = !!msg.traceUiEvents;
                }
                if (msg.traceMusPackets !== undefined) {
                    _musTracePackets = !!msg.traceMusPackets;
                }
                if (msg.traceMusFrames !== undefined) {
                    _musTraceFrames = !!msg.traceMusFrames;
                }
                _e.exports.setDebugPlaybackEnabled(msg.lingoEnabled ? 1 : 0);
                if (_e.exports.setMusTraceEnabled) {
                    _e.exports.setMusTraceEnabled(_musTracePackets ? 1 : 0);
                }
                if (_e.exports.setPauseOnScriptErrorEnabled && msg.pauseOnScriptError !== undefined) {
                    _e.exports.setPauseOnScriptErrorEnabled(msg.pauseOnScriptError ? 1 : 0);
                }
                if (_e.exports.setPauseOnAuthoredMajorEnabled && msg.pauseOnAuthoredMajor !== undefined) {
                    _e.exports.setPauseOnAuthoredMajorEnabled(msg.pauseOnAuthoredMajor ? 1 : 0);
                }
                if (_e.exports.setPropertyTraceEnabled && msg.traceProperties !== undefined) {
                    _e.exports.setPropertyTraceEnabled(msg.traceProperties ? 1 : 0);
                }
                _e._clearEx();
                break;

            case 'addTraceHandler':
                _e.addTraceHandler(msg.name);
                break;

            case 'removeTraceHandler':
                _e.removeTraceHandler(msg.name);
                break;

            case 'clearTraceHandlers':
                _e.clearTraceHandlers();
                break;

            case 'clearParams':
                _e.clearExternalParams();
                break;

            case 'setFastMovieClock':
                _e.setFastMovieClockEnabled(!!msg.enabled, msg.multiplier || 1);
                break;

            case 'setRunMode':
                _e.setRunMode(msg.value);
                break;

            case 'setPropListSetAtByKeyCompatibility':
                _e.setPropListSetAtByKeyCompatibility(!!msg.enabled);
                break;

            case 'preloadCasts': {
                var castT0 = performance.now();
                var n = _e.preloadCasts();
                console.log('[WORKER] preloadCasts: ' + n + ' casts queued');
                var fired = _e.pumpNetworkFire();
                console.log('[WORKER] preloadCasts fired ' + fired + ' async fetches in ' +
                            Math.round(performance.now() - castT0) + 'ms');
                var drained = n > 0
                    ? await _drainRequiredStartupFetches('preloadCasts')
                    : await _drainStartupFetches(500);
                console.log('[WORKER] preloadCasts drained delivered=' + drained.delivered +
                            ' fired=' + drained.fired +
                            ' inFlight=' + _fetchInFlight +
                            ' timedOut=' + drained.timedOut +
                            ' total=' + Math.round(performance.now() - castT0) + 'ms');
                var prefetched = _e.prefetchExternalCasts();
                if (prefetched > 0) {
                    console.log('[WORKER] prefetchExternalCasts queued=' + prefetched +
                                ' inFlight=' + _fetchInFlight);
                }

                _flushWasmDiagnostics();
                self.postMessage({ type: 'castsDone' });
                break;
            }

            case 'play':
                console.log('[WORKER] play() - starting animation');
                _e.exports.play(); _e._clearEx(); _e.playing = true; _refreshTempoCache();
                _drainGotoNetPages();
                break;
            case 'pause':
                _e.exports.pause(); _e._clearEx(); _e.playing = false;
                break;
            case 'stop':
                _e.exports.stop(); _e._clearEx(); _e.playing = false;
                break;

            case 'goToFrame':
                _e.exports.goToFrame(msg.frame); _e._clearEx(); _refreshTempoCache(); _drainGotoNetPages();
                break;
            case 'stepForward':
                _e.exports.stepForward(); _e._clearEx(); _refreshTempoCache(); _drainGotoNetPages();
                break;
            case 'stepBackward':
                _e.exports.stepBackward(); _e._clearEx(); _refreshTempoCache(); _drainGotoNetPages();
                break;
            case 'setTempo':
                _e.exports.setPuppetTempo(msg.tempo | 0); _e._clearEx(); _refreshTempoCache();
                break;

            // --- Input events ---
            case 'mouseMove':
                if (_e && !_e._wasmDead) try { _e.mouseMove(msg.x, msg.y); } catch(ie) {}
                break;
            case 'mouseDown':
                if (_e && !_e._wasmDead) try {
                    _drainMusBeforeInputEvent('mouseDown');
                    _e.mouseDown(msg.x, msg.y, msg.button);
                    _pumpAfterInputEvent('mouseDown');
                } catch(ie) {
                    console.error('[WORKER] mouseDown error:', ie);
                }
                break;
            case 'mouseUp':
                if (_e && !_e._wasmDead) try {
                    _drainMusBeforeInputEvent('mouseUp');
                    _e.mouseUp(msg.x, msg.y, msg.button);
                    _pumpAfterInputEvent('mouseUp');
                } catch(ie) {
                    console.error('[WORKER] mouseUp error:', ie);
                }
                break;
            case 'inspectStageAt':
                if (_e && !_e._wasmDead) try {
                    self.postMessage({
                        type: 'spriteInspect',
                        x: msg.x,
                        y: msg.y,
                        phase: msg.phase || '',
                        json: _e.inspectStageAt(msg.x, msg.y)
                    });
                } catch(ie) {
                    self.postMessage({
                        type: 'spriteInspect',
                        x: msg.x,
                        y: msg.y,
                        phase: msg.phase || '',
                        json: '{"schema":"libreshockwave.stageInspector.v1","error":"'
                            + String(ie && ie.message ? ie.message : ie).replace(/["\\\r\n]/g, ' ')
                            + '"}'
                    });
                }
                break;
            case 'keyDown':
                if (_e && !_e._wasmDead) try {
                    _drainMusBeforeInputEvent('keyDown');
                    _e.keyDown(msg.keyCode, msg.key || '', msg.modifiers);
                    _pumpAfterInputEvent('keyDown');
                } catch(ie) { console.error('[WORKER] keyDown error:', ie); }
                break;
            case 'keyUp':
                if (_e && !_e._wasmDead) try {
                    _drainMusBeforeInputEvent('keyUp');
                    _e.keyUp(msg.keyCode, msg.key || '', msg.modifiers);
                    _pumpAfterInputEvent('keyUp');
                } catch(ie) { console.error('[WORKER] keyUp error:', ie); }
                break;

            case 'paste':
                if (_e && !_e._wasmDead) try {
                    _drainMusBeforeInputEvent('paste');
                    _e.pasteText(msg.text);
                    _pumpAfterInputEvent('paste');
                } catch(e) {}
                break;

            case 'getSelectedText':
                var selText = '';
                if (_e && !_e._wasmDead) try {
                    var len = _e.exports.getSelectedTextLength(); _e._clearEx();
                    if (len > 0) {
                        var addr = _e.exports.getStringBufferAddress(); _e._clearEx();
                        selText = new TextDecoder().decode(new Uint8Array(_e._mem(), addr, len));
                    }
                } catch(e) {}
                self.postMessage({ type: 'selectedText', text: selText });
                break;

            case 'cutSelectedText':
                var cutText = '';
                if (_e && !_e._wasmDead) try {
                    var clen = _e.exports.cutSelectedText(); _e._clearEx();
                    if (clen > 0) {
                        var caddr = _e.exports.getStringBufferAddress(); _e._clearEx();
                        cutText = new TextDecoder().decode(new Uint8Array(_e._mem(), caddr, clen));
                    }
                } catch(e) {}
                self.postMessage({ type: 'cutText', text: cutText });
                break;

            case 'selectAll':
                if (_e && !_e._wasmDead) try {
                    _e.exports.selectAll(); _e._clearEx();
                } catch(e) {}
                break;

            case 'fetchRelayResult': {
                // Main thread completed a cross-origin fetch on our behalf
                var relay = _fetchRelayMap[msg.relayId];
                if (!relay) break;
                delete _fetchRelayMap[msg.relayId];
                if (msg.error) {
                    _fetchOrderLog('relay-error url=' + relay.url
                        + ' status=' + (msg.status || 0));
                    console.log('[WORKER] relay ERR: ' + relay.url + ' status=' + (msg.status || 0));
                    if (relay.reject) {
                        relay.reject({ status: msg.status || 0 });
                    } else {
                        _fetchQueue.push({ taskId: relay.taskId, error: msg.status || 0, fetchSeq: 0 });
                    }
                } else {
                    _fetchOrderLog('relay-complete url=' + relay.url
                        + ' bytes=' + msg.data.byteLength);
                    console.log('[WORKER] relay OK: ' + relay.url + ' (' + msg.data.byteLength + ' bytes)');
                    if (relay.resolve) {
                        relay.resolve(msg.data);
                    } else {
                        _fetchQueue.push({ taskId: relay.taskId, data: msg.data, url: relay.url, fetchSeq: 0 });
                    }
                }
                break;
            }

            case 'tick': {
                if (_isTicking) {
                    // ALWAYS respond so main thread's _waitFor('frame') doesn't hang
                    self.postMessage({
                        type: 'frame', playing: true,
                        enginePlaying: _e ? _e.playing : false,
                        tempo: _e ? _e._lastTempo : 15,
                        lastFrame: _e ? _e._lastFrame : 0,
                        frameCount: _e ? _e._lastFrameCount : 0,
                        rendered: false,
                        networkBusy: true,
                        rgba: null, width: 0, height: 0, spriteCount: 0
                    });
                    return;
                }
                _isTicking = true;
                try {
                    var stillPlaying = true;
                    var frame = null;

                    // Phase 1: deliver completed network results from previous ticks.
                    // This runs BEFORE tick() so netDone() returns true for finished fetches.
                    // Cast files are automatically detected and cached in CastLibManager
                    // when delivered, so they're available when Lingo sets castLib.fileName.
                    if (!_e._wasmDead) {
                        try {
                            _e.deliverQueuedResults({
                                maxResults: msg.skipRender
                                    ? SKIP_RENDER_FETCH_DELIVERY_MAX
                                    : VISIBLE_FETCH_DELIVERY_MAX,
                                budgetMs: msg.skipRender
                                    ? SKIP_RENDER_FETCH_DELIVERY_BUDGET_MS
                                    : VISIBLE_FETCH_DELIVERY_BUDGET_MS
                            });
                            _e.deliverJpegDecodeResults(msg.skipRender
                                    ? SKIP_RENDER_JPEG_DELIVERY_MAX
                                    : VISIBLE_JPEG_DELIVERY_MAX);
                        } catch (deliverErr) {
                            console.error(_formatWorkerError('[WORKER] deliver error', deliverErr));
                        }
                    }

                    // Phase 1.8: deliver pending Multiuser Xtra events (WebSocket)
                    if (!_e._wasmDead) {
                        try { _e.deliverMusEvents(); }
                        catch (musErr) { console.error(_formatWorkerError('[WORKER] MUS deliver error', musErr)); }
                    }
                    var autoPausedOnScriptError = _consumeScriptErrorPauseRequest();

                    // Phase 2: advance WASM by one Lingo frame
                    if (autoPausedOnScriptError) {
                        stillPlaying = false;
                    } else if (_e._wasmDead) {
                        console.error('[WORKER] WASM instance is dead, skipping tick');
                        stillPlaying = false;
                    } else {
                        try {
                            stillPlaying = _e.tick();
                            if (_consumeScriptErrorPauseRequest()) {
                                autoPausedOnScriptError = true;
                                stillPlaying = false;
                            }
                        } catch (tickErr) {
                            console.error(_formatWorkerError('[WORKER] tick() error', tickErr));
                            // Check if WASM is still alive
                            try { _e.exports.getStringBufferAddress(); _e._clearEx(); } catch(e) {
                                console.error('[WORKER] WASM instance dead after tick error');
                                _e._wasmDead = true;
                            }
                        }
                    }

                    // Phase 3: fire new network requests (non-blocking).
                    // Results are queued asynchronously and delivered at the start of
                    // the next tick. This decouples network I/O from frame execution,
                    // preventing deadlocks when Lingo polls netDone() in update loops.
                    if (!autoPausedOnScriptError) {
                        try {
                            _e.pumpNetworkFire();
                        } catch (netErr) {
                            console.error(_formatWorkerError('[WORKER] pump error', netErr));
                        }
                    }

                    // Phase 3.5: pump Multiuser Xtra WebSocket requests
                    if (!autoPausedOnScriptError) {
                        try { _e.pumpMusRequests(); }
                        catch (musErr2) { console.error(_formatWorkerError('[WORKER] MUS pump error', musErr2)); }
                    }

                    // Phase 3.6: pump audio commands and send to main thread
                    try { _e.pumpAudioCommands(); }
                    catch (audioErr) { /* silent */ }

                    // Phase 3.7: decode embedded JPEG/ediM bitmaps in the browser.
                    try { _e.pumpJpegDecodeRequests(); }
                    catch (jpegErr) { console.error('[WORKER] JPEG pump error: ' + jpegErr); }

                    // Keep frame metadata current even when this tick skips visible rendering.
                    try {
                        _e._lastFrame      = _e.exports.getCurrentFrame();
                        _e._lastFrameCount = _e.exports.getFrameCount();
                        _e._lastTempo      = _e.exports.getTempo();
                    } catch (ignore) {}

                    // Phase 4: render only when the main-thread scheduler asks
                    // for a visible frame. Ticks without rendering still advance
                    // Lingo, network delivery, audio, and Xtra callbacks.
                    if (!msg.skipRender) {
                        try {
                            frame = _e.renderFrame();
                            _e.pumpJpegDecodeRequests();
                        } catch (renderErr) {
                            console.error(_formatWorkerError('[WORKER] render() error', renderErr));
                        }
                    }

                    var spriteCount = 0;
                    if (!msg.skipRender) {
                        try { spriteCount = _e.exports.getSpriteCount(); _e._clearEx(); } catch(e4) {}
                    }

                    var cursorType = 0;
                    if (!msg.skipRender) {
                        try { cursorType = _e.exports.getCursorType(); _e._clearEx(); } catch(e5) {}
                    }

                    var cursorBitmap = null;
                    if (!msg.skipRender) {
                        try {
                            var hasCursor = _e.exports.updateCursorBitmap(); _e._clearEx();
                            if (hasCursor) {
                                var cw = _e.exports.getCursorBitmapWidth();
                                var ch = _e.exports.getCursorBitmapHeight();
                                var cLen = _e.exports.getCursorBitmapLength();
                                var cAddr = _e.exports.getCursorBitmapAddress();
                                if (cAddr && cLen > 0) {
                                    var cRgba = new Uint8ClampedArray(cLen);
                                    cRgba.set(new Uint8ClampedArray(_e._mem(), cAddr, cLen));
                                    _e._clearEx();
                                    cursorBitmap = {
                                        rgba: cRgba,
                                        w: cw,
                                        h: ch,
                                        regX: _e.exports.getCursorRegPointX(),
                                        regY: _e.exports.getCursorRegPointY()
                                    };
                                }
                            }
                        } catch(e6) {}
                    }

                    var caretInfo = null;
                    var selectionRects = null;
                    if (!msg.skipRender) {
                        try {
                            if (_e.exports.isCaretVisible()) {
                                caretInfo = { x: _e.exports.getCaretX(), y: _e.exports.getCaretY(),
                                              h: _e.exports.getCaretHeight() };
                            }
                            _e._clearEx();
                            var selCount = _e.exports.getSelectionRectCount(); _e._clearEx();
                            if (selCount > 0) {
                                selectionRects = [];
                                for (var si = 0; si < selCount; si++) {
                                    selectionRects.push({
                                        x: _e.exports.getSelectionRectX(si),
                                        y: _e.exports.getSelectionRectY(si),
                                        w: _e.exports.getSelectionRectW(si),
                                        h: _e.exports.getSelectionRectH(si)
                                    });
                                }
                                _e._clearEx();
                            }
                        } catch(e7) {}
                    }

                    var debugLog = null;
                    if (_debugLogsEnabled) {
                        try {
                            var logLen = _e.exports.getDebugLog(); _e._clearEx();
                            if (logLen > 0) {
                                var strAddr = _e.exports.getStringBufferAddress(); _e._clearEx();
                                debugLog = _e._readString(strAddr, logLen);
                            }
                        } catch (logErr) {}
                    }

                    _relayLastError();
                    _drainGotoNetPages();

                    var pendingFetches = 0;
                    try { pendingFetches = _e.exports.getPendingFetchCount(); _e._clearEx(); } catch(e8) {}
                    var networkBusy = pendingFetches > 0
                        || _regularFetchInFlight > 0
                        || _hasRegularQueuedFetchResult()
                        || _jpegDecodeQueue.length > 0
                        || Object.keys(_jpegDecodeInFlight).length > 0;

                    var sharedFrame = false;
                    var sharedSeq = 0;
                    if (frame && _sharedFrameBytes && frame.rgba.length <= _sharedFrameCapacity) {
                        _sharedFrameBytes.set(frame.rgba);
                        sharedSeq = ++_sharedFrameSeq;
                        Atomics.store(_sharedFrameControl, 0, sharedSeq);
                        Atomics.store(_sharedFrameControl, 1, frame.rgba.length);
                        Atomics.store(_sharedFrameControl, 2, frame.w);
                        Atomics.store(_sharedFrameControl, 3, frame.h);
                        Atomics.notify(_sharedFrameControl, 0, 1);
                        sharedFrame = true;
                    }

                    // Always send a frame response to unblock main thread
                    var transferList = [];
                    if (frame && !sharedFrame) transferList.push(frame.rgba.buffer);
                    if (cursorBitmap) transferList.push(cursorBitmap.rgba.buffer);
                    self.postMessage({
                        type:          'frame',
                        playing:       stillPlaying,
                        enginePlaying: _e.playing,
                        tempo:         _e._lastTempo,
                        lastFrame:     _e._lastFrame,
                        frameCount:    _e._lastFrameCount,
                        rendered:      !!frame,
                        rgba:          frame && !sharedFrame ? frame.rgba : null,
                        sharedFrame:   sharedFrame,
                        sharedSeq:     sharedSeq,
                        width:         frame ? frame.w : 0,
                        height:        frame ? frame.h : 0,
                        spriteCount:   spriteCount,
                        cursorType:    cursorType,
                        cursorBitmap:  cursorBitmap,
                        caretInfo:     caretInfo,
                        selectionRects: selectionRects,
                        networkBusy:   networkBusy,
                        debugLog:      debugLog
                    }, transferList);

                } finally {
                    _isTicking = false;
                }
                break;
            }

            case 'audioStopped': {
                // Main thread notifies that a sound channel finished playing
                try {
                    _e.exports.audioNotifyStopped(msg.channel); _e._clearEx();
                } catch(e) {}
                break;
            }

            case 'triggerTestError': {
                var result = 0;
                try {
                    result = _e.exports.triggerTestError(); _e._clearEx();
                } catch (err) {}
                // Re-render so the error dialog appears on screen
                var frame = null;
                try { frame = _e.renderFrame(); } catch (re) {}
                self.postMessage({
                    type: 'frame',
                    playing: true,
                    enginePlaying: _e.playing,
                    tempo: _e._lastTempo,
                    lastFrame: _e._lastFrame,
                    frameCount: _e._lastFrameCount,
                    rgba: frame ? frame.rgba : null,
                    width: frame ? frame.w : 0,
                    height: frame ? frame.h : 0,
                    spriteCount: 0
                }, frame ? [frame.rgba.buffer] : []);
                break;
            }

            case 'getCallStack': {
                var stackStr = '';
                try {
                    var len = _e.exports.getCallStack(); _e._clearEx();
                    if (len > 0) {
                        var addr = _e.exports.getStringBufferAddress(); _e._clearEx();
                        stackStr = _e._readString(addr, len);
                    }
                } catch (csErr) {}
                self.postMessage({ type: 'callStack', callStack: stackStr });
                break;
            }

            case 'getWindowSpriteDiagnostics': {
                var diagStr = '';
                try {
                    var dlen = _e.exports.getWindowSpriteDiagnostics(); _e._clearEx();
                    if (dlen > 0) {
                        var daddr = _e.exports.getStringBufferAddress(); _e._clearEx();
                        diagStr = _e._readString(daddr, dlen);
                    }
                } catch (diagErr) {}
                self.postMessage({ type: 'windowSpriteDiagnostics', diagnostics: diagStr });
                break;
            }

            default:
                break;
        }
    } catch (err) {
                self.postMessage({ type: 'error', msg: String(err) });
    }
};
