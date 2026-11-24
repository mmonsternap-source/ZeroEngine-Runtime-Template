// ==================== ZeroEngine 编辑器核心状态、工程/场景、撤销重做与资源管理 ====================

const imageCache = {};
let audioAssets = [];
let scriptAssets = [];
let dataAssets = [];
let scriptTabMode = 'node'; // 'node' | 'module' | 'data'
let activeScriptFile = '';
let activeScriptContent = '';
let activeDataFile = '';
let activeDataContent = '';

let activeTab = 'inspector';
let selectedIndex = 0;
let currentScene = 'main.scene';
let snapGrid = true;
const GRID_SIZE = 30;

let tileBrushMode = 'off';
let brushFrame = 0;

let cameraCfg = { mode: '2.5D', pitch: 48, distance: 1250 };
let camX = 540, camY = 960, camZoom = 0.42;
let dragMode = null, dragStartWorld = { x: 0, y: 0 }, origNodeState = null;
let lastPanScreen = { x: 0, y: 0 }, lastPinchDist = 0;
let modalCallback = null;

const MAX_HISTORY = 40;
let undoStack = [];
let redoStack = [];
let pendingSnapshot = null;
let inputDebounceTimer = null;

let nodes = defaultNodes();

function escHtml(s) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function serializeNodesForEngine() {
  return nodes.map(raw => {
    const copy = JSON.parse(JSON.stringify(raw));
    if (Array.isArray(copy.clips)) {
      copy.clips = copy.clips.map(c => {
        const evObj = {};
        if (c.eventsStr && typeof c.eventsStr === 'string') {
          c.eventsStr.split(',').forEach(pair => {
            const parts = pair.split(':');
            if (parts.length === 2) {
              const f = parseInt(parts[0].trim(), 10);
              const ev = parts[1].trim();
              if (!isNaN(f) && ev) evObj[f] = ev;
            }
          });
        } else if (c.events && typeof c.events === 'object') {
          Object.assign(evObj, c.events);
        }
        return { name: c.name, start: c.start, end: c.end, fps: c.fps, loop: c.loop, events: evObj };
      });
    }
    return copy;
  });
}

function createSnapshot() {
  return JSON.stringify({ camera: cameraCfg, nodes, selectedIndex });
}

function updateUndoRedoButtons() {
  const uBtn = document.getElementById('undoBtn');
  const rBtn = document.getElementById('redoBtn');
  if (uBtn) uBtn.disabled = undoStack.length === 0;
  if (rBtn) rBtn.disabled = redoStack.length === 0;
}

function clearHistory() {
  undoStack = [];
  redoStack = [];
  pendingSnapshot = null;
  updateUndoRedoButtons();
}

function recordUndoPoint(snapStr = createSnapshot()) {
  if (undoStack.length > 0 && undoStack[undoStack.length - 1] === snapStr) return;
  undoStack.push(snapStr);
  if (undoStack.length > MAX_HISTORY) undoStack.shift();
  redoStack = [];
  updateUndoRedoButtons();
}

function restoreSnapshot(snapStr) {
  try {
    const data = JSON.parse(snapStr);
    if (data.camera) cameraCfg = data.camera;
    nodes = Array.isArray(data.nodes) ? data.nodes.map(normalizeNode) : [];
    nodes.forEach(n => { if (n.texture) preloadLocalImage(n.texture); });
    selectedIndex = Math.min(nodes.length - 1, data.selectedIndex ?? (nodes.length > 0 ? 0 : -1));
    if (selectedIndex < 0 || nodes[selectedIndex]?.nodeType !== 'TileMap') tileBrushMode = 'off';
    document.getElementById('modeBtn').textContent = cameraCfg.mode;
    autoSave();
    refreshAll();
    updateUndoRedoButtons();
  } catch (e) {}
}

function undo() {
  if (undoStack.length === 0) return;
  const currentSnap = createSnapshot();
  const prevSnap = undoStack.pop();
  redoStack.push(currentSnap);
  restoreSnapshot(prevSnap);
  showToast(`已撤销 (剩余 ${undoStack.length} 步)`);
}

