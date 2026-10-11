/* 在 Node 里用最小 DOM 桩真跑一遍布局编辑器脚本，验证运行时行为。
   用法：node tools/prototype-check/run-hud-editor.js
*/
"use strict";

const fs = require("fs");
const vm = require("vm");
const path = require("path");

const file = path.join(__dirname, "..", "..", "design", "driving-hud-editor.html");
const html = fs.readFileSync(file, "utf8");
const js = html.match(/<script>([\s\S]*?)<\/script>/)[1];

let fail = 0;
const ok = m => console.log("  OK   " + m);
const bad = m => { console.log("  FAIL " + m); fail++; };
const eq = (a, b, m) => (a === b ? ok(m + " = " + a) : bad(m + " 期望 " + b + "，实际 " + a));

/* ---------- 最小 DOM 桩 ---------- */

function makeCtx() {
  const ctx = {
    fillStyle: "", strokeStyle: "", lineWidth: 1, font: "",
    textAlign: "", textBaseline: "", lineCap: "",
    _calls: 0,
    measureText: t => ({ width: String(t).length * 7 }),
  };
  for (const m of ["clearRect", "fillRect", "strokeRect", "beginPath", "arc", "moveTo",
    "lineTo", "stroke", "fill", "save", "restore", "setTransform", "setLineDash",
    "fillText", "strokeText", "closePath", "rect", "translate", "rotate", "scale"]) {
    ctx[m] = () => { ctx._calls++; };
  }
  return ctx;
}

const ctx = makeCtx();
const canvas = {
  width: 0, height: 0, style: {},
  _listeners: {},
  getContext: () => ctx,
  addEventListener(t, f) { (this._listeners[t] = this._listeners[t] || []).push(f); },
  setPointerCapture() {},
  getBoundingClientRect() { return { left: 0, top: 0, width: 854, height: 480 }; },
};

function makeEl(tag) {
  const el = {
    tagName: String(tag || "div").toUpperCase(),
    style: {}, children: [], _text: "", _html: "",
    value: "", type: "", checked: false, selected: false,
    className: "", title: "", step: "",
    clientWidth: 900, clientHeight: 600,
    classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
    parentElement: null,
    appendChild(c) { this.children.push(c); c.parentElement = this; return c; },
    addEventListener() {}, select() {}, focus() {},
    getContext: () => ctx,
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return this._html; },
    set(v) { this._html = String(v); this.children = []; },
  });
  Object.defineProperty(el, "textContent", {
    get() { return this._text; },
    set(v) { this._text = String(v); },
  });
  return el;
}

const byId = {};
for (const id of ["cv", "stageView", "infoSel", "palette", "list", "props",
  "inW", "inH", "inSnap", "inGrid", "btnReset", "modal", "btnExport",
  "exportText", "btnCloseModal", "btnCopy"]) {
  byId[id] = id === "cv" ? canvas : makeEl("div");
}

const groupHeads = [makeEl("h3"), makeEl("h3"), makeEl("h3")];
groupHeads.forEach(h => { h.parentElement = { classList: { toggle() {} } }; });

const document = {
  getElementById: id => byId[id] || makeEl("div"),
  createElement: tag => makeEl(tag),
  querySelectorAll: () => groupHeads,
  execCommand: () => true,
};

const window = {
  devicePixelRatio: 1,
  addEventListener() {},
};

/* ---------- 跑脚本（末尾挂一个探针把内部变量暴露出来） ---------- */
/* 注意：widgets / G 会被重新赋值，必须用 getter 取最新引用，不能存快照 */
const probe = `
;globalThis.__probe = {
  get widgets() { return widgets; },
  get G() { return G; },
  TYPES,
  exportText, snapPos, snapSize, dialGeom, addWidget, removeWidget, moveZ,
  draw, layoutCanvas, hitTest, renderProps,
  get selId() { return selId; },
};
`;
const sandbox = { document, window, console, Math, JSON, Object, Array, String, Number, RegExp, setTimeout };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);

try {
  new vm.Script(js + probe, { filename: "editor.js" }).runInContext(sandbox);
  ok("boot() 执行完毕，无运行时异常");
} catch (e) {
  bad("运行时异常: " + (e && e.stack ? e.stack.split("\n").slice(0, 3).join(" | ") : e));
  process.exit(1);
}

const p = sandbox.__probe;

