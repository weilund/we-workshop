/* WE 工坊 v0.2 */
/* 安全区：Activity 把状态栏/手势条高度注入这两个 CSS 变量，避免内容被系统栏遮挡 */
window.__applySafeTop = function (top, bottom) {
  const root = document.documentElement;
  root.style.setProperty('--safe-top', (Number(top) || 0) + 'px');
  root.style.setProperty('--safe-bottom', (Number(bottom) || 0) + 'px');
};
try {
  const t = window.Bridge && window.Bridge.getSafeTop ? Number(window.Bridge.getSafeTop()) : 0;
  const b = window.Bridge && window.Bridge.getSafeBottom ? Number(window.Bridge.getSafeBottom()) : 0;
  window.__applySafeTop(t, b);
} catch (e) { /* 非 App 环境忽略 */ }

const APPID = '431960';
const PAGE_SIZE = 30;
const MAX_PAGE = 1000;

const state = {
  sort: 'trend',
  typeTag: '',
  catTag: '',
  ageTag: '',
  search: '',
  page: 1,
  total: 0,
  items: [],
  details: {},
  detailId: null,
  downloads: {},
  loading: false,
};

/* ---- 筛选状态持久化：重开 App 记住上次的排序/类型/分类/分级/搜索 ---- */
const FILTER_KEY = 'we_filters';

function saveFilters() {
  try {
    localStorage.setItem(FILTER_KEY, JSON.stringify({
      sort: state.sort, typeTag: state.typeTag, catTag: state.catTag,
      ageTag: state.ageTag, search: state.search,
    }));
  } catch (e) { /* ignore */ }
}

function restoreFilters() {
  try {
    const f = JSON.parse(localStorage.getItem(FILTER_KEY) || 'null');
    if (!f) return;
    if (f.sort) state.sort = f.sort;
    if (typeof f.typeTag === 'string') state.typeTag = f.typeTag;
    if (typeof f.catTag === 'string') state.catTag = f.catTag;
    if (typeof f.ageTag === 'string') state.ageTag = f.ageTag;
    if (typeof f.search === 'string') state.search = f.search;
  } catch (e) { /* ignore */ }
}

function applyFilterUI() {
  document.querySelectorAll('#sortTabs .tab').forEach((b) => {
    b.classList.toggle('active', b.dataset.sort === state.sort);
  });
  [['typeChips', 'tag', state.typeTag],
   ['catChips', 'cat', state.catTag],
   ['ageChips', 'age', state.ageTag]].forEach((row) => {
    document.querySelectorAll('#' + row[0] + ' .chip').forEach((b) => {
      b.classList.toggle('active', (b.dataset[row[1]] || '') === (row[2] || ''));
    });
  });
  const s = document.getElementById('search');
  if (s) s.value = state.search || '';
}

let cbSeq = 0;
const pending = {};

window.__bridgeResult = function (cb, data) {
  const fn = pending[cb];
  if (!fn) return;
  delete pending[cb];
  fn(data);
};

function callBridge(method, ...args) {
  return new Promise((resolve) => {
    const cb = 'cb' + (++cbSeq);
    pending[cb] = resolve;
    args.push(cb);
    try {
      window.Bridge[method](...args);
    } catch (e) {
      delete pending[cb];
      resolve({ ok: false, error: String(e) });
    }
  });
}

function buildQuery() {
  const parts = ['appid=' + APPID, 'section=readonly', 'browsesort=' + encodeURIComponent(state.sort),
    'num_per_page=' + PAGE_SIZE, 'p=' + state.page];
  if (state.typeTag) parts.push('requiredtags%5B%5D=' + encodeURIComponent(state.typeTag));
  if (state.catTag) parts.push('requiredtags%5B%5D=' + encodeURIComponent(state.catTag));
  if (state.ageTag) parts.push('requiredtags%5B%5D=' + encodeURIComponent(state.ageTag));
  const q = state.search.trim();
  if (q) parts.push('searchtext=' + encodeURIComponent(q));
  return parts.join('&');
}

