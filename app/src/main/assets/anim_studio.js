// ==================== ZeroEngine P7 商业级可视化动画工作台 (Animation Studio) ====================
(function () {
  let studioOpen = false;
  let studioCanvas = null;
  let studioCtx = null;
  let clipIdx = 0;
  let frameIdx = 0;
  let boxIdx = -1;
  let subMode = 'timeline'; // 'timeline' | 'fsm'
  let toolMode = 'box';     // 'offset' | 'box'
  let onionSkin = true;
  let playSpeed = 1.0;
  let isPlaying = false;
  let rafId = 0;
  let lastTs = 0;
  let frameAccumMs = 0;
  let viewScale = 1.6;

  // 触控拖拽状态
  let dragTarget = null; // 'offset' | 'box_move' | 'box_resize'
  let dragStart = { x: 0, y: 0 };
  let dragOrig = null;

  function ensureProData(n) {
    if (!Array.isArray(n.proClips)) n.proClips = [];
    if (!Array.isArray(n.fsmTransitions)) n.fsmTransitions = [];
    if (n.proClips.length === 0) {
      n.proClips.push({
        name: 'attack',
        loop: true,
        speed: 1.0,
        frames: [
          { frameIndex: 0, atlasFrame: '', durationMs: 110, offsetX: -12, offsetY: 0, event: 'windup', cancelable: false, boxes: [] },
          {
            frameIndex: 1, atlasFrame: '', durationMs: 60, offsetX: 18, offsetY: 0, event: 'slash', cancelable: false,
            boxes: [{ id: 'slash_1', type: 'hitbox', offsetX: 70, offsetY: 0, width: 110, height: 85, damage: 25, knockbackX: 380, knockbackY: -220, hitstop: 0.06 }]
          },
          { frameIndex: 2, atlasFrame: '', durationMs: 90, offsetX: 4, offsetY: 0, event: 'recover', cancelable: true, boxes: [] }
        ],
        boneTracks: []
      });
    }
    return n;
  }

  function getCurNode() {
    if (typeof selectedIndex === 'undefined' || selectedIndex < 0 || !nodes[selectedIndex]) return null;
    return ensureProData(nodes[selectedIndex]);
  }

  function getCurClip() {
    const n = getCurNode();
    if (!n || n.proClips.length === 0) return null;
    clipIdx = Math.max(0, Math.min(clipIdx, n.proClips.length - 1));
    return n.proClips[clipIdx];
  }

  function getCurFrame() {
    const c = getCurClip();
    if (!c || !c.frames || c.frames.length === 0) return null;
    frameIdx = Math.max(0, Math.min(frameIdx, c.frames.length - 1));
    return c.frames[frameIdx];
  }

  function injectStudioDOM() {
    if (document.getElementById('animStudioOverlay')) return;
    const style = document.createElement('style');
    style.textContent = `
      #animStudioOverlay { position:fixed; inset:0; background:#090c12; z-index:1200; display:none; flex-direction:column; color:#e2e8f0; font-family:system-ui,sans-serif; }
      .as-bar { display:flex; gap:5px; align-items:center; padding:6px 8px; background:#131822; border-bottom:1px solid #232c3d; overflow-x:auto; }
      .as-bar select, .as-bar input { background:#090c12; color:#38bdf8; border:1px solid #334155; padding:4px 6px; border-radius:4px; font-size:11px; }
      .as-canvas-wrap { flex:1; position:relative; background:#070a10; overflow:hidden; touch-action:none; min-height:220px; }
      #asCanvas { width:100%; height:100%; display:block; }
      .as-hud { position:absolute; top:6px; left:6px; font-size:10px; font-family:monospace; color:#94a3b8; background:rgba(9,12,18,0.82); padding:3px 7px; border-radius:4px; pointer-events:none; }
      .as-tools { position:absolute; bottom:6px; left:6px; display:flex; gap:4px; }
      .as-dock { height:255px; background:#131822; border-top:1px solid #232c3d; display:flex; flex-direction:column; }
      .as-strip { display:flex; gap:5px; padding:6px 8px; overflow-x:auto; background:#0c1018; border-bottom:1px solid #1e293b; min-height:58px; align-items:center; }
      .as-fcard { min-width:56px; padding:4px 5px; border-radius:5px; background:#161e2e; border:1px solid #334155; font-size:10px; text-align:center; cursor:pointer; }
      .as-fcard.active { border:2px solid #38bdf8; background:#1e3a8a; color:#fff; }
      .as-props { flex:1; overflow-y:auto; padding:7px 8px; display:grid; grid-template-columns:1fr 1fr 1fr; gap:6px; align-content:start; }
      .as-field { display:flex; flex-direction:column; gap:2px; font-size:10px; color:#94a3b8; }
      .as-field input, .as-field select { background:#090c12; border:1px solid #334155; color:#fff; padding:4px; border-radius:4px; font-size:11px; width:100%; }
      .as-row-full { grid-column:1 / -1; display:flex; gap:5px; align-items:center; flex-wrap:wrap; }
    `;
    document.head.appendChild(style);

    const wrap = document.createElement('div');
    wrap.id = 'animStudioOverlay';
    wrap.innerHTML = `
      <div class="as-bar">
        <span style="font-size:12px;font-weight:800;color:#38bdf8;" id="asNodeTitle">🎬 动画工作台</span>
        <select id="asClipSel" onchange="AnimStudio.selectClip(+this.value)"></select>
        <button onclick="AnimStudio.addClip()">+片段</button>
        <button class="danger" onclick="AnimStudio.delClip()">删</button>
        <button id="asOnionBtn" class="active-toggle" onclick="AnimStudio.toggleOnion()">🧅洋葱皮</button>
        <button id="asFsmBtn" onclick="AnimStudio.toggleSubMode()">🔀状态机</button>
        <button class="play" style="margin-left:auto;" onclick="AnimStudio.close()">✔完成返回</button>
      </div>
      <div class="as-canvas-wrap">
        <canvas id="asCanvas"></canvas>
        <div class="as-hud" id="asHud">Frame 0 | 0ms</div>
        <div class="as-tools">
          <button id="asToolOffset" onclick="AnimStudio.setTool('offset')">🎯拖重心</button>
          <button id="asToolBox" class="active-toggle" onclick="AnimStudio.setTool('box')">🟥拖判定框</button>
          <button onclick="AnimStudio.zoom(0.25)">+</button>
          <button onclick="AnimStudio.zoom(-0.25)">-</button>
        </div>
      </div>
      <div class="as-dock">
        <div class="as-bar" style="padding:4px 8px;">
          <button id="asPlayBtn" class="play" style="margin-left:0;" onclick="AnimStudio.togglePlay()">▶播放</button>
          <button onclick="AnimStudio.step(-1)">⏮</button>
          <button onclick="AnimStudio.step(1)">⏭</button>
          <select id="asSpeedSel" onchange="AnimStudio.setSpeed(+this.value)">
            <option value="0.25">0.25x慢放</option>
            <option value="0.5">0.5x</option>
            <option value="1" selected>1.0x</option>
            <option value="1.5">1.5x</option>
          </select>
          <label style="font-size:10px;display:flex;align-items:center;gap:3px;"><input type="checkbox" id="asLoopChk" onchange="AnimStudio.toggleLoop(this.checked)">循环</label>
          <button onclick="AnimStudio.addFrame()">+加帧</button>
          <button onclick="AnimStudio.dupFrame()">复制帧</button>
          <button class="danger" onclick="AnimStudio.delFrame()">删帧</button>
        </div>
        <div class="as-strip" id="asStrip"></div>
        <div class="as-props" id="asProps"></div>
      </div>
    `;
    document.body.appendChild(wrap);
    studioCanvas = document.getElementById('asCanvas');
    studioCtx = studioCanvas.getContext('2d');
    bindCanvasTouch();
  }

  function resizeStudioCanvas() {
    if (!studioCanvas) return;
    const rect = studioCanvas.parentElement.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    studioCanvas.width = Math.max(1, Math.floor(rect.width * dpr));
    studioCanvas.height = Math.max(1, Math.floor(rect.height * dpr));
    drawStudio();
  }

  function screenToLocal(clientX, clientY) {
    const rect = studioCanvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const sx = (clientX - rect.left) * dpr;
    const sy = (clientY - rect.top) * dpr;
    return {
      x: (sx - studioCanvas.width / 2) / (viewScale * dpr),
      y: (sy - studioCanvas.height / 2) / (viewScale * dpr)
    };
  }

  function bindCanvasTouch() {
    studioCanvas.addEventListener('touchstart', (e) => {
      if (e.touches.length !== 1) return;
      const f = getCurFrame();
      if (!f) return;
      const pt = screenToLocal(e.touches[0].clientX, e.touches[0].clientY);
      dragStart = pt;

      if (toolMode === 'box') {
        const boxes = f.boxes || [];
        // 先检测当前选中框的右下角缩放手柄
        if (boxIdx >= 0 && boxes[boxIdx]) {
          const b = boxes[boxIdx];
          const hx = b.offsetX + b.width / 2;
          const hy = b.offsetY + b.height / 2;
          if (Math.hypot(pt.x - hx, pt.y - hy) <= 22) {
            dragTarget = 'box_resize';
            dragOrig = { ...b };
            return;
          }
        }
        // 检测点中任意框
        for (let i = boxes.length - 1; i >= 0; i--) {
          const b = boxes[i];
          if (Math.abs(pt.x - b.offsetX) <= b.width / 2 && Math.abs(pt.y - b.offsetY) <= b.height / 2) {
            boxIdx = i;
            dragTarget = 'box_move';
            dragOrig = { ...b };
            renderProps();
            drawStudio();
            return;
          }
        }
      }
      if (toolMode === 'offset') {
        dragTarget = 'offset';
        dragOrig = { offsetX: f.offsetX || 0, offsetY: f.offsetY || 0 };
      }
    });

    studioCanvas.addEventListener('touchmove', (e) => {
      if (!dragTarget || e.touches.length !== 1) return;
      e.preventDefault();
      const f = getCurFrame();
      if (!f) return;
      const pt = screenToLocal(e.touches[0].clientX, e.touches[0].clientY);
      const dx = Math.round(pt.x - dragStart.x);
      const dy = Math.round(pt.y - dragStart.y);

      if (dragTarget === 'offset') {
        f.offsetX = dragOrig.offsetX + dx;
        f.offsetY = dragOrig.offsetY + dy;
      } else if (dragTarget === 'box_move' && f.boxes[boxIdx]) {
        f.boxes[boxIdx].offsetX = dragOrig.offsetX + dx;
        f.boxes[boxIdx].offsetY = dragOrig.offsetY + dy;
      } else if (dragTarget === 'box_resize' && f.boxes[boxIdx]) {
        f.boxes[boxIdx].width = Math.max(16, dragOrig.width + dx * 2);
        f.boxes[boxIdx].height = Math.max(16, dragOrig.height + dy * 2);
      }
      renderProps();
      drawStudio();
    }, { passive: false });

    studioCanvas.addEventListener('touchend', () => {
      if (dragTarget) {
        dragTarget = null;
        if (typeof autoSave === 'function') autoSave();
      }
    });
  }

  function drawSpriteFrame(ctx, n, f, alpha, tintColor) {
    if (!f) return;
    const w = n.width || 120;
    const h = n.height || 120;
    const ox = f.offsetX || 0;
    const oy = f.offsetY || 0;
    ctx.save();
    ctx.globalAlpha = alpha;
    ctx.translate(ox, oy);

    const img = (n.texture && typeof imageCache !== 'undefined') ? imageCache[n.texture] : null;
    if (img) {
      const hf = Math.max(1, n.hFrames || 1);
      const vf = Math.max(1, n.vFrames || 1);
      const fi = Math.max(0, Math.min(hf * vf - 1, f.frameIndex || 0));
      const sw = img.width / hf;
      const sh = img.height / vf;
      ctx.drawImage(img, (fi % hf) * sw, Math.floor(fi / hf) * sh, sw, sh, -w / 2, -h / 2, w, h);
    } else {
      ctx.fillStyle = tintColor || `rgb(${Math.round((n.r ?? 0.3) * 255)},${Math.round((n.g ?? 0.8) * 255)},${Math.round((n.b ?? 1) * 255)})`;
      ctx.fillRect(-w / 2, -h / 2, w, h);
      ctx.strokeStyle = 'rgba(255,255,255,0.4)';
      ctx.lineWidth = 2;
      ctx.strokeRect(-w / 2, -h / 2, w, h);
      ctx.fillStyle = '#090c12';
      ctx.font = 'bold 22px monospace';
      ctx.textAlign = 'center';
      ctx.fillText('F' + (f.frameIndex || 0), 0, 8);
    }
    ctx.restore();
  }

  function drawStudio() {
    if (!studioOpen || !studioCtx || !studioCanvas) return;
    const n = getCurNode();
    const c = getCurClip();
    const f = getCurFrame();
    const dpr = window.devicePixelRatio || 1;
    const W = studioCanvas.width;
    const H = studioCanvas.height;

    studioCtx.clearRect(0, 0, W, H);
    studioCtx.save();
    studioCtx.translate(W / 2, H / 2);
    studioCtx.scale(viewScale * dpr, viewScale * dpr);

    // 参考网格与重心十字线
    studioCtx.strokeStyle = '#141c2b';
    studioCtx.lineWidth = 1 / viewScale;
    for (let x = -300; x <= 300; x += 30) { studioCtx.beginPath(); studioCtx.moveTo(x, -300); studioCtx.lineTo(x, 300); studioCtx.stroke(); }
    for (let y = -300; y <= 300; y += 30) { studioCtx.beginPath(); studioCtx.moveTo(-300, y); studioCtx.lineTo(300, y); studioCtx.stroke(); }

    studioCtx.strokeStyle = 'rgba(251,191,36,0.55)';
    studioCtx.lineWidth = 1.5 / viewScale;
    studioCtx.beginPath(); studioCtx.moveTo(-260, 0); studioCtx.lineTo(260, 0); studioCtx.stroke();
    studioCtx.beginPath(); studioCtx.moveTo(0, -260); studioCtx.lineTo(0, 260); studioCtx.stroke();

    if (n && c && f) {
      // 洋葱皮：上一帧（青色半透明）+ 下一帧（紫红半透明）
      if (onionSkin && c.frames.length > 1) {
        const prevF = c.frames[(frameIdx - 1 + c.frames.length) % c.frames.length];
        const nextF = c.frames[(frameIdx + 1) % c.frames.length];
        if (prevF && prevF !== f) drawSpriteFrame(studioCtx, n, prevF, 0.28, '#38bdf8');
        if (nextF && nextF !== f && nextF !== prevF) drawSpriteFrame(studioCtx, n, nextF, 0.22, '#f43f5e');
      }
      // 当前帧本体
      drawSpriteFrame(studioCtx, n, f, 1.0, null);

      // 绘制当前帧的 Hitbox / Hurtbox
      (f.boxes || []).forEach((b, i) => {
        const isHit = b.type === 'hitbox';
        studioCtx.fillStyle = isHit ? 'rgba(239,68,68,0.28)' : 'rgba(34,197,94,0.25)';
        studioCtx.strokeStyle = (i === boxIdx) ? '#fbbf24' : (isHit ? '#ef4444' : '#22c55e');
        studioCtx.lineWidth = (i === boxIdx ? 3 : 2) / viewScale;
        studioCtx.fillRect(b.offsetX - b.width / 2, b.offsetY - b.height / 2, b.width, b.height);
        studioCtx.strokeRect(b.offsetX - b.width / 2, b.offsetY - b.height / 2, b.width, b.height);

        if (i === boxIdx && toolMode === 'box') {
          const hx = b.offsetX + b.width / 2;
          const hy = b.offsetY + b.height / 2;
          studioCtx.fillStyle = '#fbbf24';
          studioCtx.beginPath();
          studioCtx.arc(hx, hy, 7 / viewScale, 0, Math.PI * 2);
          studioCtx.fill();
        }
      });

      document.getElementById('asHud').textContent =
        `${c.name} | 帧 ${frameIdx + 1}/${c.frames.length} | ${f.durationMs}ms | 偏移(${f.offsetX},${f.offsetY}) | 框:${(f.boxes || []).length}`;
    }
    studioCtx.restore();
  }

  function renderUI() {
    const n = getCurNode();
    const c = getCurClip();
    if (!n || !c) return;

    document.getElementById('asNodeTitle').textContent = `🎬 ${n.name}`;
    document.getElementById('asClipSel').innerHTML = n.proClips
      .map((cl, i) => `<option value="${i}" ${i === clipIdx ? 'selected' : ''}>${escHtml(cl.name)} (${cl.frames.length}帧)</option>`).join('');
    document.getElementById('asLoopChk').checked = !!c.loop;
    document.getElementById('asOnionBtn').classList.toggle('active-toggle', onionSkin);
    document.getElementById('asFsmBtn').classList.toggle('active-toggle', subMode === 'fsm');
    document.getElementById('asToolOffset').classList.toggle('active-toggle', toolMode === 'offset');
    document.getElementById('asToolBox').classList.toggle('active-toggle', toolMode === 'box');
    document.getElementById('asPlayBtn').textContent = isPlaying ? '⏸暂停' : '▶播放';

    // 时间轴帧条
    document.getElementById('asStrip').innerHTML = c.frames.map((f, i) => {
      const hasHit = (f.boxes || []).some(b => b.type === 'hitbox');
      const badges = (hasHit ? '🟥' : '') + (f.event ? '⚡' : '') + (f.cancelable ? '✂' : '');
      return `<div class="as-fcard ${i === frameIdx ? 'active' : ''}" onclick="AnimStudio.selectFrame(${i})">
        <div style="font-weight:700;">#${i + 1} (F${f.frameIndex})</div>
        <div style="color:#94a3b8;font-size:9px;">${f.durationMs}ms ${badges}</div>
      </div>`;
    }).join('');

    renderProps();
    drawStudio();
  }

  function renderProps() {
    const propsEl = document.getElementById('asProps');
    const n = getCurNode();
    const f = getCurFrame();
    if (!n || !f) { propsEl.innerHTML = ''; return; }

    if (subMode === 'fsm') {
      const trs = n.fsmTransitions || [];
      const rows = trs.map((t, i) => {
        const cond = (t.conditions && t.conditions[0]) || { param: 'speed', op: '>', value: 10 };
        return `<div class="as-row-full" style="background:#0c1018;padding:5px;border-radius:4px;border:1px solid #232c3d;">
          <input style="width:58px;" value="${escHtml(t.from)}" placeholder="从(*)" onchange="AnimStudio.updTr(${i},'from',this.value)">
          <span>→</span>
          <input style="width:62px;" value="${escHtml(t.to)}" placeholder="到" onchange="AnimStudio.updTr(${i},'to',this.value)">
          <input style="width:58px;" value="${escHtml(cond.param)}" placeholder="参数" onchange="AnimStudio.updTrCond(${i},'param',this.value)">
          <select style="width:58px;" onchange="AnimStudio.updTrCond(${i},'op',this.value)">
            ${['trigger','>','<','==','!='].map(op => `<option value="${op}" ${cond.op===op?'selected':''}>${op}</option>`).join('')}
          </select>
          <input style="width:42px;" value="${escHtml(cond.value ?? '')}" placeholder="值" onchange="AnimStudio.updTrCond(${i},'value',this.value)">
          <label style="font-size:9px;"><input type="checkbox" ${t.requireCancelWindow?'checked':''} onchange="AnimStudio.updTr(${i},'requireCancelWindow',this.checked)">取消窗</label>
          <button class="danger" style="padding:2px 5px;" onclick="AnimStudio.delTr(${i})">×</button>
        </div>`;
      }).join('');
      propsEl.innerHTML = `
        <div class="as-row-full" style="justify-content:space-between;">
          <span style="font-size:11px;color:#38bdf8;font-weight:700;">🔀 FSM 状态机流转规则</span>
          <button class="save-btn" onclick="AnimStudio.addTr()">+新增连线</button>
        </div>
        ${rows || '<div class="as-row-full" style="color:#64748b;font-size:11px;">暂无状态转换，点击右上角添加（如 * → attack, trigger）</div>'}`;
      return;
    }

    const boxes = f.boxes || [];
    if (boxIdx >= boxes.length) boxIdx = boxes.length - 1;
    const b = boxIdx >= 0 ? boxes[boxIdx] : null;

    const boxHtml = b ? `
      <div class="as-field"><label>框类型</label>
        <select onchange="AnimStudio.updBox('type',this.value)">
          <option value="hitbox" ${b.type==='hitbox'?'selected':''}>🟥攻击框</option>
          <option value="hurtbox" ${b.type==='hurtbox'?'selected':''}>🟩受击框</option>
        </select>
      </div>
      <div class="as-field"><label>伤害(dmg)</label><input type="number" value="${b.damage}" oninput="AnimStudio.updBox('damage',+this.value)"></div>
      <div class="as-field"><label>顿帧(秒)</label><input type="number" step="0.01" value="${b.hitstop}" oninput="AnimStudio.updBox('hitstop',+this.value)"></div>
      <div class="as-field"><label>击退 X</label><input type="number" value="${b.knockbackX}" oninput="AnimStudio.updBox('knockbackX',+this.value)"></div>
      <div class="as-field"><label>击退 Y</label><input type="number" value="${b.knockbackY}" oninput="AnimStudio.updBox('knockbackY',+this.value)"></div>
      <div class="as-field"><label>宽×高</label><input value="${b.width}x${b.height}" onchange="AnimStudio.updBoxSize(this.value)"></div>` : '';

    propsEl.innerHTML = `
      <div class="as-field"><label>帧号(frameIndex)</label><input type="number" min="0" value="${f.frameIndex}" oninput="AnimStudio.updFrame('frameIndex',Math.max(0,+this.value))"></div>
      <div class="as-field"><label>时长(ms)</label><input type="number" min="16" value="${f.durationMs}" oninput="AnimStudio.updFrame('durationMs',Math.max(16,+this.value))"></div>
      <div class="as-field"><label>图集帧名(可选)</label><input value="${escHtml(f.atlasFrame || '')}" placeholder="atk_01" onchange="AnimStudio.updFrame('atlasFrame',this.value)"></div>
      <div class="as-field"><label>重心偏移 X</label><input type="number" value="${f.offsetX}" oninput="AnimStudio.updFrame('offsetX',+this.value)"></div>
      <div class="as-field"><label>重心偏移 Y</label><input type="number" value="${f.offsetY}" oninput="AnimStudio.updFrame('offsetY',+this.value)"></div>
      <div class="as-field"><label>帧事件(Event)</label><input value="${escHtml(f.event || '')}" placeholder="sfx_hit" onchange="AnimStudio.updFrame('event',this.value)"></div>
      <div class="as
