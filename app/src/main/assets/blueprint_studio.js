// ==================== ZeroEngine P12 手机端可视化节点蓝图工作台 (Blueprint Studio) ====================
(function () {
  let bpOpen = false;
  let panX = 24, panY = 24;
  let pendingPin = null; // { nodeId, port }
  let dragInfo = null;   // { type: 'node'|'pan', nodeId, startX, startY, origX, origY }
  let compiledPreview = false;

  // 节点库定义（事件 / 条件分支 / 动作执行）
  const NODE_DEFS = {
    // --- 事件入口 ---
    ev_ready:   { cat: '事件', title: '⚡ 游戏启动 onReady', color: '#dc2626', inPorts: [], outPorts: ['next'], params: [] },
    ev_process: { cat: '事件', title: '🔄 每帧循环 onProcess', color: '#dc2626', inPorts: [], outPorts: ['next'], params: [] },
    ev_trigger: { cat: '事件', title: '💥 触发进入 onTrigger', color: '#dc2626', inPorts: [], outPorts: ['next'], params: [] },
    ev_hurt:    { cat: '事件', title: '🩸 受到伤害 onHurt', color: '#dc2626', inPorts: [], outPorts: ['next'], params: [] },
    ev_input:   { cat: '事件', title: '👆 触控输入 onInput', color: '#dc2626', inPorts: [], outPorts: ['next'], params: [] },
    // --- 条件分支 ---
    if_touch:   { cat: '条件', title: '❓ 正在触控屏幕', color: '#d97706', inPorts: ['in'], outPorts: ['true', 'false'], params: [] },
    if_action:  { cat: '条件', title: '❓ 按键/动作触发', color: '#d97706', inPorts: ['in'], outPorts: ['true', 'false'],
                  params: [{ k: 'action', l: '动作', v: 'jump' }, { k: 'mode', l: '方式', v: 'just', opts: ['just', 'hold'] }] },
    if_tag:     { cat: '条件', title: '❓ 碰撞标签匹配', color: '#d97706', inPorts: ['in'], outPorts: ['true', 'false'],
                  params: [{ k: 'tag', l: '目标Tag', v: 'coin' }] },
    // --- 动作执行 ---
    act_move:   { cat: '动作', title: '🏃 移向触控点', color: '#2563eb', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'speed', l: '速度', v: 460, num: true }] },
    act_stop:   { cat: '动作', title: '🛑 停止移动', color: '#2563eb', inPorts: ['in'], outPorts: ['next'], params: [] },
    act_slide:  { cat: '动作', title: '⚙️ 物理步进 MoveSlide', color: '#2563eb', inPorts: ['in'], outPorts: ['next'], params: [] },
    act_jump:   { cat: '动作', title: '⬆️ 角色跳跃', color: '#2563eb', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'force', l: '力度', v: 920, num: true }] },
    act_anim:   { cat: '动作', title: '🎬 播放动画片段', color: '#059669', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'clip', l: '片段名', v: 'run' }] },
    act_trig:   { cat: '动作', title: '🔀 触发状态机', color: '#059669', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'param', l: '触发器', v: 'attack' }] },
    act_fx:     { cat: '动作', title: '✨ 闪白+震屏+顿帧', color: '#7c3aed', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'flash', l: '闪白秒', v: 0.14, num: true }, { k: 'shake', l: '震幅', v: 14, num: true }, { k: 'hitstop', l: '顿帧秒', v: 0.05, num: true }] },
    act_cloth:  { cat: '动作', title: '🧣 挂载物理披风', color: '#7c3aed', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'width', l: '宽度', v: 34, num: true }, { k: 'wind', l: '风力X', v: -160, num: true }] },
    act_gi:     { cat: '动作', title: '🌌 暗夜GI+软阴影', color: '#7c3aed', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'amb', l: '环境光', v: 0.14, num: true }, { k: 'gi', l: 'GI强度', v: 1.0, num: true }] },
    act_tone:   { cat: '动作', title: '🔊 播放提示音', color: '#0891b2', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'hz', l: '频率Hz', v: 880, num: true }, { k: 'ms', l: '时长ms', v: 80, num: true }] },
    act_destroy:{ cat: '动作', title: '🗑️ 销毁节点', color: '#b91c1c', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'target', l: '对象', v: 'other', opts: ['other', 'self'] }] },
    act_code:   { cat: '动作', title: '📜 自定义JS代码', color: '#475569', inPorts: ['in'], outPorts: ['next'],
                  params: [{ k: 'code', l: '代码', v: 'Engine.log("Blueprint OK");' }] }
  };

  function getCurNode() {
    if (typeof selectedIndex === 'undefined' || selectedIndex < 0 || !nodes[selectedIndex]) return null;
    const n = nodes[selectedIndex];
    if (!n.blueprint || !Array.isArray(n.blueprint.nodes)) {
      n.blueprint = { nodes: [], links: [] };
    }
    return n;
  }

  function injectDOM() {
    if (document.getElementById('bpOverlay')) return;
    const st = document.createElement('style');
    st.textContent = `
      #bpOverlay { position:fixed; inset:0; background:#070a10; z-index:1250; display:none; flex-direction:column; color:#e2e8f0; font-family:system-ui,sans-serif; }
      .bp-bar { display:flex; gap:5px; align-items:center; padding:6px 8px; background:#131822; border-bottom:1px solid #232c3d; overflow-x:auto; }
      .bp-stage { flex:1; position:relative; overflow:hidden; touch-action:none; background:radial-gradient(#1e293b 1px, transparent 1px); background-size:24px 24px; }
      #bpSvg { position:absolute; inset:0; width:100%; height:100%; pointer-events:none; overflow:visible; }
      #bpLayer { position:absolute; inset:0; transform-origin:0 0; }
      .bp-node { position:absolute; width:176px; background:#111827; border:1px solid #334155; border-radius:7px; box-shadow:0 6px 18px rgba(0,0,0,0.55); font-size:11px; }
      .bp-nhdr { padding:5px 7px; border-radius:6px 6px 0 0; font-weight:700; font-size:11px; color:#fff; display:flex; justify-content:space-between; align-items:center; }
      .bp-nhdr span.del { cursor:pointer; padding:0 4px; opacity:0.85; }
      .bp-nbody { padding:6px 7px; display:flex; flex-direction:column; gap:5px; }
      .bp-param { display:flex; align-items:center; justify-content:space-between; gap:4px; font-size:10px; color:#94a3b8; }
      .bp-param input, .bp-param select { width:92px; background:#070a10; border:1px solid #334155; color:#fff; padding:2px 4px; border-radius:3px; font-size:10px; }
      .bp-ports { display:flex; justify-content:space-between; align-items:center; padding:4px 7px 6px; border-top:1px solid #1e293b; }
      .bp-pin { display:inline-flex; align-items:center; gap:3px; font-size:10px; padding:3px 6px; border-radius:10px; background:#1e293b; border:1px solid #475569; color:#e2e8f0; }
      .bp-pin.out { background:#1e3a8a; border-color:#3b82f6; }
      .bp-pin.out-false { background:#7f1d1d; border-color:#ef4444; }
      .bp-pin.selected { background:#f59e0b !important; color:#000 !important; border-color:#fef08a !important; font-weight:800; }
      .bp-palette { display:flex; gap:4px; padding:6px 8px; background:#0f141e; border-top:1px solid #232c3d; overflow-x:auto; }
      .bp-palette button { font-size:10px; padding:5px 7px; }
      #bpCodePreview { position:absolute; inset:10px; background:rgba(8,10,15,0.96); border:1px solid #38bdf8; border-radius:8px; padding:10px; display:none; flex-direction:column; gap:6px; z-index:20; }
      #bpCodeText { flex:1; overflow:auto; font-family:monospace; font-size:11px; color:#a5f3fc; white-space:pre-wrap; user-select:text; }
      .bp-hint { position:absolute; bottom:8px; left:8px; font-size:10px; color:#94a3b8; background:rgba(9,12,18,0.82); padding:3px 7px; border-radius:4px; pointer-events:none; }
    `;
    document.head.appendChild(st);

    const div = document.createElement('div');
    div.id = 'bpOverlay';
    div.innerHTML = `
      <div class="bp-bar">
        <span style="font-size:12px;font-weight:800;color:#a78bfa;" id="bpTitle">🧩 蓝图工作台</span>
        <button onclick="BlueprintStudio.loadPreset()">✨套用模板</button>
        <button onclick="BlueprintStudio.togglePreview()">📜预览JS</button>
        <button class="danger" onclick="BlueprintStudio.clearGraph()">清空</button>
        <button class="play" style="margin-left:auto;" onclick="BlueprintStudio.applyAndClose()">⚡编译并应用</button>
      </div>
      <div class="bp-stage" id="bpStage">
        <svg id="bpSvg"></svg>
        <div id="bpLayer"></div>
        <div class="bp-hint" id="bpHint">拖动卡片移动 | 先点蓝色出口▶再点灰色入口▷连线</div>
        <div id="bpCodePreview">
          <div style="display:flex;justify-content:space-between;align-items:center;">
            <span style="font-size:12px;font-weight:700;color:#38bdf8;">⚡ 蓝图实时编译产物 (Rhino JS)</span>
            <button onclick="BlueprintStudio.togglePreview()">关闭预览</button>
          </div>
          <pre id="bpCodeText"></pre>
        </div>
      </div>
      <div class="bp-palette" id="bpPalette"></div>
    `;
    document.body.appendChild(div);
    buildPalette();
    bindStageEvents();
  }

  function buildPalette() {
    const pal = document.getElementById('bpPalette');
    pal.innerHTML = Object.entries(NODE_DEFS).map(([type, def]) =>
      `<button style="border-left:3px solid ${def.color};" onclick="BlueprintStudio.addNode('${type}')">+${def.title.replace(/^[^\s]+\s/, '')}</button>`
    ).join('');
  }

  function bindStageEvents() {
    const stage = document.getElementById('bpStage');
    stage.addEventListener('touchstart', (e) => {
      if (e.touches.length !== 1) return;
      if (['INPUT', 'SELECT', 'BUTTON'].includes(e.target.tagName) || e.target.classList.contains('bp-pin') || e.target.classList.contains('del')) return;
      const card = e.target.closest('.bp-node');
      const t = e.touches[0];
      if (card) {
        const nid = card.dataset.nid;
        const bp = getCurNode()?.blueprint;
        const nd = bp?.nodes.find(x => x.id === nid);
        if (nd) dragInfo = { type: 'node', nodeId: nid, startX: t.clientX, startY: t.clientY, origX: nd.x, origY: nd.y };
      } else {
        dragInfo = { type: 'pan', startX: t.clientX, startY: t.clientY, origX: panX, origY: panY };
      }
    });

    stage.addEventListener('touchmove', (e) => {
      if (!dragInfo || e.touches.length !== 1) return;
      e.preventDefault();
      const t = e.touches[0];
      const dx = t.clientX - dragInfo.startX;
      const dy = t.clientY - dragInfo.startY;
      if (dragInfo.type === 'pan') {
        panX = dragInfo.origX + dx;
        panY = dragInfo.origY + dy;
        applyTransform();
        renderWires();
      } else {
        const bp = getCurNode()?.blueprint;
        const nd = bp?.nodes.find(x => x.id === dragInfo.nodeId);
        if (nd) {
          nd.x = Math.round(dragInfo.origX + dx);
          nd.y = Math.round(dragInfo.origY + dy);
          const el = document.querySelector(`.bp-node[data-nid="${nd.id}"]`);
          if (el) { el.style.left = nd.x + 'px'; el.style.top = nd.y + 'px'; }
          renderWires();
        }
      }
    }, { passive: false });

    stage.addEventListener('touchend', () => {
      if (dragInfo) { dragInfo = null; if (typeof autoSave === 'function') autoSave(); }
    });
  }

  function applyTransform() {
    const layer = document.getElementById('bpLayer');
    if (layer) layer.style.transform = `translate(${panX}px, ${panY}px)`;
  }

  function renderGraph() {
    const n = getCurNode();
    if (!n) return;
    document.getElementById('bpTitle').textContent = `🧩 蓝图: ${n.name}`;
    const layer = document.getElementById('bpLayer');
    layer.innerHTML = n.blueprint.nodes.map(bn => {
      const def = NODE_DEFS[bn.type] || NODE_DEFS.act_code;
      const paramsHtml = (def.params || []).map(p => {
        const val = bn.params[p.k] ?? p.v;
        if (p.opts) {
          return `<div class="bp-param"><span>${p.l}</span><select onchange="BlueprintStudio.updParam('${bn.id}','${p.k}',this.value,false)">${p.opts.map(o => `<option value="${o}" ${val === o ? 'selected' : ''}>${o}</option>`).join('')}</select></div>`;
        }
        return `<div class="bp-param"><span>${p.l}</span><input type="${p.num ? 'number' : 'text'}" step="any" value="${escHtml(val)}" onchange="BlueprintStudio.updParam('${bn.id}','${p.k}',this.value,${!!p.num})"></div>`;
      }).join('');

      const inHtml = def.inPorts.map(p =>
        `<span class="bp-pin in" data-pin-in="${bn.id}:${p}" onclick="BlueprintStudio.tapIn('${bn.id}','${p}')">▷ 接入</span>`
      ).join('');

      const outHtml = def.outPorts.map(p => {
        const sel = pendingPin && pendingPin.nodeId === bn.id && pendingPin.port === p;
        const label = p === 'true' ? '是▶' : (p === 'false' ? '否▶' : '执行▶');
        const cls = p === 'false' ? 'out-false' : 'out';
        return `<span class="bp-pin ${cls} ${sel ? 'selected' : ''}" data-pin-out="${bn.id}:${p}" onclick="BlueprintStudio.tapOut('${bn.id}','${p}')">${label}</span>`;
      }).join(' ');

      return `<div class="bp-node" data-nid="${bn.id}" style="left:${bn.x}px;top:${bn.y}px;">
        <div class="bp-nhdr" style="background:${def.color};">
          <span>${def.title}</span>
          <span class="del" onclick="BlueprintStudio.delNode('${bn.id}')">×</span>
        </div>
        ${paramsHtml ? `<div class="bp-nbody">${paramsHtml}</div>` : ''}
        <div class="bp-ports"><div>${inHtml}</div><div style="display:flex;gap:4px;">${outHtml}</div></div>
      </div>`;
    }).join('');
    applyTransform();
    requestAnimationFrame(renderWires);
  }

  function renderWires() {
    const svg = document.getElementById('bpSvg');
    const stage = document.getElementById('bpStage');
    const bp = getCurNode()?.blueprint;
    if (!svg || !stage || !bp) return;
    const sRect = stage.getBoundingClientRect();
    let paths = '';
    bp.links.forEach(lk => {
      const outEl = document.querySelector(`[data-pin-out="${lk.from}:${lk.fromPort}"]`);
      const inEl = document.querySelector(`[data-pin-in="${lk.to}:in"]`);
      if (!outEl || !inEl) return;
      const r1 = outEl.getBoundingClientRect();
      const r2 = inEl.getBoundingClientRect();
      const x1 = r1.right - sRect.left;
      const y1 = r1.top + r1.height / 2 - sRect.top;
      const x2 = r2.left - sRect.left;
      const y2 = r2.top + r2.height / 2 - sRect.top;
      const dx = Math.max(45, Math.abs(x2 - x1) * 0.55);
      const stroke = lk.fromPort === 'false' ? '#f87171' : '#38bdf8';
      paths += `<path d="M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}" fill="none" stroke="${stroke}" stroke-width="2.6" stroke-linecap="round"/>`;
    });
    svg.innerHTML = paths;
  }

  // ==================== 蓝图 -> Rhino JS 编译器 ====================
  function compileBlueprint(bp) {
    if (!bp || !bp.nodes.length) return '';
    const map = {};
    bp.nodes.forEach(bn => { map[bn.id] = bn; });

    function nextOf(id, port) {
      const lk = bp.links.find(l => l.from === id && l.fromPort === port);
      return lk ? map[lk.to] : null;
    }

    function emitChain(node, indent, visited) {
      if (!node || visited.has(node.id)) return '';
      const nextVisited = new Set(visited);
      nextVisited.add(node.id);
      const p = node.params || {};
      let out = '';

      switch (node.type) {
        case 'if_touch':
          out += `${indent}if (Input.isTouching && Input.screenY < 1550) {\n${emitChain(nextOf(node.id, 'true'), indent + '  ', nextVisited)}${indent}}`;
          if (nextOf(node.id, 'false')) out += ` else {\n${emitChain(nextOf(node.id, 'false'), indent + '  ', nextVisited)}${indent}}`;
          return out + '\n';
        case 'if_action': {
          const fn = p.mode === 'hold' ? 'isActionPressed' : 'isActionJustPressed';
          out += `${indent}if (Input.${fn}(${JSON.stringify(p.action || 'jump')})) {\n${emitChain(nextOf(node.id, 'true'), indent + '  ', nextVisited)}${indent}}`;
          if (nextOf(node.id, 'false')) out += ` else {\n${emitChain(nextOf(node.id, 'false'), indent + '  ', nextVisited)}${indent}}`;
          return out + '\n';
        }
        case 'if_tag':
          out += `${indent}if (typeof other !== "undefined" && other && other.tag === ${JSON.stringify(p.tag || 'coin')}) {\n${emitChain(nextOf(node.id, 'true'), indent + '  ', nextVisited)}${indent}}`;
          if (nextOf(node.id, 'false')) out += ` else {\n${emitChain(nextOf(node.id, 'false'), indent + '  ', nextVisited)}${indent}}`;
          return out + '\n';
        case 'act_move':
          out += `${indent}var _dx = Input.worldX - self.x, _dy = Input.worldY - self.y, _d = Math.hypot(_dx, _dy);\n`;
          out += `${indent}if (_d > 14) { self.vx = (_dx / _d) * ${Number(p.speed) || 460}; self.vy = (_dy / _d) * ${Number(p.speed) || 460}; self.scaleX = _dx < 0 ? -1 : 1; }\n`;
          break;
        case 'act_stop':
          out += `${indent}self.vx = 0; self.vy = 0;\n`;
          break;
        case 'act_slide':
          out += `${indent}Engine.moveAndSlide(self, dt);\n`;
          break;
        case 'act_jump':
          out += `${indent}self.jump(${Number(p.force) || 920});\n`;
          break;
        case 'act_anim':
          out += `${indent}self.play(${JSON.stringify(p.clip || 'run')});\n`;
          break;
        case 'act_trig':
          out += `${indent}self.triggerAnim(${JSON.stringify(p.param || 'attack')});\n`;
          break;
        case 'act_fx':
          out += `${indent}self.flash(${Number(p.flash) || 0.14}); Engine.shake(${Number(p.shake) || 14}, 0.14); Engine.hitstop(${Number(p.hitstop) || 0.05});\n`;
          break;
        case 'act_cloth':
          out += `${indent}self.attachCloth({ offsetY: -35, segments: 10, segmentLen: 18, widthStart: ${Number(p.width) || 34}, widthEnd: 8, r: 0.92, g: 0.15, b: 0.22, windX: ${Number(p.wind) || -160} });\n`;
          break;
        case 'act_gi': {
          const a = Number(p.amb) || 0.14;
          out += `${indent}Engine.setAmbientLight(${a}, ${(a * 1.1).toFixed(2)}, ${(a * 1.5).toFixed(2)});\n`;
          out += `${indent}Engine.setGI({ enabled: true, intensity: ${Number(p.gi) || 1.0}, radius: 0.20, threshold: 0.15, saturation: 1.6, skyR: 0, skyG: 0, skyB: 0 });\n`;
          break;
        }
        case 'act_tone':
          out += `${indent}Engine.playTone(${Number(p.hz) || 880}, ${Number(p.ms) || 80});\n`;
          break;
        case 'act_destroy':
          out += p.target === 'self' ? `${indent}Engine.destroy(self);\n` : `${indent}if (typeof other !== "undefined" && other) Engine.destroy(other);\n`;
          break;
        case 'act_code':
          out += `${indent}${p.code || ''}\n`;
          break;
      }
      return out + emitChain(nextOf(node.id, 'next'), indent, nextVisited);
    }

    const evSig = {
      ev_ready:   'function onReady()',
      ev_process: 'function onProcess(dt)',
      ev_trigger: 'function onTriggerEnter(other)',
      ev_hurt:    'function onHurt(attacker, dmg)',
      ev_input:   'function onInput(ev)'
    };

    const blocks = ['// [ZeroEngine P12 蓝图自动生成代码]'];
    Object.entries(evSig).forEach(([evType, sig]) => {
      const evNodes = bp.nodes.filter(x => x.type === evType);
      if (!evNodes.length) return;
      let body = '';
      evNodes.forEach(en => { body += emitChain(nextOf(en.id, 'next'), '  ', new Set([en.id])); });
      if (evType === 'ev_ready') body = '  Engine.setCameraTarget(self);\n' + body;
      blocks.push(`${sig} {\n${body}}`);
    });
    return blocks.join('\n\n');
  }

  window.BlueprintStudio = {
    open() {
      const n = getCurNode();
      if (!n) { if (typeof showToast === 'function') showToast('请先在左侧选中一个节点'); return; }
      injectDOM();
      bpOpen = true;
      pendingPin = null;
      compiledPreview = false;
      document.getElementById('bpCodePreview').style.display = 'none';
      document.getElementById('bpOverlay').style.display = 'flex';
      renderGraph();
    },
    addNode(type) {
      const n = getCurNode(); if (!n) return;
      const def = NODE_DEFS[type]; if (!def) return;
      const params = {};
      (def.params || []).forEach(p => { params[p.k] = p.v; });
      const offset = (n.blueprint.nodes.length % 5) * 26;
      n.blueprint.nodes.push({ id: 'n_' + Date.now() + '_' + (Math.random() * 999 | 0), type, x: 40 - panX + offset, y: 40 - panY + offset, params });
      autoSave(); renderGraph();
    },
    delNode(id) {
      const n = getCurNode(); if (!n) return;
      n.blueprint.nodes = n.blueprint.nodes.filter(x => x.id !== id);
      n.blueprint.links = n.blueprint.links.filter(l => l.from !== id && l.to !== id);
      if (pendingPin && pendingPin.nodeId === id) pendingPin = null;
      autoSave(); renderGraph();
    },
    updParam(id, k, val, isNum) {
      const n = getCurNode(); if (!n) return;
      const nd = n.blueprint.nodes.find(x => x.id === id);
      if (nd) { nd.params[k] = isNum ? Number(val) : val; autoSave(); }
    },
    tapOut(nodeId, port) {
      if (pendingPin && pendingPin.nodeId === nodeId && pendingPin.port === port) {
        pendingPin = null;
      } else {
        pendingPin = { nodeId, port };
        document.getElementById('bpHint').textContent = '已选中出口 ▶，现在点击目标卡片左侧「▷ 接入」完成连线';
      }
      renderGraph();
    },
    tapIn(nodeId) {
      const n = getCurNode(); if (!n) return;
      if (!pendingPin) {
        // 未选中出口时，点击入口可断开该入口的旧连线
        const before = n.blueprint.links.length;
        n.blueprint.links = n.blueprint.links.filter(l => l.to !== nodeId);
        if (n.blueprint.links.length !== before) { autoSave(); renderWires(); showToast('已断开入口连线'); }
        return;
      }
      if (pendingPin.nodeId === nodeId) { pendingPin = null; renderGraph(); return; }
      n.blueprint.links = n.blueprint.links.filter(l => !(l.from === pendingPin.nodeId && l.fromPort === pendingPin.port));
      n.blueprint.links.push({ from: pendingPin.nodeId, fromPort: pendingPin.port, to: nodeId });
      pendingPin = null;
      document.getElementById('bpHint').textContent = '连线成功！拖动卡片可调整布局';
      autoSave(); renderGraph();
    },
    loadPreset() {
      const n = getCurNode(); if (!n) return;
      panX = 16; panY = 16;
      n.blueprint = {
        nodes: [
          { id: 'r1', type: 'ev_ready', x: 10, y: 10, params: {} },
          { id: 'r2', type: 'act_gi', x: 215, y: 10, params: { amb: 0.14, gi: 1.05 } },
          { id: 'r3', type: 'act_cloth', x: 420, y: 10, params: { width: 34, wind: -160 } },
          { id: 'p1', type: 'ev_process', x: 10, y: 150, params: {} },
          { id: 'p2', type: 'if_touch', x: 215, y: 150, params: {} },
          { id: 'p3', type: 'act_move', x: 420, y: 120, params: { speed: 480 } },
          { id: 'p4', type: 'act_stop', x: 420, y: 220, params: {} },
          { id: 'p5', type: 'act_slide', x: 625, y: 165, params: {} },
          { id: 't1', type: 'ev_trigger', x: 10, y: 310, params: {} },
          { id: 't2', type: 'if_tag', x: 215, y: 310, params: { tag: 'coin' } },
          { id: 't3', type: 'act_fx', x: 420, y: 310, params: { flash: 0.14, shake: 14, hitstop: 0.05 } },
          { id: 't4', type: 'act_destroy', x: 625, y: 310, params: { target: 'other' } }
        ],
        links: [
          { from: 'r1', fromPort: 'next', to: 'r2' }, { from: 'r2', fromPort: 'next', to: 'r3' },
          { from: 'p1', fromPort: 'next', to: 'p2' }, { from: 'p2', fromPort: 'true', to: 'p3' },
          { from: 'p2', fromPort: 'false', to: 'p4' }, { from: 'p3', fromPort: 'next', to: 'p5' },
          { from: 'p4', fromPort: 'next', to: 'p5' },
          { from: 't1', fromPort: 'next', to: 't2' }, { from: 't2', fromPort: 'true', to: 't3' },
          { from: 't3', fromPort: 'next', to: 't4' }
        ]
      };
      autoSave(); renderGraph();
      showToast('✨ 已载入「暗夜GI+披风+触控移动+吃金币」蓝图');
    },
    clearGraph() {
      const n = getCurNode(); if (!n) return;
      n.blueprint = { nodes: [], links: [] };
      pendingPin = null; autoSave(); renderGraph();
    },
    togglePreview() {
      const n = getCurNode(); if (!n) return;
      compiledPreview = !compiledPreview;
      const box = document.getElementById('bpCodePreview');
      box.style.display = compiledPreview ? 'flex' : 'none';
      if (compiledPreview) document.getElementById('bpCodeText').textContent = compileBlueprint(n.blueprint) || '// (画布为空)';
    },
    applyAndClose() {
      const n = getCurNode();
      if (n && n.blueprint.nodes.length > 0) {
        if (typeof recordUndoPoint === 'function') recordUndoPoint();
        n.script = compileBlueprint(n.blueprint);
        showToast('⚡ 蓝图已编译并写入 ' + n.name + ' 脚本');
      }
      bpOpen = false;
      document.getElementById('bpOverlay').style.display = 'none';
      if (typeof autoSave === 'function') autoSave();
      if (typeof refreshAll === 'function') refreshAll();
    }
  };

  window.addEventListener('resize', () => { if (bpOpen) renderWires(); });
})();