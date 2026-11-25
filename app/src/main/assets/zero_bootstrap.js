(function() {
  const BRIDGED = new Set([
    "name","tag","sceneGroup","persistent","x","y","z","vx","vy","vz","width","height","rotation",
    "tiltX","scaleX","scaleY","parallaxX","parallaxY","atlas","atlasFrame","normalMap","normalStrength",
    "specular","sliceLeft","sliceRight","sliceTop","sliceBottom","clipChildren","clipX","clipY","clipW",
    "clipH","slopeType","colOffsetX","colOffsetY","colWidth","colHeight","colLayer","colMask","bodyMode",
    "gravity","fallMultiplier","maxJumps","isGrounded","isHitbox","isHurtbox","damage","invulnTimer",
    "r","g","b","a","visible","text","currentClip","lightRadius","lightIntensity"
  ]);
  const READONLY = new Set(["name","sceneGroup","isGrounded","currentClip"]);
  const __zNodes = Object.create(null);
  const __zHooks = Object.create(null);
  const __zModules = Object.create(null);
  const __zTables = Object.create(null);
  const __zGlobals = Object.create(null);
  const __zEvents = Object.create(null);
  const __zAdCbs = Object.create(null);
  let __zAdCbSeq = 1;

  const nid = (n) => (n && typeof n === "object" && n.__zid) ? n.__zid : -1;
  const arrJson = (v) => JSON.stringify(Array.isArray(v) ? v.map(String) : (v != null ? [String(v)] : []));
  const fmtArgs = (a) => Array.from(a).map(x => (typeof x === "object" && x !== null) ? (() => { try { return JSON.stringify(x); } catch(_) { return String(x); } })() : String(x)).join(" ");

  globalThis.console = {
    log: (...a) => __zLog("[LOG] " + fmtArgs(a)),
    warn: (...a) => __zLog("[WARN] " + fmtArgs(a)),
    error: (...a) => __zLog("[ERR] " + fmtArgs(a))
  };

  globalThis.__zOnAdResult = function(cbId, ok) {
    const fn = __zAdCbs[cbId];
    delete __zAdCbs[cbId];
    if (typeof fn === "function") {
      try { fn(!!ok); } catch (e) { __zLog("[ADS 回调错误] " + (e && e.message ? e.message : e)); }
    }
  };

  globalThis.ZeroAds = {
    isReady: (type) => !!__zAdReady(String(type || "reward")),
    showInterstitial: (cb) => {
      let id = -1;
      if (typeof cb === "function") { id = __zAdCbSeq++; __zAdCbs[id] = cb; }
      __zAdShowInter(id);
    },
    showReward: (cb) => {
      let id = -1;
      if (typeof cb === "function") { id = __zAdCbSeq++; __zAdCbs[id] = cb; }
      __zAdShowReward(id);
    },
    showRewarded: (cb) => globalThis.ZeroAds.showReward(cb)
  };

  const nodeProto = {
    createSkinnedMesh(c, r) { return __zNodeCall(this.__zid, "createSkinnedMesh", c, r); },
    bindBones(bones, smooth) { return __zNodeCall(this.__zid, "bindBones", arrJson(bones), smooth); },
    setMeshWave(ax, ay, freq, spd) { return __zNodeCall(this.__zid, "setMeshWave", ax, ay, freq, spd); },
    removeSkinnedMesh() { return __zNodeCall(this.__zid, "removeSkinnedMesh"); },
    attachCloth(cfg) { return __zNodeCall(this.__zid, "attachCloth", JSON.stringify(cfg || {})); },
    setNormalMap(p, s, sp) { return __zNodeCall(this.__zid, "setNormalMap", p, s, sp); },
    setAnimFloat(k, v) { return __zNodeCall(this.__zid, "setAnimFloat", k, v); },
    setAnimBool(k, v) { return __zNodeCall(this.__zid, "setAnimBool", k, v); },
    triggerAnim(k) { return __zNodeCall(this.__zid, "triggerAnim", k); },
    setAnimSpeed(s) { return __zNodeCall(this.__zid, "setAnimSpeed", s); },
    getAnimState() { return __zNodeCall(this.__zid, "getAnimState"); },
    isAnimCancelable() { return __zNodeCall(this.__zid, "isAnimCancelable"); },
    addAnimTransition(a,b,c,d,e,f,g,h) { return __zNodeCall(this.__zid, "addAnimTransition", a,b,c,d,e,f,g,h); },
    registerProClip(clip) { return __zNodeCall(this.__zid, "registerProClip", typeof clip === "string" ? clip : JSON.stringify(clip || {})); },
    setAtlas(a, f) { return __zNodeCall(this.__zid, "setAtlas", a, f); },
    setNineSlice(l, r, t, b) { return __zNodeCall(this.__zid, "setNineSlice", l, r, t, b); },
    setClipRect(x, y, w, h) { return __zNodeCall(this.__zid, "setClipRect", x, y, w, h); },
    jump(f) { return __zNodeCall(this.__zid, "jump", f); },
    dropThrough(d) { return __zNodeCall(this.__zid, "dropThrough", d); },
    bounce(vx, vy) { return __zNodeCall(this.__zid, "bounce", vx, vy); },
    play(c, f) { return __zNodeCall(this.__zid, "play", c, f); },
    playAnim(s, e, fps, loop) { return __zNodeCall(this.__zid, "playAnim", s, e, fps, loop); },
    stopAnim() { return __zNodeCall(this.__zid, "stopAnim"); },
    flash(d) { return __zNodeCall(this.__zid, "flash", d); },
    emitBurst(c) { return __zNodeCall(this.__zid, "emitBurst", c); }
  };

  function createNodeProxy(id) {
    if (__zNodes[id]) return __zNodes[id];
    const raw = Object.create(nodeProto);
    raw.__zid = id;
    const proxy = new Proxy(raw, {
      get(target, prop, receiver) {
        if (typeof prop === "string" && BRIDGED.has(prop)) return __zNodeGet(id, prop);
        return Reflect.get(target, prop, receiver);
      },
      set(target, prop, value, receiver) {
        if (typeof prop === "string" && BRIDGED.has(prop)) {
          if (!READONLY.has(prop)) __zNodeSet(id, prop, value);
          return true;
        }
        return Reflect.set(target, prop, value, receiver);
      },
      has(target, prop) {
        return (typeof prop === "string" && BRIDGED.has(prop)) || Reflect.has(target, prop);
      }
    });
    __zNodes[id] = proxy;
    return proxy;
  }

  globalThis.require = function(rawPath) {
    const resJson = __zReadScript(String(rawPath || ""));
    if (!resJson) return null;
    const pair = JSON.parse(resJson);
    const normPath = pair[0], code = pair[1];
    if (Object.prototype.hasOwnProperty.call(__zModules, normPath)) return __zModules[normPath];
    const mod = { exports: {} };
    __zModules[normPath] = mod.exports;
    try {
      const fn = new Function("module", "exports", "require", code);
      fn(mod, mod.exports, globalThis.require);
      __zModules[normPath] = mod.exports;
      return mod.exports;
    } catch (e) {
      delete __zModules[normPath];
      __zLog("[MODULE 错误 @ " + normPath + "] " + (e && e.message ? e.message : e));
      return null;
    }
  };

  globalThis.Engine = {
    drawInstanced: (tex, x, y, w, h, rot, r, g, b, a, additive, z) => __zEngineCall("drawInstanced", tex || "", x, y, w, h, rot || 0, r ?? 1, g ?? 1, b ?? 1, a ?? 1, !!additive, z || 2),
    log: (...a) => __zLog("[LOG] " + fmtArgs(a)),
    unloadUnusedTextures: () => __zEngineCall("unloadUnusedTextures"),
    clearModuleCache: () => { for (const k in __zModules) delete __zModules[k]; },
    setSdfShadow: (e, s, m) => __zEngineCall("setSdfShadow", e, s, m),
    setGI: (cfg) => __zEngineCall("setGI", typeof cfg === "boolean" ? String(cfg) : JSON.stringify(cfg || {})),
    createCloth: (cfg) => __zEngineCall("createCloth", JSON.stringify(cfg || {})),
    removeCloth: (id) => __zEngineCall("removeCloth", id),
    setGlobalWind: (wx, wy) => __zEngineCall("setGlobalWind", wx, wy),
    solveIK: (a,b,c,d,e,f,g) => __zEngineCall("solveIK", a,b,c,d,e,f,g),
    registerIK: (a,b,c,d,e,f) => __zEngineCall("registerIK", a,b,c,d,e,f),
    setIKTarget: (a,b,c,d) => __zEngineCall("setIKTarget", a,b,c,d),
    removeIK: (id) => __zEngineCall("removeIK", id),
    createSkinnedMesh: (n, c, r) => __zEngineCall("createSkinnedMesh", n, c, r),
    bindBones: (n, b, s) => __zEngineCall("bindBones", n, arrJson(b), s),
    setMeshWave: (n, ax, ay, f, s) => __zEngineCall("setMeshWave", n, ax, ay, f, s),
    removeSkinnedMesh: (n) => __zEngineCall("removeSkinnedMesh", n),
    loadAtlas: (n) => { const s = __zEngineCall("loadAtlas", n); return s ? JSON.parse(s) : null; },
    setFlag: (k, v) => __zEngineCall("setFlag", k, arguments.length >= 2 ? v : true),
    getFlag: (k, def) => __zEngineCall("getFlag", k, def),
    hasFlag: (k) => __zEngineCall("hasFlag", k),
    clearFlag: (k) => __zEngineCall("clearFlag", k),
    clearAllFlags: () => __zEngineCall("clearAllFlags"),
    getAllFlags: () => JSON.parse(__zEngineCall("getAllFlags") || "{}"),
    saveWorldFlags: (s) => __zEngineCall("saveWorldFlags", s),
    loadWorldFlags: (s) => __zEngineCall("loadWorldFlags", s),
    loadTable: (name) => {
      const key = String(name || "");
      if (Object.prototype.hasOwnProperty.call(__zTables, key)) return __zTables[key];
      const raw = __zEngineCall("readTable", key);
      if (!raw) return null;
      try { const p = JSON.parse(raw); __zTables[key] = p; return p; }
      catch (e) { __zLog("[DATA 解析错误 @ " + key + "] " + e.message); return null; }
    },
    reloadTables: () => { for (const k in __zTables) delete __zTables[k]; },
    setLanguage: (l) => __zEngineCall("setLanguage", l),
    getLanguage: () => __zEngineCall("getLanguage"),
    setI18nDict: (l, obj) => __zEngineCall("setI18nDict", l, JSON.stringify(obj || {})),
    t: (k, fb) => __zEngineCall("t", k, fb != null ? String(fb) : ""),
    setDebugDraw: (e) => __zEngineCall("setDebugDraw", !!e),
    isDebugDraw: () => __zEngineCall("isDebugDraw"),
    setPostFX: (cfg) => __zEngineCall("setPostFX", JSON.stringify(cfg || {})),
    setFade: (tg, d) => __zEngineCall("setFade", tg, d),
    fadeToScene: (s, d) => __zEngineCall("fadeToScene", s, d),
    preloadScene: (s) => __zEngineCall("preloadScene", s),
    loadSceneAdditive: (s, ox, oy) => __zEngineCall("loadSceneAdditive", s, ox, oy),
    unloadScene: (s) => __zEngineCall("unloadScene", s),
    clearSceneCache: () => __zEngineCall("clearSceneCache"),
    getStats: () => {
      const s = JSON.parse(__zEngineCall("getStats") || "{}");
      s.modules = Object.keys(__zModules).length;
      s.tables = Object.keys(__zTables).length;
      return s;
    },
    loadScene: (s) => __zEngineCall("loadScene", s),
    setGlobal: (k, v) => { if (k) __zGlobals[String(k)] = v; },
    getGlobal: (k, def) => Object.prototype.hasOwnProperty.call(__zGlobals, String(k)) ? __zGlobals[String(k)] : def,
    saveDisk: (slot, data) => __zEngineCall("saveDisk", slot, typeof data === "string" ? data : JSON.stringify(data == null ? {} : data)),
    loadDisk: (slot) => { const r = __zEngineCall("loadDisk", slot); return r ? JSON.parse(r) : null; },
    deleteDisk: (slot) => __zEngineCall("deleteDisk", slot),
    raycast: (x1, y1, x2, y2, tag, mask) => {
      const r = JSON.parse(__zEngineCall("raycast", x1, y1, x2, y2, tag || "", mask == null ? 0xFFFF : mask));
      return { hit: r.hit, x: r.x, y: r.y, distance: r.distance, node: r.nodeId > 0 ? (__zNodes[r.nodeId] || null) : null };
    },
    hitstop: (d) => __zEngineCall("hitstop", d),
    setTimeScale: (s) => __zEngineCall("setTimeScale", s),
    setCameraMode: (m) => __zEngineCall("setCameraMode", m),
    setCameraTilt: (p, d, f) => __zEngineCall("setCameraTilt", p, d, f),
    setCameraTarget: (n) => __zEngineCall("setCameraTarget", nid(n)),
    setCameraDeadzone: (x, y) => __zEngineCall("setCameraDeadzone", x, y),
    setCameraLookAhead: (x, y) => __zEngineCall("setCameraLookAhead", x, y),
    setCameraBounds: (a,b,c,d) => __zEngineCall("setCameraBounds", a,b,c,d),
    clearCameraBounds: () => __zEngineCall("clearCameraBounds"),
    getCamera: () => JSON.parse(__zEngineCall("getCamera") || "{}"),
    setAmbientLight: (r, g, b) => __zEngineCall("setAmbientLight", r, g, b),
    shake: (i, d) => __zEngineCall("shake", i, d),
    tween: (n, p, tv, d, e) => __zEngineCall("tween", nid(n), p, tv, d, e),
    playTone: (f, d) => __zEngineCall("playTone", f, d),
    playBGM: (f, l, v) => __zEngineCall("playBGM", f, l, v),
    crossfadeBGM: (f, d, l, v) => __zEngineCall("crossfadeBGM", f, d, l, v),
    stopBGM: (d) => __zEngineCall("stopBGM", d),
    playSFX: (f, v, p1, p2) => __zEngineCall("playSFX", f, v, p1, p2),
    setVolume: (c, v) => __zEngineCall("setVolume", c, v),
    getVolume: (c) => __zEngineCall("getVolume", c),
    moveAndSlide: (n, dt) => __zEngineCall("moveAndSlide", nid(n), dt),
    distance: (a, b) => __zEngineCall("distance", nid(a), nid(b)),
    angleTo: (a, b) => __zEngineCall("angleTo", nid(a), nid(b)),
    spawn: (cfg, parent) => { const id = __zEngineCall("spawn", JSON.stringify(cfg || {}), parent || ""); return id > 0 ? createNodeProxy(id) : null; },
    instantiate: (s, x, y, z) => { const id = __zEngineCall("instantiate", s, x, y, z); return id > 0 ? createNodeProxy(id) : null; },
    destroy: (n) => __zEngineCall("destroy", nid(n)),
    findNode: (name) => { const id = __zEngineCall("findNode", name); return id > 0 ? (__zNodes[id] || null) : null; },
    findNodesByTag: (tag) => JSON.parse(__zEngineCall("findNodesByTag", tag) || "[]").map(id => __zNodes[id]).filter(Boolean),
    on: (ev, fn) => { if (ev && typeof fn === "function") (__zEvents[ev] || (__zEvents[ev] = [])).push(fn); },
    emit: (ev, data) => { const list = __zEvents[ev]; if (list) list.slice().forEach(fn => { try { fn(data); } catch(_) {} }); }
  };

  globalThis.Input = {
    get isTouching() { return __zInputGet("isTouching"); },
    get touchCount() { return __zInputGet("touchCount"); },
    get screenX() { return __zInputGet("screenX"); },
    get touchX() { return __zInputGet("touchX"); },
    get screenY() { return __zInputGet("screenY"); },
    get touchY() { return __zInputGet("touchY"); },
    get worldX() { return __zInputGet("worldX"); },
    get worldY() { return __zInputGet("worldY"); },
    isActionPressed: (a) => __zInputCall("isActionPressed", a),
    isActionJustPressed: (a) => __zInputCall("isActionJustPressed", a),
    isActionJustReleased: (a) => __zInputCall("isActionJustReleased", a),
    getAxis: (a, b) => __zInputCall("getAxis", a, b),
    bindAction: (a, keys) => __zInputCall("bindAction", a, arrJson(keys)),
    setVirtualAction: (a, v) => __zInputCall("setVirtualAction", a, !!v),
    bindTouchZone: (a, x, y, w, h) => __zInputCall("bindTouchZone", a, x, y, w, h),
    clearTouchZones: () => __zInputCall("clearTouchZones")
  };

  globalThis.__zBindNode = function(id, name, code) {
    const self = createNodeProxy(id);
    const hooks = {};
    if (code && code.trim().length > 0) {
      try {
        const wrapped = code + "\n;return {" +
          "onReady: typeof onReady === 'function' ? onReady : null," +
          "onProcess: typeof onProcess === 'function' ? onProcess : null," +
          "onCollision: typeof onCollision === 'function' ? onCollision : null," +
          "onTriggerEnter: typeof onTriggerEnter === 'function' ? onTriggerEnter : null," +
          "onHit: typeof onHit === 'function' ? onHit : null," +
          "onHurt: typeof onHurt === 'function' ? onHurt : null," +
          "onInput: typeof onInput === 'function' ? onInput : null," +
          "onAnimFinished: typeof onAnimFinished === 'function' ? onAnimFinished : null," +
          "onFrameEvent: typeof onFrameEvent === 'function' ? onFrameEvent : null" +
        "};";
        const factory = new Function("self", wrapped);
        const res = factory.call(self, self);
        if (res) { for (const k in res) if (res[k]) hooks[k] = res[k]; }
      } catch (e) {
        __zLog("[JS 编译错误 @ " + name + "] " + (e && e.message ? e.message : e));
      }
    }
    __zHooks[id] = hooks;
  };

  globalThis.__zDestroyNode = function(id) {
    delete __zHooks[id];
    delete __zNodes[id];
  };

  globalThis.__zCallHook = function(id, fnName, mode, a1, a2, a3, a4) {
    const h = __zHooks[id];
    if (!h) return;
    const fn = h[fnName];
    if (!fn) return;
    const self = __zNodes[id];
    try {
      if (mode === "node") fn.call(self, __zNodes[a1] || null);
      else if (mode === "hurt") fn.call(self, __zNodes[a1] || null, a2, a3, a4);
      else if (mode === "two") fn.call(self, a1, a2);
      else if (a1 !== undefined && a1 !== null) fn.call(self, a1);
      else fn.call(self);
    } catch (e) {
      __zLog("[JS 错误 @ " + (self ? self.name : id) + "." + fnName + "] " + (e && e.message ? e.message : e));
    }
  };

  globalThis.__zDispatchTouch = function(type, sx, sy, wx, wy) {
    const ev = { type, screenX: sx, screenY: sy, worldX: wx, worldY: wy };
    const ids = Object.keys(__zHooks);
    for (let i = 0; i < ids.length; i++) {
      const id = ids[i], h = __zHooks[id];
      if (h && h.onInput) {
        const self = __zNodes[id];
        try { h.onInput.call(self, ev); } catch (e) {
          __zLog("[JS 错误 @ " + (self ? self.name : id) + ".onInput] " + (e && e.message ? e.message : e));
        }
      }
    }
  };
})();