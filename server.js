import http from 'http';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { spawn } from 'child_process';
import qrcodeTerminal from 'qrcode-terminal';
import QRCode from 'qrcode';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 8080;
const WEB_DIR = path.join(__dirname, 'app', 'src', 'main', 'assets', 'web');
const DOWNLOADS_DIR = path.join(__dirname, 'downloads');
const APK_PATH = path.join(__dirname, 'ApexMusic-Player-HuaweiP20Pro.apk');
const APK_REDMI_PATH = path.join(__dirname, 'ApexMusic-Player-RedmiAndroid15.apk');
const QR_IMG_PATH = path.join(__dirname, 'qr_code.png');
const PYTHON_PATH = 'C:\\Users\\Yarom\\AppData\\Local\\Programs\\Python\\Python311\\python.exe';

if (!fs.existsSync(DOWNLOADS_DIR)) {
  fs.mkdirSync(DOWNLOADS_DIR, { recursive: true });
}

function getLocalIp() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        if (net.address.startsWith('192.168.') || net.address.startsWith('10.') || net.address.startsWith('172.16.')) {
          return net.address;
        }
      }
    }
  }
  return 'localhost';
}

const localIp = getLocalIp();
const downloadUrl = `http://${localIp}:${PORT}/ApexMusic-Player-HuaweiP20Pro.apk`;
const webUrl = `http://${localIp}:${PORT}/`;

// Generate PNG QR Code
await QRCode.toFile(QR_IMG_PATH, downloadUrl, {
  width: 400,
  margin: 2,
  color: {
    dark: '#00F2FE',
    light: '#07070A'
  }
});

const MIME_TYPES = {
  '.html': 'text/html; charset=UTF-8',
  '.js': 'application/javascript; charset=UTF-8',
  '.css': 'text/css; charset=UTF-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.apk': 'application/vnd.android.package-archive',
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.flac': 'audio/flac',
  '.ogg': 'audio/ogg',
  '.m4a': 'audio/mp4'
};

const FFMPEG_PATH = 'C:\\Users\\Yarom\\AppData\\Local\\Microsoft\\WinGet\\Links\\ffmpeg.exe';

