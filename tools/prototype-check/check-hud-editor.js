/* 手动驾驶 HUD 布局编辑器的静态自检：
   1) 内嵌脚本语法
   2) 所有 getElementById 的 id 在 HTML 里存在
   3) 关键函数都有定义
   4) TYPES 的键与 defaultLayout 用到的类型一致
   用法：node tools/prototype-check/check-hud-editor.js
*/
"use strict";

const fs = require("fs");
const vm = require("vm");
const path = require("path");

const file = path.join(__dirname, "..", "..", "design", "driving-hud-editor.html");
const html = fs.readFileSync(file, "utf8");

let fail = 0;
const ok = m => console.log("  OK   " + m);
const bad = m => { console.log("  FAIL " + m); fail++; };

const m = html.match(/<script>([\s\S]*?)<\/script>/);
if (!m) { console.log("FAIL: 没找到 <script> 块"); process.exit(1); }
const js = m[1];

try {
  new vm.Script(js, { filename: "editor.js" });
  ok("语法检查通过（" + js.split("\n").length + " 行 / " + js.length + " 字符）");
} catch (e) {
  bad("语法错误: " + e.message);
}

const ids = [...new Set([...js.matchAll(/getElementById\(\s*["']([^"']+)["']\s*\)/g)].map(x => x[1]))];
for (const id of ids) {
  if (html.includes('id="' + id + '"')) ok("id 存在: " + id);
  else bad("id 缺失: " + id);
}

const fns = [
  "resolveColor", "mk", "defaultLayout", "dialGeom", "layoutCanvas", "draw", "drawWorld",
  "drawGrid", "drawDial", "drawWidget", "drawSelection", "drawGuides", "snapTargets",
  "snapPos", "snapSize", "toGui", "hitTest", "endDrag", "addWidget", "removeWidget",
  "moveZ", "renderUI", "renderPalette", "renderList", "iconBtn", "syncNums", "renderProps",
  "ctrlRow", "kvItem", "numInput", "textInput", "colorInput", "checkInput", "hexOf",
  "exportText", "bindUI", "boot",
];
for (const f of fns) {
  if (new RegExp("function\\s+" + f + "\\s*\\(").test(js)) ok("函数已定义: " + f);
  else bad("函数缺失: " + f);
}

const typeBlock = js.match(/const TYPES = \{([\s\S]*?)\n\};/);
if (!typeBlock) bad("没找到 TYPES 定义");
else {
  const keys = [...typeBlock[1].matchAll(/^\s{2}([a-z_]+)\s*:/gm)].map(x => x[1]);
  ok("控件库共 " + keys.length + " 类: " + keys.join(", "));
  for (const need of ["panel", "departure", "next_station", "reverser"]) {
    if (keys.includes(need)) ok("默认布局用到的类型存在: " + need);
    else bad("默认布局用到但控件库没有: " + need);
  }
  for (const k of keys) {
    if (new RegExp('mk\\("' + k + '"').test(js) || k === "panel") ok("类型被使用: " + k);
  }
}

for (const fn of ["snapPos", "snapSize"]) {
  const body = js.match(new RegExp("function\\s+" + fn + "[\\s\\S]*?\\n\\}"));
  if (body && /axis:\s*"x"/.test(body[0]) && /axis:\s*"y"/.test(body[0])) ok(fn + " 会同时产出横向/纵向对齐线");
  else bad(fn + " 没有产出对齐线");
}

if (/exportText/.test(js) && /rightInset/.test(js) && /bottomInset/.test(js)) {
  ok("导出含 rightInset / bottomInset（右下角锚定）");
} else {
  bad("导出缺少右下角锚定字段");
}

console.log("");
console.log(fail === 0 ? "全部通过（" + (ids.length + fns.length + 5) + " 项）" : "有 " + fail + " 项失败");
process.exit(fail === 0 ? 0 : 1);
