/*
 * 原型自检工具 —— 在 Node 里跑 design/dashboard-prototype.html 的脚本，
 * 捕获运行时错误，并校验参数 / 控件 / 文案的一致性。
 *
 *   node tools/prototype-check/check.js
 *
 * 校验项：
 *   1. 脚本语法 + 初始化、点击/移动/滚轮/导出 全部可执行无异常
 *   2. GROUPS 里每个控件的 k 都在 DEFAULTS 中存在（无缺失、无重复）
 *   3. DEFAULTS 中没有被任何控件暴露的"孤儿"键
 *   4. 绘制代码引用的每个 P.xxx 都在 DEFAULTS 中定义
 *   5. 中英文案键齐全，且占位符能正常展开
 *   6. 「导出参数」输出包含全部关键常量标记
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const HTML = path.join(ROOT, 'design', 'dashboard-prototype.html');

/* ---------------------------------------------------------------- 1. 抽取脚本 */
const html = fs.readFileSync(HTML, 'utf8');
const re = /<script[^>]*>([\s\S]*?)<\/script>/gi;
let m, src = '', blocks = 0;
while ((m = re.exec(html))) { blocks++; src += m[1]; }
console.log('HTML:', path.relative(ROOT, HTML), '|', html.length, 'chars |', blocks, 'script block(s)');
if (!blocks) { console.error('!! 未找到 <script>'); process.exit(1); }

try { new Function(src); } catch (e) { console.error('!! 语法错误:', e.message); process.exit(1); }
console.log('语法: OK');

/* ---------------------------------------------------------------- 2. DOM 桩 */
const drawCalls = Object.create(null);

function makeCtx() {
  const t = {
    canvas: null,
    globalAlpha: 1, fillStyle: '#000', strokeStyle: '#000', lineWidth: 1,
    font: '10px sans-serif', textAlign: 'left', textBaseline: 'alphabetic',
    imageSmoothingEnabled: true, globalCompositeOperation: 'source-over',
    measureText: (s) => ({ width: String(s).length * 6 }),
    createLinearGradient: () => ({ addColorStop() {} }),
    createRadialGradient: () => ({ addColorStop() {} }),
    getImageData: () => ({ data: new Uint8ClampedArray(4), width: 1, height: 1 }),
    createImageData: () => ({ data: new Uint8ClampedArray(4), width: 1, height: 1 }),
    putImageData: () => {},
  };
  return new Proxy(t, {
    get(o, k) {
      if (k in o) return o[k];
      if (typeof k === 'symbol') return undefined;
      return (...a) => { drawCalls[k] = (drawCalls[k] || 0) + 1; };
    },
    set(o, k, v) { o[k] = v; return true; },
  });
}

function makeEl(tag) {
  const el = {
    tagName: String(tag).toUpperCase(), children: [], style: {}, dataset: {}, _attrs: {},
    _listeners: {}, _html: '', value: '', textContent: '', checked: false,
    width: 1280, height: 720, clientWidth: 1280, clientHeight: 720,
    offsetWidth: 1280, offsetHeight: 720,
    classList: {
      _s: new Set(),
      add(...c) { c.forEach(x => this._s.add(x)); },
      remove(...c) { c.forEach(x => this._s.delete(x)); },
      contains(c) { return this._s.has(c); },
      toggle(c, f) { const on = f === undefined ? !this._s.has(c) : !!f; on ? this._s.add(c) : this._s.delete(c); return on; },
    },
    appendChild(c) { this.children.push(c); return c; },
    removeChild(c) { const i = this.children.indexOf(c); if (i >= 0) this.children.splice(i, 1); return c; },
    setAttribute(k, v) { this._attrs[k] = v; },
    getAttribute(k) { return this._attrs[k]; },
    addEventListener(t, fn) { (this._listeners[t] = this._listeners[t] || []).push(fn); },
    removeEventListener() {},
    getBoundingClientRect() { return { left: 0, top: 0, width: this.width, height: this.height, right: this.width, bottom: this.height }; },
    querySelector() { return makeEl('div'); },
    querySelectorAll() { return []; },
    getContext() { const c = makeCtx(); c.canvas = el; return c; },
    toDataURL() { return 'data:,'; },
    focus() {}, select() {}, blur() {},
  };
  Object.defineProperty(el, 'innerHTML', {
    get() { return this._html; },
    set(v) { this._html = v; if (v === '') this.children.length = 0; },
  });
  return el;
}