function redo() {
  if (redoStack.length === 0) return;
  const currentSnap = createSnapshot();
  const nextSnap = redoStack.pop();
  undoStack.push(currentSnap);
  restoreSnapshot(nextSnap);
  showToast(`已重做 (剩余 ${redoStack.length} 步)`);
}

function showToast(msg) {
  const bar = document.getElementById('toastBar');
  bar.textContent = msg;
  bar.style.display = 'block';
  clearTimeout(bar._timer);
  bar._timer = setTimeout(() => { bar.style.display = 'none'; }, 2200);
}

function openNewMenuModal() {
  document.getElementById('newModalPathHint').textContent = getSaveFolderPath();
  document.getElementById('newMenuModal').style.display = 'flex';
}
function closeNewMenuModal() { document.getElementById('newMenuModal').style.display = 'none'; }

function getSaveFolderPath() {
  if (window.ZeroBridge && window.ZeroBridge.getSaveLocation) {
    return window.ZeroBridge.getSaveLocation();
  }
  return `/storage/emulated/0/Android/data/com.zero.engine/files/ZeroEngine/projects/DefaultProject`;
}

function showSaveLocationModal(isStartup = false) {
  document.getElementById('pathModalTitle').textContent = isStartup
    ? "📂 开始前：选择工程 Save 文件夹位置"
    : "📂 工程文件夹 Save 位置";
  document.getElementById('savePathText').textContent = getSaveFolderPath();
  document.getElementById('pathModal').style.display = 'flex';
}

function chooseSaveFolderNative() {
  if (window.ZeroBridge && window.ZeroBridge.pickSaveLocation) window.ZeroBridge.pickSaveLocation();
}

function onSaveLocationPicked() {
  const path = getSaveFolderPath();
  document.getElementById('savePathText').textContent = path;
  document.getElementById('newModalPathHint').textContent = path;
  if (activeTab === 'docs') renderDock();
  showToast("已选择并绑定工程保存文件夹！");
}

function resetDefaultFolder() {
  if (window.ZeroBridge && window.ZeroBridge.resetDefaultSaveLocation) {
    window.ZeroBridge.resetDefaultSaveLocation();
  }
  document.getElementById('savePathText').textContent = getSaveFolderPath();
  if (activeTab === 'docs') renderDock();
  showToast("已恢复默认内部存档目录");
}

function copySavePath() {
  const txt = document.getElementById('savePathText').textContent;
  if (navigator.clipboard) navigator.clipboard.writeText(txt);
  showToast("已复制存档路径");
}

function flushActiveEditorsSilent() {
  if (activeTab === 'script' && scriptTabMode === 'module' && activeScriptFile) saveActiveScriptModule(true);
  if (activeTab === 'script' && scriptTabMode === 'data' && activeDataFile) saveActiveDataFile(true);
}

function manualSave() {
  flushActiveEditorsSilent();
  autoSave();
  const proj = (window.ZeroBridge && window.ZeroBridge.getCurrentProject) ? window.ZeroBridge.getCurrentProject() : 'DefaultProject';
  showToast(`💾 已保存: ${proj}/${currentScene}`);
}

function openInputModal(title, defaultVal, cb) {
  modalCallback = cb;
  document.getElementById('modalTitle').textContent = title;
  const inp = document.getElementById('modalInput');
  inp.value = defaultVal;
  document.getElementById('inputModal').style.display = 'flex';
  setTimeout(() => { inp.focus(); inp.select(); }, 50);
}

function closeInputModal() {
  document.getElementById('inputModal').style.display = 'none';
  modalCallback = null;
}

function confirmInputModal() {
  const val = document.getElementById('modalInput').value.trim();
  const cb = modalCallback;
  closeInputModal();
  if (val && cb) cb(val);
}

// ==================== 外部脚本模块管理 (scripts/*.js) ====================
function refreshScriptList() {
  if (!window.ZeroBridge || !window.ZeroBridge.listScripts) { scriptAssets = []; return; }
  try { scriptAssets = JSON.parse(window.ZeroBridge.listScripts()); } catch (e) { scriptAssets = []; }
  if (scriptAssets.length === 0) {
    activeScriptFile = ''; activeScriptContent = '';
  } else if (!scriptAssets.includes(activeScriptFile)) {
    activeScriptFile = scriptAssets[0];
    activeScriptContent = window.ZeroBridge.loadScriptFile(activeScriptFile) || '';
  }
}

