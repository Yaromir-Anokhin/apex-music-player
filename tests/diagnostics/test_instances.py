import urllib.request
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

videoId = "n61ULEU7CO0" # Best of lofi hip hop
instances = [
    "https://invidious.nerdvpn.de",
    "https://inv.nadeko.net",
    "https://invidious.f5.si",
    "https://invidious.jing.rocks",
    "https://pipedapi.kavin.rocks",
    "https://api.piped.private.coffee"
]

for inst in instances:
    try:
        url = f"{inst}/api/v1/videos/{videoId}"
        if "piped" in inst:
            url = f"{inst}/streams/{videoId}"
        print(f"Testing {url} ...")
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        res = urllib.request.urlopen(req, timeout=5, context=ctx)
        data = json.loads(res.read())
        print(f"-> SUCCESS from {inst}! Keys: {list(data.keys())[:5]}")
        # check audio streams
        if "piped" in inst:
            audio_streams = data.get('audioStreams', [])
            print(f"   Piped Audio streams: {len(audio_streams)}")
            if audio_streams:
                print(f"   Sample url: {audio_streams[0].get('url', '')[:60]}...")
        else:
            adaptive = data.get('adaptiveFormats', [])
            audio_formats = [f for f in adaptive if f.get('type', '').startswith('audio/')]
            print(f"   Invidious Audio streams: {len(audio_formats)}")
            if audio_formats:
                print(f"   Sample url: {audio_formats[0].get('url', '')[:60]}...")
    except Exception as e:
        print(f"-> FAILED {inst}: {e}")