/* Steam 客户端 QueryFiles 排序枚举：0=最高评分 1=最新 3=趋势 */
const QUERY_TYPE = { trend: 3, mostrecent: 1, toprated: 0 };

/* 登录后走 Steam 客户端 API（与 WE 客户端数据源一致），未登录时回退网页抓取 */
function buildOnlineQuery() {
  const tags = [state.typeTag, state.catTag, state.ageTag].filter(Boolean);
  return JSON.stringify({
    queryType: QUERY_TYPE[state.sort] !== undefined ? QUERY_TYPE[state.sort] : 3,
    page: state.page,
    numPerPage: PAGE_SIZE,
    tags: tags,
    search: state.search.trim(),
  });
}

function fmtNum(n) {
  n = Number(n) || 0;
  if (n >= 100000000) return (n / 100000000).toFixed(1) + '亿';
  if (n >= 10000) return (n / 10000).toFixed(1) + '万';
  return String(n);
}

function fmtSize(b) {
  b = Number(b) || 0;
  if (!b) return '';
  if (b >= 1073741824) return (b / 1073741824).toFixed(2) + ' GB';
  if (b >= 1048576) return (b / 1048576).toFixed(1) + ' MB';
  if (b >= 1024) return (b / 1024).toFixed(0) + ' KB';
  return b + ' B';
}