function selectScriptModule(fileName) {
  activeScriptFile = fileName;
  activeScriptContent = (window.ZeroBridge && window.ZeroBridge.loadScriptFile && fileName)
    ? (window.ZeroBridge.loadScriptFile(fileName) || '') : '';
  renderDock();
}

function openCreateScriptModal() {
  openInputModal("新建外部 JS 模块 (scripts/*.js)", "combat_config.js", (rawName) => {
    let safe = rawName.replace(/[^\u4e00-\u9fa5a-zA-Z0-9._-]/g, '_');
    if (!safe) { showToast("请输入有效的脚本文件名"); return; }
    if (!safe.toLowerCase().endsWith('.js')) safe += '.js';
    const tpl = `// 外部模块: scripts/${safe}\n// 在任意节点脚本中用 require("${safe}") 引入\nmodule.exports = {\n  baseDamage: 25,\n  critRate: 0.2\n};\n`;
    if (window.ZeroBridge && window.ZeroBridge.saveScriptFile && !window.ZeroBridge.saveScriptFile(safe, tpl)) {
      showToast("创建脚本失败"); return;
    }
    refreshScriptList();
    activeScriptFile = safe; activeScriptContent = tpl;
    scriptTabMode = 'module';
    switchTab('script');
    showToast(`📜 已创建: scripts/${safe}`);
  });
}

function saveActiveScriptModule(silent = false) {
  if (!activeScriptFile) return;
  const ta = document.getElementById('moduleEditor');
  if (ta) activeScriptContent = ta.value;
  if (window.ZeroBridge && window.ZeroBridge.saveScriptFile) {
    window.ZeroBridge.saveScriptFile(activeScriptFile, activeScriptContent);
  }
  if (!silent) showToast(`💾 已保存模块: scripts/${activeScriptFile}`);
}

function deleteActiveScriptModule() {
  if (!activeScriptFile) return;
  const target = activeScriptFile;
  if (window.ZeroBridge && window.ZeroBridge.deleteScriptFile) window.ZeroBridge.deleteScriptFile(target);
  activeScriptFile = ''; activeScriptContent = '';
  refreshScriptList(); renderDock();
  showToast(`已删除: scripts/${target}`);
}

function insertRequireToSelectedNode() {
  if (!activeScriptFile) { showToast("请先新建或选择一个模块"); return; }
  if (selectedIndex < 0 || !nodes[selectedIndex]) { showToast("请先在左侧选中一个节点"); return; }
  const varName = activeScriptFile.replace(/\.js$/i, '').replace(/[^a-zA-Z0-9_]/g, '_') || 'mod';
  recordUndoPoint();
  nodes[selectedIndex].script = `var ${varName} = require("${activeScriptFile}");\n` + (nodes[selectedIndex].script || '');
  scriptTabMode = 'node';
  autoSave(); renderTree(); renderDock();
  showToast(`已插入 require("${activeScriptFile}")`);
}

function mountActiveModuleToNode() {
  if (!activeScriptFile) { showToast("请先新建或选择一个模块"); return; }
  if (selectedIndex < 0 || !nodes[selectedIndex]) { showToast("请先在左侧选中一个节点"); return; }
  recordUndoPoint();
  nodes[selectedIndex].scriptFile = activeScriptFile;
  autoSave(); renderTree();
  showToast(`已将 ${activeScriptFile} 挂载到 ${nodes[selectedIndex].name}`);
}

// ==================== 数据表与多语言管理 (data/*.json) ====================
function refreshDataList() {
  if (!window.ZeroBridge || !window.ZeroBridge.listDataFiles) { dataAssets = []; return; }
  try { dataAssets = JSON.parse(window.ZeroBridge.listDataFiles()); } catch (e) { dataAssets = []; }
  if (dataAssets.length === 0) {
    activeDataFile = ''; activeDataContent = '';
  } else if (!dataAssets.includes(activeDataFile)) {
    activeDataFile = dataAssets[0];
    activeDataContent = window.ZeroBridge.loadDataFile(activeDataFile) || '';
  }
}

