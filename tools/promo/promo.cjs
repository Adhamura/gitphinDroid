// Frames of the promo video, drawn from real pictures: the app's screens (Robolectric), the web
// UI (gitphind + Chromium) and the output of a real `git push`. Run by make_promo.sh.
// Usage: node promo.cjs <promo frames dir> <screenshots dir> <push.txt> <out dir>
const { chromium } = require("playwright-core");
const fs = require("fs");
const path = require("path");

const [framesDir, shotsDir, pushTxt, outDir] = process.argv.slice(2);
const exe = process.env.CHROMIUM ||
    ["/opt/pw-browsers/chromium-1194/chrome-linux/chrome", "/opt/pw-browsers/chromium"].find(p => fs.existsSync(p));
const FPS = 30, LENGTH = 48;
const uri = (dir, n) => "data:image/png;base64," + fs.readFileSync(path.join(dir, n)).toString("base64");
const logo = "data:image/svg+xml;base64," +
    fs.readFileSync(path.join(__dirname, "../../docs/gitphin_logo_orange.svg")).toString("base64");

const IMG = {};
for (const n of ["p-before", "p-after", ...[0, 1, 2, 3, 4, 5, 6, 7].map(i => "p-push-" + i)]) IMG[n] = uri(framesDir, n + ".png");
for (const n of ["main", "qr", "code", "commits", "diff", "pull-request", "issues", "push", "activity", "share", "settings", "widget", "repos"])
    IMG[n] = uri(shotsDir, n + ".png");
const push = fs.readFileSync(pushTxt, "utf8").split("\n").filter(l => l.trim());