function fmtDate(sec) {
  if (!sec) return '';
  const d = new Date(Number(sec) * 1000);
  const p = (x) => String(x).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

function typeLabel(tag) {
  switch (tag) {
    case 'Video': return '视频';
    case 'Scene': return '场景';
    case 'Application': return '应用';
    case 'Web': return '网页';
    default: return tag || '';
  }
}

function setStatus(text, isError) {
  const el = document.getElementById('status');
  el.textContent = text || '';
  el.className = 'status' + (isError ? ' error' : '');
}

function render() {
  const list = document.getElementById('list');
  const frag = document.createDocumentFragment();
  state.items.forEach((it) => {
    const d = state.details[String(it.id)] || {};
    const tags = (d.tags || []).map((t) => t.name);
    const typeTag = tags.find((t) => ['Scene', 'Video', 'Application', 'Web'].includes(t));

    const card = document.createElement('div');
    card.className = 'card';
    card.onclick = () => openDetail(it.id);

    const thumb = document.createElement('div');
    thumb.className = 'thumb';
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.src = it.img || d.preview_url || '';
    img.alt = it.title || '';
    thumb.appendChild(img);
    if (typeTag) {
      const badge = document.createElement('span');
      badge.className = 'type-badge';
      badge.textContent = typeLabel(typeTag);
      thumb.appendChild(badge);
    }

    const meta = document.createElement('div');
    meta.className = 'meta';
    const title = document.createElement('div');
    title.className = 'title';
    title.textContent = it.title || d.title || '(无标题)';
    const sub = document.createElement('div');
    sub.className = 'sub';
    const subParts = [];
    if (it.author) subParts.push(it.author);
    if (d.subscriptions) subParts.push(fmtNum(d.subscriptions) + ' 订阅');
    sub.textContent = subParts.join(' · ');
    meta.appendChild(title);
    meta.appendChild(sub);

    card.appendChild(thumb);
    card.appendChild(meta);
    frag.appendChild(card);
  });
  list.innerHTML = '';
  list.appendChild(frag);

  const totalPages = state.total ? Math.min(Math.ceil(state.total / PAGE_SIZE), MAX_PAGE) : 0;
  document.getElementById('pageInfo').textContent =
    '第 ' + state.page + ' 页' + (totalPages ? ' / 共 ' + totalPages.toLocaleString() + ' 页' : '');
  document.getElementById('prevBtn').disabled = state.page <= 1;
  document.getElementById('nextBtn').disabled = totalPages > 0 && state.page >= totalPages;
  const pageInput = document.getElementById('pageInput');
  if (pageInput) pageInput.max = String(totalPages || MAX_PAGE);
}

async function loadPage() {
  if (state.loading) return;
  state.loading = true;
  setStatus('加载中…');
  document.getElementById('list').innerHTML = '';
  let r = null;
  if (steam.loggedIn) {
    // 登录后走 Steam 客户端 QueryFiles，条目与 WE 客户端一致
    r = await callBridge('browseOnline', buildOnlineQuery());
  }
  if (!r || !r.ok) {
    // 未登录 / Steam 查询失败 → 回退网页匿名抓取（条目会少于客户端）
    if (steam.loggedIn) setStatus('Steam 客户端查询失败，已回退网页数据…');
    r = await callBridge('browse', buildQuery());
  }
  state.loading = false;
  if (!r || !r.ok) {
    setStatus('加载失败：' + ((r && r.error) || '未知错误'), true);
    return;
  }
  state.items = r.items || [];
  state.total = Number(r.total) || 0;
  state.details = {};
  // Steam 客户端 QueryFiles 已带回完整字段（含 file_size / hcontent_file），无需再补详情
  const hasFull = state.items.length > 0 && state.items[0].file_size !== undefined;
  if (hasFull) {
    state.items.forEach((it) => { state.details[String(it.id)] = it; });
  }
  setStatus(state.items.length ? '' : '没有找到内容');
  render();
  if (!hasFull && state.items.length) {
    const ids = state.items.map((i) => i.id).join(',');
    const dr = await callBridge('details', ids);
    if (dr && dr.ok && dr.items) {
      Object.keys(dr.items).forEach((k) => { state.details[k] = dr.items[k]; });
      render();
    }
  }
}

function fillDetail(d) {
  const img = document.getElementById('detailImg');
  img.src = d.preview_url || d.img || '';
  document.getElementById('detailTitle').textContent = d.title || '';
  const parts = [];
  if (d.subscriptions) parts.push(fmtNum(d.subscriptions) + ' 订阅');
  if (d.favorited) parts.push(fmtNum(d.favorited) + ' 收藏');
  if (d.views) parts.push(fmtNum(d.views) + ' 浏览');
  if (d.file_size) parts.push(fmtSize(d.file_size));
  if (d.time_created) parts.push(fmtDate(d.time_created) + ' 发布');
  document.getElementById('detailSub').textContent = parts.join(' · ');

  const tagBox = document.getElementById('detailTags');
  tagBox.innerHTML = '';
  (d.tags || []).forEach((t) => {
    const span = document.createElement('span');
    span.className = 'tag';
    span.textContent = t.display || t.name;
    tagBox.appendChild(span);
  });

  let desc = d.description || '';
  desc = desc.replace(/\[\/?[^\]]*\]/g, ' ').replace(/[ \t]+/g, ' ').trim();
  document.getElementById('detailDesc').textContent = desc;

  const dl = document.getElementById('downloadBtn');
  dl.classList.remove('hidden');
  dl.textContent = d.file_size ? '下载到手机（' + fmtSize(d.file_size) + '）' : '下载到手机';
  dl.onclick = () => startDownload(d);
  document.getElementById('detail').dataset.itemId = String(d.id || '');
  document.getElementById('openSteamBtn').onclick = () => {
    window.Bridge.openUrl('https://steamcommunity.com/sharedfiles/filedetails/?id=' + d.id);
  };
}

function renderDetailProgress(p) {
  const box = document.getElementById('dlProgress');
  if (!box) return;
  box.classList.remove('hidden');
  const pct = p.total > 0 ? Math.min(100, Math.round((p.received / p.total) * 100)) : 0;
  document.getElementById('dlBarFill').style.width = pct + '%';
  const text = document.getElementById('dlText');
  if (p.error) {
    text.textContent = '下载失败：' + p.error;
  } else if (p.done) {
    text.textContent = '已保存到 Download/Wallpaper WE/';
  } else {
    text.textContent = (p.phase || '下载中') + ' ' + pct + '%'
      + (p.total ? '（' + fmtSize(p.received) + ' / ' + fmtSize(p.total) + '）' : '');
  }
}