function selectDataFile(fileName) {
  activeDataFile = fileName;
  activeDataContent = (window.ZeroBridge && window.ZeroBridge.loadDataFile && fileName)
    ? (window.ZeroBridge.loadDataFile(fileName) || '') : '';
  renderDock();
}

function openCreateDataModal() {
  openInputModal("新建数据表 / 多语言 (data/*.json)", "weapons.json", (rawName) => {
    let safe = rawName.replace(/[^\u4e00-\u9fa5a-zA-Z0-9._-]/g, '_');
    if (!safe) { showToast("请输入有效的文件名"); return; }
    if (!safe.toLowerCase().endsWith('.json')) safe += '.json';
    const isI18n = safe.toLowerCase().startsWith('i18n');
    const tpl = isI18n
      ? JSON.stringify({ zh: { title: "苍白圣殿", boss_clear: "苍白织者已击败" }, en: { title: "Pale Sanctum", boss_clear: "Pale Weaver Defeated" } }, null, 2)
      : JSON.stringify({ needle_lv1: { name: "骨针", dmg: 18, speed: 1.0 }, needle_lv2: { name: "丝钢长针", dmg: 32, speed: 1.15 } }, null, 2);
    if (window.ZeroBridge && window.ZeroBridge.saveDataFile && !window.ZeroBridge.saveDataFile(safe, tpl)) {
      showToast("创建数据表失败"); return;
    }
    refreshDataList();
    activeDataFile = safe; activeDataContent = tpl;
    scriptTabMode = 'data';
    switchTab('script');
    showToast(`📊 已创建: data/${safe}`);
  });
}

function saveActiveDataFile(silent = false) {
  if (!activeDataFile) return;
  const ta = document.getElementById('dataEditor');
  if (ta) activeDataContent = ta.value;
  try { JSON.parse(activeDataContent); } catch (e) {
    if (!silent) showToast("❌ JSON 语法错误: " + e.message);
    return;
  }
  if (window.ZeroBridge && window.ZeroBridge.saveDataFile) {
    window.ZeroBridge.saveDataFile(activeDataFile, activeDataContent);
  }
  if (!silent) showToast(`💾 已保存数据表: data/${activeDataFile}`);
}

function deleteActiveDataFile() {
  if (!activeDataFile) return;
  const target = activeDataFile;
  if (window.ZeroBridge && window.ZeroBridge.deleteDataFile) window.ZeroBridge.deleteDataFile(target);
  activeDataFile = ''; activeDataContent = '';
  refreshDataList(); renderDock();
  showToast(`已删除: data/${target}`);
}

function insertLoadTableToNode() {
  if (!activeDataFile) { showToast("请先新建或选择一个数据表"); return; }
  if (selectedIndex < 0 || !nodes[selectedIndex]) { showToast("请先在左侧选中一个节点"); return; }
  const varName = activeDataFile.replace(/\.json$/i, '').replace(/[^a-zA-Z0-9_]/g, '_') || 'tbl';
  recordUndoPoint();
  nodes[selectedIndex].script = `var ${varName} = Engine.loadTable("${activeDataFile}");\n` + (nodes[selectedIndex].script || '');
  scriptTabMode = 'node';
  autoSave(); renderTree(); renderDock();
  showToast(`已插入 loadTable("${activeDataFile}")`);
}

// ==================== 工程、场景与资源导入 ====================
function refreshAudioList() {
  if (!window.ZeroBridge || !window.ZeroBridge.listAudioFiles) return;
  try { audioAssets = JSON.parse(window.ZeroBridge.listAudioFiles()); } catch (e) { audioAssets = []; }
}

function refreshProjectAndSceneLists() {
  if (!window.ZeroBridge) return;
  const projs = JSON.parse(window.ZeroBridge.listProjects());
  const curP = window.ZeroBridge.getCurrentProject();
  document.getElementById('projSelect').innerHTML = projs.map(p => `<option value="${p}" ${p === curP ? 'selected' : ''}>工程:${p}</option>`).join('');
  const scenes = JSON.parse(window.ZeroBridge.listScenes());
  if (!scenes.includes(currentScene)) currentScene = scenes[0] || 'main.scene';
  document.getElementById('sceneSelect').innerHTML = scenes.map(s => `<option value="${s}" ${s === currentScene ? 'selected' : ''}>${s}</option>`).join('');
}

