from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.connect_over_cdp('http://127.0.0.1:9223')
    pg = b.contexts[0].pages[0]
    js = """() => {
      const imgs = Array.from(document.querySelectorAll('img')).map(function(img) {
        return { src: (img.src || '').slice(0,120), w: img.naturalWidth, h: img.naturalHeight, complete: img.complete };
      });
      const wrap = document.querySelector('.mirror-wrap, .screen-container, .screen-canvas');
      return JSON.stringify({ imgs: imgs, wrap: wrap ? wrap.outerHTML.slice(0, 500) : 'NO-WRAP', bodyLen: document.body.innerText.length });
    }"""
    print(pg.evaluate(js))
