import urllib.request
import json
import base64
import re
import time

def get_mp3_download_url(video_id):
    url = f"https://www.youtube.com/watch?v={video_id}"
    req = urllib.request.Request(f"https://loader.to/ajax/download.php?format=mp3&url={url}", headers={"User-Agent": "Mozilla/5.0"})
    res = urllib.request.urlopen(req, timeout=10)
    data = json.loads(res.read().decode('utf-8'))
    html = base64.b64decode(data['content']).decode('utf-8', errors='ignore')
    
    m = re.search(r'id=[\'"]card-(v2_stream_[a-f0-9]+)[\'"]', html)
    if not m:
        m = re.search(r'(v2_stream_[a-f0-9]+)', html)
    if not m:
        print("Could not find stream ID")
        return None
    
    stream_id = m.group(1)
    print("Stream ID:", stream_id)
    
    for attempt in range(15):
        time.sleep(2)
        prog_req = urllib.request.Request(f"https://loader.to/ajax/progress.php?id={stream_id}", headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(prog_req, timeout=10) as r:
            prog_data = json.loads(r.read().decode('utf-8'))
            print(f"Attempt {attempt}: success={prog_data.get('success')}, progress={prog_data.get('progress')}, text={prog_data.get('text')}")
            if prog_data.get('success') == 1 and prog_data.get('download_url'):
                return prog_data.get('download_url')
    return None

if __name__ == "__main__":
    dl = get_mp3_download_url("kXYiU_JCYtU")
    print("RESULT DOWNLOAD URL:", dl)