function loadCurrentSceneFromDisk() {
  if (!window.ZeroBridge) return;
  const raw = window.ZeroBridge.loadSceneFile(currentScene);
  if (raw) {
    try {
      const parsed = JSON.parse(raw);
      if (parsed.camera) cameraCfg = parsed.camera;
      nodes = Array.isArray(parsed.nodes) ? parsed.nodes.map(normalizeNode) : [];
      nodes.forEach(n => {
        if (n.texture) preloadLocalImage(n.texture);
        n.clips.forEach(c => {
          if (!c.eventsStr && c.events && typeof c.events === 'object') {
            c.eventsStr = Object.entries(c.events).map(([k, v]) => `${k}:${v}`).join(',');
          }
        });
      });
    } catch (e) { nodes = defaultNodes(); }
  } else {
    nodes = currentScene === 'main.scene' ? defaultNodes() : [];
    autoSave();
  }
  document.getElementById('modeBtn').textContent = cameraCfg.mode;
  selectedIndex = nodes.length > 0 ? 0 : -1;
  clearHistory();
}

function toggleCamMode() {
  recordUndoPoint();
  cameraCfg.mode = cameraCfg.mode === '2.5D' ? '2D' : '2.5D';
  document.getElementById('modeBtn').textContent = cameraCfg.mode;
  autoSave(); draw();
}

function openCreateProjectModal(tplType) {
  const titleMap = {
    empty: "新建空白工程 (支持中文/英文/数字)",
    rpg25d: "新建 2.5D 探索模板工程",
    platformer: "新建 2D 横版动作模板工程"
  };
  openInputModal(titleMap[tplType] || "新建工程", "MyGame_" + (Date.now() % 1000), (name) => {
    const safeName = name.replace(/[^\u4e00-\u9fa5a-zA-Z0-9_-]/g, '');
    if (!safeName) { showToast("请输入有效的工程名称"); return; }
    const withTemplate = tplType !== 'empty';
    if (window.ZeroBridge) {
      if (window.ZeroBridge.createNewProject) window.ZeroBridge.createNewProject(safeName, withTemplate);
      else window.ZeroBridge.setProject(safeName);
    }
    currentScene = 'main.scene';
    if (tplType === 'platformer') {
      cameraCfg = { mode: '2D', pitch: 0, distance: 1250 };
      nodes = platformerTemplateNodes();
    } else if (tplType === 'rpg25d') {
      cameraCfg = { mode: '2.5D', pitch: 48, distance: 1250 };
      nodes = defaultNodes();
    } else {
      cameraCfg = { mode: '2D', pitch: 0, distance: 1250 };
      nodes = [];
    }
    document.getElementById('modeBtn').textContent = cameraCfg.mode;
    selectedIndex = nodes.length > 0 ? 0 : -1;
    activeScriptFile = ''; activeScriptContent = '';
    activeDataFile = ''; activeDataContent = '';
    clearHistory(); autoSave();
    refreshProjectAndSceneLists(); refreshAudioList(); refreshScriptList(); refreshDataList();
    refreshAll();
    showToast(`已创建工程 ${safeName}`);
  });
}

function switchProject(name) {
  if (!window.ZeroBridge) return;
  flushActiveEditorsSilent();
  window.ZeroBridge.setProject(name);
  currentScene = 'main.scene';
  activeScriptFile = ''; activeScriptContent = '';
  activeDataFile = ''; activeDataContent = '';
  refreshProjectAndSceneLists(); refreshAudioList(); refreshScriptList(); refreshDataList();
  loadCurrentSceneFromDisk(); refreshAll();
  showToast("已切换工程: " + name);
}

