import urllib.request
import re
import json

def get_video_info(video_id):
    # 1. oEmbed
    oembed_url = f"https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v={video_id}&format=json"
    req = urllib.request.Request(oembed_url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
    data = {}
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode('utf-8'))
    except Exception as e:
        print("oEmbed error:", e)

    # 2. Watch page for duration
    watch_url = f"https://www.youtube.com/watch?v={video_id}"
    req2 = urllib.request.Request(watch_url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Accept-Language": "en-US,en;q=0.9"
    })
    duration_sec = 210
    try:
        with urllib.request.urlopen(req2, timeout=10) as resp:
            html = resp.read().decode('utf-8', errors='ignore')
            m = re.search(r'"approxDurationMs"\s*:\s*"(\d+)"', html)
            if m:
                duration_sec = int(m.group(1)) // 1000
    except Exception as e:
        print("Watch page error:", e)

    m = duration_sec // 60
    s = duration_sec % 60
    dur_str = f"{m}:{s:02d}"

    result = {
        "success": True,
        "id": video_id,
        "title": data.get("title", "YouTube Track"),
        "artist": data.get("author_name", "YouTube"),
        "thumbnail": f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
        "durationSec": duration_sec,
        "durationStr": dur_str,
        "duration": dur_str,
        "url": f"https://www.youtube.com/watch?v={video_id}"
    }
    return result

if __name__ == "__main__":
    for vid in ["kXYiU_JCYtU", "dQw4w9WgXcQ", "fJ9rUzIMcZQ"]:
        info = get_video_info(vid)
        print(f"[{vid}] Title: {info['title']} | Artist: {info['artist']} | Duration: {info['durationStr']}")
