from playwright.sync_api import sync_playwright
import time
import urllib.parse

def main():
    UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0 Safari/537.36'
    with sync_playwright() as p:
        ctx = p.chromium.launch_persistent_context(
            '/home/vivo/live_photo_agent/data/xhs_dog_live/chrome_data',
            headless=False,
            args=['--no-sandbox'],
            executable_path='/opt/google/chrome/google-chrome',
            user_agent=UA,
            viewport={'width': 1280, 'height': 900}
        )
        page = ctx.new_page()
        page.goto('https://www.xiaohongshu.com', wait_until='load')
        print('请登录小红书，完成后按 Enter...')
        input()
        # 验证登录
        keyword = "狗 live photo"
        url = f'https://www.xiaohongshu.com/search_result?keyword={urllib.parse.quote(keyword)}&source=web_search_result_notes'
        page.goto(url, wait_until='load')
        time.sleep(5)
        feeds = page.evaluate('''
            () => {
                var f = window.__INITIAL_STATE__?.search?.feeds?._rawValue;
                return f ? f.length : 0;
            }
        ''')
        print(f'登录验证: {feeds} 条搜索结果')
        print('保持浏览器打开，不要关...')
        input('按 Enter 关闭')
        ctx.close()

if __name__ == "__main__":
    main()