// 打开详情时按当前条目重置进度条，避免显示其他任务的进度
function resetDetailProgress(id) {
  const box = document.getElementById('dlProgress');
  if (!box) return;
  const p = state.downloads[String(id)];
  if (p && !p.done && !p.error) {
    renderDetailProgress(p);
  } else {
    box.classList.add('hidden');
    document.getElementById('dlBarFill').style.width = '0%';
    document.getElementById('dlText').textContent = '';
  }
}

async function openDetail(id, hint) {
  state.detailId = id;
  const key = String(id);
  const item = state.items.find((i) => String(i.id) === key) || {};
  const known = state.details[key] || state.details[id] || {};
  fillDetail(Object.assign({
    id: key,
    title: (hint && hint.title) || item.title,
    preview_url: (hint && hint.img) || item.img,
  }, known));
  // WebView 重建（切后台被回收等）后内存里可能没有进度记录，
  // 此时从原生下载器恢复一次，避免「列表在下载但详情页没有进度条」。
  if (!state.downloads[key] && window.Bridge.downloadState) {
    try {
      const raw = window.Bridge.downloadState(key);
      if (raw) {
        const p = JSON.parse(raw);
        if (p) state.downloads[key] = p;
      }
    } catch (e) { /* ignore */ }
  }
  resetDetailProgress(key);
  document.getElementById('detail').classList.remove('hidden');
  document.getElementById('detail').scrollTop = 0;

  if (!state.details[key]) {
    const r = await callBridge('details', key);
    if (r && r.ok && r.items && (r.items[key] || r.items[id])) {
      state.details[key] = r.items[key] || r.items[id];
      if (String(state.detailId) === key) {
        fillDetail(Object.assign({ id: key, title: (hint && hint.title) || item.title }, state.details[key]));
      }
      render();
    }
  }
}

function closeDetail() {
  state.detailId = null;
  document.getElementById('detail').classList.add('hidden');
}

window.__onBack = function () {
  const settings = document.getElementById('settings');
  if (settings && !settings.classList.contains('hidden')) {
    closeSettings();
    return true;
  }
  const modal = document.getElementById('detail');
  if (modal && !modal.classList.contains('hidden')) {
    closeDetail();
    return true;
  }
  return false;
};

/* ---- events ---- */
document.getElementById('sortTabs').addEventListener('click', (e) => {
  const btn = e.target.closest('.tab');
  if (!btn) return;
  document.querySelectorAll('#sortTabs .tab').forEach((b) => b.classList.remove('active'));
  btn.classList.add('active');
  state.sort = btn.dataset.sort;
  state.page = 1;
  saveFilters();
  loadPage();
});

document.getElementById('typeChips').addEventListener('click', (e) => {
  const btn = e.target.closest('.chip');
  if (!btn) return;
  document.querySelectorAll('#typeChips .chip').forEach((b) => b.classList.remove('active'));
  btn.classList.add('active');
  state.typeTag = btn.dataset.tag;
  state.page = 1;
  saveFilters();
  loadPage();
});

document.getElementById('ageChips').addEventListener('click', (e) => {
  const btn = e.target.closest('.chip');
  if (!btn) return;
  document.querySelectorAll('#ageChips .chip').forEach((b) => b.classList.remove('active'));
  btn.classList.add('active');
  state.ageTag = btn.dataset.age;
  state.page = 1;
  saveFilters();
  loadPage();
});

document.getElementById('catChips').addEventListener('click', (e) => {
  const btn = e.target.closest('.chip');
  if (!btn) return;
  document.querySelectorAll('#catChips .chip').forEach((b) => b.classList.remove('active'));
  btn.classList.add('active');
  state.catTag = btn.dataset.cat;
  state.page = 1;
  saveFilters();
  loadPage();
});

let searchTimer = null;
document.getElementById('search').addEventListener('input', (e) => {
  clearTimeout(searchTimer);
  const v = e.target.value;
  searchTimer = setTimeout(() => {
    state.search = v;
    state.page = 1;
    saveFilters();
    loadPage();
  }, 450);
});

