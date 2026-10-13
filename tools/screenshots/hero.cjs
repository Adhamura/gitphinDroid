// The picture at the top of the README: the web UI in a PC browser and the app on a phone.
// Usage: node hero.cjs <screenshots dir>   (reads code.png and main.png, writes hero.png)
const { chromium } = require("playwright-core");
const fs = require("fs");
const path = require("path");

const dir = path.resolve(process.argv[2]);
const exe = process.env.CHROMIUM ||
    ["/opt/pw-browsers/chromium-1194/chrome-linux/chrome", "/opt/pw-browsers/chromium"].find(p => fs.existsSync(p));
const img = n => "data:image/png;base64," + fs.readFileSync(path.join(dir, n)).toString("base64");
const phone = fs.existsSync(path.join(dir, "main.png"));

const html = `<!doctype html><meta charset="utf-8"><style>
html,body{margin:0;background:transparent}
.stage{position:relative;width:1600px;height:900px;overflow:hidden;border-radius:24px;
  background:radial-gradient(circle at 75% 30%,#3a2a12 0,#16130f 60%)}
.win{position:absolute;left:48px;top:84px;width:1050px;border-radius:12px;overflow:hidden;
  box-shadow:0 30px 80px rgba(0,0,0,.6);border:2px solid #ff8200;background:#16130f}
.bar{height:34px;display:flex;align-items:center;gap:8px;padding:0 14px;background:#24201b}
.dot{width:12px;height:12px;border-radius:50%;background:#5a5148}
.url{margin-left:16px;flex:1;height:22px;border-radius:6px;background:#16130f;color:#ffd9a8;
  font:14px/22px monospace;padding:0 10px}
.win img{display:block;width:100%}
.phone{position:absolute;right:70px;top:40px;width:372px;padding:14px;border-radius:52px;
  background:#0b0a09;box-shadow:0 30px 90px rgba(0,0,0,.75),0 0 0 2px #3b342c}
.phone img{display:block;width:100%;border-radius:38px}
</style><div class="stage">
<div class="win"><div class="bar"><i class="dot"></i><i class="dot"></i><i class="dot"></i>
<div class="url">http://127.0.0.1:8080/#repos/weather-station</div></div><img src="${img("code.png")}"></div>
${phone ? `<div class="phone"><img src="${img("main.png")}"></div>` : ""}
</div>`;

(async () => {
    const browser = await chromium.launch({ executablePath: exe });
    const page = await browser.newPage({ viewport: { width: 1600, height: 900 }, deviceScaleFactor: 1 });
    await page.setContent(html);
    await page.locator(".stage").screenshot({ path: path.join(dir, "hero.png"), omitBackground: true });
    await browser.close();
    console.log(path.join(dir, "hero.png"));
})();
