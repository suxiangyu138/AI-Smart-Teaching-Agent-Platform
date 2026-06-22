"""
Playwright 无头浏览器渲染器 — 爬取 JS 动态渲染的数学教育网站
安装: pip install playwright && playwright install chromium
启动: python playwright_crawler.py
API:  POST http://127.0.0.1:8002/render  {"url":"...", "waitSelector":"..."}
"""
from fastapi import FastAPI
from pydantic import BaseModel
from playwright.sync_api import sync_playwright
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("playwright_crawler")

app = FastAPI(title="Playwright HTML Renderer")


class RenderRequest(BaseModel):
    url: str
    wait_selector: str | None = None  # 等待特定元素加载
    scroll_times: int = 0              # 懒加载滚动次数
    timeout_ms: int = 20000


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/render")
def render_page(req: RenderRequest):
    """渲染一个URL，返回完整DOM HTML"""
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True, args=[
                "--no-sandbox", "--disable-gpu",
                "--disable-blink-features=AutomationControlled"
            ])
            context = browser.new_context(
                user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                           "AppleWebKit/537.36 (KHTML, like Gecko) "
                           "Chrome/131.0.0.0 Safari/537.36",
                viewport={"width": 1920, "height": 1080},
                locale="zh-CN"
            )
            page = context.new_page()

            # 隐藏自动化特征
            page.add_init_script("""
                Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]});
                Object.defineProperty(navigator, 'languages', {get: () => ['zh-CN','zh','en']});
            """)

            logger.info(f"Rendering: {req.url}")
            page.goto(req.url, wait_until="networkidle", timeout=req.timeout_ms)

            # 等待特定选择器
            if req.wait_selector:
                try:
                    page.wait_for_selector(req.wait_selector, timeout=10000)
                except Exception:
                    pass

            # 懒加载滚动
            for _ in range(req.scroll_times):
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                page.wait_for_timeout(1500)

            page.wait_for_timeout(1000)
            html = page.content()
            title = page.title()
            browser.close()

            return {
                "success": True,
                "url": req.url,
                "title": title,
                "html": html,
                "length": len(html)
            }
    except Exception as e:
        logger.error(f"Render failed: {req.url} - {e}")
        return {"success": False, "url": req.url, "error": str(e)}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("playwright_crawler:app", host="127.0.0.1", port=8002, log_level="info")
