// ==================== ZeroEngine 左侧节点树与底部检查器/脚本/着色器/控制台面板 (P0~P10) ====================

function renderTree() {
  document.getElementById('treeList').innerHTML = nodes.map((n, i) => {
    const indent = n.parent ? '└ ' : '';
    const icon = n.nodeType === 'PointLight' ? '💡'
      : (n.nodeType === 'ParticleEmitter' ? '🔥'
      : (n.nodeType === 'TileMap' ? '🧱'
      : (n.slopeType && n.slopeType !== 'none' ? '📐'
      : (n.isHitbox ? '⚔️'
      : (n.layer === 'UI' ? '📱' : (n.billboard ? '🧍' : ''))))));
    const hasScript = (n.script && n.script.trim()) || (n.scriptFile && n.scriptFile.trim());
    const hasProAnim = Array.isArray(n.proClips) && n.proClips.length > 0;
    const badges = (hasProAnim ? '🎬' : '') + (n.normalMap ? '💎' : '') + (hasScript ? '📜' : '') + (n.shader && n.shader.trim() ? '✨' : '');
    return `<div class="tree-item ${i === selectedIndex ? 'active' : ''}" onclick="selectNode(${i})">${indent}${icon}${n.name} ${badges}</div>`;
  }).join('');
}

function selectNode(i) {
  selectedIndex = i;
  if (nodes[i]?.nodeType !== 'TileMap') tileBrushMode = 'off';
  refreshAll();
}

function switchTab(tab) {
  flushActiveEditorsSilent();
  activeTab = tab;
  document.querySelectorAll('.tab').forEach(el => el.classList.remove('active'));
  document.getElementById(`tab-${tab}`).classList.add('active');
  if (tab === 'script' || tab === 'inspector' || tab === 'docs') {
    refreshScriptList();
    refreshDataList();
  }
  renderDock();
}

function updateProp(key, val, isTyping = false) {
  if (selectedIndex < 0) return;
  if (isTyping) {
    if (!inputDebounceTimer) recordUndoPoint();
    clearTimeout(inputDebounceTimer);
    inputDebounceTimer = setTimeout(() => { inputDebounceTimer = null; }, 800);
  } else {
    recordUndoPoint();
  }
  if (key === 'name') {
    const oldName = nodes[selectedIndex].name;
    nodes.forEach(item => { if (item.parent === oldName) item.parent = val; });
  }
  nodes[selectedIndex][key] = val;
  if ((key === 'texture' || key === 'normalMap') && val) preloadLocalImage(val);
  autoSave();
  renderTree();
  draw();
  if (
    key === 'texture' ||
    key === 'normalMap' ||
    key === 'hFrames' ||
    key === 'vFrames' ||
    key === 'bodyMode' ||
    key === 'isHitbox' ||
    key === 'slopeType' ||
    key === 'scriptFile'
  ) {
    renderDock();
  }
}

function updateColor(hex) {
  if (selectedIndex < 0) return;
  if (!inputDebounceTimer) recordUndoPoint();
  clearTimeout(inputDebounceTimer);
  inputDebounceTimer = setTimeout(() => { inputDebounceTimer = null; }, 600);
  const c = hexToRgb(hex);
  nodes[selectedIndex].r = c.r;
  nodes[selectedIndex].g = c.g;
  nodes[selectedIndex].b = c.b;
  autoSave();
  draw();
}

function applyDefaultShaderTemplate() {
  if (selectedIndex < 0) return;
  const tpl = [
    'precision mediump float;',
    'uniform sampler2D uTexture;',
    'uniform vec4 uColor;',
    'uniform float uTime;',
    'varying vec2 vTexCoord;',
    'void main() {',
    '  vec4 c = texture2D(uTexture, vTexCoord) * uColor;',
    '  gl_FragColor = c;',
    '}'
  ].join('\n');
  updateProp('shader', tpl);
  renderDock();
}

function insertCode(id, snip) {
  const ta = document.getElementById(id);
  if (!ta) return;
  const s = ta.selectionStart;
  const e = ta.selectionEnd;
  ta.value = ta.value.substring(0, s) + snip + ta.value.substring(e);
  ta.selectionStart = ta.selectionEnd = s + snip.length;
  ta.focus();
  if (id === 'moduleEditor') { activeScriptContent = ta.value; return; }
  if (id === 'dataEditor') { activeDataContent = ta.value; return; }
  recordUndoPoint();
  nodes[selectedIndex][id === 'shaderEditor' ? 'shader' : 'script'] = ta.value;
  autoSave();
  renderTree();
}