// YouTube extraction & MP3 conversion via yt-dlp
function extractAndConvertYt(query, clientHost) {
  return new Promise((resolve, reject) => {
    let target = query.trim();
    const host = clientHost || `${localIp}:${PORT}`;

    // Clean YouTube Music, YouTube standard URLs, Shorts, and mobile links
    if (target.includes('music.youtube.com') || target.includes('youtube.com') || target.includes('youtu.be')) {
      const match = target.match(/(?:youtu\.be\/|v\/|u\/\w\/|embed\/|watch\?v=|&v=|shorts\/)([\w-]{11})/);
      if (match && match[1]) {
        target = `https://www.youtube.com/watch?v=${match[1]}`;
      }
    } else if (/^[a-zA-Z0-9_-]{11}$/.test(target)) {
      target = `https://www.youtube.com/watch?v=${target}`;
    } else if (!target.startsWith('http://') && !target.startsWith('https://')) {
      target = `ytsearch1:${target}`;
    }

    console.log(`[yt-dlp] Запуск поиска/информации: ${target}`);

    // Step 1: Get metadata via --dump-json
    const infoArgs = ['-m', 'yt_dlp', '-j', '--no-playlist', '--no-warnings', target];
    const infoProc = spawn(PYTHON_PATH, infoArgs);

    let infoStdout = '';
    let infoStderr = '';

    infoProc.stdout.on('data', (d) => { infoStdout += d.toString(); });
    infoProc.stderr.on('data', (d) => { infoStderr += d.toString(); });

    infoProc.on('close', (code) => {
      if (code !== 0 || !infoStdout.trim()) {
        return reject(new Error(`yt-dlp info failed: ${infoStderr || 'Empty output'}`));
      }

      const lines = infoStdout.trim().split('\n');
      let meta = null;
      for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
          try {
            meta = JSON.parse(trimmed);
            if (meta.id) break;
          } catch (e) {}
        }
      }

      if (!meta) {
        return reject(new Error(`Не удалось разобрать ответ YouTube: ${infoStderr || 'JSON not found'}`));
      }

      const id = meta.id;
      const title = meta.title || 'YouTube Track';
      const artist = meta.uploader || meta.channel || 'YouTube Artist';
      const duration = meta.duration
        ? `${Math.floor(meta.duration / 60)}:${(meta.duration % 60).toString().padStart(2, '0')}`
        : '3:30';
      const thumbnail = meta.thumbnail || `https://i.ytimg.com/vi/${id}/hqdefault.jpg`;
      const mp3FileName = `${id}.mp3`;
      const mp3FilePath = path.join(DOWNLOADS_DIR, mp3FileName);
      const cleanTitle = (artist ? artist + ' - ' : '') + title;
      const safeQueryName = encodeURIComponent(cleanTitle.replace(/[\\/:*?"<>|]/g, '_') + '.mp3');

      // If MP3 already cached locally, return immediately!
      if (fs.existsSync(mp3FilePath) && fs.statSync(mp3FilePath).size > 50000) {
        console.log(`[yt-dlp] Файл уже в кэше: ${mp3FileName}`);
        return resolve({
          id,
          title,
          artist,
          duration,
          thumbnail,
          audioUrl: `http://${host}/downloads/${mp3FileName}?name=${safeQueryName}`,
          downloadUrl: `http://${host}/downloads/${mp3FileName}?name=${safeQueryName}`
        });
      }

      // Step 2: Download and convert to true MP3
      console.log(`[yt-dlp] Скачивание и конвертация в MP3: ${title} (${id})`);
      const dlArgs = [
        '-m', 'yt_dlp',
        '-x', '--audio-format', 'mp3',
        '--audio-quality', '320k',
        '--ffmpeg-location', FFMPEG_PATH,
        '-o', path.join(DOWNLOADS_DIR, '%(id)s.%(ext)s'),
        '--no-playlist',
        '--no-warnings',
        `https://www.youtube.com/watch?v=${id}`
      ];

      const dlProc = spawn(PYTHON_PATH, dlArgs);
      let dlStderr = '';

      dlProc.stderr.on('data', (d) => { dlStderr += d.toString(); });

      dlProc.on('close', (dlCode) => {
        if (dlCode !== 0 || !fs.existsSync(mp3FilePath)) {
          return reject(new Error(`yt-dlp MP3 conversion failed (code ${dlCode}): ${dlStderr}`));
        }

        console.log(`[yt-dlp] MP3 успешно создан: ${mp3FileName} (${(fs.statSync(mp3FilePath).size / 1024 / 1024).toFixed(2)} MB)`);
        resolve({
          id,
          title,
          artist,
          duration,
          thumbnail,
          audioUrl: `http://${host}/downloads/${mp3FileName}?name=${safeQueryName}`,
          downloadUrl: `http://${host}/downloads/${mp3FileName}?name=${safeQueryName}`
        });
      });
    });
  });
}

const server = http.createServer(async (req, res) => {
  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', '*');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const parsedUrl = new URL(req.url, `http://${req.headers.host}`);
  let pathname = decodeURI(parsedUrl.pathname);

  // Ping endpoint
  if (pathname === '/api/ping') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', ip: localIp, port: PORT }));
    return;
  }

  // YouTube Extraction API Endpoint
  if (pathname === '/api/youtube') {
    const q = parsedUrl.searchParams.get('q');
    if (!q) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Missing query parameter q' }));
      return;
    }
    console.log(`[YouTube API] Запрос от клиента ${req.socket.remoteAddress} (host: ${req.headers.host}): "${q}"`);
    try {
      const data = await extractAndConvertYt(q, req.headers.host);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(data));
      console.log(`[YouTube API] Ответ отправлен клиенту: ${data.title}`);
    } catch (err) {
      console.warn(`[YouTube API] Ошибка: ${err.message}`);
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: err.message }));
    }
    return;
  }

  // Serve Downloads folder with Range Requests (for MP3 seeking)
  if (pathname.startsWith('/downloads/')) {
    const fileName = path.basename(pathname);
    const filePath = path.join(DOWNLOADS_DIR, fileName);

    if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
      const stat = fs.statSync(filePath);
      const total = stat.size;
      const range = req.headers.range;

      const customName = parsedUrl.searchParams.get('name') || fileName;
      const safeName = customName.replace(/[\\/:*?"<>|]/g, '_');
      const asciiName = safeName.replace(/[^\x20-\x7E]/g, '_');
      const dispHeader = `attachment; filename="${asciiName}"; filename*=UTF-8''${encodeURIComponent(safeName)}`;

      if (range) {
        const parts = range.replace(/bytes=/, "").split("-");
        const partialstart = parts[0];
        const partialend = parts[1];

        const start = parseInt(partialstart, 10);
        const end = partialend ? parseInt(partialend, 10) : total - 1;
        const chunksize = (end - start) + 1;

        res.writeHead(206, {
          'Content-Range': 'bytes ' + start + '-' + end + '/' + total,
          'Accept-Ranges': 'bytes',
          'Content-Length': chunksize,
          'Content-Type': 'audio/mpeg',
          'Content-Disposition': dispHeader
        });

        const stream = fs.createReadStream(filePath, { start: start, end: end });
        stream.pipe(res);
      } else {
        res.writeHead(200, {
          'Content-Length': total,
          'Accept-Ranges': 'bytes',
          'Content-Type': 'audio/mpeg',
          'Content-Disposition': dispHeader
        });
        fs.createReadStream(filePath).pipe(res);
      }
      return;
    } else {
      res.writeHead(404);
      res.end('File not found');
      return;
    }
  }

  // Serve Redmi / Android 15 APK
  if (pathname === '/ApexMusic-Player-RedmiAndroid15.apk' || pathname === '/redmi') {
    if (fs.existsSync(APK_REDMI_PATH)) {
      const stat = fs.statSync(APK_REDMI_PATH);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="ApexMusic-Player-RedmiAndroid15.apk"',
        'Content-Length': stat.size
      });
      fs.createReadStream(APK_REDMI_PATH).pipe(res);
      console.log(`[APK] Загрузка Redmi APK на устройство: ${req.socket.remoteAddress}`);
    } else {
      res.writeHead(404);
      res.end('Redmi APK file not found');
    }
    return;
  }

  // Serve APK directly
  if (pathname === '/ApexMusic-Player-HuaweiP20Pro.apk' || pathname === '/Apex3D-Player-HuaweiP20Pro.apk' || pathname === '/download') {
    if (fs.existsSync(APK_PATH)) {
      const stat = fs.statSync(APK_PATH);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="ApexMusic-Player-HuaweiP20Pro.apk"',
        'Content-Length': stat.size
      });
      fs.createReadStream(APK_PATH).pipe(res);
      console.log(`[APK] Загрузка Music APK на устройство: ${req.socket.remoteAddress}`);
      return;
    } else {
      res.writeHead(404);
      res.end('APK file not found');
      return;
    }
  }

  // Serve QR Image
  if (pathname === '/qr' || pathname === '/qr_code.png') {
    if (fs.existsSync(QR_IMG_PATH)) {
      res.writeHead(200, { 'Content-Type': 'image/png' });
      fs.createReadStream(QR_IMG_PATH).pipe(res);
      return;
    }
  }

  // Serve Web Player
  if (pathname === '/') pathname = '/index.html';
  const filePath = path.join(WEB_DIR, pathname);

  if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    if (ext === '.html') {
      let content = fs.readFileSync(filePath, 'utf8');
      const banner = `
        <div style="position:fixed;top:10px;right:14px;z-index:99999;pointer-events:auto;display:flex;flex-direction:column;gap:6px;align-items:flex-end;">
          <a href="/ApexMusic-Player-HuaweiP20Pro.apk" download style="display:inline-flex;align-items:center;gap:6px;background:linear-gradient(135deg,#ff0033,#7f00ff);color:#fff;padding:8px 16px;border-radius:20px;font-size:12px;font-weight:700;text-decoration:none;box-shadow:0 4px 15px rgba(255,0,51,0.4);">
            📥 APK для Huawei P20 Pro (Android 10)
          </a>
          <a href="/ApexMusic-Player-RedmiAndroid15.apk" download style="display:inline-flex;align-items:center;gap:6px;background:linear-gradient(135deg,#ff6a00,#00c6ff);color:#fff;padding:8px 16px;border-radius:20px;font-size:12px;font-weight:700;text-decoration:none;box-shadow:0 4px 15px rgba(255,106,0,0.4);">
            📥 APK для Redmi (Android 15)
          </a>
        </div>
      `;
      content = content.replace('</body>', `${banner}</body>`);
      res.writeHead(200, { 'Content-Type': contentType });
      res.end(content);
      return;
    }

    res.writeHead(200, { 'Content-Type': contentType });
    fs.createReadStream(filePath).pipe(res);
  } else {
    res.writeHead(404);
    res.end('Not Found');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('\n' + '='.repeat(64));
  console.log('  🎵 APEX MUSIC & YOUTUBE PLAYER — ДЛЯ ANDROID 10 (HUAWEI P20 PRO)');
  console.log('='.repeat(64));
  console.log(`\n📲 Наведите камеру Huawei P20 Pro на QR-код для установки:\n`);

  qrcodeTerminal.generate(downloadUrl, { small: true });

  console.log(`\n🔗 Прямая ссылка на скачивание APK:`);
  console.log(`   Huawei P20 Pro: ${downloadUrl}`);
  console.log(`   Redmi Android 15: http://${localIp}:${PORT}/ApexMusic-Player-RedmiAndroid15.apk`);
  console.log(`\n🌐 Веб-версия плеера (в браузере телефона без установки):`);
  console.log(`   ${webUrl}`);
  console.log(`\n📁 Готовый файл APK:`);
  console.log(`   ${APK_PATH}`);
  console.log('='.repeat(64) + '\n');
});