function openCreateSceneModal() {
  openInputModal("新建场景文件 (支持中文/英文/数字)", "level_" + (Date.now() % 100), (name) => {
    const safeName = name.replace(/[^\u4e00-\u9fa5a-zA-Z0-9._-]/g, '');
    if (!safeName) { showToast("请输入有效的场景文件名"); return; }
    currentScene = safeName.endsWith('.scene') ? safeName : safeName + '.scene';
    nodes = []; selectedIndex = -1;
    clearHistory(); autoSave();
    refreshProjectAndSceneLists(); refreshAll();
    showToast("已新建空白场景: " + currentScene);
  });
}

function switchScene(sceneName) {
  currentScene = sceneName;
  tileBrushMode = 'off';
  loadCurrentSceneFromDisk();
  refreshAll();
}

function toggleSnap() {
  snapGrid = !snapGrid;
  const btn = document.getElementById('snapBtn');
  btn.textContent = snapGrid ? '吸附' : '自由';
  btn.classList.toggle('active-toggle', snapGrid);
}

function resetView() { camX = 540; camY = 960; camZoom = 0.42; draw(); }

function exportBundle() {
  flushActiveEditorsSilent();
  autoSave();
  if (window.ZeroBridge && window.ZeroBridge.exportProjectBundle) window.ZeroBridge.exportProjectBundle();
}

function onExportFinished(ok) {
  showToast(ok ? "发布包 (.zip) 导出成功！" : "导出失败，请查看控制台");
}

function preloadLocalImage(fileName) {
  if (!window.ZeroBridge || imageCache[fileName]) return;
  const dataUrl = window.ZeroBridge.getTextureBase64(fileName);
  if (dataUrl) {
    const img = new Image();
    img.onload = () => { imageCache[fileName] = img; if (activeTab === 'inspector') renderDock(); draw(); };
    img.src = dataUrl;
  }
}

function pickNativeImage() { if (window.ZeroBridge) window.ZeroBridge.pickImage(); }
function pickNativeAudio() { if (window.ZeroBridge && window.ZeroBridge.pickAudio) window.ZeroBridge.pickAudio(); }

function onNativeAudioPicked(fileName) {
  refreshAudioList();
  if (activeTab === 'docs') renderDock();
  showToast("音频已导入: " + fileName);
}

function onNativeImagePicked(fileName, dataUrl, w, h) {
  const img = new Image();
  img.onload = () => {
    recordUndoPoint();
    imageCache[fileName] = img;
    if (selectedIndex >= 0 && nodes[selectedIndex]?.nodeType === 'TileMap') {
      nodes[selectedIndex].texture = fileName;
      showToast("已将图集绑定到 " + nodes[selectedIndex].name);
    } else {
      nodes.push(normalizeNode({
        name: fileName.replace(/\.[^.]+$/, ''), nodeType: "Sprite2D", layer: "World", parent: "", tag: "sprite",
        x: 540, y: 960, z: 0, width: w || img.width || 160, height: h || img.height || 160,
        rotation: 0, tiltX: 0, billboard: cameraCfg.mode === '2.5D', scaleX: 1, scaleY: 1, zIndex: 1,
        texture: fileName, r: 1, g: 1, b: 1, a: 1, collider: true
      }));
      selectedIndex = nodes.length - 1;
    }
    autoSave(); refreshAll();
  };
  img.src = dataUrl;
}

function autoSave() {
  if (window.ZeroBridge) {
    window.ZeroBridge.saveSceneFile(currentScene, JSON.stringify({ camera: cameraCfg, nodes: serializeNodesForEngine() }));
  }
}

function playScene() {
  flushActiveEditorsSilent();
  autoSave();
  if (window.ZeroBridge) {
    window.ZeroBridge.runGame(JSON.stringify({ camera: cameraCfg, nodes: serializeNodesForEngine() }));
  }
}