function refreshConsoleFromNative() {
  if (activeTab === 'console') renderDock();
}

function buildTilePaletteHtml(n) {
  if (!n.texture || !imageCache[n.texture]) {
    return `<div style="font-size:10px;color:#64748b;margin-top:5px;">未绑定图集时将使用纯色方块绘制；选中本节点后点左上角 <b>+图</b> 可直接导入并绑定图集。</div>`;
  }
  const img = imageCache[n.texture];
  const hf = Math.max(1, n.hFrames || 1);
  const vf = Math.max(1, n.vFrames || 1);
  const total = Math.min(64, hf * vf);
  let cells = '';
  for (let i = 0; i < total; i++) {
    const col = i % hf;
    const row = Math.floor(i / hf);
    const posX = hf > 1 ? (col / (hf - 1)) * 100 : 0;
    const posY = vf > 1 ? (row / (vf - 1)) * 100 : 0;
    cells += `<div class="palette-cell ${brushFrame === i ? 'selected' : ''}"
      style="background-image:url('${img.src}');background-size:${hf * 100}% ${vf * 100}%;background-position:${posX}% ${posY}%;"
      onclick="brushFrame=${i};tileBrushMode='paint';renderDock();draw();">
      <span class="palette-idx">${i}</span>
    </div>`;
  }
  return `<div class="palette-grid">${cells}</div>`;
}