document.getElementById('prevBtn').onclick = () => {
  if (state.page > 1) {
    state.page--;
    loadPage();
    window.scrollTo({ top: 0 });
  }
};

document.getElementById('nextBtn').onclick = () => {
  const totalPages = state.total ? Math.min(Math.ceil(state.total / PAGE_SIZE), MAX_PAGE) : MAX_PAGE;
  if (state.page < totalPages) {
    state.page++;
    loadPage();
    window.scrollTo({ top: 0 });
  }
};

/* 输入页码直接跳转 */
function jumpToPage() {
  const inp = document.getElementById('pageInput');
  if (!inp) return;
  const totalPages = state.total ? Math.min(Math.ceil(state.total / PAGE_SIZE), MAX_PAGE) : MAX_PAGE;
  let n = parseInt(inp.value, 10);
  inp.value = '';
  if (!n || n < 1) n = 1;
  if (n > totalPages) n = totalPages;
  if (n === state.page) return;
  state.page = n;
  loadPage();
  window.scrollTo({ top: 0 });
}

document.getElementById('jumpBtn').onclick = jumpToPage;
document.getElementById('pageInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    e.preventDefault();
    jumpToPage();
  }
});

/* ---- settings ---- */
function openSettings() {
  let cur = localStorage.getItem('proxy') || '';
  // 清掉早期调试时写入的局域网调试地址（*:7891），避免残留在设置里
  if (/^192\.168\.\d+\.\d+:7891$/.test(cur)) { cur = ''; localStorage.removeItem('proxy'); }
  try { cur = window.Bridge.getProxy() || cur; } catch (e) { /* bridge 不可用时用缓存 */ }
  document.getElementById('proxyInput').value = cur || '';
  renderSteam();
  document.getElementById('settings').classList.remove('hidden');
}

function closeSettings() {
  document.getElementById('settings').classList.add('hidden');
}

document.getElementById('settingsBtn').onclick = openSettings;

document.getElementById('saveProxyBtn').onclick = () => {
  const v = document.getElementById('proxyInput').value.trim();
  const r = window.Bridge.setProxy(v);
  if (r === 'ok') {
    localStorage.setItem('proxy', v);
    closeSettings();
    window.Bridge.toast(v ? '代理已保存：' + v : '已切换为直连');
    state.page = 1;
    loadPage();
  } else {
    window.Bridge.toast(String(r || '保存失败').replace(/^err:?/, ''));
  }
};

/* ---- steam ---- */
let steam = {
  loggedIn: false, busy: false, guard: false, guardType: '', guardEmail: '',
  account: '', status: '未登录', error: '',
};

window.__steamState = function (st) {
  const wasLoggedIn = steam.loggedIn;
  steam = Object.assign(steam, st || {});
  renderSteam();
  if (wasLoggedIn !== steam.loggedIn && !steam.busy) {
    // 登录/登出后切换数据源并刷新列表
    state.page = 1;
    loadPage();
  }
};

window.__steamGuard = function (type, email) {
  steam.guard = true;
  steam.guardType = type || 'device';
  steam.guardEmail = email || '';
  renderSteam();
  document.getElementById('settings').classList.remove('hidden');
  const inp = document.getElementById('steamGuardInput');
  if (inp) { inp.value = ''; inp.focus(); }
};

function renderSteam() {
  const st = document.getElementById('steamStatus');
  if (!st) return;
  st.textContent = steam.status || (steam.loggedIn ? '已登录：' + steam.account : '未登录');
  st.classList.toggle('err', !!steam.error);
  document.getElementById('steamLoggedOut').classList.toggle('hidden', !!steam.loggedIn);
  document.getElementById('steamLoggedIn').classList.toggle('hidden', !steam.loggedIn);
  const guardBox = document.getElementById('steamGuardBox');
  guardBox.classList.toggle('hidden', !steam.guard || !!steam.loggedIn);
  const hint = document.getElementById('steamGuardHint');
  if (hint && steam.guard) {
    hint.textContent = steam.guardType === 'email'
      ? ('验证码已发送到邮箱 ' + (steam.guardEmail || '') + '，请输入')
      : '请在 Steam 手机上确认登录，或输入手机令牌验证码';
  }
  const user = document.getElementById('steamUser');
  const pass = document.getElementById('steamPass');
  if (pass && steam.loggedIn) pass.value = '';
}

