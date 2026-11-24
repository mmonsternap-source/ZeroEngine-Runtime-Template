// ==================== ZeroEngine 视口画布渲染、触控交互、Gizmo 与瓦片画笔 ====================

const canvas = document.getElementById('stage');
const ctx = canvas.getContext('2d');

function resizeCanvas() {
  const box = document.getElementById('vpBox');
  canvas.width = box.clientWidth * window.devicePixelRatio;
  canvas.height = box.clientHeight * window.devicePixelRatio;
  draw();
}
window.addEventListener('resize', resizeCanvas);

function paintTileAtWorld(wx, wy) {
  if (selectedIndex < 0 || !nodes[selectedIndex] || nodes[selectedIndex].nodeType !== 'TileMap') return;
  const n = nodes[selectedIndex];
  if (!Array.isArray(n.tiles)) n.tiles = [];
  const gt = getGlobalTransform(n);
  const ts = Math.max(8, n.tileSize || 90);
  const gx = Math.floor((wx - gt.gx) / ts);
  const gy = Math.floor((wy - gt.gy) / ts);
  const existIdx = n.tiles.findIndex(t => t.gx === gx && t.gy === gy);
  if (tileBrushMode === 'paint') {
    if (existIdx >= 0) n.tiles[existIdx].frame = brushFrame;
    else n.tiles.push({ gx, gy, frame: brushFrame });
  } else if (tileBrushMode === 'erase') {
    if (existIdx >= 0) n.tiles.splice(existIdx, 1);
  }
  draw();
}

function getGlobalTransform(n, visited = new Set()) {
  const pxOffset = (n.layer !== 'UI' && n.parallaxX !== undefined && n.parallaxX !== 1) ? (camX - 540) * (1 - n.parallaxX) : 0;
  const pyOffset = (n.layer !== 'UI' && n.parallaxY !== undefined && n.parallaxY !== 1) ? (camY - 960) * (1 - n.parallaxY) : 0;
  if (!n.parent || visited.has(n.name)) {
    return { gx: n.x + pxOffset, gy: n.y + pyOffset, gz: n.z || 0, gRot: n.rotation || 0, gSx: n.scaleX ?? 1, gSy: n.scaleY ?? 1 };
  }
  visited.add(n.name);
  const p = nodes.find(item => item.name === n.parent);
  if (!p) return { gx: n.x + pxOffset, gy: n.y + pyOffset, gz: n.z || 0, gRot: n.rotation || 0, gSx: n.scaleX ?? 1, gSy: n.scaleY ?? 1 };
  const pt = getGlobalTransform(p, visited);
  const rad = pt.gRot * Math.PI / 180;
  const sx = n.x * pt.gSx, sy = n.y * pt.gSy;
  return {
    gx: pt.gx + (sx * Math.cos(rad) - sy * Math.sin(rad)),
    gy: pt.gy + (sx * Math.sin(rad) + sy * Math.cos(rad)),
    gz: pt.gz + (n.z || 0),
    gRot: pt.gRot + (n.rotation || 0),
    gSx: pt.gSx * (n.scaleX ?? 1),
    gSy: pt.gSy * (n.scaleY ?? 1)
  };
}

function screenToWorld(clientX, clientY) {
  const rect = canvas.getBoundingClientRect();
  const sx = (clientX - rect.left) * (canvas.width / rect.width);
  const sy = (clientY - rect.top) * (canvas.height / rect.height);
  return { x: (sx - canvas.width / 2) / camZoom + camX, y: (sy - canvas.height / 2) / camZoom + camY };
}

function applySnap(v) { return snapGrid ? Math.round(v / GRID_SIZE) * GRID_SIZE : Math.round(v); }