const html = `<!doctype html><meta charset="utf-8"><style>
*{box-sizing:border-box}
html,body{margin:0;width:1280px;height:720px;overflow:hidden;background:#16130f;
  font-family:"Inter","DejaVu Sans",system-ui,sans-serif;color:#fff3e0}
.scene{position:absolute;inset:0;opacity:0}
.bg{position:absolute;inset:0;background:radial-gradient(circle at 70% 25%,#3a2a12 0,#16130f 62%)}
.cap{position:absolute;left:56px;top:40px;font-size:34px;font-weight:700;letter-spacing:.2px}
.cap b{color:#ff8200}
.sub{position:absolute;left:58px;top:88px;font-size:19px;color:#d8c2a0}
.phone{position:absolute;padding:10px;border-radius:40px;background:#0b0a09;
  box-shadow:0 24px 70px rgba(0,0,0,.7),0 0 0 2px #3b342c}
.phone .scr{position:relative;border-radius:30px;overflow:hidden}
.phone img{display:block;width:100%}
.phone .scr img+img{position:absolute;left:0;top:0}
.term{position:absolute;left:56px;top:150px;width:690px;height:470px;border-radius:12px;overflow:hidden;
  background:#0d0c0b;border:2px solid #3b342c;box-shadow:0 24px 70px rgba(0,0,0,.6)}
.term .bar,.win .bar{height:30px;display:flex;align-items:center;gap:8px;padding:0 12px;background:#24201b}
.dot{width:11px;height:11px;border-radius:50%;background:#5a5148}
.bar span{margin-left:10px;color:#a8957a;font:13px monospace}
.term pre{margin:0;padding:16px 18px;font:15.5px/1.55 "DejaVu Sans Mono",monospace;white-space:pre-wrap;color:#e8dcc8}
.term .p{color:#ff8200}.term .ok{color:#7bd88f}
.cursor{display:inline-block;width:9px;height:18px;background:#ff8200;vertical-align:-3px}
.win{position:absolute;left:150px;top:132px;width:980px;border-radius:12px;overflow:hidden;
  border:2px solid #ff8200;box-shadow:0 24px 80px rgba(0,0,0,.65);background:#16130f}
.win .view{position:relative;height:612px;overflow:hidden}
.win .view img{position:absolute;left:0;top:0;width:100%;transform-origin:30% 30%}
.chip{position:absolute;padding:8px 16px;border-radius:999px;background:#ff8200;color:#16130f;
  font-weight:700;font-size:19px;box-shadow:0 8px 24px rgba(0,0,0,.5);white-space:nowrap}
.hl{position:absolute;border:3px solid #ff8200;border-radius:8px;box-shadow:0 0 0 4000px rgba(0,0,0,.35)}
.list{position:absolute;font-size:25px;line-height:2.0}
.list div{opacity:.35}
.list div.on{opacity:1}
.list small{color:#d8c2a0;font-size:17px;margin-left:8px}
.label{position:absolute;width:240px;text-align:center;font-size:18px;font-weight:700;color:#ffd9a8}
.center{position:absolute;left:0;right:0;text-align:center}
</style>
<div class="bg"></div>

<div class="scene" id="s1">
  <img id="logo" src="${logo}" style="position:absolute;left:560px;top:150px;width:160px">
  <div class="center" style="top:330px;font-size:64px;font-weight:800;color:#ff8200">Gitphin Droid</div>
  <div class="center" style="top:420px;font-size:30px;font-weight:600">Git hosting in your pocket</div>
  <div class="center" style="top:475px;font-size:20px;color:#d8c2a0">A Git server with a web UI that runs on your Android phone · no cloud, no account</div>
</div>

<div class="scene" id="s2">
  <div class="cap">① <b>git push</b> from your PC</div>
  <div class="sub">over the USB cable · the repo is made on the first push</div>
  <div class="term"><div class="bar"><i class="dot"></i><i class="dot"></i><i class="dot"></i><span>~/weather-station</span></div><pre id="tt"></pre></div>
  <div class="phone" style="left:800px;top:36px;width:350px"><div class="scr" id="ph" style="height:640px"></div></div>
  <div class="chip" id="c2" style="left:56px;top:640px">weather-station is on the phone ✓</div>
</div>

<div class="scene" id="s3">
  <div class="cap">② Browse it in <b>any browser</b>, or in the app</div>
  <div class="win"><div class="bar"><i class="dot"></i><i class="dot"></i><i class="dot"></i><span id="url">http://127.0.0.1:8080/#repos/weather-station</span></div><div class="view" id="wv"></div></div>
  <div class="chip" id="c3" style="left:190px;top:640px"></div>
</div>

<div class="scene" id="s4">
  <div class="cap">③ Connect <b>your way</b></div>
  <div class="sub">Every address in the top bar; the link in use lights up</div>
  <div class="bar-crop" style="position:absolute;left:56px;top:150px;width:640px;height:${Math.round(262 * 640 / 824)}px;overflow:hidden;
    border-radius:12px;border:2px solid #ff8200;box-shadow:0 24px 70px rgba(0,0,0,.6)">
    <img src="${IMG.main}" style="display:block;width:100%"><div class="hl" id="hl"></div></div>
  <div class="list" id="ls" style="left:60px;top:385px">
    <div>📱 On the phone <small>the app itself</small></div>
    <div>🔌 USB cable <small>no password, gitphin-usb</small></div>
    <div>🔌 USB tethering <small>no password, any OS</small></div>
    <div>📶 Wi-Fi <small>opt-in, password, gitphin.local</small></div>
  </div>
  <div class="phone" id="qr" style="left:870px;top:110px;width:260px"><div class="scr"><img src="${IMG.qr}"></div></div>
</div>

<div class="scene" id="s5">
  <div class="cap">④ Made for <b>Android</b></div>
  <div class="sub">Live transfers · activity log · share sheet · settings · widget and tile</div>
  ${["push", "activity", "share", "settings"].map((n, i) =>
    `<div class="phone f5" style="left:${76 + i * 290}px;top:150px;width:250px"><div class="scr"><img src="${IMG[n]}"></div></div>`).join("")}
  ${["Live push, dark theme", "Recent activity", "Share files to a repo", "Wi-Fi, folder, start"].map((n, i) =>
    `<div class="label f5l" style="left:${81 + i * 290}px;top:680px">${n}</div>`).join("")}
</div>

<div class="scene" id="s6">
  <img src="${logo}" style="position:absolute;left:575px;top:110px;width:130px">
  <div class="center" style="top:270px;font-size:56px;font-weight:800;color:#ff8200">Gitphin Droid</div>
  <div class="center" style="top:350px;font-size:26px">Free and open source · MIT · Android 7.0+</div>
  <div class="center" style="top:400px;font-size:22px;color:#d8c2a0">Plain git · offline · your repos stay normal bare repos</div>
  <div class="center" style="top:480px;font-size:30px;font-weight:700;color:#ffd9a8">github.com/Adhamura/gitphinDroid</div>
  <div class="center" style="top:640px;font-size:14px;color:#8c7a62">Demo data. Real app screens and web UI, rendered from the app's code.</div>
</div>`;