document.getElementById('steamLoginBtn').onclick = () => {
  const u = document.getElementById('steamUser').value.trim();
  const p = document.getElementById('steamPass').value;
  if (!u || !p) { window.Bridge.toast('请输入账号和密码'); return; }
  window.Bridge.steamLogin(u, p);
};

document.getElementById('steamGuardBtn').onclick = () => {
  const c = document.getElementById('steamGuardInput').value.trim();
  if (!c) return;
  window.Bridge.steamGuard(c);
};

document.getElementById('steamLogoutBtn').onclick = () => window.Bridge.steamLogout();

/* ---- download list ---- */
function escHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, ch => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[ch]));
}

function openDownloads() {
  renderDownloads();
  document.getElementById('downloads').classList.remove('hidden');
}
function closeDownloads() {
  document.getElementById('downloads').classList.add('hidden');
}
document.getElementById('downloadsBtn').onclick = openDownloads;
document.getElementById('clearDownloadsBtn').onclick = () => {
  window.Bridge.clearDownloads();
  renderDownloads();
  window.Bridge.toast('已清空下载记录');
};

function renderDownloads() {
  const box = document.getElementById('downloadItems');
  if (!box) return;
  let arr = [];
  try { arr = JSON.parse(window.Bridge.getDownloads() || '[]'); } catch (e) { arr = []; }
  if (!arr.length) {
    box.innerHTML = '<p class="setting-hint">还没有下载记录</p>';
    return;
  }
  arr.sort((a, b) => (b.time || 0) - (a.time || 0));
  box.innerHTML = arr.map(d => {
    const pct = d.size > 0 ? Math.min(100, Math.round((d.received / d.size) * 100)) : 0;
    let statusText;
    if (d.status === 'done') statusText = '已完成';
    else if (d.status === 'failed') statusText = '失败';
    else if (d.status === 'canceled') statusText = '已取消';
    else {
      const lp = state.downloads[String(d.id)];
      statusText = (lp && lp.phase && /自动重试|继续下载/.test(lp.phase)) ? lp.phase : ('下载中 ' + pct + '%');
    }
    const img = d.img ? '<img src="' + escHtml(d.img) + '" alt="" loading="lazy">' : '<div class="dl-ph"></div>';
    return '<div class="dl-item" data-id="' + escHtml(d.id) + '"'
      + ' data-title="' + escHtml(d.title || '') + '" data-img="' + escHtml(d.img || '') + '">'
      + '<div class="dl-thumb">' + img + '</div>'
      + '<div class="dl-meta">'
      + '<div class="dl-title">' + escHtml(d.title || ('ID ' + d.id)) + '</div>'
      + '<div class="dl-status dl-' + escHtml(d.status) + '">' + statusText
      + (d.size ? ' · ' + fmtSize(d.size) : '') + '</div>'
      + '</div>'
      + '<div class="dl-actions">'
      + (d.status === 'done'
        ? '<button class="dl-open" data-open="' + escHtml(d.id) + '">打开文件夹</button>'
        : (d.status === 'downloading'
          ? '<button class="dl-cancel" data-cancel="' + escHtml(d.id) + '">取消</button>'
          : '<button class="dl-retry" data-retry="' + escHtml(d.id) + '" data-manifest="' + escHtml(d.manifest || '') + '" data-title="' + escHtml(d.title || '') + '" data-img="' + escHtml(d.img || '') + '">重试</button>'))
      + (d.status === 'done' ? '' : '<button class="dl-del" data-del="' + escHtml(d.id) + '">移除</button>')
      + '</div>'
      + '</div>';
  }).join('');
  // 点击整条记录（非按钮区域）→ 跳到该条目的详情页
  box.querySelectorAll('.dl-item').forEach(el => {
    el.onclick = (e) => {
      if (e.target.closest('button')) return;
      const id = Number(el.dataset.id);
      if (!id) return;
      closeDownloads();
      openDetail(id, { title: el.dataset.title, img: el.dataset.img });
    };
  });
  box.querySelectorAll('.dl-open').forEach(btn => {
    btn.onclick = (e) => {
      e.stopPropagation();
      window.Bridge.openDownloadFolder(btn.dataset.open);
    };
  });
  box.querySelectorAll('.dl-del').forEach(btn => {
    btn.onclick = (e) => {
      e.stopPropagation();
      window.Bridge.removeDownload(btn.dataset.del);
      renderDownloads();
    };
  });
  box.querySelectorAll('.dl-cancel').forEach(btn => {
    btn.onclick = (e) => {
      e.stopPropagation();
      if (window.Bridge.cancelDownloadById) window.Bridge.cancelDownloadById(btn.dataset.cancel);
      window.Bridge.toast('正在取消…');
      setTimeout(renderDownloads, 600);
    };
  });
  box.querySelectorAll('.dl-retry').forEach(btn => {
    btn.onclick = (e) => {
      e.stopPropagation();
      const mid = btn.dataset.manifest || '';
      if (!mid || mid === '0') { window.Bridge.toast('缺少源文件信息，请重新在详情页下载'); return; }
      if (window.Bridge.retryDownload) {
        window.Bridge.retryDownload(btn.dataset.retry, mid, btn.dataset.title || '', btn.dataset.img || '');
        window.Bridge.toast('已重新开始下载');
        setTimeout(renderDownloads, 800);
      }
    };
  });
}

