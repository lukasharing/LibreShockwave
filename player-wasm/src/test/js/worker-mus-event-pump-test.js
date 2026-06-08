const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const workerPath = path.join(__dirname, '../../main/resources/web/shockwave-worker.js');
const wasmEntryPath = path.join(__dirname, '../../main/java/com/libreshockwave/player/wasm/WasmEntry.java');

test('MUS websocket events are staged for the Director tick pump', () => {
    const worker = fs.readFileSync(workerPath, 'utf8');

    assert.doesNotMatch(worker, /_musPumpImmediate/);
    assert.doesNotMatch(worker, /processXtraCallbacks/);
    assert.doesNotMatch(worker, /_flushDeferredMusImmediate/);
    assert.match(worker, /Phase 1\.8: deliver pending Multiuser Xtra events/);
});

test('MUS request pump drains stable batches without reentrant callbacks', () => {
    const worker = fs.readFileSync(workerPath, 'utf8');

    assert.match(worker, /pump requests skipped reentrant/);
    assert.match(worker, /maxRounds = 8/);
    assert.match(worker, /getMusPendingRequestId/);
    assert.match(worker, /pump requests stopped with pending=/);
});

test('WASM input exports dispatch Director events before the input MUS pump runs', () => {
    const worker = fs.readFileSync(workerPath, 'utf8');
    const wasmEntry = fs.readFileSync(wasmEntryPath, 'utf8');

    assert.match(worker, /_e\.mouseUp\(msg\.x, msg\.y, msg\.button\);\s+_pumpAfterInputEvent\('mouseUp'\);/);
    assert.match(wasmEntry, /onMouseUp\(stageX, stageY, button == 2\);\s+wasmPlayer\.processInputEvents\(\);/);
    assert.match(wasmEntry, /onMouseDown\(stageX, stageY, button == 2\);\s+wasmPlayer\.processInputEvents\(\);/);
});

test('startup and skip-render ticks drain larger async batches without changing visible-frame budget', () => {
    const worker = fs.readFileSync(workerPath, 'utf8');

    assert.match(worker, /var STARTUP_FETCH_DELIVERY_MAX = 32;/);
    assert.match(worker, /var STARTUP_FETCH_DELIVERY_BUDGET_MS = 32;/);
    assert.match(worker, /var SKIP_RENDER_FETCH_DELIVERY_MAX = 24;/);
    assert.match(worker, /var SKIP_RENDER_FETCH_DELIVERY_BUDGET_MS = 24;/);
    assert.match(worker, /var VISIBLE_FETCH_DELIVERY_MAX = 8;/);
    assert.match(worker, /maxResults: msg\.skipRender\s+\? SKIP_RENDER_FETCH_DELIVERY_MAX\s+: VISIBLE_FETCH_DELIVERY_MAX/);
    assert.match(worker, /budgetMs: msg\.skipRender\s+\? SKIP_RENDER_FETCH_DELIVERY_BUDGET_MS\s+: VISIBLE_FETCH_DELIVERY_BUDGET_MS/);
});

test('external cast prefetch is delivered through the invisible raw-cache path', () => {
    const worker = fs.readFileSync(workerPath, 'utf8');

    assert.match(worker, /prefetchExternalCasts = function/);
    assert.match(worker, /var IDLE_PREFETCH_FIRE_MAX = 1;/);
    assert.match(worker, /isPendingFetchInternalPrefetch/);
    assert.match(worker, /_deferredPrefetchRequests = prefetchReqs\.concat\(_deferredPrefetchRequests\)/);
    assert.match(worker, /regularReqs\.length > 0 \|\| _regularFetchInFlight > 0 \|\| _hasRegularQueuedFetchResult\(\)/);
    assert.match(worker, /var networkBusy = pendingFetches > 0\s+\|\| _regularFetchInFlight > 0\s+\|\| _hasRegularQueuedFetchResult\(\)/);
    assert.match(worker, /_deliverPrefetchResult\(item\.requestedUrl \|\| item\.url, item\.data, item\.url\)/);
    assert.match(worker, /this\.exports\.deliverPrefetchResult\(requestedBytes\.length, completedBytes\.length, bytes\.length\)/);
    assert.match(worker, /var prefetched = _e\.prefetchExternalCasts\(\);/);
    assert.doesNotMatch(worker, /prefetchFired = prefetched > 0 \? _e\.pumpNetworkFire\(\) : 0/);
    assert.match(worker, /self\.postMessage\(\{ type: 'castsDone' \}\);/);
});