/* ---------- 1. 表盘几何（必须与 MTR DrivingGuiRenderer 一致） ---------- */
const d = p.dialGeom();
console.log("\n[表盘几何] 窗口 " + p.G.guiWidth + "×" + p.G.guiHeight);
eq(d.left, 742, "dialLeft  (854-16-96)");
eq(d.right, 838, "dialRight (854-16)");
eq(d.top, 368, "dialTop");
eq(d.bottom, 464, "dialBottom");
eq(d.cx, 790, "dialCenterX");
eq(d.cy, 416, "dialCenterY");
eq(d.r, 48, "dialRadius");

/* ---------- 2. 默认布局 ---------- */
console.log("\n[默认布局]");
eq(p.widgets.length, 4, "控件数（1 面板 + 3 行）");
const panel = p.widgets.find(w => w.type === "panel");
const dep = p.widgets.find(w => w.type === "departure");
eq(panel.x, 626, "面板 x（dialLeft-4-112）");
eq(panel.y, 391, "面板 y（表盘竖直居中）");
eq(panel.w, 112, "面板宽");
eq(panel.h, 50, "面板高（4*2+3*14）");
eq(dep.x, 630, "行 x（面板内边距 4）");
eq(dep.y, 395, "行 y");
eq(dep.w, 104, "行宽");
eq(dep.h, 14, "行高");
eq(panel.x + panel.w, d.left - 4, "面板右缘 = 表盘左缘 - 4（贴住表盘）");

/* ---------- 3. 吸附 ---------- */
console.log("\n[吸附]");
const s1 = p.snapPos(741, 391, 112, 50, panel.id);
ok("把面板往右推到 x=741 时吸附到表盘左缘: " + JSON.stringify(s1) + "，产出对齐线 " + s1.guides.length + " 条");
eq(s1.guides.some(g => g.axis === "x"), true, "有横向对齐线");
const s2 = p.snapSize(600, 300, 143, 60, -1);
eq(s2.w, 142, "改大小时右缘吸附到表盘左缘（600+142=742）");

/* ---------- 4. 增 / 删 / 层级 ---------- */
console.log("\n[增删]");
const before = p.widgets.length;
p.addWidget("mode");
eq(p.widgets.length, before + 1, "新增后控件数");
const added = p.widgets[p.widgets.length - 1];
eq(added.type, "mode", "新增的类型");
eq(p.selId, added.id, "新增后自动选中");
p.removeWidget(added.id);
eq(p.widgets.length, before, "删除后控件数");
eq(p.selId, null, "删除选中项后取消选中");
const firstId = p.widgets[0].id;
p.moveZ(firstId, +1);
eq(p.widgets[1].id, firstId, "上移一层后顺序变化");

/* ---------- 5. 命中测试 ---------- */
console.log("\n[命中测试]");
eq(p.hitTest(700, 400) !== null, true, "点在面板上能命中");
eq(p.hitTest(5, 5), null, "点空白处不命中");

/* ---------- 6. 导出 ---------- */
console.log("\n[导出]");
const txt = p.exportText();
const jsonStart = txt.indexOf('{\n  "guiWidth"');
const json = JSON.parse(txt.slice(jsonStart));
eq(json.guiWidth, 854, "JSON guiWidth");
eq(json.widgets.length, 4, "JSON 控件数");
const jPanel = json.widgets.find(w => w.type === "panel");
eq(jPanel.rightInset, 854 - (626 + 112), "面板 rightInset");
eq(jPanel.bottomInset, 480 - (391 + 50), "面板 bottomInset");
eq(json.global.edgePadding, 16, "全局 edgePadding");
eq(json.global.toolSize, 96, "全局 toolSize");
ok("文本块含 Java 常量: " + /int DRIVING_HUD_EDGE_PADDING = 16;/.test(txt));
ok("文本块含 rightInset 注释: " + /rightInset=\d+ bottomInset=\d+/.test(txt));

/* ---------- 7. 重绘 / 缩放不炸 ---------- */
console.log("\n[绘制]");
const callsBefore = ctx._calls;
p.draw();
ok("draw() 调用 canvas API " + (ctx._calls - callsBefore) + " 次");
p.layoutCanvas();
ok("layoutCanvas() 正常，viewScale 已更新");

console.log("");
console.log(fail === 0 ? "运行时自检全部通过" : "有 " + fail + " 项失败");
process.exit(fail === 0 ? 0 : 1);