// the timeline: render(t) sets everything for time t (seconds), no animation state
const script = ({ IMG, push }) => {
    const $ = id => document.getElementById(id);
    const cl = (x, a, b) => Math.max(a, Math.min(b, x));
    const ease = x => x < 0 ? 0 : x > 1 ? 1 : x * x * (3 - 2 * x);
    const win = (t, a, b, f = 0.5) => ease((t - a) / f) * (1 - ease((t - b + f) / f));   // fade in at a, out at b
    const scenes = [["s1", 0, 3.6], ["s2", 3.4, 14.2], ["s3", 14, 24.2], ["s4", 24, 33.2], ["s5", 33, 42.2], ["s6", 42, 48.5]];

    // phone of scene 2: stacked images, one visible
    const ph = $("ph"), phNames = ["p-before", ...[0, 1, 2, 3, 4, 5, 6, 7].map(i => "p-push-" + i), "p-after"];
    phNames.forEach((n, i) => { const im = document.createElement("img"); im.src = IMG[n]; im.dataset.n = n; ph.append(im); });
    const wv = $("wv"), pages = [["code", "Code, README, Download ZIP", "#repos/weather-station"],
        ["commits", "Commits", "#repos/weather-station/commits"], ["diff", "Diffs", "#repos/weather-station/commit/95b7e04"],
        ["pull-request", "Pull requests with merge", "#repos/weather-station/pulls/3"], ["issues", "Issues, labels, milestones", "#repos/weather-station/issues"]];
    pages.forEach(p => { const im = document.createElement("img"); im.src = IMG[p[0]]; wv.append(im); });

    const cmds = [["git remote add phone http://127.0.0.1:8080/git/weather-station.git", 4.0, 6.0], ["git push phone main", 6.3, 7.1]];
    const outStart = 7.3, outEnd = 10.4;

    window.render = t => {
        for (const [id, a, b] of scenes) $(id).style.opacity = win(t, a, b, 0.4);
        // 1. title
        const k = ease((t - 0.1) / 0.9);
        $("logo").style.transform = `scale(${0.6 + 0.4 * k}) rotate(${(1 - k) * -12}deg)`;
        // 2. terminal + phone
        let txt = "";
        for (const [c, a, b] of cmds) {
            if (t < a) break;
            const n = Math.round(cl((t - a) / (b - a), 0, 1) * c.length);
            txt += `<span class="p">$</span> ${c.slice(0, n)}${t < b + 0.15 && n < c.length + 1 ? '<i class="cursor"></i>' : ""}\n`;
        }
        if (t >= outStart) {
            const n = Math.floor(cl((t - outStart) / (outEnd - outStart), 0, 1) * push.length);
            txt += push.slice(0, n).map(l => l.includes("->") ? `<span class="ok">${l}</span>` : l).join("\n") + "\n";
        }
        if (t >= outEnd + 0.4) txt += '<span class="p">$</span> <i class="cursor"></i>';
        $("tt").innerHTML = txt;
        const step = t < outStart ? 0 : t >= outEnd + 0.5 ? 9 : 1 + Math.min(7, Math.floor((t - outStart) / ((outEnd - outStart) / 8)));
        [...ph.children].forEach((im, i) => im.style.opacity = i === step ? 1 : 0);
        $("c2").style.opacity = ease((t - 11) / 0.4);
        $("c2").style.transform = `translateY(${(1 - ease((t - 11) / 0.4)) * 20}px)`;
        // 3. browser pages, 2 s each with a slow zoom
        const pi = cl(Math.floor((t - 14.2) / 2), 0, pages.length - 1);
        [...wv.children].forEach((im, i) => {
            const a = 14.2 + i * 2;
            im.style.opacity = i === 0 ? (t < a + 2 ? 1 : 1 - ease((t - a - 2) / 0.35)) : ease((t - a) / 0.35) * (i === pages.length - 1 ? 1 : 1 - ease((t - a - 2) / 0.35));
            im.style.transform = `scale(${1 + 0.035 * cl((t - a) / 2.4, 0, 1)})`;
        });
        $("c3").textContent = pages[pi][1];
        $("url").textContent = "http://127.0.0.1:8080/" + pages[pi][2];
        // 4. the address rows, one after another; then the QR
        const rows = [30, 70, 110, 150], ri = cl(Math.floor((t - 24.6) / 1.4), 0, 3);   // row centers in main.png, top bar 262 px
        const hl = $("hl");
        hl.style.top = `${(rows[ri] - 19) / 262 * 100}%`;
        hl.style.height = `${38 / 262 * 100}%`;
        hl.style.left = "1.5%"; hl.style.width = "79%";
        hl.style.opacity = t > 24.4 && t < 30.5 ? 1 : 0;
        [...$("ls").children].forEach((d, i) => d.className = (t < 30.5 ? i === ri : true) ? "on" : "");
        const q = ease((t - 30.2) / 0.6);
        $("qr").style.opacity = q;
        $("qr").style.transform = `translateX(${(1 - q) * 60}px)`;
        // 5. four screens slide in
        document.querySelectorAll(".f5,.f5l").forEach((e, i) => {
            const a = 33.4 + (i % 4) * 0.35, v = ease((t - a) / 0.6);
            e.style.opacity = v;
            e.style.transform = `translateY(${(1 - v) * 50}px)`;
        });
    };
};

(async () => {
    fs.mkdirSync(outDir, { recursive: true });
    const browser = await chromium.launch({ executablePath: exe });
    const page = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
    await page.setContent(html);
    await page.evaluate(script, { IMG, push });
    await page.evaluate(() => Promise.all([...document.images].map(i => i.decode().catch(() => {}))));
    const n = LENGTH * FPS;
    for (let f = 0; f < n; f++) {
        await page.evaluate(t => window.render(t), f / FPS);
        await page.screenshot({ path: path.join(outDir, String(f).padStart(5, "0") + ".png") });
        if (f % 150 === 0) console.log(`frame ${f}/${n}`);
    }
    await browser.close();
})();
