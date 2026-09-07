from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    ctx = p.chromium.launch_persistent_context(
        '/home/vivo/live_photo_agent/data/xhs_dog_live/chrome_data',
        headless=False, args=['--no-sandbox'],
        viewport={'width': 1280, 'height': 900},
    )
    page = ctx.new_page()
    page.goto('https://www.xiaohongshu.com', wait_until='domcontentloaded')
    print('请在浏览器窗口里登录小红书（扫码/手机号），登录完成后按 Enter 继续...')
    input()
    logged = page.evaluate('() => !!window.__INITIAL_STATE__?.user?.userId')
    print('登录状态:', '成功' if logged else '失败')
    ctx.close()
