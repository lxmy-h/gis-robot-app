/*
 * 下载接管（由 App 在页面加载完成后注入）
 *
 * 为什么需要它：
 *   Chrome 里页面点"拍照/结束录制"会自动下载到相册，但 WebView 不会。
 *   尤其当页面用 URL.createObjectURL(blob) + <a download>.click() 生成文件时，
 *   WebView 连 onDownloadStart 都不会回调 —— 必须在这里把数据抠出来交给 App。
 *
 * 做法：
 *   - 劫持 HTMLAnchorElement.prototype.click（覆盖"页面自己触发下载"的情况，
 *     包括还没插入 document 的临时 <a>）
 *   - 再加一个 document 捕获阶段的 click 监听（覆盖用户直接点链接的情况）
 *   - 命中 blob: 或 data: 时，分块读成 base64 通过 __rwDownload 桥推给 App
 *   - 普通的 http(s) 下载不碰，交给 WebView 的 DownloadListener
 */
(function () {
  'use strict';

  var V = 1;
  if (window.__rwDl && window.__rwDl.v === V) return;

  var CHUNK = 256 * 1024;   // 每次推给 App 的原始字节数

  function api() {
    return (window.__rwDownload && window.__rwDownload.begin) ? window.__rwDownload : null;
  }

  function stamp() {
    var d = new Date();
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return '' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '_' +
      p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
  }

  function extOf(mime) {
    mime = (mime || '').toLowerCase();
    if (mime.indexOf('png') >= 0) return '.png';
    if (mime.indexOf('jpeg') >= 0 || mime.indexOf('jpg') >= 0) return '.jpg';
    if (mime.indexOf('webp') >= 0) return '.webp';
    if (mime.indexOf('gif') >= 0) return '.gif';
    if (mime.indexOf('webm') >= 0) return '.webm';
    if (mime.indexOf('mp4') >= 0) return '.mp4';
    if (mime.indexOf('quicktime') >= 0) return '.mov';
    return '.bin';
  }

  function newId() {
    return 'dl' + Date.now() + '_' + Math.floor(Math.random() * 1000000);
  }

  /** 把 Blob 分块推给 App（录像几十 MB 也不会爆内存） */
  function sendBlob(blob, name) {
    var a = api();
    if (!a || !blob || !blob.size) return false;

    var mime = blob.type || 'application/octet-stream';
    var id = newId();
    var file = name || ('robot_' + stamp() + extOf(mime));
    var offset = 0;
    var reader = new FileReader();

    try {
      a.begin(id, file, mime);
    } catch (e) {
      return false;
    }

    function step() {
      if (offset >= blob.size) {
        try { a.finish(id); } catch (e) { }
        return;
      }
      var end = Math.min(offset + CHUNK, blob.size);
      var slice = blob.slice(offset, end);
      reader.onload = function () {
        try {
          var s = String(reader.result);
          var c = s.indexOf(',');
          a.append(id, s.substring(c + 1));
        } catch (e) {
          try { a.abort(id); } catch (e2) { }
          return;
        }
        offset = end;
        step();
      };
      reader.onerror = function () {
        try { a.abort(id); } catch (e) { }
      };
      reader.readAsDataURL(slice);
    }

    step();
    return true;
  }

  /** data: URL（canvas.toDataURL 之类） */
  function sendDataUrl(url, name) {
    var a = api();
    if (!a) return false;

    var c = url.indexOf(',');
    if (c < 0) return false;

    var head = url.substring(5, c);                 // 例如 image/png;base64
    var mime = head.split(';')[0] || 'application/octet-stream';
    var data = url.substring(c + 1);

    if (head.indexOf('base64') < 0) {
      try {
        data = btoa(unescape(encodeURIComponent(decodeURIComponent(data))));
      } catch (e) {
        return false;
      }
    }

    var id = newId();
    try {
      a.begin(id, name || ('robot_' + stamp() + extOf(mime)), mime);
      a.append(id, data);
      a.finish(id);
    } catch (e) {
      return false;
    }
    return true;
  }

  /** 用 XHR 把 blob: 读成 Blob（fetch 在个别老 WebView 上不稳） */
  function fetchBlob(url, name) {
    var ok = false;
    try {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', url, true);
      xhr.responseType = 'blob';
      xhr.onload = function () {
        try {
          sendBlob(xhr.response, name);
        } catch (e) { }
      };
      xhr.onerror = function () { };
      xhr.send();
      ok = true;
    } catch (e) {
      ok = false;
    }
    return ok;
  }

  function hrefOf(a) {
    var h = a.getAttribute('href');
    if (h) return h;
    return a.href || '';
  }

  /* --------- 1. 覆盖页面自己触发的 a.click() --------- */

  var origClick = HTMLAnchorElement.prototype.click;

  HTMLAnchorElement.prototype.click = function () {
    try {
      var href = hrefOf(this);
      var dl = this.getAttribute('download');
      if (href.indexOf('blob:') === 0) {
        if (fetchBlob(href, dl)) return;
      } else if (href.indexOf('data:') === 0) {
        if (sendDataUrl(href, dl)) return;
      }
    } catch (e) { }
    return origClick.apply(this, arguments);
  };

  /* --------- 2. 用户直接点链接 --------- */

  document.addEventListener('click', function (e) {
    var t = e.target;
    while (t && t.tagName !== 'A') t = t.parentNode;
    if (!t) return;
    try {
      var href = hrefOf(t);
      if (href.indexOf('blob:') === 0) {
        if (fetchBlob(href, t.getAttribute('download'))) {
          e.preventDefault();
          e.stopPropagation();
        }
      } else if (href.indexOf('data:') === 0 && t.getAttribute('download') !== null) {
        if (sendDataUrl(href, t.getAttribute('download'))) {
          e.preventDefault();
          e.stopPropagation();
        }
      }
    } catch (err) { }
  }, true);

  /* --------- 3. 页面用 window.open(blobUrl) 触发时 --------- */

  var origOpen = window.open;
  window.open = function (url) {
    try {
      if (url) {
        var u = String(url);
        if (u.indexOf('blob:') === 0) {
          fetchBlob(u, '');
          return null;
        }
        if (u.indexOf('data:') === 0 && sendDataUrl(u, '')) {
          return null;
        }
      }
    } catch (e) { }
    try {
      return origOpen.apply(window, arguments);
    } catch (e) {
      return null;
    }
  };

  /* --------- 4. 兜底接口，方便在 Console 里手动测 --------- */

  window.__rwDl = {
    v: V,
    /** __rwDl.saveBlob(new Blob([...], {type:'image/png'}), 'test.png') */
    saveBlob: sendBlob,
    /** __rwDl.saveUrl('data:image/png;base64,...', 'test.png') */
    saveUrl: sendDataUrl,
    /** 从 blob: URL 抓一份存下来 */
    fetchBlob: fetchBlob
  };
})();