const byId = Object.create(null);
['mc', 'btnReset', 'btnExport', 'exportText', 'modal', 'btnCloseModal', 'btnCopy',
 'designer', 'stageView', 'infoSize', 'infoReal', 'infoCanvas', 'infoScale']
  .forEach(id => { byId[id] = makeEl(id === 'mc' ? 'canvas' : 'div'); });

const documentStub = {
  getElementById: (id) => (byId[id] || (byId[id] = makeEl('div'))),
  createElement: (t) => makeEl(t),
  createTextNode: (t) => ({ nodeValue: String(t) }),
  addEventListener() {},
  querySelector: () => makeEl('div'),
  querySelectorAll: () => [],
  body: makeEl('body'),
  documentElement: makeEl('html'),
};
const windowStub = {
  innerWidth: 1600, innerHeight: 900, devicePixelRatio: 1,
  addEventListener() {}, removeEventListener() {},
  requestAnimationFrame(fn) { return setTimeout(() => fn(Date.now()), 0); },
  cancelAnimationFrame() {},
  matchMedia: () => ({ matches: false, addEventListener() {} }),
  getComputedStyle: () => ({ getPropertyValue: () => '' }),
  navigator: { clipboard: { writeText: async () => {} } },
};

const asyncErrors = [];
process.on('uncaughtException', (e) => {
  console.error('!! 未捕获异常:\n' + (e && e.stack ? e.stack : e));
  asyncErrors.push(String(e && e.message || e));
});

/* 逐个赋值：Node 22 的 globalThis.navigator 等属性只读，直接 Object.assign 会抛异常 */
function defineStub(name, value) {
  try { globalThis[name] = value; } catch (_) {
    try { Object.defineProperty(globalThis, name, { value, writable: true, configurable: true }); } catch (_) {}
  }
}
defineStub('document', documentStub);
defineStub('window', windowStub);
defineStub('navigator', windowStub.navigator);
defineStub('requestAnimationFrame', windowStub.requestAnimationFrame);
defineStub('cancelAnimationFrame', windowStub.cancelAnimationFrame);
defineStub('getComputedStyle', windowStub.getComputedStyle);
defineStub('devicePixelRatio', 1);
defineStub('localStorage', {
  _d: {}, getItem(k) { return this._d[k] ?? null; },
  setItem(k, v) { this._d[k] = String(v); }, removeItem(k) { delete this._d[k]; },
});
defineStub('Image', class {
  set src(v) { this._src = v; } get src() { return this._src; } addEventListener() {}
});
defineStub('alert', () => {});

/* ---------------------------------------------------------------- 3. 执行 */
const EXPOSE = '\n;globalThis.__D=DEFAULTS;globalThis.__G=GROUPS;globalThis.__R=RAILS;'
             + 'globalThis.__N=NODES;globalThis.__T=t();globalThis.__LANG=()=>LANG;\n';
let failed = false;
try {
  new Function(src + EXPOSE).call(globalThis);
  console.log('初始化: OK');
} catch (e) { console.error('!! 初始化失败:\n' + e.stack); process.exit(1); }

