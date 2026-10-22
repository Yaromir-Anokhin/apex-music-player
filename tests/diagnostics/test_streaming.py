import urllib.request
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

# Test cobalt API
try:
    req = urllib.request.Request('https://api.cobalt.tools', data=json.dumps({
        'url': 'https://www.youtube.com/watch?v=kXYiU_JCYtU',
        'downloadMode': 'audio'
    }).encode('utf-8'), headers={
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        'User-Agent': 'Mozilla/5.0'
    })
    res = urllib.request.urlopen(req, timeout=10, context=ctx)
    data = json.loads(res.read())
    print("Cobalt response:", data)
except Exception as e:
    print("Cobalt error:", e)

# Test invidious instances from uptime list
invidious_public = [
    "https://inv.tux.pizza",
    "https://invidious.private.coffee",
    "https://vid.priv.au",
    "https://invidious.projectsegfau.lt",
    "https://invidious.perennialte.ch"
]

for inst in invidious_public:
    try:
        url = f"{inst}/api/v1/videos/kXYiU_JCYtU"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        res = urllib.request.urlopen(req, timeout=5, context=ctx)
        data = json.loads(res.read())
        formats = [f for f in data.get('adaptiveFormats', []) if f.get('type', '').startswith('audio/')]
        print(f"Invidious {inst} SUCCESS! Found {len(formats)} audio formats. First: {formats[0].get('url','')[:50]}...")
        break
    except Exception as e:
        print(f"Invidious {inst} failed: {e}")