canvas.addEventListener('touchstart', e => {
  if (e.touches.length === 2) {
    dragMode = 'pinch';
    pendingSnapshot = null;
    lastPinchDist = Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);
    lastPanScreen = { x: (e.touches[0].clientX + e.touches[1].clientX) / 2, y: (e.touches[0].clientY + e.touches[1].clientY) / 2 };
    return;
  }
  if (e.touches.length === 1) {
    const wp = screenToWorld(e.touches[0].clientX, e.touches[0].clientY);
    if (selectedIndex >= 0 && nodes[selectedIndex] && nodes[selectedIndex].nodeType === 'TileMap' && tileBrushMode !== 'off') {
      pendingSnapshot = createSnapshot();
      dragMode = 'tile_brush';
      paintTileAtWorld(wp.x, wp.y);
      return;
    }
    const gizmoLen = 110 / camZoom, hitR = 32 / camZoom;
    if (selectedIndex >= 0 && nodes[selectedIndex] && nodes[selectedIndex].nodeType !== 'TileMap') {
      const sel = nodes[selectedIndex], gt = getGlobalTransform(sel);
      if (Math.hypot(wp.x - (gt.gx + gizmoLen), wp.y - gt.gy) < hitR) { pendingSnapshot = createSnapshot(); dragMode = 'move_x'; dragStartWorld = wp; origNodeState = { ...sel }; return; }
      if (Math.hypot(wp.x - gt.gx, wp.y - (gt.gy + gizmoLen)) < hitR) { pendingSnapshot = createSnapshot(); dragMode = 'move_y'; dragStartWorld = wp; origNodeState = { ...sel }; return; }
      if (Math.hypot(wp.x - gt.gx, wp.y - (gt.gy - gizmoLen)) < hitR) { pendingSnapshot = createSnapshot(); dragMode = 'rotate'; dragStartWorld = wp; origNodeState = { ...sel }; return; }
    }
    let hit = -1;
    for (let i = nodes.length - 1; i >= 0; i--) {
      const n = nodes[i];
      if (n.nodeType === 'TileMap') continue;
      const gt = getGlobalTransform(n);
      if (Math.abs(wp.x - gt.gx) <= (n.width * Math.abs(gt.gSx)) / 2 && Math.abs(wp.y - gt.gy) <= (n.height * Math.abs(gt.gSy)) / 2) { hit = i; break; }
    }
    if (hit >= 0) {
      selectedIndex = hit;
      pendingSnapshot = createSnapshot();
      dragMode = 'move_xy'; dragStartWorld = wp; origNodeState = { ...nodes[hit] };
      refreshAll();
    } else {
      pendingSnapshot = null;
      dragMode = 'pan'; lastPanScreen = { x: e.touches[0].clientX, y: e.touches[0].clientY };
    }
  }
});

canvas.addEventListener('touchmove', e => {
  e.preventDefault();
  if (dragMode === 'pinch' && e.touches.length === 2) {
    const dist = Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);
    const midX = (e.touches[0].clientX + e.touches[1].clientX) / 2, midY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
    if (lastPinchDist > 0) camZoom = Math.min(3, Math.max(0.15, camZoom * (dist / lastPinchDist)));
    const dpr = window.devicePixelRatio;
    camX -= (midX - lastPanScreen.x) * dpr / camZoom;
    camY -= (midY - lastPanScreen.y) * dpr / camZoom;
    lastPinchDist = dist; lastPanScreen = { x: midX, y: midY };
    draw(); return;
  }
  if (e.touches.length !== 1) return;
  if (dragMode === 'tile_brush') {
    const wp = screenToWorld(e.touches[0].clientX, e.touches[0].clientY);
    paintTileAtWorld(wp.x, wp.y);
    return;
  }
  if (dragMode === 'pan') {
    const dpr = window.devicePixelRatio;
    camX -= (e.touches[0].clientX - lastPanScreen.x) * dpr / camZoom;
    camY -= (e.touches[0].clientY - lastPanScreen.y) * dpr / camZoom;
    lastPanScreen = { x: e.touches[0].clientX, y: e.touches[0].clientY };
    draw(); return;
  }
  if (selectedIndex < 0 || !origNodeState) return;
  const wp = screenToWorld(e.touches[0].clientX, e.touches[0].clientY);
  const n = nodes[selectedIndex], dx = wp.x - dragStartWorld.x, dy = wp.y - dragStartWorld.y;
  if (dragMode === 'move_xy') { n.x = applySnap(origNodeState.x + dx); n.y = applySnap(origNodeState.y + dy); }
  else if (dragMode === 'move_x') { n.x = applySnap(origNodeState.x + dx); }
  else if (dragMode === 'move_y') { n.y = applySnap(origNodeState.y + dy); }
  else if (dragMode === 'rotate') {
    const gt = getGlobalTransform(n);
    n.rotation = Math.round(Math.atan2(wp.y - gt.gy, wp.x - gt.gx) * 180 / Math.PI + 90);
  }
  if (activeTab === 'inspector') renderDock();
  draw();
}, { passive: false });

