import urllib.request
import urllib.parse
import json
import re
import zipfile
import os

print("==================================================")
print("     STARTING FULL END-TO-END VERIFICATION       ")
print("==================================================")

# 1. Verify YouTube Music URLs
test_urls = [
    ("YouTube Music URL", "https://music.youtube.com/watch?v=kXYiU_JCYtU&feature=share"),
    ("Standard YouTube URL", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
    ("Short URL", "https://youtu.be/fJ9rUzIMcZQ")
]

def extract_id(url):
    m = re.search(r'[?&]v=([a-zA-Z0-9_-]{11})', url)
    if m: return m.group(1)
    m = re.search(r'(?:youtu\.be/|embed/|shorts/|v/|live/|track/)([a-zA-Z0-9_-]{11})', url)
    if m: return m.group(1)
    return None

for label, u in test_urls:
    vid = extract_id(u)
    assert vid is not None, f"Failed to extract ID from {u}"
    oembed_url = f"https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v={vid}&format=json"
    req = urllib.request.Request(oembed_url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        print(f"[{label}] ID: {vid} | Title: {data.get('title')[:40]}... | Artist: {data.get('author_name')}")
        assert data.get('title'), f"Missing title for {vid}"

print("\n--- TEST 2: Keyword Search Direct Scraping ---")
for q in ["Linkin park numb", "Miyagi"]:
    url = "https://www.youtube.com/results?search_query=" + urllib.parse.quote(q)
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8"
    })
    with urllib.request.urlopen(req, timeout=10) as resp:
        html = resp.read().decode('utf-8', errors='ignore')
        idx = html.find("ytInitialData = {")
        if idx == -1: idx = html.find("ytInitialData={")
        start = html.find("{", idx)
        end = html.find(";</script>", start)
        if end == -1: end = html.find("};", start)
        data = json.loads(html[start:end])
        contents = data['contents']['twoColumnSearchResultsRenderer']['primaryContents']['sectionListRenderer']['contents']
        items = []
        for sec in contents:
            isr = sec.get('itemSectionRenderer', {}).get('contents', [])
            for it in isr:
                if 'videoRenderer' in it:
                    vr = it['videoRenderer']
                    items.append({
                        'id': vr.get('videoId'),
                        'title': vr.get('title', {}).get('runs', [{}])[0].get('text', '')
                    })
        print(f"Search for '{q}': found {len(items)} tracks. Top: {items[0]['title']}")
        assert len(items) > 0, f"No tracks found for '{q}'"

print("\n--- TEST 3: APK Package Integrity ---")
apk_path = "ApexMusic-Player-HuaweiP20Pro.apk"
assert os.path.exists(apk_path), "APK not found!"
with zipfile.ZipFile(apk_path, 'r') as z:
    names = z.namelist()
    has_manifest = "AndroidManifest.xml" in names
    has_classes = any(n.startswith("classes") and n.endswith(".dex") for n in names)
    has_index_html = "assets/web/index.html" in names
    has_index_js = any(n.startswith("assets/web/assets/index") and n.endswith(".js") for n in names)
    print(f"APK verification:")
    print(f" - AndroidManifest: {has_manifest}")
    print(f" - Classes.dex: {has_classes}")
    print(f" - assets/web/index.html: {has_index_html}")
    print(f" - assets/web/assets/index.js: {has_index_js}")
    assert has_manifest and has_classes and has_index_html and has_index_js, "APK missing crucial assets!"

print("\n==================================================")
print("     ALL VERIFICATION CHECKS PASSED 100%!         ")
print("==================================================")