// ==================== 节点增删与快捷片段操作 ====================
function addNode(type) {
  recordUndoPoint();
  const idx = nodes.length + 1;
  const isEmitter = type === 'ParticleEmitter';
  const isLight = type === 'PointLight';
  const isTile = type === 'TileMap';
  nodes.push(normalizeNode({
    name: `${type}_${idx}`, nodeType: type, layer: "World", parent: "",
    tag: isLight ? 'light' : (isEmitter ? 'fx' : (isTile ? 'wall' : 'default')),
    x: isTile ? 0 : 540, y: isTile ? 0 : 960, z: isLight ? 60 : 0,
    width: isLight ? 60 : (isTile ? 90 : 140), height: isLight ? 60 : (isTile ? 90 : 140),
    billboard: type === 'Sprite2D' && cameraCfg.mode === '2.5D', zIndex: isEmitter ? 6 : 1,
    text: type === 'Label2D' ? 'Label' : '', fontSize: 44,
    r: isLight ? 1.0 : (isEmitter ? 1.0 : (isTile ? 0.36 : 0.25)),
    g: isLight ? 0.8 : (isEmitter ? 0.65 : (isTile ? 0.44 : 0.85)),
    b: isLight ? 0.35 : (isEmitter ? 0.2 : (isTile ? 0.56 : 0.65)),
    a: isEmitter ? 0.95 : 1,
    tileSize: 90, lightRadius: 500, lightIntensity: 1.6,
    emitRate: 24, particleLife: 1.0, particleSpeed: 150, particleSize: 16, particleGravity: -60,
    collider: type === 'Sprite2D' || isTile
  }));
  selectedIndex = nodes.length - 1;
  if (isTile) tileBrushMode = 'paint';
  autoSave(); refreshAll();
}

function addUINode() {
  recordUndoPoint();
  const idx = nodes.length + 1;
  nodes.push(normalizeNode({
    name: `UI_Button_${idx}`, nodeType: "Sprite2D", layer: "UI", parent: "", tag: "ui",
    x: 200, y: 1680, z: 0, width: 160, height: 160, zIndex: 100,
    r: 0.3, g: 0.6, b: 1.0, a: 0.85
  }));
  selectedIndex = nodes.length - 1;
  autoSave(); refreshAll();
}

function duplicateSelected() {
  if (selectedIndex < 0) return;
  recordUndoPoint();
  const copy = normalizeNode(JSON.parse(JSON.stringify(nodes[selectedIndex])));
  copy.name += "_copy"; copy.x += 60; copy.y += 60;
  nodes.push(copy); selectedIndex = nodes.length - 1;
  autoSave(); refreshAll();
}

function deleteSelected() {
  if (selectedIndex < 0) return;
  recordUndoPoint();
  const removedName = nodes[selectedIndex].name;
  nodes.splice(selectedIndex, 1);
  nodes.forEach(n => { if (n.parent === removedName) n.parent = ""; });
  selectedIndex = nodes.length - 1;
  tileBrushMode = 'off';
  autoSave(); refreshAll();
}

function clearAllTiles() {
  if (selectedIndex < 0 || nodes[selectedIndex]?.nodeType !== 'TileMap') return;
  if (!nodes[selectedIndex].tiles?.length) return;
  recordUndoPoint();
  nodes[selectedIndex].tiles = [];
  autoSave(); refreshAll();
  showToast("已清空当前瓦片层");
}

function addClip() {
  if (selectedIndex < 0) return;
  recordUndoPoint();
  const n = nodes[selectedIndex];
  if (!Array.isArray(n.clips)) n.clips = [];
  n.clips.push({ name: `anim_${n.clips.length + 1}`, start: 0, end: 0, fps: 12, loop: true, eventsStr: "" });
  autoSave(); renderDock();
}

function updateClip(cIdx, key, val) {
  if (selectedIndex < 0) return;
  const n = nodes[selectedIndex];
  if (!n.clips || !n.clips[cIdx]) return;
  recordUndoPoint();
  n.clips[cIdx][key] = val;
  autoSave();
}

function deleteClip(cIdx) {
  if (selectedIndex < 0) return;
  recordUndoPoint();
  nodes[selectedIndex].clips.splice(cIdx, 1);
  autoSave(); renderDock();
}

window.onload = () => {
  resizeCanvas();
  if (window.ZeroBridge) {
    refreshProjectAndSceneLists();
    refreshAudioList();
    refreshScriptList();
    refreshDataList();
    loadCurrentSceneFromDisk();
    if (window.ZeroBridge.hasCustomSaveLocation && !window.ZeroBridge.hasCustomSaveLocation()) {
      showSaveLocationModal(true);
    }
  }
  refreshAll();
  updateUndoRedoButtons();
};