canvas.addEventListener('touchend', () => {
  if (pendingSnapshot && createSnapshot() !== pendingSnapshot) recordUndoPoint(pendingSnapshot);
  pendingSnapshot = null;
  if (dragMode === 'tile_brush') { autoSave(); if (activeTab === 'inspector') renderDock(); }
  else if (dragMode && dragMode !== 'pan' && dragMode !== 'pinch') autoSave();
  dragMode = null;
});

function rgbToHex(r, g, b) {
  const f = v => Math.round(Math.min(1, Math.max(0, v)) * 255).toString(16).padStart(2, '0');
  return `#${f(r)}${f(g)}${f(b)}`;
}

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return { r: +(((n >> 16) & 255) / 255).toFixed(2), g: +(((n >> 8) & 255) / 255).toFixed(2), b: +((n & 255) / 255).toFixed(2) };
}

function draw() {
  const brushHint = (selectedIndex >= 0 && nodes[selectedIndex]?.nodeType === 'TileMap' && tileBrushMode !== 'off')
    ? ` | 🖌️${tileBrushMode === 'paint' ? '刷瓦片(#' + brushFrame + ')' : '橡皮擦'}` : '';
  document.getElementById('vpHud').textContent = `v1.4 | ${cameraCfg.mode} | ${Math.round(camZoom * 100)}% | ${currentScene}${brushHint}`;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.save();
  ctx.translate(canvas.width / 2, canvas.height / 2);
  ctx.scale(camZoom, camZoom);
  ctx.translate(-camX, -camY);

  ctx.strokeStyle = '#151b26'; ctx.lineWidth = 2 / camZoom;
  for (let x = -1080; x <= 2160; x += 180) { ctx.beginPath(); ctx.moveTo(x, -1920); ctx.lineTo(x, 3840); ctx.stroke(); }
  for (let y = -1920; y <= 3840; y += 180) { ctx.beginPath(); ctx.moveTo(-1080, y); ctx.lineTo(2160, y); ctx.stroke(); }
  ctx.strokeStyle = '#334155'; ctx.lineWidth = 4 / camZoom;
  ctx.strokeRect(0, 0, 1080, 1920);

  nodes.forEach((n, idx) => {
    const gt = getGlobalTransform(n);
    const w = n.width * gt.gSx, h = n.height * gt.gSy;
    ctx.save();
    ctx.translate(gt.gx, gt.gy);
    ctx.rotate(gt.gRot * Math.PI / 180);
    ctx.globalAlpha = n.a ?? 1;

    if (n.nodeType === 'TileMap') {
      const ts = Math.max(8, n.tileSize || 90);
      const img = (n.texture && imageCache[n.texture]) ? imageCache[n.texture] : null;
      const hf = Math.max(1, n.hFrames || 1), vf = Math.max(1, n.vFrames || 1);
      (n.tiles || []).forEach(cell => {
        const cx = cell.gx * ts, cy = cell.gy * ts;
        if (img) {
          const fi = Math.min(hf * vf - 1, Math.max(0, cell.frame || 0));
          const sw = img.width / hf, sh = img.height / vf;
          ctx.drawImage(img, (fi % hf) * sw, Math.floor(fi / hf) * sh, sw, sh, cx, cy, ts, ts);
        } else {
          ctx.fillStyle = rgbToHex(n.r, n.g, n.b);
          ctx.fillRect(cx, cy, ts, ts);
        }
        if (idx === selectedIndex) {
          ctx.strokeStyle = 'rgba(56,189,248,0.55)';
          ctx.lineWidth = 2 / camZoom;
          ctx.strokeRect(cx, cy, ts, ts);
        }
      });
    } else if (n.nodeType === 'PointLight') {
      const grad = ctx.createRadialGradient(0, 0, 5, 0, 0, n.lightRadius || 300);
      grad.addColorStop(0, rgbToHex(n.r, n.g, n.b));
      grad.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = grad;
      ctx.beginPath(); ctx.arc(0, 0, n.lightRadius || 300, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#fde047'; ctx.beginPath(); ctx.arc(0, 0, 18 / camZoom, 0, Math.PI * 2); ctx.fill();
    } else if (n.nodeType === 'ParticleEmitter') {
      ctx.strokeStyle = '#fb923c'; ctx.lineWidth = 3 / camZoom;
      ctx.beginPath(); ctx.arc(0, 0, 26 / camZoom, 0, Math.PI * 2); ctx.stroke();
      ctx.fillStyle = rgbToHex(n.r, n.g, n.b);
      ctx.beginPath(); ctx.arc(0, 0, 10 / camZoom, 0, Math.PI * 2); ctx.fill();
    } else if (n.nodeType === 'Label2D') {
      ctx.fillStyle = '#ffffff'; ctx.font = `bold ${n.fontSize || 44}px sans-serif`;
      ctx.fillText(n.text || 'Label', -w / 2, 10);
    } else if (n.slopeType === 'left_up' || n.slopeType === 'right_up') {
      ctx.fillStyle = rgbToHex(n.r, n.g, n.b);
      ctx.beginPath();
      if (n.slopeType === 'left_up') {
        ctx.moveTo(w / 2, -h / 2); ctx.lineTo(-w / 2, h / 2); ctx.lineTo(w / 2, h / 2);
      } else {
        ctx.moveTo(-w / 2, -h / 2); ctx.lineTo(-w / 2, h / 2); ctx.lineTo(w / 2, h / 2);
      }
      ctx.closePath(); ctx.fill();
    } else if (n.texture && imageCache[n.texture]) {
      const img = imageCache[n.texture];
      const hf = Math.max(1, n.hFrames || 1), vf = Math.max(1, n.vFrames || 1);
      const fi = Math.min(hf * vf - 1, Math.max(0, n.currentFrame || 0));
      const sw = img.width / hf, sh = img.height / vf;
      ctx.drawImage(img, (fi % hf) * sw, Math.floor(fi / hf) * sh, sw, sh, -w / 2, -h / 2, w, h);
    } else {
      ctx.fillStyle = rgbToHex(n.r, n.g, n.b);
      ctx.fillRect(-w / 2, -h / 2, w, h);
    }

    if (n.nodeType !== 'TileMap') {
      if (n.layer === 'UI') {
        ctx.strokeStyle = n.clipChildren ? '#38bdf8' : '#f59e0b'; ctx.lineWidth = 3 / camZoom;
        ctx.strokeRect(-w / 2, -h / 2, w, h);
      } else if (n.isHitbox) {
        ctx.strokeStyle = '#f43f5e'; ctx.lineWidth = 4 / camZoom;
        ctx.setLineDash([8 / camZoom, 4 / camZoom]);
        ctx.strokeRect(-w / 2, -h / 2, w, h); ctx.setLineDash([]);
      } else if (n.isHurtbox) {
        ctx.strokeStyle = '#22c55e'; ctx.lineWidth = 3 / camZoom;
        ctx.setLineDash([8 / camZoom, 4 / camZoom]);
        ctx.strokeRect(-w / 2, -h / 2, w, h); ctx.setLineDash([]);
      } else if (n.collider) {
        ctx.strokeStyle = n.isTrigger ? '#a855f7' : (n.oneWay ? '#34d399' : '#38bdf8');
        ctx.lineWidth = 3 / camZoom; ctx.setLineDash([10 / camZoom, 6 / camZoom]);
        ctx.strokeRect(-w / 2, -h / 2, w, h); ctx.setLineDash([]);
      }
    }
    ctx.restore();

    if (idx === selectedIndex && n.nodeType !== 'TileMap') {
      const len = 110 / camZoom, rHandle = 14 / camZoom;
      ctx.save(); ctx.translate(gt.gx, gt.gy);
      ctx.strokeStyle = '#ef4444'; ctx.fillStyle = '#ef4444'; ctx.lineWidth = 4 / camZoom;
      ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(len, 0); ctx.stroke();
      ctx.beginPath(); ctx.arc(len, 0, rHandle, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = '#22c55e'; ctx.fillStyle = '#22c55e';
      ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0, len); ctx.stroke();
      ctx.beginPath(); ctx.arc(0, len, rHandle, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = '#fbbf24'; ctx.fillStyle = '#fbbf24';
      ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0, -len); ctx.stroke();
      ctx.beginPath(); ctx.arc(0, -len, rHandle, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
    }
  });
  ctx.restore();
}