/* ---- download ---- */
function startDownload(d) {
  if (!steam.loggedIn) {
    window.Bridge.toast('请先在设置中登录 Steam');
    openSettings();
    return;
  }
  // 已经在下载中的条目不要重复启动（重复启动会并发写同一文件）
  const key = String(d.id);
  const cur = state.downloads[key];
  if (cur && !cur.done && !cur.error) {
    renderDetailProgress(cur);
    window.Bridge.toast('该条目正在下载中…');
    return;
  }
  if (window.Bridge.isDownloading && window.Bridge.isDownloading(key)) {
    try {
      const raw = window.Bridge.downloadState ? window.Bridge.downloadState(key) : null;
      if (raw) state.downloads[key] = JSON.parse(raw);
    } catch (e) { /* ignore */ }
    resetDetailProgress(key);
    window.Bridge.toast('该条目正在下载中…');
    return;
  }
  const mid = String((d && (d.hcontent_file || d.manifest)) || '0');
  if (!mid || mid === '0') {
    window.Bridge.toast('该条目缺少源文件信息');
    return;
  }
  const box = document.getElementById('dlProgress');
  box.classList.remove('hidden');
  document.getElementById('dlText').textContent = '准备下载…';
  document.getElementById('dlBarFill').style.width = '0%';
  window.Bridge.download(String(d.id), mid, String(d.title || ''), String(d.img || ''));
}

window.__downloadProgress = function (p) {
  if (!p) return;
  state.downloads[String(p.itemId)] = p;
  try {
    const dlg = document.getElementById('downloads');
    if (dlg && !dlg.classList.contains('hidden')) renderDownloads();
  } catch (e) { /* ignore */ }
  // 只有当前详情页正在下载的条目才显示详情页进度条，其他任务不干扰浏览
  if (state.detailId == null || String(p.itemId) !== String(state.detailId)) {
    if (p.done) window.Bridge.toast('后台下载完成：已保存到 Download/Wallpaper WE/');
    return;
  }
  renderDetailProgress(p);
  if (p.done) window.Bridge.toast('下载完成，已保存到 Download/Wallpaper WE/');
};

restoreFilters();
applyFilterUI();
loadPage();
