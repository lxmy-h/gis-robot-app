/*
 * 画面放大模式（由 App 在页面加载完成后注入，不改页面原有代码和布局）
 *
 * 交互：
 *   - 屏幕左侧一个半透明 ⊕ 圆钮（可拖动，位置会记住）
 *   - 点圆钮 -> 左侧整块区域变成放大画布：视频铺满左侧，右侧控制区完全不动（也不影响右侧点击）
 *   - 双指捏合 = 连续缩放（不是固定档位）；单指拖动 = 平移
 *   - 双击 = 复位到"铺满左侧"的初始状态
 *   - 点 × 退出，回到原页面
 *
 * 关键设计：
 *   1. 覆盖层是 position:fixed 且 pointer-events:none，只有画布本身接收手势，
 *      所以右侧云台/风机/升降/停止按钮全部照常可见、可点。
 *   2. 画面用 canvas 自绘（drawImage 视频元素），不修改页面 DOM 结构，
 *      不会引起页面重排，也不会让页面控件跑位。
 *   3. 只在放大模式打开时跑 requestAnimationFrame，退出即停。
 *
 * 对外接口（App 调用）：
 *   window.__rwMag.setHandleVisible(true|false)
 *   window.__rwMag.close()
 */
(function () {
  'use strict';

  var VERSION = 2;
  if (window.__rwMag && window.__rwMag.version === VERSION) return;

  var MIN_S = 1;          // 1 = 恰好"装下"（可能左右有黑边）
  var MAX_S = 12;         // 最大放大
  var PAD = 12;           // 放大区域右边界离画面的余量

  var S = {
    on: false,
    visible: true,
    scale: 1,
    tx: 0,
    ty: 0,
    src: null,
    srcAt: 0,
    overlays: [],
    raf: 0,
    cssW: 0,
    cssH: 0,
    dpr: 1,
    fit: { x: 0, y: 0, w: 0, h: 0 },   // scale=1 时画面在画布中的位置
    cover: 1,                           // 铺满左侧区域所需的 scale
    hintUntil: 0,
    lastDraw: 0
  };

  var n = {};              // 节点
  var pts = {};            // 当前按下的指针
  var ptCount = 0;
  var g = { mode: 0, s0: 1, tx0: 0, ty0: 0, d0: 0, mx0: 0, my0: 0, px0: 0, py0: 0, moved: false };
  var lastTapAt = 0;
  var LS_KEY = 'rw_mag_v2';

  function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }
  function vw() { return window.innerWidth; }
  function vh() { return window.innerHeight; }

  function saveState() {
    try {
      localStorage.setItem(LS_KEY, JSON.stringify({
        hx: parseFloat(n.handle.style.left) || 12,
        hy: parseFloat(n.handle.style.top) || 200,
        visible: S.visible
      }));
    } catch (e) { }
  }

  function loadState() {
    try { return JSON.parse(localStorage.getItem(LS_KEY) || '{}'); } catch (e) { return {}; }
  }

  /* ------------------------------------------------------------------ 样式 */

  function ensureStyle() {
    if (document.getElementById('__rw_z_style')) return;
    var css = [
      '#__rw_zbtn,#__rw_zroot,#__rw_zhud,#__rw_zclose{position:fixed!important;z-index:2147483000!important;',
      'box-sizing:border-box!important;-webkit-user-select:none!important;user-select:none!important;',
      'font:12px/1.4 -apple-system,Roboto,sans-serif!important;}',

      '#__rw_zbtn{width:44px!important;height:44px!important;border-radius:50%!important;',
      'background:rgba(0,0,0,.45)!important;border:1px solid rgba(0,229,255,.85)!important;',
      'color:#00e5ff!important;align-items:center!important;justify-content:center!important;',
      'font-size:20px!important;line-height:1!important;opacity:.35!important;',
      'transition:opacity .15s!important;touch-action:none!important;}',
      '#__rw_zbtn.__on{opacity:0!important;}',

      /* 覆盖层：整屏铺开但不接收事件，只有画布接收手势 —— 所以右侧控制区照常可点 */
      '#__rw_zroot{left:0!important;top:0!important;width:100%!important;height:100%!important;',
      'pointer-events:none!important;}',
      '#__rw_zcanvas{position:absolute!important;left:0!important;top:0!important;display:block!important;',
      'pointer-events:auto!important;touch-action:none!important;background:#000!important;}',

      '#__rw_zclose{pointer-events:auto!important;width:42px!important;height:42px!important;',
      'border-radius:50%!important;background:rgba(0,0,0,.6)!important;',
      'border:1px solid rgba(0,229,255,.9)!important;color:#00e5ff!important;',
      'font-size:22px!important;line-height:40px!important;text-align:center!important;}',

      '#__rw_zhud{pointer-events:none!important;color:#cfeeff!important;',
      'background:rgba(0,0,0,.55)!important;padding:5px 10px!important;border-radius:14px!important;',
      'font-size:12px!important;white-space:nowrap!important;}'
    ].join('');
    var st = document.createElement('style');
    st.id = '__rw_z_style';
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  }

  /* ------------------------------------------------------------------ 画面元素识别 */

  function pickSource() {
    var i, e, r, area, best = null, bestArea = 0;
    var list = document.querySelectorAll('video,img');
    for (i = 0; i < list.length; i++) {
      e = list[i];
      r = e.getBoundingClientRect();
      if (r.width < 200 || r.height < 120) continue;
      area = r.width * r.height;
      if (area > bestArea) { bestArea = area; best = e; }
    }
    if (best) return best;

    var cvs = document.querySelectorAll('canvas');
    for (i = 0; i < cvs.length; i++) {
      e = cvs[i];
      r = e.getBoundingClientRect();
      area = r.width * r.height;
      if (area > bestArea) { bestArea = area; best = e; }
    }
    return best;
  }

  function naturalSize(e) {
    if (!e) return { w: 0, h: 0 };
    if (e.tagName === 'VIDEO') return { w: e.videoWidth || 0, h: e.videoHeight || 0 };
    if (e.tagName === 'IMG') return { w: e.naturalWidth || 0, h: e.naturalHeight || 0 };
    return { w: e.width || 0, h: e.height || 0 };
  }

  function srcLabel(e) {
    if (!e) return '未找到画面';
    var ns = naturalSize(e);
    return e.tagName.toLowerCase() + ' ' + ns.w + '×' + ns.h;
  }

  /** 找和画面几乎重合的 canvas（检测框/激光笔叠加层），一起画进去才不会错位 */
  function findOverlays(main) {
    var out = [];
    if (!main) return out;
    var r = main.getBoundingClientRect();
    var scope = main.parentElement ? main.parentElement.parentElement : null;
    var list = (scope || document).querySelectorAll('canvas');
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (c === main) continue;
      var cr = c.getBoundingClientRect();
      if (Math.abs(cr.left - r.left) < 6 && Math.abs(cr.top - r.top) < 6 &&
        Math.abs(cr.width - r.width) < 6 && Math.abs(cr.height - r.height) < 6) {
        out.push(c);
      }
    }
    return out;
  }

  /* ------------------------------------------------------------------ 布局计算 */

  /** 左侧放大区域：从屏幕左边到画面右边缘，整屏高度 */
  function computeArea() {
    var e = S.src || pickSource();
    var right = e ? e.getBoundingClientRect().right : vw() * 0.65;
    var w = clamp(right + PAD, Math.round(vw() * 0.45), vw());
    return { w: Math.round(w), h: vh() };
  }

  function applyCanvasSize() {
    var a = computeArea();
    S.cssW = a.w;
    S.cssH = a.h;
    S.dpr = Math.min(2, window.devicePixelRatio || 1);
    n.canvas.style.width = a.w + 'px';
    n.canvas.style.height = a.h + 'px';
    n.canvas.width = Math.round(a.w * S.dpr);
    n.canvas.height = Math.round(a.h * S.dpr);
    n.close.style.left = (a.w - 52) + 'px';
    n.close.style.top = '10px';
    n.hud.style.left = '12px';
    n.hud.style.top = (a.h - 44) + 'px';
    computeFit();
  }

  /** scale=1 时的位置（contain）以及铺满所需的倍率 */
  function computeFit() {
    var e = S.src;
    var ns = naturalSize(e);
    if (!ns.w || !ns.h) { ns = { w: 16, h: 9 }; }
    var k = Math.min(S.cssW / ns.w, S.cssH / ns.h);
    var w = ns.w * k;
    var h = ns.h * k;
    S.fit = { x: (S.cssW - w) / 2, y: (S.cssH - h) / 2, w: w, h: h };
    S.cover = Math.max(S.cssW / w, S.cssH / h);
  }

  /* ------------------------------------------------------------------ 视图变换 */

  function clampView() {
    S.scale = clamp(S.scale, MIN_S, MAX_S);
    var f = S.fit;
    var w = f.w * S.scale;
    var h = f.h * S.scale;

    if (w <= S.cssW) {
      S.tx = (S.cssW - w) / 2 - f.x * S.scale;
    } else {
      S.tx = clamp(S.tx, S.cssW - (f.x + f.w) * S.scale, -f.x * S.scale);
    }
    if (h <= S.cssH) {
      S.ty = (S.cssH - h) / 2 - f.y * S.scale;
    } else {
      S.ty = clamp(S.ty, S.cssH - (f.y + f.h) * S.scale, -f.y * S.scale);
    }
  }

  /** 复位到"铺满左侧" */
  function resetView() {
    S.scale = S.cover;
    S.tx = 0;
    S.ty = 0;
    clampView();
    S.hintUntil = Date.now() + 3000;
  }

  /* ------------------------------------------------------------------ 渲染 */

  function render() {
    if (!S.on) { S.raf = 0; return; }
    S.raf = requestAnimationFrame(render);

    var now = performance.now();
    // 源流本身是 30fps，这里也限到 ~30fps，减少平板绘制压力
    if (now - S.lastDraw < 30) return;
    S.lastDraw = now;
    if (!S.src || !S.src.isConnected) {
      S.src = pickSource();
      S.srcAt = now;
      S.overlays = findOverlays(S.src);
      computeFit();
      clampView();
    } else if (now - S.srcAt > 2000) {
      // 周期性复查：页面可能重建过画面元素
      S.srcAt = now;
      var fresh = pickSource();
      if (fresh && fresh !== S.src) {
        S.src = fresh;
        S.overlays = findOverlays(S.src);
        computeFit();
        clampView();
      }
    }

    var ctx = n.ctx;
    var cv = n.canvas;

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, cv.width, cv.height);

    var e = S.src;
    if (!e) { setHud('未找到画面元素'); return; }
    var ns = naturalSize(e);
    if (!ns.w || !ns.h) { setHud('等待画面…'); return; }

    ctx.setTransform(S.dpr, 0, 0, S.dpr, 0, 0);
    ctx.translate(S.tx, S.ty);
    ctx.scale(S.scale, S.scale);
    ctx.imageSmoothingEnabled = true;

    var f = S.fit;
    try {
      ctx.drawImage(e, f.x, f.y, f.w, f.h);
      for (var i = 0; i < S.overlays.length; i++) {
        if (S.overlays[i].isConnected) {
          ctx.drawImage(S.overlays[i], f.x, f.y, f.w, f.h);
        }
      }
    } catch (err) {
      ctx.setTransform(S.dpr, 0, 0, S.dpr, 0, 0);
      setHud('绘制失败：' + (err && err.name ? err.name : 'Error'));
      return;
    }

    // 倍率常驻显示；刚打开时附上操作提示
    var hint = Date.now() < S.hintUntil ? '   ·   双指缩放 / 单指拖动 / 双击复位' : '';
    n.hud.textContent = S.scale.toFixed(1) + '×  ·  ' + srcLabel(e) + hint;
  }

  function setHud(text) {
    n.hud.textContent = text;
  }

  /* ------------------------------------------------------------------ 手势 */

  function ptCountNow() {
    var c = 0;
    for (var k in pts) { if (pts.hasOwnProperty(k)) c++; }
    return c;
  }

  function firstPt() {
    for (var k in pts) { if (pts.hasOwnProperty(k)) return pts[k]; }
    return null;
  }

  function twoPts() {
    var a = [];
    for (var k in pts) { if (pts.hasOwnProperty(k)) a.push(pts[k]); }
    return a;
  }

  function dist(p, q) { return Math.hypot(p.x - q.x, p.y - q.y); }
  function mid(p, q) { return { x: (p.x + q.x) / 2, y: (p.y + q.y) / 2 }; }

  function beginGesture() {
    g.s0 = S.scale;
    g.tx0 = S.tx;
    g.ty0 = S.ty;
    g.moved = false;
    if (ptCountNow() >= 2) {
      var a = twoPts();
      g.d0 = dist(a[0], a[1]);
      var m = mid(a[0], a[1]);
      g.mx0 = m.x;
      g.my0 = m.y;
      g.mode = 2;
    } else if (ptCountNow() === 1) {
      var p = firstPt();
      g.px0 = p.x;
      g.py0 = p.y;
      g.mode = 1;
    } else {
      g.mode = 0;
    }
  }

  function canvasXY(e) {
    // 画布在 left:0 top:0，所以客户端坐标即画布坐标
    return { x: e.clientX, y: e.clientY };
  }

  function onDown(e) {
    if (e.button && e.button !== 0) return;
    var p = canvasXY(e);
    pts[e.pointerId] = p;
    try { n.canvas.setPointerCapture(e.pointerId); } catch (err) { }
    beginGesture();
    e.preventDefault();
    e.stopPropagation();
  }

  function onMove(e) {
    if (!pts[e.pointerId]) return;
    var p = canvasXY(e);
    var prev = pts[e.pointerId];
    if (Math.abs(p.x - prev.x) > 3 || Math.abs(p.y - prev.y) > 3) g.moved = true;
    pts[e.pointerId] = p;

    var cnt = ptCountNow();
    if (cnt >= 2) {
      if (g.mode !== 2) beginGesture();
      var a = twoPts();
      var d = dist(a[0], a[1]);
      var m = mid(a[0], a[1]);
      if (g.d0 > 12) {
        var k = d / g.d0;
        S.scale = clamp(g.s0 * k, MIN_S, MAX_S);
        var rr = S.scale / g.s0;
        S.tx = m.x - (g.mx0 - g.tx0) * rr;
        S.ty = m.y - (g.my0 - g.ty0) * rr;
      } else {
        S.tx = g.tx0 + (m.x - g.mx0);
        S.ty = g.ty0 + (m.y - g.my0);
      }
      clampView();
    } else if (cnt === 1) {
      if (g.mode !== 1) beginGesture();
      S.tx = g.tx0 + (p.x - g.px0);
      S.ty = g.ty0 + (p.y - g.py0);
      clampView();
    }
    e.preventDefault();
    e.stopPropagation();
  }

  function onUp(e) {
    if (!pts[e.pointerId]) return;
    delete pts[e.pointerId];
    try { n.canvas.releasePointerCapture(e.pointerId); } catch (err) { }

    if (ptCountNow() === 0) {
      if (!g.moved) {
        var now = Date.now();
        if (now - lastTapAt < 300) {
          lastTapAt = 0;
          resetView();
        } else {
          lastTapAt = now;
        }
      }
      g.mode = 0;
    } else {
      beginGesture();
    }
    e.preventDefault();
    e.stopPropagation();
  }

  /* ------------------------------------------------------------------ 开关 */

  function open() {
    if (S.on) return;
    S.on = true;
    S.src = pickSource();
    S.srcAt = performance.now();
    S.overlays = findOverlays(S.src);

    n.root.style.display = 'block';
    n.close.style.display = 'block';
    n.hud.style.display = 'block';
    n.handle.classList.add('__on');
    n.handle.style.pointerEvents = 'none';

    applyCanvasSize();
    resetView();
    S.lastDraw = 0;
    if (!S.raf) S.raf = requestAnimationFrame(render);
  }

  function close() {
    if (!S.on) return;
    S.on = false;
    n.root.style.display = 'none';
    n.close.style.display = 'none';
    n.hud.style.display = 'none';
    n.handle.classList.remove('__on');
    n.handle.style.pointerEvents = 'auto';
    pts = {};
    ptCount = 0;
    g.mode = 0;
    if (S.raf) { cancelAnimationFrame(S.raf); S.raf = 0; }
  }

  /* ------------------------------------------------------------------ 拖动圆钮 */

  function makeDraggable(node, onMove, onTap) {
    var sx = 0, sy = 0, ox = 0, oy = 0, active = false, moved = false;

    node.addEventListener('pointerdown', function (e) {
      if (e.button && e.button !== 0) return;
      active = true;
      moved = false;
      sx = e.clientX;
      sy = e.clientY;
      var r = node.getBoundingClientRect();
      ox = r.left;
      oy = r.top;
      try { node.setPointerCapture(e.pointerId); } catch (err) { }
      e.preventDefault();
      e.stopPropagation();
    }, true);

    node.addEventListener('pointermove', function (e) {
      if (!active) return;
      var dx = e.clientX - sx;
      var dy = e.clientY - sy;
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) moved = true;
      onMove(ox + dx, oy + dy);
      e.preventDefault();
      e.stopPropagation();
    }, true);

    node.addEventListener('pointerup', function (e) {
      if (!active) return;
      active = false;
      try { node.releasePointerCapture(e.pointerId); } catch (err) { }
      e.preventDefault();
      e.stopPropagation();
      if (!moved && onTap) onTap();
    }, true);

    node.addEventListener('pointercancel', function () { active = false; }, true);
  }

  /* ------------------------------------------------------------------ 初始化 */

  function build() {
    ensureStyle();
    var saved = loadState();

    // ⊕ 圆钮
    var h = document.createElement('div');
    h.id = '__rw_zbtn';
    h.textContent = '⊕';
    h.title = '放大画面';
    h.style.left = clamp(typeof saved.hx === 'number' ? saved.hx : 12, 0, vw() - 44) + 'px';
    h.style.top = clamp(typeof saved.hy === 'number' ? saved.hy : Math.round(vh() / 2 - 22), 0, vh() - 44) + 'px';
    S.visible = saved.visible !== false;
    h.style.display = S.visible ? 'flex' : 'none';
    document.documentElement.appendChild(h);
    n.handle = h;

    makeDraggable(h, function (left, top) {
      h.style.left = clamp(left, 0, vw() - 44) + 'px';
      h.style.top = clamp(top, 0, vh() - 44) + 'px';
    }, function () {
      open();
      saveState();
    });

    // 覆盖层 + 画布
    var root = document.createElement('div');
    root.id = '__rw_zroot';
    root.style.display = 'none';

    var cv = document.createElement('canvas');
    cv.id = '__rw_zcanvas';
    root.appendChild(cv);
    document.documentElement.appendChild(root);
    n.root = root;
    n.canvas = cv;
    n.ctx = cv.getContext('2d');

    cv.addEventListener('pointerdown', onDown, true);
    cv.addEventListener('pointermove', onMove, true);
    cv.addEventListener('pointerup', onUp, true);
    cv.addEventListener('pointercancel', onUp, true);
    cv.addEventListener('contextmenu', function (e) { e.preventDefault(); }, true);

    // 关闭按钮
    var closeBtn = document.createElement('div');
    closeBtn.id = '__rw_zclose';
    closeBtn.textContent = '×';
    closeBtn.style.display = 'none';
    document.documentElement.appendChild(closeBtn);
    n.close = closeBtn;
    closeBtn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      close();
    }, true);

    // HUD
    var hud = document.createElement('div');
    hud.id = '__rw_zhud';
    hud.textContent = '1.0×';
    hud.style.display = 'none';
    document.documentElement.appendChild(hud);
    n.hud = hud;

    // 尺寸变化
    window.addEventListener('resize', function () {
      n.handle.style.left = clamp(parseFloat(n.handle.style.left) || 0, 0, vw() - 44) + 'px';
      n.handle.style.top = clamp(parseFloat(n.handle.style.top) || 0, 0, vh() - 44) + 'px';
      if (S.on) {
        var oldCover = S.cover;
        applyCanvasSize();
        // 保持相对倍率，不要把用户缩放弄丢
        if (oldCover > 0) S.scale = S.scale * (S.cover / oldCover);
        clampView();
      }
    }, true);

    // 键盘 Esc 退出（外接键盘时方便）
    window.addEventListener('keydown', function (e) {
      if (S.on && (e.key === 'Escape' || e.keyCode === 27)) close();
    }, true);
  }

  try {
    build();
    window.__rwMag = {
      version: VERSION,
      setHandleVisible: function (v) {
        S.visible = !!v;
        n.handle.style.display = v ? 'flex' : 'none';
        saveState();
      },
      open: open,
      close: close
    };
  } catch (err) {
    if (window.console) console.warn('[rwMag] init failed', err);
  }
})();