function renderDock() {
  const body = document.getElementById('dockBody');
  if (activeTab === 'docs') {
    refreshAudioList();
    refreshScriptList();
    refreshDataList();
    const audioStr = audioAssets.length ? audioAssets.join(', ') : '(暂无导入音频，点击左上角 +音 导入)';
    const scriptStr = scriptAssets.length ? scriptAssets.join(', ') : '(暂无外部模块，切到 脚本/数据 -> 模块库 新建)';
    const dataStr = dataAssets.length ? dataAssets.join(', ') : '(暂无数据表，切到 脚本/数据 -> 数据表 新建)';
    body.innerHTML = `<div class="doc-box">
<b>📂 当前工程 Save 文件夹位置：</b> <button style="padding:2px 6px;font-size:10px;" onclick="chooseSaveFolderNative()">更改目录</button><br>
<span style="color:#34d399;">${escHtml(getSaveFolderPath())}</span><br><br>
<b>ZeroEngine SDK v1.6 商业版手册 (P0~P10)</b><br>
• <b>P10 2D骨骼蒙皮与网格变形</b>: self.createSkinnedMesh(8, 12) / self.bindBones(["Spine","Head"], 2.2) / self.setMeshWave(8, 2, 6, 4)<br>
• <b>P9 实时布料与骨骼IK</b>: self.attachCloth({segments:10, widthStart:36, widthEnd:8, r:0.9, g:0.15, b:0.2}) / Engine.setGlobalWind(-160, 0) / Engine.solveIK("Arm","Forearm", tx, ty, 70, 65, false)<br>
• <b>P8 2D法线光照与SDF软阴影</b>: 勾选「SDF 投射软阴影」即生成实时软阴影；Engine.setSdfShadow(true, 10, 0.08) / self.setNormalMap("hero_n.png", 1.2, 0.4)<br>
• <b>P7 专业动画与状态机</b>: 点击顶部 <b>🎬动画</b> 打开工作台；self.setAnimFloat("speed", vx) / self.triggerAnim("attack") / self.isAnimCancelable()<br>
• <b>外部 JS 模块 (scripts/)</b>: ${escHtml(scriptStr)}<br>
• <b>数值数据表 (data/)</b>: ${escHtml(dataStr)}<br>
• <b>大世界状态标志 (P5)</b>: Engine.setFlag("door_1", true) / hasFlag("door_1") / saveWorldFlags("main")<br>
• <b>数据表与多语言 (P5)</b>: var w = Engine.loadTable("weapons.json") / Engine.setLanguage("en") / Engine.t("boss_clear")<br>
• <b>多轨 BGM 混音 (P5)</b>: Engine.crossfadeBGM("boss.mp3", 1.5, true, 0.8) / Engine.playSFX("hit.wav", 1, 0.9, 1.1)<br>
• <b>UI 九宫格与裁剪 (P5)</b>: self.setNineSlice(16,16,16,16) / self.setClipRect(x,y,w,h) / self.clipChildren = true<br>
• <b>模块化与合批 (P4)</b>: require("combat_config.js") / Engine.getStats() / Engine.unloadUnusedTextures()<br>
• <b>已导入音频</b>: ${escHtml(audioStr)}
</div>`;
    return;
  }

  if (activeTab === 'console') {
    const logs = window.ZeroBridge ? window.ZeroBridge.getConsoleLogs() : 'Console ready.';
    body.innerHTML = `
      <div style="display:flex;justify-content:space-between;margin-bottom:6px;">
        <span style="font-size:11px;color:#94a3b8;">Rhino JS 虚拟机与 OpenGL 着色器实时日志</span>
        <button style="padding:2px 6px;font-size:10px;" onclick="if(window.ZeroBridge)window.ZeroBridge.clearConsoleLogs();renderDock();">清空</button>
      </div>
      <pre id="consoleOut">${escHtml(logs || '(暂无日志输出)')}</pre>`;
    return;
  }

  if (activeTab === 'script') {
    const selNode = (selectedIndex >= 0 && nodes[selectedIndex]) ? nodes[selectedIndex] : null;
    const modeBar = `
      <div class="script-mode-bar">
        <button class="${scriptTabMode === 'node' ? 'active-toggle' : ''}" onclick="flushActiveEditorsSilent();scriptTabMode='node';renderDock();">节点${selNode ? '(' + escHtml(selNode.name) + ')' : ''}</button>
        <button class="${scriptTabMode === 'module' ? 'active-toggle' : ''}" onclick="flushActiveEditorsSilent();refreshScriptList();scriptTabMode='module';renderDock();">模块(${scriptAssets.length})</button>
        <button class="${scriptTabMode === 'data' ? 'active-toggle' : ''}" onclick="flushActiveEditorsSilent();refreshDataList();scriptTabMode='data';renderDock();">数据表(${dataAssets.length})</button>
        <button class="save-btn" onclick="${scriptTabMode === 'data' ? 'openCreateDataModal()' : 'openCreateScriptModal()'}">${scriptTabMode === 'data' ? '+.json' : '+.js'}</button>
      </div>`;

    if (scriptTabMode === 'module') {
      if (scriptAssets.length === 0) {
        body.innerHTML = modeBar + `<div style="color:#94a3b8;font-size:11px;text-align:center;margin-top:34px;line-height:1.7;">当前工程 <span style="color:#34d399;font-family:monospace;">scripts/</span> 下暂无外部 JS 模块。<br>点击右上角 <b>+.js</b> 创建，随后在节点中用 <b>require("xxx.js")</b> 引入。</div>`;
        return;
      }
      const modOpts = scriptAssets.map(f => `<option value="${escHtml(f)}" ${f === activeScriptFile ? 'selected' : ''}>scripts/${escHtml(f)}</option>`).join('');
      body.innerHTML = modeBar + `
        <div class="script-mode-bar">
          <select onchange="saveActiveScriptModule(true);selectScriptModule(this.value);">${modOpts}</select>
          <button class="save-btn" onclick="saveActiveScriptModule(false)">💾保存</button>
          <button onclick="insertRequireToSelectedNode()">+require</button>
          <button onclick="mountActiveModuleToNode()">挂载</button>
          <button class="danger" onclick="deleteActiveScriptModule()">删</button>
        </div>
        <textarea id="moduleEditor" class="code-box" spellcheck="false" oninput="activeScriptContent=this.value">${escHtml(activeScriptContent)}</textarea>`;
      return;
    }

    if (scriptTabMode === 'data') {
      if (dataAssets.length === 0) {
        body.innerHTML = modeBar + `<div style="color:#94a3b8;font-size:11px;text-align:center;margin-top:30px;line-height:1.7;">当前工程 <span style="color:#34d399;font-family:monospace;">data/</span> 下暂无数据表或语言包。<br>点击右上角 <b>+.json</b> 创建，用 <b>Engine.loadTable("xxx.json")</b> 读取。</div>`;
        return;
      }
      const dataOpts = dataAssets.map(f => `<option value="${escHtml(f)}" ${f === activeDataFile ? 'selected' : ''}>data/${escHtml(f)}</option>`).join('');
      body.innerHTML = modeBar + `
        <div class="script-mode-bar">
          <select onchange="saveActiveDataFile(true);selectDataFile(this.value);">${dataOpts}</select>
          <button class="save-btn" onclick="saveActiveDataFile(false)">💾校验保存</button>
          <button onclick="insertLoadTableToNode()">+loadTable</button>
          <button class="danger" onclick="deleteActiveDataFile()">删</button>
        </div>
        <textarea id="dataEditor" class="code-box" spellcheck="false" oninput="activeDataContent=this.value">${escHtml(activeDataContent)}</textarea>`;
      return;
    }

    if (!selNode) {
      body.innerHTML = modeBar + '<div style="color:#64748b;font-size:12px;text-align:center;margin-top:35px;">请先在左侧选中节点编辑脚本，或切到上方 <b>模块 / 数据表</b></div>';
      return;
    }

    const mountHint = selNode.scriptFile
      ? `<div style="font-size:10px;color:#fbbf24;margin-bottom:4px;">⚡ 已挂载外部脚本: <b>scripts/${escHtml(selNode.scriptFile)}</b> <button style="padding:1px 5px;font-size:9px;" onclick="updateProp('scriptFile','')">解除挂载</button></div>`
      : '';
    body.innerHTML = modeBar + mountHint + `
      <div class="script-toolbar">
        <button onclick="insertCode('scriptEditor','self.createSkinnedMesh(8, 12);\\nself.setMeshWave(8, 2, 6, 4);\\n')">createSkinnedMesh</button>
        <button onclick="insertCode('scriptEditor','self.bindBones([\\'Spine\\', \\'Head\\'], 2.2);\\n')">bindBones</button>
        <button onclick="insertCode('scriptEditor','self.attachCloth({offsetY:-30,segments:10,segmentLen:18,widthStart:34,widthEnd:8,r:0.9,g:0.15,b:0.22,windX:-160});\\n')">attachCloth</button>
        <button onclick="insertCode('scriptEditor','Engine.solveIK(\\'Arm\\', \\'Forearm\\', Input.worldX, Input.worldY, 70, 65, false);\\n')">solveIK</button>
        <button onclick="insertCode('scriptEditor','Engine.setSdfShadow(true, 10, 0.08);\\n')">setSdfShadow</button>
        <button onclick="insertCode('scriptEditor','self.setNormalMap(\\'hero_n.png\\', 1.2, 0.4);\\n')">setNormalMap</button>
        <button onclick="insertCode('scriptEditor','self.triggerAnim(\\'attack\\');\\n')">triggerAnim</button>
        <button onclick="insertCode('scriptEditor','Engine.setDebugDraw(true);\\n')">DebugDraw</button>
        <button onclick="insertCode('scriptEditor','var s = Engine.getStats();\\n')">getStats</button>
      </div>
      <textarea id="scriptEditor" class="code-box" spellcheck="false" oninput="updateProp('script', this.value, true)">${escHtml(selNode.script || '')}</textarea>`;
    return;
  }

  if (selectedIndex < 0 || !nodes[selectedIndex]) {
    body.innerHTML = '<div style="color:#64748b;font-size:12px;text-align:center;margin-top:45px;">请在左侧节点树或视口中选择节点（或点上方 +精 / +瓦 创建）</div>';
    return;
  }

  const n = nodes[selectedIndex];

  if (activeTab === 'shader') {
    body.innerHTML = `
      <div class="script-toolbar">
        <button onclick="applyDefaultShaderTemplate()">载入自定义GLSL模板</button>
        <button onclick="updateProp('shader', '');renderDock();">恢复默认实时光照Shader</button>
      </div>
      <textarea id="shaderEditor" class="code-box" spellcheck="false" placeholder="留空则自动使用内置2.5D多光源/P8法线光照Shader..." oninput="updateProp('shader', this.value, true)">${escHtml(n.shader || '')}</textarea>`;
    return;
  }

  const parentOptions = [`<option value="">(根节点 Root)</option>`]
    .concat(nodes.filter((_, idx) => idx !== selectedIndex).map(p => `<option value="${escHtml(p.name)}" ${n.parent === p.name ? 'selected' : ''}>${escHtml(p.name)}</option>`)).join('');
  const texNames = Array.from(new Set([...Object.keys(imageCache), ...(n.texture ? [n.texture] : []), ...(n.normalMap ? [n.normalMap] : [])]));
  const texOptions = [`<option value="">(纯色方块)</option>`]
    .concat(texNames.map(t => `<option value="${escHtml(t)}" ${n.texture === t ? 'selected' : ''}>${escHtml(t)}</option>`)).join('');
  const normalOptions = [`<option value="">(无 - 平面光照)</option>`]
    .concat(texNames.map(t => `<option value="${escHtml(t)}" ${n.normalMap === t ? 'selected' : ''}>${escHtml(t)}</option>`)).join('');
  const scriptFileOptions = [`<option value="">(使用节点内嵌脚本)</option>`]
    .concat(Array.from(new Set([...scriptAssets, ...(n.scriptFile ? [n.scriptFile] : [])])).map(s => `<option value="${escHtml(s)}" ${n.scriptFile === s ? 'selected' : ''}>scripts/${escHtml(s)}</option>`)).join('');

  const clipsHtml = (n.clips || []).map((c, cIdx) => `
    <div class="clip-row">
      <input value="${escHtml(c.name)}" placeholder="名" onchange="updateClip(${cIdx}, 'name', this.value)">
      <input type="number" min="0" value="${c.start}" title="起" onchange="updateClip(${cIdx}, 'start', +this.value)">
      <input type="number" min="0" value="${c.end}" title="止" onchange="updateClip(${cIdx}, 'end', +this.value)">
      <input type="number" min="1" value="${c.fps}" title="FPS" onchange="updateClip(${cIdx}, 'fps', +this.value)">
      <input value="${escHtml(c.eventsStr || '')}" placeholder="帧事件 3:hit" onchange="updateClip(${cIdx}, 'eventsStr', this.value)">
      <label style="display:flex;align-items:center;gap:1px;color:#94a3b8;"><input type="checkbox" ${c.loop ? 'checked' : ''} onchange="updateClip(${cIdx}, 'loop', this.checked)">环</label>
      <button class="danger" style="padding:2px 5px;font-size:10px;" onclick="deleteClip(${cIdx})">×</button>
    </div>`).join('');

  let extraFields = '';
  if (n.nodeType === 'TileMap') {
    extraFields = `
      <div class="field"><label>图集贴图 (Tileset)</label><select onchange="updateProp('texture', this.value)">${texOptions}</select></div>
      <div class="field"><label>瓦片尺寸 (TileSize)</label><input type="number" min="16" value="${n.tileSize || 90}" oninput="updateProp('tileSize', Math.max(16,+this.value), true)"></div>
      <div class="field"><label>图集列数 (H-Frames)</label><input type="number" min="1" value="${n.hFrames || 1}" onchange="updateProp('hFrames', Math.max(1,+this.value))"></div>
      <div class="field"><label>图集行数 (V-Frames)</label><input type="number" min="1" value="${n.vFrames || 1}" onchange="updateProp('vFrames', Math.max(1,+this.value))"></div>
      <div class="field"><label>纯色模式颜色</label><input type="color" value="${rgbToHex(n.r, n.g, n.b)}" oninput="updateColor(this.value)"></div>
      <div class="field"><label>当前画笔图块帧号</label><input type="number" min="0" value="${brushFrame}" oninput="brushFrame = Math.max(0,+this.value);draw();"></div>`;
  } else if (n.nodeType === 'PointLight') {
    extraFields = `
      <div class="field"><label>光照半径 (Radius)</label><input type="number" value="${n.lightRadius || 450}" oninput="updateProp('lightRadius', +this.value, true)"></div>
      <div class="field"><label>光照强度 (Intensity)</label><input type="number" step="0.1" value="${n.lightIntensity || 1.4}" oninput="updateProp('lightIntensity', +this.value, true)"></div>
      <div class="field"><label>灯光颜色</label><input type="color" value="${rgbToHex(n.r, n.g, n.b)}" oninput="updateColor(this.value)"></div>`;
  } else if (n.nodeType === 'ParticleEmitter') {
    extraFields = `
      <div class="field"><label>发射速率 (/秒)</label><input type="number" value="${n.emitRate ?? 24}" oninput="updateProp('emitRate', +this.value, true)"></div>
      <div class="field"><label>粒子寿命 (秒)</label><input type="number" step="0.1" value="${n.particleLife ?? 1.0}" oninput="updateProp('particleLife', +this.value, true)"></div>
      <div class="field"><label>初速度</label><input type="number" value="${n.particleSpeed ?? 150}" oninput="updateProp('particleSpeed', +this.value, true)"></div>
      <div class="field"><label>粒子大小</label><input type="number" value="${n.particleSize ?? 16}" oninput="updateProp('particleSize', +this.value, true)"></div>
      <div class="field"><label>重力加速度</label><input type="number" value="${n.particleGravity ?? -60}" oninput="updateProp('particleGravity', +this.value, true)"></div>
      <div class="field"><label>粒子颜色</label><input type="color" value="${rgbToHex(n.r, n.g, n.b)}" oninput="updateColor(this.value)"></div>`;
  } else {
    extraFields = `
      <div class="field"><label>贴图资源</label><select onchange="updateProp('texture', this.value)">${texOptions}</select></div>
      <div class="field"><label>混合颜色</label><input type="color" value="${rgbToHex(n.r, n.g, n.b)}" oninput="updateColor(this.value)"></div>
      <div class="field"><label>透明度 (0~1)</label><input type="number" step="0.1" value="${n.a ?? 1}" oninput="updateProp('a', +this.value, true)"></div>
      <div class="field"><label>序列帧列数 (H-Frames)</label><input type="number" min="1" value="${n.hFrames || 1}" onchange="updateProp('hFrames', Math.max(1,+this.value))"></div>
      <div class="field"><label>序列帧行数 (V-Frames)</label><input type="number" min="1" value="${n.vFrames || 1}" onchange="updateProp('vFrames', Math.max(1,+this.value))"></div>`;
  }

  const physicsExtraHtml = (n.bodyMode === 'platformer' || n.bodyMode === 'rigid') ? `
    <div class="field"><label>重力加速度 (Gravity)</label><input type="number" value="${n.gravity ?? 2200}" oninput="updateProp('gravity', +this.value, true)"></div>
    <div class="field"><label>下落倍率 (FallMul)</label><input type="number" step="0.1" value="${n.fallMultiplier ?? 1.55}" oninput="updateProp('fallMultiplier', +this.value, true)"></div>
    <div class="field"><label>最大跳跃段数 (MaxJumps)</label><input type="number" min="1" value="${n.maxJumps ?? 2}" onchange="updateProp('maxJumps', Math.max(1,+this.value))"></div>
    <div class="field"><label>弹性系数 (Bounciness)</label><input type="number" step="0.1" value="${n.bounciness ?? 0.5}" oninput="updateProp('bounciness', +this.value, true)"></div>` : '';

  const tileBrushBar = n.nodeType === 'TileMap' ? `
    <div class="clip-box" style="margin-bottom:8px;margin-top:0;">
      <div style="display:flex;justify-content:space-between;align-items:center;gap:4px;flex-wrap:wrap;">
        <span style="font-size:11px;font-weight:700;color:#38bdf8;">🧱 瓦片画笔 (${(n.tiles||[]).length} 格)</span>
        <div style="display:flex;gap:3px;">
          <button class="${tileBrushMode==='off'?'active-toggle':''}" style="padding:2px 5px;font-size:10px;" onclick="tileBrushMode='off';renderDock();draw();">拖动</button>
          <button class="${tileBrushMode==='paint'?'active-toggle':''}" style="padding:2px 5px;font-size:10px;" onclick="tileBrushMode='paint';renderDock();draw();">🖌️刷</button>
          <button class="${tileBrushMode==='erase'?'active-toggle':''}" style="padding:2px 5px;font-size:10px;" onclick="tileBrushMode='erase';renderDock();draw();">擦除</button>
          <button class="danger" style="padding:2px 5px;font-size:10px;" onclick="clearAllTiles()">清空</button>
        </div>
      </div>
      ${buildTilePaletteHtml(n)}
    </div>` : '';

  body.innerHTML = `
    <div style="display:flex;gap:6px;align-items:center;margin-bottom:7px;">
      <input style="background:#090c12;border:1px solid #334155;color:#fbbf24;font-weight:700;padding:4px 6px;border-radius:4px;font-size:12px;flex:1;" value="${escHtml(n.name)}" onchange="updateProp('name', this.value)">
      <button class="anim-btn" onclick="AnimStudio.open()">🎬动画工作台</button>
      <button onclick="duplicateSelected()">复制</button>
      <button class="danger" onclick="deleteSelected()">删除</button>
    </div>
    ${tileBrushBar}
    <div class="grid">
      <div class="field"><label>渲染图层 (Layer)</label>
        <select onchange="updateProp('layer', this.value)">
          <option value="World" ${n.layer !== 'UI' ? 'selected' : ''}>World (2D/2.5D空间)</option>
          <option value="UI" ${n.layer === 'UI' ? 'selected' : ''}>UI (固定屏幕HUD层)</option>
        </select>
      </div>
      <div class="field"><label>父节点 (Hierarchy)</label><select onchange="updateProp('parent', this.value)">${parentOptions}</select></div>
      <div class="field"><label>局部 X</label><input type="number" value="${n.x}" oninput="updateProp('x', +this.value, true)"></div>
      <div class="field"><label>局部 Y</label><input type="number" value="${n.y}" oninput="updateProp('y', +this.value, true)"></div>
      <div class="field"><label>空间高度 Z</label><input type="number" value="${n.z || 0}" oninput="updateProp('z', +this.value, true)"></div>
      <div class="field"><label>节点标签 (Tag)</label><input value="${escHtml(n.tag || 'default')}" onchange="updateProp('tag', this.value)"></div>
      <div class="field"><label>宽度</label><input type="number" value="${n.width}" oninput="updateProp('width', +this.value, true)"></div>
      <div class="field"><label>高度</label><input type="number" value="${n.height}" oninput="updateProp('height', +this.value, true)"></div>
      <div class="field"><label>Z轴旋转</label><input type="number" value="${n.rotation || 0}" oninput="updateProp('rotation', +this.value, true)"></div>
      <div class="field"><label>X轴倾角 (TiltX)</label><input type="number" value="${n.tiltX || 0}" oninput="updateProp('tiltX', +this.value, true)"></div>
      <div class="field"><label>视差 X (ParallaxX)</label><input type="number" step="0.05" value="${n.parallaxX ?? 1}" oninput="updateProp('parallaxX', +this.value, true)"></div>
      <div class="field"><label>视差 Y (ParallaxY)</label><input type="number" step="0.05" value="${n.parallaxY ?? 1}" oninput="updateProp('parallaxY', +this.value, true)"></div>
      ${extraFields}
      <div class="field"><label>挂载外部脚本 (scriptFile)</label><select onchange="updateProp('scriptFile', this.value)">${scriptFileOptions}</select></div>
      <div class="field row"><input type="checkbox" ${n.persistent ? 'checked' : ''} onchange="updateProp('persistent', this.checked)"><label>跨场景常驻 (Persistent)</label></div>

      <div class="sec-title">💎 P8 2D法线材质与 SDF 软阴影</div>
      <div class="field"><label>法线贴图 (normalMap)</label><select onchange="updateProp('normalMap', this.value)">${normalOptions}</select></div>
      <div class="field"><label>法线凹凸强度 (0~2.5)</label><input type="number" step="0.1" min="0" max="2.5" value="${n.normalStrength ?? 1.0}" oninput="updateProp('normalStrength', +this.value, true)"></div>
      <div class="field"><label>高光反射 (Specular)</label><input type="number" step="0.05" min="0" max="3" value="${n.specular ?? 0.25}" oninput="updateProp('specular', +this.value, true)"></div>
      <div class="field row"><input type="checkbox" ${n.shadowCaster ? 'checked' : ''} onchange="updateProp('shadowCaster', this.checked)"><label>SDF 投射软阴影</label></div>

      <div class="sec-title">🖼️ UI 九宫格 (9-Slice) 与裁剪区 (P5)</div>
      <div class="field"><label>九宫格 左边距 (sliceLeft)</label><input type="number" min="0" value="${n.sliceLeft ?? 0}" oninput="updateProp('sliceLeft', Math.max(0,+this.value), true)"></div>
      <div class="field"><label>九宫格 右边距 (sliceRight)</label><input type="number" min="0" value="${n.sliceRight ?? 0}" oninput="updateProp('sliceRight', Math.max(0,+this.value), true)"></div>
      <div class="field"><label>九宫格 上边距 (sliceTop)</label><input type="number" min="0" value="${n.sliceTop ?? 0}" oninput="updateProp('sliceTop', Math.max(0,+this.value), true)"></div>
      <div class="field"><label>九宫格 下边距 (sliceBottom)</label><input type="number" min="0" value="${n.sliceBottom ?? 0}" oninput="updateProp('sliceBottom', Math.max(0,+this.value), true)"></div>
      <div class="field"><label>裁剪框 X (clipX)</label><input type="number" value="${n.clipX ?? 0}" oninput="updateProp('clipX', +this.value, true)"></div>
      <div class="field"><label>裁剪框 Y (clipY)</label><input type="number" value="${n.clipY ?? 0}" oninput="updateProp('clipY', +this.value, true)"></div>
      <div class="field"><label>裁剪框 宽 (0=不裁)</label><input type="number" min="0" value="${n.clipW ?? 0}" oninput="updateProp('clipW', Math.max(0,+this.value), true)"></div>
      <div class="field"><label>裁剪框 高 (0=不裁)</label><input type="number" min="0" value="${n.clipH ?? 0}" oninput="updateProp('clipH', Math.max(0,+this.value), true)"></div>
      <div class="field row"><input type="checkbox" ${n.clipChildren ? 'checked' : ''} onchange="updateProp('clipChildren', this.checked)"><label>裁剪子节点 (ClipChildren)</label></div>
      <div class="field"></div>

      <div class="sec-title">⚙️ 物理、独立碰撞体 (P1) 与战斗组件</div>
      <div class="field"><label>物理模式 (BodyMode)</label>
        <select onchange="updateProp('bodyMode', this.value)">
          <option value="kinematic" ${n.bodyMode === 'kinematic' ? 'selected' : ''}>kinematic (自由8向/俯视角)</option>
          <option value="platformer" ${n.bodyMode === 'platformer' ? 'selected' : ''}>platformer (横版重力平台)</option>
          <option value="rigid" ${n.bodyMode === 'rigid' ? 'selected' : ''}>rigid (弹跳抛物线刚体)</option>
        </select>
      </div>
      <div class="field"><label>斜坡形状 (SlopeType)</label>
        <select onchange="updateProp('slopeType', this.value)">
          <option value="none" ${n.slopeType === 'none' ? 'selected' : ''}>none (标准矩形方块)</option>
          <option value="left_up" ${n.slopeType === 'left_up' ? 'selected' : ''}>left_up (左低右高 / 坡道)</option>
          <option value="right_up" ${n.slopeType === 'right_up' ? 'selected' : ''}>right_up (左高右低 \ 坡道)</option>
        </select>
      </div>
      <div class="field"><label>碰撞宽 (0=同节点)</label><input type="number" value="${n.colWidth ?? 0}" oninput="updateProp('colWidth', +this.value, true)"></div>
      <div class="field"><label>碰撞高 (0=同节点)</label><input type="number" value="${n.colHeight ?? 0}" oninput="updateProp('colHeight', +this.value, true)"></div>
      <div class="field"><label>碰撞偏移 X</label><input type="number" value="${n.colOffsetX ?? 0}" oninput="updateProp('colOffsetX', +this.value, true)"></div>
      <div class="field"><label>碰撞偏移 Y</label><input type="number" value="${n.colOffsetY ?? 0}" oninput="updateProp('colOffsetY', +this.value, true)"></div>
      <div class="field"><label>碰撞层 (colLayer)</label><input type="number" value="${n.colLayer ?? 1}" oninput="updateProp('colLayer', +this.value, true)"></div>
      <div class="field"><label>碰撞掩码 (colMask)</label><input type="number" value="${n.colMask ?? 65535}" oninput="updateProp('colMask', +this.value, true)"></div>
      <div class="field"><label>攻击伤害值 (Damage)</label><input type="number" value="${n.damage ?? 10}" oninput="updateProp('damage', +this.value, true)"></div>
      ${physicsExtraHtml}
      <div class="field row"><input type="checkbox" ${n.billboard ? 'checked' : ''} onchange="updateProp('billboard', this.checked)"><label>HD-2D 立牌</label></div>
      <div class="field row"><input type="checkbox" ${n.collider ? 'checked' : ''} onchange="updateProp('collider', this.checked)"><label>开启碰撞体</label></div>
      <div class="field row"><input type="checkbox" ${n.isTrigger ? 'checked' : ''} onchange="updateProp('isTrigger', this.checked)"><label>触发器 (不阻挡)</label></div>
      <div class="field row"><input type="checkbox" ${n.oneWay ? 'checked' : ''} onchange="updateProp('oneWay', this.checked)"><label>单向平台 (可上穿)</label></div>
      <div class="field row"><input type="checkbox" ${n.isHitbox ? 'checked' : ''} onchange="updateProp('isHitbox', this.checked)"><label>攻击框 (Hitbox)</label></div>
      <div class="field row"><input type="checkbox" ${n.isHurtbox ? 'checked' : ''} onchange="updateProp('isHurtbox', this.checked)"><label>受击框 (Hurtbox)</label></div>
    </div>
    ${n.nodeType === 'Sprite2D' ? `
      <div class="clip-box">
        <div style="display:flex;justify-content:space-between;align-items:center;">
          <span style="font-size:11px;font-weight:700;color:#38bdf8;">🎬 动画片段库 (旧版快捷列表)</span>
          <button style="padding:2px 6px;font-size:10px;" onclick="addClip()">+新增动画</button>
        </div>
        ${clipsHtml || '<div style="font-size:10px;color:#64748b;margin-top:4px;">建议使用上方 <b>🎬动画工作台</b> 编辑专业时间轴、洋葱皮与帧判定框</div>'}
      </div>` : ''}`;
}

function refreshAll() { renderTree(); renderDock(); draw(); }