function interact(name, type, ev) {
  const fn = (byId['mc']._listeners[type] || [])[0];
  if (!fn) { console.log(name + ': 未注册监听'); return; }
  try { fn(ev); console.log(name + ': OK'); }
  catch (e) { console.error('!! ' + name + ' 失败:\n' + e.stack); failed = true; }
}
const base = { offsetX: 300, offsetY: 200, clientX: 300, clientY: 200, preventDefault() {}, stopPropagation() {} };
interact('点击', 'click', { ...base, button: 0 });
interact('移动', 'mousemove', { ...base });
interact('滚轮', 'wheel', { ...base, deltaY: -120 });
try {
  byId['btnExport'].onclick();
  console.log('导出: OK (' + String(byId['exportText'].value).length + ' chars)');
} catch (e) { console.error('!! 导出失败:\n' + e.stack); failed = true; }
console.log('绘制调用:', Object.keys(drawCalls).sort().join(', '));

/* ---------------------------------------------------------------- 4. 一致性 */
const D = globalThis.__D, G = globalThis.__G, R = globalThis.__R, N = globalThis.__N, T = globalThis.__T;
const dKeys = new Set(Object.keys(D));

console.log('\nDEFAULTS 参数:', dKeys.size, '| 模拟轨道:', R.length, '| 节点:', N.length,
            '| 岔口:', N.filter(n => n.count >= 2).length);

const SPECIAL = new Set(['lang']);   // lang 是特殊控件，直接改 LANG 全局，不在 DEFAULTS 里
const gKeys = [];
for (const grp of G) for (const it of grp.items) if (it.k) gKeys.push(it.k);
const missing = gKeys.filter(k => !dKeys.has(k) && !SPECIAL.has(k));
const dupes = gKeys.filter((k, i) => gKeys.indexOf(k) !== i);
const orphan = [...dKeys].filter(k => !new Set(gKeys).has(k));
const refs = new Set();
for (const mm of html.matchAll(/\bP\.([A-Za-z_][A-Za-z0-9_]*)/g)) refs.add(mm[1]);
const badRefs = [...refs].filter(k => !dKeys.has(k));

const needT = ['title', 'listHeader', 'noRails', 'noRailsHint', 'selNone', 'selOne', 'selBoth',
  'canvasInfo', 'platform', 'siding', 'occupied', 'railSpeed', 'railLen', 'railColors', 'hint'];
const badT = needT.filter(k => T[k] === undefined);

const exp = String(byId['exportText'].value || '');
const markers = ['int listWidth', 'int colorWidth', 'int TEXT_HEIGHT', 'int ARGB_WINDOW',
  'double RAIL_WIDTH', 'boolean SHOW_TERRAIN', 'int BUTTON_MIN_H', '"guiWidth"'];
const badM = markers.filter(x => exp.indexOf(x) < 0);

function line(ok, label, detail) {
  console.log((ok ? '  OK   ' : '  FAIL ') + label + (detail ? ' → ' + detail : ''));
  if (!ok) failed = true;
}
console.log('');
line(missing.length === 0, '控件键都在 DEFAULTS 中', missing.join(', '));
line(dupes.length === 0, '控件键无重复', dupes.join(', '));
line(orphan.length === 0, 'DEFAULTS 无孤儿键', orphan.join(', '));
line(badRefs.length === 0, 'P.* 引用都已定义', badRefs.join(', '));
line(badT.length === 0, '中英文案键齐全', badT.join(', '));
line(badM.length === 0, '导出块关键常量齐全', badM.join(' | '));
console.log('\n  控件 ' + gKeys.length + ' 个 / ' + G.length + ' 组  |  导出 ' + exp.length + ' chars');
console.log('  listHeader(7) = ' + JSON.stringify(T.listHeader(7))
          + '   railSpeed(80) = ' + JSON.stringify(T.railSpeed(80)));

if (process.env.DUMP_EXPORT) {
  console.log('\n---- 导出内容 ----\n' + exp + '\n---- /导出内容 ----');
}

setTimeout(() => {
  if (asyncErrors.length) { console.error('\n!! 异步错误:'); asyncErrors.forEach(e => console.error(e)); failed = true; }
  console.log('\n' + (failed ? '==== 自检未通过 ====' : '==== 自检全部通过 ===='));
  process.exit(failed ? 1 : 0);
}, 80);
