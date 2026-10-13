// Screenshots of the web UI for the README. Run by capture.sh.
// Usage: node capture.cjs <base url> <out dir> [name=hash ...]
//        (with name=hash arguments: only those, in the phone viewport)
const { chromium } = require("playwright-core");
const fs = require("fs");

const [base, out, ...only] = process.argv.slice(2);
const exe = process.env.CHROMIUM ||
    ["/opt/pw-browsers/chromium-1194/chrome-linux/chrome", "/opt/pw-browsers/chromium"].find(p => fs.existsSync(p));

// [file, hash, text to click or null, color scheme]
const desktop = [
    ["repos", "#repos", null, "light"],
    ["code", "#repos/weather-station", null, "light"],
    ["commits", "#repos/weather-station/commits", null, "light"],
    ["diff", "#repos/weather-station/commit/HEAD", "Show diff", "light"],
    ["pull-request", "#repos/weather-station/pulls/3", null, "light"],
    ["issues", "#repos/weather-station/issues", null, "light"],
    ["collector", "#collector", null, "light"],
    ["code-dark", "#repos/weather-station", null, "dark"],
];
// the page as the app's WebView shows it, under the app's top bar (412 x 760 dp)
const phone = [
    ["phone-repos", "#repos", null, "light"],
    ["phone-dark", "#repos", null, "dark"],
    ["phone-collector", "#collector", null, "light"],
];

async function shoot(browser, list, opts) {
    for (const [name, hash, click, scheme] of list) {
        const page = await browser.newPage({ ...opts, colorScheme: scheme });
        page.on("pageerror", e => console.error("page error:", e.message));
        await page.goto(base + "/");
        await page.evaluate(() => localStorage.setItem("hub.author", "Mika Tanaka"));
        await page.goto(base + "/" + hash);
        await page.waitForLoadState("networkidle");
        await page.waitForTimeout(1500);   // avatars load after the page, one by one; /pc/hub every 2 s
        if (click) {
            await page.getByText(click, { exact: true }).first().click();
            await page.waitForLoadState("networkidle");
            await page.waitForTimeout(500);
        }
        const file = `${out}/${name}.png`;
        await page.screenshot({ path: file });
        console.log(file);
        await page.close();
    }
}

(async () => {
    const browser = await chromium.launch({ executablePath: exe });
    const phoneOpts = { viewport: { width: 412, height: 760 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true };
    if (only.length) {
        await shoot(browser, only.map(a => [a.split("=")[0], a.slice(a.indexOf("=") + 1), null, "light"]), phoneOpts);
        return browser.close();
    }
    await shoot(browser, desktop, { viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
    await shoot(browser, phone, { viewport: { width: 412, height: 760 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
    await browser.close();
})();
