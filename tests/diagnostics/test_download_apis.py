import urllib.request
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

videoId = "kXYiU_JCYtU"

# Test 1: y2mate / y2down / yt1s / loader.to
apis = [
    # Y2Mate style
    ("https://api.vkrdown.com/api/v1/download", {"url": f"https://www.youtube.com/watch?v={videoId}"}),
    # Cobalt v10 / v7
    ("https://co.wuk.sh/api/json", {"url": f"https://www.youtube.com/watch?v={videoId}", "isAudioOnly": True}),
]

for endpoint, payload in apis:
    try:
        data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(endpoint, data=data, headers={
            'Content-Type': 'application/json',
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'
        })
        res = urllib.request.urlopen(req, timeout=8, context=ctx)
        print(f"Success from {endpoint}:", json.loads(res.read()))
    except Exception as e:
        print(f"Failed {endpoint}:", e)
