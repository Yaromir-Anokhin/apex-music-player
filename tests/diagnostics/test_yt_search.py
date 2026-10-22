import urllib.request
import json
import re

query = "Lo-Fi Hip Hop Beats"

# Method 1: YouTube Search HTML scraping (what yt-dlp does)
url = "https://www.youtube.com/results?search_query=" + urllib.parse.quote(query)
req = urllib.request.Request(url, headers={
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept-Language': 'ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7'
})

try:
    res = urllib.request.urlopen(req, timeout=10)
    html = res.read().decode('utf-8', errors='ignore')
    print("Fetched HTML length:", len(html))
    
    # Extract ytInitialData
    m = re.search(r'var ytInitialData = ({.*?});</script>', html)
    if not m:
        m = re.search(r'ytInitialData = ({.*?});', html)
    
    if m:
        data = json.loads(m.group(1))
        print("Found ytInitialData! Parsing video items...")
        
        # Traverse contents
        contents = data.get('contents', {}).get('twoColumnSearchResultsRenderer', {}).get('primaryContents', {}).get('sectionListRenderer', {}).get('contents', [])
        found = []
        for section in contents:
            itemSection = section.get('itemSectionRenderer', {}).get('contents', [])
            for item in itemSection:
                if 'videoRenderer' in item:
                    vr = item['videoRenderer']
                    vid = vr.get('videoId')
                    title = vr.get('title', {}).get('runs', [{}])[0].get('text', '')
                    author = vr.get('ownerText', {}).get('runs', [{}])[0].get('text', '')
                    dur = vr.get('lengthText', {}).get('simpleText', '')
                    thumbs = vr.get('thumbnail', {}).get('thumbnails', [])
                    thumb = thumbs[-1].get('url') if thumbs else ''
                    is_live = 'badges' in vr and any('LIVE' in str(b) for b in vr.get('badges', []))
                    if vid and title:
                        found.append({'id': vid, 'title': title, 'author': author, 'duration': dur, 'thumb': thumb, 'isLive': is_live})
        
        print(f"Extracted {len(found)} videos directly from YouTube!")
        for v in found[:5]:
            print(" -", v['title'], f"({v['id']})", "by", v['author'], "dur:", v['duration'], "live:", v['isLive'])
    else:
        print("Could not find ytInitialData in HTML")
except Exception as e:
    print("Error:", e)
