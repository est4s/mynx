// Sketch: strokes are lists of points in 0..1 of the pad's size, so the
// drawing survives turning the phone. They're kept in localStorage.
"use strict";

const KEY = "sketch.strokes";
const COLORS = ["#05d9e8", "#ff2a6d", "#f9f871", "#7cff4f", "#e0e0ff"];
const pad = document.getElementById("pad");
const ctx = pad.getContext("2d");
let strokes = load();
let color = COLORS[0];
let current = null;

function load() {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY));
    return Array.isArray(saved) ? saved : [];
  } catch (e) {
    console.warn("couldn't read the saved drawing: " + e);
    return [];
  }
}

function save() {
  try {
    localStorage.setItem(KEY, JSON.stringify(strokes));
  } catch (e) {
    console.error("couldn't save the drawing: " + e);
  }
}

function resize() {
  const ratio = window.devicePixelRatio || 1;
  pad.width = pad.clientWidth * ratio;
  pad.height = pad.clientHeight * ratio;
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  redraw();
}

function draw(stroke) {
  const w = pad.clientWidth, h = pad.clientHeight;
  ctx.strokeStyle = stroke.color;
  ctx.lineWidth = 4;
  ctx.lineCap = ctx.lineJoin = "round";
  ctx.beginPath();
  stroke.points.forEach(([x, y], i) => (i ? ctx.lineTo(x * w, y * h) : ctx.moveTo(x * w, y * h)));
  if (stroke.points.length === 1) ctx.lineTo(stroke.points[0][0] * w + 0.1, stroke.points[0][1] * h);
  ctx.stroke();
}

function redraw() {
  ctx.clearRect(0, 0, pad.clientWidth, pad.clientHeight);
  strokes.forEach(draw);
}

function point(event) {
  const r = pad.getBoundingClientRect();
  return [(event.clientX - r.left) / r.width, (event.clientY - r.top) / r.height];
}

pad.addEventListener("pointerdown", (event) => {
  pad.setPointerCapture(event.pointerId);
  current = { color, points: [point(event)] };
  strokes.push(current);
  draw(current);
});
pad.addEventListener("pointermove", (event) => {
  if (!current) return;
  current.points.push(point(event));
  draw({ color: current.color, points: current.points.slice(-2) });  // just the new bit
});
const end = () => {
  if (current) save();
  current = null;
};
pad.addEventListener("pointerup", end);
pad.addEventListener("pointercancel", end);

document.getElementById("undo").onclick = () => { strokes.pop(); save(); redraw(); };
document.getElementById("clear").onclick = () => { strokes = []; save(); redraw(); };

const swatches = document.getElementById("colors");
COLORS.forEach((c) => {
  const b = document.createElement("button");
  b.className = "swatch" + (c === color ? " on" : "");
  b.style.background = c;
  b.setAttribute("aria-label", "Colour " + c);
  b.onclick = () => {
    color = c;
    swatches.querySelectorAll(".swatch").forEach((s) => s.classList.toggle("on", s === b));
  };
  swatches.appendChild(b);
});

window.addEventListener("resize", resize);
resize();
