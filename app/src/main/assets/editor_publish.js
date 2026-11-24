// ==================== ZeroEngine P13 面向全体开发者的云端发布中心 (APK + AAB) ====================
(function () {
  let isBuilding = false;

  function injectPublishDOM() {
    if (document.getElementById('publishModal')) return;
    const st = document.createElement('style');
    st.textContent = `
      #publishModal { position:fixed; inset:0; background:rgba(5,8,14,0.86); z-index:1300; display:none; align-items:center; justify-content:center; padding:12px; font-family:system-ui,sans-serif; }
      .pub-card { width:100%; max-width:420px; max-height:92vh; overflow-y:auto; background:#111827; border:1px solid #334155; border-radius:10px; padding:12px; color:#e2e8f0; display:flex; flex-direction:column; gap:8px; box-shadow:0 12px 32px rgba(0,0,0,0.65); }
      .pub-title { font-size:14px; font-weight:800; color:#38bdf8; display:flex; justify-content:space-between; align-items:center; }
      .pub-grid { display:grid; grid-template-columns:1fr 1fr; gap:7px; }
      .pub-field { display:flex; flex-direction:column; gap:3px; font-size:11px; color:#94a3b8; }
      .pub-field.full { grid-column:1 / -1; }
      .pub-field input, .pub-field select { background:#070a10; border:1px solid #334155; color:#fff; padding:6px 8px; border-radius:5px; font-size:12px; }
      .pub-prog-wrap { background:#070a10; border:1px solid #1e293b; border-radius:6px; padding:8px; display:none; flex-direction:column; gap:6px; }
      .pub-bar-bg { width:100%; height:8px; background:#1e293b; border-radius:4px; overflow:hidden; }
      .pub-bar-fill { width:0%; height:100%; background:linear-gradient(90deg, #3b82f6, #10b981); transition:width 0.3s ease; }
      .pub-msg { font-size:11px; color:#a5f3fc; line-height:1.45; word-break:break-all; }
      .pub-tip { font-size:10px; color:#94a3b8; background:#0b1120; border-left:3px solid #38bdf8; padding:6px 8px; border-radius:4px; line-height:1.5; }
      .pub-actions { display:flex; gap:8px; justify-content:flex-end; margin-top:4px; }
    `;
    document.head.appendChild(st);

    const wrap = document.createElement('div');
    wrap.id = 'publishModal';
    wrap.innerHTML = `
      <div class="pub-card">
        <div class="pub-title">
          <span>🚀 云端一键打包发布 (APK + AAB)</span>
          <button style="padding:2px 7px;font-size:11px;" onclick="PublishStudio.close()">✕</button>
        </div>
        <div class="pub-tip">
          零费用多开发者模式：填入<b>您自己的 GitHub Token</b>（需勾选 <code>repo</code> 与 <code>workflow</code> 权限），首次发布将自动克隆专属<b>私有</b>打包库，消耗您自己的免费云端额度，编完自动下载回手机。
        </div>
        <div class="pub-grid">
          <div class="pub-field full">
            <label>GitHub Personal Access Token (仅存本地)</label>
            <input id="pubToken" type="password" placeholder="ghp_xxxxxxxxxxxxxxxxxxxx">
          </div>
          <div class="pub-field">
            <label>游戏名称 (App Name)</label>
            <input id="pubAppName" type="text" placeholder="MyZeroGame">
          </div>
          <div class="pub-field">
            <label>导出格式 (Format)</label>
            <select id="pubFormat">
              <option value="both">APK + AAB (全平台)</option>
              <option value="apk">仅 APK (测试/TapTap/Amazon)</option>
              <option value="aab">仅 AAB (Google Play 专供)</option>
            </select>
          </div>
          <div class="pub-field full">
            <label>独立包名 (Package Name / ApplicationId)</label>
            <input id="pubPkg" type="text" placeholder="com.studio.mygame">
          </div>
          <div class="pub-field">
            <label>版本名 (versionName)</label>
            <input id="pubVerName" type="text" placeholder="1.0.0">
          </div>
          <div class="pub-field">
            <label>版本号 (versionCode)</label>
            <input id="pubVerCode" type="number" min="1" value="1">
          </div>
          <div class="pub-field">
            <label>私有打包库名</label>
            <input id="pubRepo" type="text" placeholder="ZeroGame-CloudBuilder">
          </div>
          <div class="pub-field">
            <label>官方模板作者 (Template Owner)</label>
            <input id="pubTplOwner" type="text" placeholder="模板仓库用户名">
          </div>
        </div>

        <div class="pub-prog-wrap" id="pubProgWrap">
          <div style="display:flex;justify-content:space-between;font-size:11px;">
            <span style="color:#38bdf8;font-weight:700;">云端构建进度</span>
            <span id="pubPctText" style="color:#34d399;font-weight:700;">0%</span>
          </div>
          <div class="pub-bar-bg"><div class="pub-bar-fill" id="pubBarFill"></div></div>
          <div class="pub-msg" id="pubMsg">准备中...</div>
        </div>

        <div class="pub-actions">
          <button onclick="PublishStudio.close()">关闭</button>
          <button id="pubStartBtn" class="play" style="margin-left:0;" onclick="PublishStudio.start()">☁️ 开始云端出包</button>
        </div>
      </div>
    `;
    document.body.appendChild(wrap);
  }

  window.onCloudPublishProgress = function (pct, msg) {
    const wrap = document.getElementById('pubProgWrap');
    if (wrap) wrap.style.display = 'flex';
    const bar = document.getElementById('pubBarFill');
    const txt = document.getElementById('pubPctText');
    const msgEl = document.getElementById('pubMsg');
    if (bar) bar.style.width = Math.max(0, Math.min(100, pct)) + '%';
    if (txt) txt.textContent = pct + '%';
    if (msgEl) msgEl.textContent = msg;
  };

  window.onCloudPublishDone = function (ok, msg, outDir) {
    isBuilding = false;
    const btn = document.getElementById('pubStartBtn');
    if (btn) { btn.disabled = false; btn.textContent = '☁️ 开始云端出包'; }
    const msgEl = document.getElementById('pubMsg');
    if (msgEl) {
      msgEl.style.color = ok ? '#34d399' : '#f87171';
      msgEl.textContent = ok ? `✅ ${msg}\n📂 目录：${outDir}` : `❌ ${msg}`;
    }
    if (typeof showToast === 'function') showToast(ok ? '🎉 APK/AAB 已下载回手机！' : '打包失败，请查看提示');
  };

  window.PublishStudio = {
    open() {
      injectPublishDOM();
      let cfg = {};
      if (window.ZeroBridge && typeof window.ZeroBridge.getCloudPublishConfig === 'function') {
        try { cfg = JSON.parse(window.ZeroBridge.getCloudPublishConfig()); } catch (_) {}
      }
      document.getElementById('pubToken').value = cfg.githubToken || '';
      document.getElementById('pubAppName').value = cfg.appName || 'MyZeroGame';
      document.getElementById('pubPkg').value = cfg.packageName || 'com.developer.myzerogame';
      document.getElementById('pubVerName').value = cfg.versionName || '1.0.0';
      document.getElementById('pubVerCode').value = cfg.versionCode || 1;
      document.getElementById('pubRepo').value = cfg.repoName || 'ZeroGame-CloudBuilder';
      document.getElementById('pubTplOwner').value = cfg.templateOwner || '';
      document.getElementById('pubFormat').value = cfg.buildFormat || 'both';
      document.getElementById('publishModal').style.display = 'flex';
    },
    close() {
      document.getElementById('publishModal').style.display = 'none';
    },
    start() {
      if (isBuilding) return;
      if (!window.ZeroBridge || typeof window.ZeroBridge.startCloudPublish !== 'function') {
        alert('当前客户端未接入 ZeroCloudPublisher 原生桥接');
        return;
      }
      if (typeof manualSave === 'function') manualSave();

      const payload = {
        githubToken: document.getElementById('pubToken').value.trim(),
        appName: document.getElementById('pubAppName').value.trim() || 'MyZeroGame',
        packageName: document.getElementById('pubPkg').value.trim(),
        versionName: document.getElementById('pubVerName').value.trim() || '1.0.0',
        versionCode: Math.max(1, parseInt(document.getElementById('pubVerCode').value, 10) || 1),
        repoName: document.getElementById('pubRepo').value.trim() || 'ZeroGame-CloudBuilder',
        templateOwner: document.getElementById('pubTplOwner').value.trim(),
        templateRepo: 'ZeroEngine-Runtime-Template',
        buildFormat: document.getElementById('pubFormat').value
      };

      isBuilding = true;
      const btn = document.getElementById('pubStartBtn');
      btn.disabled = true;
      btn.textContent = '⏳ 云端编译中...';
      document.getElementById('pubMsg').style.color = '#a5f3fc';
      window.onCloudPublishProgress(2, '正在启动云端发布任务...');
      window.ZeroBridge.startCloudPublish(JSON.stringify(payload));
    }
  };
})();
