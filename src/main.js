import './style.css';

// Native Android YouTube Extraction Callbacks & Promise Wrappers
const nativeCallbacks = new Map();

window.onNativeYouTubeResult = (callbackId, jsonResultStr) => {
  const cb = nativeCallbacks.get(callbackId);
  if (cb) {
    nativeCallbacks.delete(callbackId);
    try {
      const data = typeof jsonResultStr === 'string' ? JSON.parse(jsonResultStr) : jsonResultStr;
      cb.resolve(data);
    } catch (e) {
      cb.reject(e);
    }
  }
};

window.onNativeYouTubeStreamResult = (callbackId, jsonResultStr) => {
  const cb = nativeCallbacks.get(callbackId);
  if (cb) {
    nativeCallbacks.delete(callbackId);
    try {
      const data = typeof jsonResultStr === 'string' ? JSON.parse(jsonResultStr) : jsonResultStr;
      cb.resolve(data);
    } catch (e) {
      cb.reject(e);
    }
  }
};

window.onNativeMp3DownloadResult = (callbackId, jsonResultStr) => {
  const cb = nativeCallbacks.get(callbackId);
  if (cb) {
    nativeCallbacks.delete(callbackId);
    try {
      const data = typeof jsonResultStr === 'string' ? JSON.parse(jsonResultStr) : jsonResultStr;
      cb.resolve(data);
    } catch (e) {
      cb.reject(e);
    }
  }
};

function callNativeDownloadMp3(videoId, title, artist) {
  return new Promise((resolve, reject) => {
    const callbackId = 'dl_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7);
    nativeCallbacks.set(callbackId, { resolve, reject });
    try {
      window.AndroidBridge.downloadMp3Async(videoId, title, artist, callbackId);
    } catch (err) {
      nativeCallbacks.delete(callbackId);
      reject(err);
      return;
    }
    setTimeout(() => {
      if (nativeCallbacks.has(callbackId)) {
        nativeCallbacks.delete(callbackId);
        reject(new Error('Время ожидания конвертации MP3 истекло'));
      }
    }, 90000);
  });
}

function callNativeSearch(query) {
  return new Promise((resolve, reject) => {
    const callbackId = 'search_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7);
    nativeCallbacks.set(callbackId, { resolve, reject });
    try {
      window.AndroidBridge.searchYouTube(query, callbackId);
    } catch (err) {
      nativeCallbacks.delete(callbackId);
      reject(err);
      return;
    }
    setTimeout(() => {
      if (nativeCallbacks.has(callbackId)) {
        nativeCallbacks.delete(callbackId);
        reject(new Error('Время ожидания поиска YouTube истекло'));
      }
    }, 30000);
  });
}

function callNativeGetAudioStream(url) {
  return new Promise((resolve, reject) => {
    const callbackId = 'stream_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7);
    nativeCallbacks.set(callbackId, { resolve, reject });
    try {
      window.AndroidBridge.getAudioStream(url, callbackId);
    } catch (err) {
      nativeCallbacks.delete(callbackId);
      reject(err);
      return;
    }
    setTimeout(() => {
      if (nativeCallbacks.has(callbackId)) {
        nativeCallbacks.delete(callbackId);
        reject(new Error('Время ожидания аудиопотока истекло'));
      }
    }, 30000);
  });
}

class ApexMusicPlayer {
  constructor() {
    this.audio = new Audio();
    this.audioContext = null;
    this.sourceNode = null;
    this.analyser = null;
    this.eqFilters = [];
    this.isAudioContextInit = false;

    // YouTube Iframe Player
    this.isYtReady = false;
    this.ytPlayer = null;
    this.pendingYtVideoId = null;
    this.ytProgressInterval = null;

    // Visualizer
    this.canvas = document.getElementById('visualizer-canvas');
    this.ctx = this.canvas ? this.canvas.getContext('2d') : null;
    this.visMode = 'bars';

    // Playback state
    this.playlist = [];
    this.currentIndex = 0;
    this.isPlaying = false;
    this.isShuffle = false;
    this.repeatMode = 0;
    this.favorites = new Set();
    this.customPlaylists = [];
    this.activePlaylistFilter = 'all';
    this.searchQuery = '';
    this.sortMode = 'default';
    this.playbackRate = 1.0;
    this.selectedTrackForMenu = null;
    this.activeEmoji = '🎵';
    this.pendingPlayTitle = null;

    // YouTube Downloader state
    this.currentYtResult = null;
    this.ytHistory = [];

    // Sleep Timer
    this.sleepTimerEnd = null;
    this.sleepTimerInterval = null;

    // EQ Presets
    this.presets = {
      bass: [8, 5, 1, 2, 4],
      electronic: [6, 4, -1, 3, 6],
      rock: [5, 3, -1, 4, 5],
      pop: [2, 3, 4, 3, 2],
      vocal: [-2, 1, 6, 4, 1],
      flat: [0, 0, 0, 0, 0]
    };

    this.initCanvas();
    this.initYouTubePlayer();
    this.initEventListeners();
    this.initAndroidCallbacks();
    this.loadFavoritesFromStorage();
    this.loadYtHistoryFromStorage();

    // Global Android hooks & Headset/Notification callbacks
    window.apexPlayer = this;
    window.onNativePlay = () => this.playAudio();
    window.onNativePause = () => this.pauseAudio();
    window.onNativePlayPause = () => this.togglePlayPause();
    window.onNativeNext = () => this.playNext();
    window.onNativePrev = () => this.playPrev();
    window.onNativeSeek = (sec) => this.seekAudio(sec);
    window.handleAndroidAudioUri = (uri) => this.handleExternalAudioUri(uri);
    window.handleBackButton = () => this.handleBackAction();
    window.rescanLocalMusic = () => this.scanLocalMusic();

    // Restore full state after app was unloaded from RAM / phone rebooted
    const restored = this.loadStateFromStorage();
    if (!restored) {
      this.initDemoTracks();
    }

    this.updateOnlineStatus();
    this.scanLocalMusic();

    this.renderVisualizer();
  }

  initAndroidCallbacks() {
    window.onAndroidTrackDownloaded = async (trackMeta) => {
      try {
        if (typeof trackMeta === 'string') {
          trackMeta = JSON.parse(trackMeta);
        }
      } catch (e) {}

      if (!trackMeta || !trackMeta.title) return;
      console.log('Android track downloaded:', trackMeta);

      let playUrl = trackMeta.url;
      try {
        playUrl = await this.prepareTrackUrl(trackMeta);
      } catch (e) {
        console.warn('prepareTrackUrl failed:', e);
      }

      const trackObj = {
        id: 'downloaded-' + Date.now(),
        title: trackMeta.title,
        artist: trackMeta.artist || 'YouTube Music',
        format: trackMeta.format || 'MP3',
        url: (trackMeta.url && !trackMeta.url.startsWith('blob:')) ? trackMeta.url : ('local-audio://' + (trackMeta.path || '')),
        blobUrl: (playUrl && playUrl.startsWith('blob:')) ? playUrl : null,
        path: trackMeta.path,
        cover: trackMeta.cover || null,
        isLocal: true,
        isDownloaded: true
      };

      // Remove demo tracks if present
      this.playlist = this.playlist.filter((t) => !t.isDemo);

      // Check if already in playlist
      const existingIdx = this.playlist.findIndex((t) =>
        (t.path && trackMeta.path && t.path === trackMeta.path) ||
        (t.title && t.title.toLowerCase() === trackMeta.title.toLowerCase())
      );

      if (existingIdx !== -1) {
        this.playlist[existingIdx] = trackObj;
      } else {
        this.playlist.unshift(trackObj);
      }

      this.renderPlaylistUI();
      this.saveState();
      this.showToast('✅ Скачано и добавлено: ' + trackObj.title);

      // Auto-play immediately if user had tapped "Слушать"
      if (this.pendingPlayTitle) {
        const pTitle = this.pendingPlayTitle.toLowerCase();
        const tTitle = trackMeta.title.toLowerCase();
        const isMatch = (pTitle === tTitle) || tTitle.includes(pTitle) || pTitle.includes(tTitle);
        if (isMatch) {
          this.pendingPlayTitle = null;
          const targetIdx = this.playlist.findIndex((t) => t.title.toLowerCase() === trackMeta.title.toLowerCase());
          const playIdx = targetIdx !== -1 ? targetIdx : 0;
          await this.loadTrack(playIdx, true);
          this.switchTab('view-player');
          this.showToast('▶ Воспроизведение: ' + trackObj.title);
        }
      }
    };

    window.onAndroidLocalFilesScanned = (callbackId, jsonArr) => {
      try {
        const list = typeof jsonArr === 'string' ? JSON.parse(jsonArr) : jsonArr;
        if (Array.isArray(list) && list.length > 0) {
          const scannedTracks = list.map((item) => ({
            id: 'scanned-' + Math.random().toString(36).substring(2, 9),
            title: item.title,
            artist: item.artist || 'Телефон',
            format: item.format || 'MP3',
            url: item.url,
            path: item.path,
            cover: item.cover || null,
            isLocal: true,
            isDownloaded: true
          }));

          const hasOnlyDemos = this.playlist.length === 0 || this.playlist.every((t) => t.isDemo);
          if (hasOnlyDemos) {
            this.playlist = scannedTracks;
            this.loadTrack(0, false);
          } else {
            scannedTracks.forEach((st) => {
              const existing = this.playlist.find((t) => (t.path && st.path && t.path === st.path) || t.title === st.title);
              if (!existing) {
                this.playlist.push(st);
              } else if (!existing.cover && st.cover) {
                existing.cover = st.cover;
              }
            });
          }
          this.renderPlaylistUI();
          this.saveState();
          this.showToast(`Найдено песен на телефоне: ${list.length}`);
        }
      } catch (e) {
        console.warn('onAndroidLocalFilesScanned error:', e);
      }
    };
  }

  scanLocalMusic() {
    if (window.AndroidBridge && typeof window.AndroidBridge.scanLocalDownloads === 'function') {
      try {
        window.AndroidBridge.scanLocalDownloads('init-scan');
      } catch (e) {
        console.warn('scanLocalDownloads error:', e);
      }
    }
  }

  async prepareTrackUrl(track) {
    if (!track) return '';
    // If we have an active in-memory blobUrl generated in this page session, use it
    if (track.blobUrl && typeof track.blobUrl === 'string' && track.blobUrl.startsWith('blob:')) {
      return track.blobUrl;
    }

    // 1. If it's a demo track, synthesize fresh sound in Web Audio API
    if (track.isDemo) {
      try {
        let synthUrl = '';
        if (track.id === 'demo-1' || (track.title && track.title.includes('Cyberwave'))) {
          synthUrl = await this.synthesizeSynthwaveTrack();
        } else if (track.id === 'demo-2' || (track.title && track.title.includes('Lo-Fi'))) {
          synthUrl = await this.synthesizeLoFiTrack();
        } else {
          synthUrl = await this.synthesizeAmbientTrack();
        }
        if (synthUrl) {
          track.blobUrl = synthUrl;
          track.url = synthUrl;
          return synthUrl;
        }
      } catch (e) {
        console.warn('Demo synthesis error:', e);
      }
    }

    // 2. Resolve local file path (ignore stale blob: URLs)
    let rawUrl = (track.url && !track.url.startsWith('blob:')) ? track.url : '';
    let filePath = track.path || '';
    if (!filePath && rawUrl) {
      filePath = rawUrl.replace(/^local-audio:\/\//, '').replace(/^file:\/\//, '');
    }

    // 3. Guaranteed Native Bridge Base64 -> Blob (works on all Android 10-15 versions)
    if (window.AndroidBridge && typeof window.AndroidBridge.getLocalAudioBase64 === 'function' && filePath) {
      try {
        const b64 = window.AndroidBridge.getLocalAudioBase64(filePath);
        if (b64 && b64.length > 100) {
          const binStr = atob(b64);
          const len = binStr.length;
          const bytes = new Uint8Array(len);
          for (let i = 0; i < len; i++) {
            bytes[i] = binStr.charCodeAt(i);
          }
          const mime = (track.format && track.format.toLowerCase() === 'm4a') ? 'audio/mp4' : 'audio/mpeg';
          const blob = new Blob([bytes], { type: mime });
          track.blobUrl = URL.createObjectURL(blob);
          return track.blobUrl;
        }
      } catch (e) {
        console.warn('Bridge getLocalAudioBase64 error:', e);
      }
    }

    // 4. Fallback: Direct Fetch via WebViewClient shouldInterceptRequest -> Blob
    const fetchUrl = rawUrl.startsWith('local-audio://') ? rawUrl : (filePath ? ('local-audio://' + filePath) : rawUrl);
    if (fetchUrl && fetchUrl.startsWith('local-audio://')) {
      try {
        const res = await fetch(fetchUrl);
        if (res.ok) {
          const blob = await res.blob();
          if (blob && blob.size > 1000) {
            track.blobUrl = URL.createObjectURL(blob);
            return track.blobUrl;
          }
        }
      } catch (e) {
        console.warn('Direct fetch failed:', e);
      }
    }

    return fetchUrl || rawUrl;
  }

  updateOnlineStatus() {
    const pill = document.getElementById('btn-server-status');
    const text = document.getElementById('server-status-text');
    if (!pill || !text) return;
    pill.className = 'server-status-pill online';
    text.textContent = '🟢 Онлайн • Автономно';
  }

  initCanvas() {
    if (!this.canvas) return;
    this.canvas.width = window.innerWidth;
    this.canvas.height = window.innerHeight;
    window.addEventListener('resize', () => {
      if (this.canvas) {
        this.canvas.width = window.innerWidth;
        this.canvas.height = window.innerHeight;
      }
    });
  }

  initYouTubePlayer() {
    const createPlayer = () => {
      if (this.ytPlayer) return;
      try {
        this.ytPlayer = new window.YT.Player('yt-iframe-player', {
          height: '10',
          width: '10',
          playerVars: {
            autoplay: 1,
            controls: 0,
            disablekb: 1,
            fs: 0,
            playsinline: 1,
            rel: 0
          },
          events: {
            onReady: () => {
              this.isYtReady = true;
              if (this.pendingYtVideoId) {
                const vid = this.pendingYtVideoId;
                this.pendingYtVideoId = null;
                this.ytPlayer.loadVideoById(vid);
              }
            },
            onStateChange: (e) => this.onYtPlayerStateChange(e),
            onError: (e) => this.onYtPlayerError(e)
          }
        });
      } catch (err) {
        console.warn('YT Player creation error:', err);
      }
    };

    if (window.YT && window.YT.Player) {
      createPlayer();
    } else {
      window.onYouTubeIframeAPIReady = () => createPlayer();
    }
  }

  onYtPlayerStateChange(e) {
    if (e.data === 1) { // PLAYING
      this.isPlaying = true;
      this.updatePlayStateUI(true);
      this.startYtProgressTracker();
    } else if (e.data === 2) { // PAUSED
      this.isPlaying = false;
      this.updatePlayStateUI(false);
      this.stopYtProgressTracker();
    } else if (e.data === 0) { // ENDED
      this.stopYtProgressTracker();
      if (this.repeatMode === 2) {
        if (this.ytPlayer && typeof this.ytPlayer.seekTo === 'function') {
          this.ytPlayer.seekTo(0);
          this.ytPlayer.playVideo();
        }
      } else {
        this.playNext();
      }
    }
  }

  onYtPlayerError(e) {
    console.warn('YT error:', e);
    this.showToast('Ошибка воспроизведения YouTube');
  }

  startYtProgressTracker() {
    this.stopYtProgressTracker();
    this.ytProgressInterval = setInterval(() => {
      if (!this.isPlaying || !this.ytPlayer || typeof this.ytPlayer.getCurrentTime !== 'function') return;
      const cur = this.ytPlayer.getCurrentTime() || 0;
      const dur = this.ytPlayer.getDuration() || 0;
      if (dur > 0) {
        const pct = (cur / dur) * 100;
        const seekBar = document.getElementById('seek-bar');
        const progressFill = document.getElementById('progress-fill');
        const timeCurrent = document.getElementById('time-current');
        const timeTotal = document.getElementById('time-total');
        if (seekBar) seekBar.value = pct;
        if (progressFill) progressFill.style.width = `${pct}%`;
        const cm = Math.floor(cur / 60);
        const cs = Math.floor(cur % 60);
        if (timeCurrent) timeCurrent.textContent = `${cm}:${cs < 10 ? '0' : ''}${cs}`;
        const dm = Math.floor(dur / 60);
        const ds = Math.floor(dur % 60);
        if (timeTotal) timeTotal.textContent = `${dm}:${ds < 10 ? '0' : ''}${ds}`;
      }
    }, 250);
  }

  stopYtProgressTracker() {
    if (this.ytProgressInterval) {
      clearInterval(this.ytProgressInterval);
      this.ytProgressInterval = null;
    }
  }

  extractVideoId(url) {
    if (!url) return null;
    url = url.trim();
    if (/^[a-zA-Z0-9_-]{11}$/.test(url)) return url;
    const m = url.match(/[?&]v=([a-zA-Z0-9_-]{11})/);
    if (m) return m[1];
    const m2 = url.match(/(?:youtu\.be\/|embed\/|shorts\/|v\/|live\/|track\/)([a-zA-Z0-9_-]{11})/);
    if (m2) return m2[1];
    return null;
  }

  initAudioGraph() {
    if (this.isAudioContextInit) return;
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      this.audioContext = new AudioCtx();
      this.sourceNode = this.audioContext.createMediaElementSource(this.audio);

      const freqs = [60, 250, 1000, 4000, 14000];
      const types = ['lowshelf', 'peaking', 'peaking', 'peaking', 'highshelf'];

      this.eqFilters = freqs.map((freq, i) => {
        const filter = this.audioContext.createBiquadFilter();
        filter.type = types[i];
        filter.frequency.value = freq;
        filter.gain.value = this.presets.bass[i];
        return filter;
      });

      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 256;
      this.analyser.smoothingTimeConstant = 0.8;

      let currentNode = this.sourceNode;
      this.eqFilters.forEach((f) => {
        currentNode.connect(f);
        currentNode = f;
      });
      currentNode.connect(this.analyser);
      this.analyser.connect(this.audioContext.destination);

      this.isAudioContextInit = true;
    } catch (e) {
      console.warn('AudioContext error:', e);
    }
  }

  async initDemoTracks() {
    const track1 = await this.synthesizeSynthwaveTrack();
    const track2 = await this.synthesizeLoFiTrack();
    const track3 = await this.synthesizeAmbientTrack();

    this.playlist = [
      {
        id: 'demo-1',
        title: 'Cyberwave Overdrive',
        artist: 'Apex Sound Lab',
        durationStr: '1:45',
        format: 'FLAC / 48kHz',
        url: track1,
        isDemo: true
      },
      {
        id: 'demo-2',
        title: 'Midnight Lo-Fi Dreams',
        artist: 'Kirin Sessions',
        durationStr: '1:30',
        format: 'Hi-Res Audio',
        url: track2,
        isDemo: true
      },
      {
        id: 'demo-3',
        title: 'Solar Flare Ambient',
        artist: 'Apex Electronics',
        durationStr: '1:20',
        format: 'OLED Mastered',
        url: track3,
        isDemo: true
      }
    ];

    this.renderPlaylistUI();
    this.loadTrack(0, false);
  }

  async synthesizeSynthwaveTrack() {
    const sampleRate = 44100;
    const duration = 24;
    const ctx = new OfflineAudioContext(2, sampleRate * duration, sampleRate);
    const bpm = 124;
    const beatLen = 60 / bpm;
    const totalBeats = Math.floor(duration / beatLen);
    const notes = [110, 130.81, 146.83, 164.81, 196.00, 220, 261.63];

    for (let beat = 0; beat < totalBeats; beat++) {
      const time = beat * beatLen;
      const kickOsc = ctx.createOscillator();
      const kickGain = ctx.createGain();
      kickOsc.frequency.setValueAtTime(140, time);
      kickOsc.frequency.exponentialRampToValueAtTime(35, time + 0.12);
      kickGain.gain.setValueAtTime(0.85, time);
      kickGain.gain.exponentialRampToValueAtTime(0.001, time + 0.22);
      kickOsc.connect(kickGain);
      kickGain.connect(ctx.destination);
      kickOsc.start(time);
      kickOsc.stop(time + 0.25);

      if (beat % 2 === 1) {
        const snareOsc = ctx.createOscillator();
        const snareGain = ctx.createGain();
        snareOsc.type = 'triangle';
        snareOsc.frequency.setValueAtTime(220, time);
        snareOsc.frequency.exponentialRampToValueAtTime(60, time + 0.1);
        snareGain.gain.setValueAtTime(0.4, time);
        snareGain.gain.exponentialRampToValueAtTime(0.001, time + 0.15);
        snareOsc.connect(snareGain);
        snareGain.connect(ctx.destination);
        snareOsc.start(time);
        snareOsc.stop(time + 0.18);
      }

      for (let sub = 0; sub < 4; sub++) {
        const subTime = time + (sub * beatLen) / 4;
        const note = notes[(beat * 4 + sub) % notes.length];
        const arpOsc = ctx.createOscillator();
        const arpGain = ctx.createGain();
        arpOsc.type = 'sawtooth';
        arpOsc.frequency.setValueAtTime(note * 2, subTime);
        arpGain.gain.setValueAtTime(0.18, subTime);
        arpGain.gain.exponentialRampToValueAtTime(0.001, subTime + (beatLen / 4) * 0.9);
        arpOsc.connect(arpGain);
        arpGain.connect(ctx.destination);
        arpOsc.start(subTime);
        arpOsc.stop(subTime + beatLen / 4);
      }
    }
    const rendered = await ctx.startRendering();
    return this.bufferToWavUrl(rendered);
  }

  async synthesizeLoFiTrack() {
    const sampleRate = 44100;
    const duration = 20;
    const ctx = new OfflineAudioContext(2, sampleRate * duration, sampleRate);
    const chords = [[261.63, 329.63, 392.00], [220.00, 261.63, 329.63], [174.61, 220.00, 261.63], [196.00, 246.94, 293.66]];

    for (let i = 0; i < 4; i++) {
      const chordTime = i * 5;
      const chord = chords[i];
      chord.forEach((freq) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(freq, chordTime);
        gain.gain.setValueAtTime(0.2, chordTime);
        gain.gain.exponentialRampToValueAtTime(0.01, chordTime + 4.8);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(chordTime);
        osc.stop(chordTime + 5.0);
      });
    }
    const rendered = await ctx.startRendering();
    return this.bufferToWavUrl(rendered);
  }

  async synthesizeAmbientTrack() {
    const sampleRate = 44100;
    const duration = 18;
    const ctx = new OfflineAudioContext(2, sampleRate * duration, sampleRate);
    [110, 164.81, 220, 293.66, 440].forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, 0);
      gain.gain.setValueAtTime(0.01, 0);
      gain.gain.linearRampToValueAtTime(0.15 / (idx + 1), 6);
      gain.gain.linearRampToValueAtTime(0.01, duration);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(0);
      osc.stop(duration);
    });
    const rendered = await ctx.startRendering();
    return this.bufferToWavUrl(rendered);
  }

  bufferToWavUrl(buffer) {
    const numOfChan = buffer.numberOfChannels;
    const length = buffer.length * numOfChan * 2 + 44;
    const out = new DataView(new ArrayBuffer(length));
    let channels = [], i, sample, offset = 0, pos = 0;

    function setUint16(data) { out.setUint16(pos, data, true); pos += 2; }
    function setUint32(data) { out.setUint32(pos, data, true); pos += 4; }

    setUint32(0x46464952);
    setUint32(length - 8);
    setUint32(0x45564157);
    setUint32(0x20746d66);
    setUint32(16);
    setUint16(1);
    setUint16(numOfChan);
    setUint32(buffer.sampleRate);
    setUint32(buffer.sampleRate * 2 * numOfChan);
    setUint16(numOfChan * 2);
    setUint16(16);
    setUint32(0x61746164);
    setUint32(length - pos - 4);

    for (i = 0; i < buffer.numberOfChannels; i++) channels.push(buffer.getChannelData(i));
    while (offset < buffer.length) {
      for (i = 0; i < numOfChan; i++) {
        sample = Math.max(-1, Math.min(1, channels[i][offset]));
        sample = (0.5 + sample < 0 ? sample * 32768 : sample * 32767) | 0;
        out.setInt16(pos, sample, true);
        pos += 2;
      }
      offset++;
    }
    const blob = new Blob([out.buffer], { type: 'audio/wav' });
    return URL.createObjectURL(blob);
  }

  async loadTrack(index, autoPlay = true) {
    if (!this.playlist[index]) return;
    this.currentIndex = index;
    const track = this.playlist[index];

    document.getElementById('track-title').textContent = track.title;
    document.getElementById('track-artist').textContent = track.artist;
    document.getElementById('format-badge').textContent = track.format || 'AUDIO';
    document.getElementById('vinyl-initials').textContent = (track.title || 'APEX').substring(0, 4).toUpperCase();
    this.updateCoverArt(track);

    this.updateFavoriteButton(this.favorites.has(track.id));

    document.querySelectorAll('.track-row').forEach((row, idx) => {
      row.classList.toggle('active', idx === index);
    });

    this.updateMediaSession(track);

    if (track.isYt && !track.isLocal && !track.blobUrl) {
      // Pause HTML5 local audio
      this.audio.pause();
      this.audio.src = '';

      const vidId = track.videoId || (track.id && track.id.startsWith('yt-') ? track.id.replace(/^yt-/, '') : track.id) || this.extractVideoId(track.url);
      if (this.ytPlayer && this.isYtReady && typeof this.ytPlayer.loadVideoById === 'function') {
        if (autoPlay) {
          this.ytPlayer.loadVideoById(vidId);
        } else {
          this.ytPlayer.cueVideoById(vidId);
        }
      } else {
        this.pendingYtVideoId = vidId;
      }
      this.startYtProgressTracker();
    } else {
      // Pause YouTube player if active
      if (this.ytPlayer && typeof this.ytPlayer.pauseVideo === 'function') {
        try { this.ytPlayer.pauseVideo(); } catch (e) {}
      }
      this.stopYtProgressTracker();

      let playUrl = (track.blobUrl && track.blobUrl.startsWith('blob:')) ? track.blobUrl : null;
      if (!playUrl) {
        try {
          playUrl = await this.prepareTrackUrl(track);
        } catch (e) {
          console.warn('prepareTrackUrl error in loadTrack:', e);
        }
      }

      if (!playUrl && track.url && !track.url.startsWith('blob:')) {
        playUrl = track.url;
      }

      this.audio.src = playUrl || '';
      this.audio.loop = this.repeatMode === 2;

      this.syncPlaybackWithNative(autoPlay);
      this.saveState();

      if (autoPlay) {
        this.playAudio();
      } else {
        this.pauseAudio();
      }
    }
  }

  playAudio() {
    const current = this.playlist[this.currentIndex];
    if (current && current.isYt && !current.isLocal && !current.blobUrl) {
      if (this.ytPlayer && typeof this.ytPlayer.playVideo === 'function') {
        this.ytPlayer.playVideo();
      }
      this.isPlaying = true;
      this.updatePlayStateUI(true);
      this.startYtProgressTracker();
      return;
    }

    if (!this.audio.src || this.audio.src === '' || this.audio.error) {
      if (current) {
        this.loadTrack(this.currentIndex, true);
        return;
      }
    }

    try {
      this.initAudioGraph();
      if (this.audioContext && this.audioContext.state === 'suspended') {
        this.audioContext.resume().catch(() => {});
      }
    } catch (e) {
      console.warn('AudioContext resume error:', e);
    }

    const playPromise = this.audio.play();
    if (playPromise !== undefined) {
      playPromise.then(() => {
        this.isPlaying = true;
        this.updatePlayStateUI(true);
        this.syncPlaybackWithNative(true);
        this.saveState();
      }).catch(async (e) => {
        console.warn('Play error:', e);
        if (current && !current._retryPlay) {
          current._retryPlay = true;
          try {
            current.blobUrl = null;
            const newUrl = await this.prepareTrackUrl(current);
            if (newUrl) {
              this.audio.src = newUrl;
              await this.audio.play();
              this.isPlaying = true;
              this.updatePlayStateUI(true);
              this.syncPlaybackWithNative(true);
              this.saveState();
              current._retryPlay = false;
              return;
            }
          } catch (retryErr) {
            console.warn('Retry play error:', retryErr);
          }
          current._retryPlay = false;
        }
        this.isPlaying = false;
        this.updatePlayStateUI(false);
        this.syncPlaybackWithNative(false);
      });
    }
  }

  pauseAudio() {
    const current = this.playlist[this.currentIndex];
    if (current && current.isYt && !current.isLocal && !current.blobUrl) {
      if (this.ytPlayer && typeof this.ytPlayer.pauseVideo === 'function') {
        this.ytPlayer.pauseVideo();
      }
      this.isPlaying = false;
      this.updatePlayStateUI(false);
      this.stopYtProgressTracker();
      return;
    }

    this.audio.pause();
    this.isPlaying = false;
    this.updatePlayStateUI(false);
    this.syncPlaybackWithNative(false);
    this.saveState();
  }

  togglePlayPause() {
    if (this.isPlaying) {
      this.pauseAudio();
    } else {
      this.playAudio();
    }
  }

  seekAudio(seconds) {
    const current = this.playlist[this.currentIndex];
    if (current && current.isYt) {
      if (this.ytPlayer && typeof this.ytPlayer.seekTo === 'function') {
        this.ytPlayer.seekTo(seconds, true);
      }
      return;
    }
    this.audio.currentTime = seconds;
  }

  playNext() {
    if (this.playlist.length === 0) return;
    let nextIndex;
    if (this.isShuffle) {
      nextIndex = Math.floor(Math.random() * this.playlist.length);
    } else {
      nextIndex = (this.currentIndex + 1) % this.playlist.length;
    }
    this.loadTrack(nextIndex, true);
  }

  playPrev() {
    if (this.playlist.length === 0) return;
    if (this.audio.currentTime > 3) {
      this.audio.currentTime = 0;
      return;
    }
    let prevIndex = (this.currentIndex - 1 + this.playlist.length) % this.playlist.length;
    this.loadTrack(prevIndex, true);
  }

  updatePlayStateUI(playing) {
    const iconPlay = document.getElementById('icon-play');
    const iconPause = document.getElementById('icon-pause');
    const vinylDisc = document.getElementById('vinyl-disc');
    const turntableArm = document.getElementById('turntable-arm');

    if (playing) {
      iconPlay.classList.add('hidden');
      iconPause.classList.remove('hidden');
      vinylDisc.classList.remove('paused');
      turntableArm.classList.remove('paused');
    } else {
      iconPlay.classList.remove('hidden');
      iconPause.classList.add('hidden');
      vinylDisc.classList.add('paused');
      turntableArm.classList.add('paused');
    }
  }

  updateCoverArt(track) {
    const art = document.getElementById('vinyl-art');
    const label = art ? art.closest('.vinyl-label') : null;
    const initials = document.getElementById('vinyl-initials');
    if (!art) return;
    const token = (this._coverToken = (this._coverToken || 0) + 1);
    if (track && track.cover) {
      const img = new Image();
      img.onload = () => {
        if (token !== this._coverToken) return;
        art.style.backgroundImage = `url("${track.cover}")`;
        art.classList.add('has-cover');
        if (label) label.classList.add('has-cover');
        if (initials) initials.style.display = 'none';
      };
      img.onerror = () => { if (token === this._coverToken) this.updateCoverArt(null); };
      img.src = track.cover;
    } else {
      art.style.backgroundImage = '';
      art.classList.remove('has-cover');
      if (label) label.classList.remove('has-cover');
      if (initials) initials.style.display = '';
    }
  }

  coverThumbHtml(cover, fallback) {
    if (cover) {
      return `<div class="track-mini-icon has-cover"><img src="${cover}" alt="" loading="lazy" onerror="this.remove()" /><span>${fallback}</span></div>`;
    }
    return `<div class="track-mini-icon">${fallback}</div>`;
  }

  updateMediaSession(track) {
    if ('mediaSession' in navigator) {
      const meta = {
        title: track.title,
        artist: track.artist,
        album: 'Apex Music Player'
      };
      if (track.cover) {
        meta.artwork = [{ src: track.cover, sizes: '300x300', type: 'image/jpeg' }];
      }
      navigator.mediaSession.metadata = new MediaMetadata(meta);

      navigator.mediaSession.setActionHandler('play', () => this.playAudio());
      navigator.mediaSession.setActionHandler('pause', () => this.pauseAudio());
      navigator.mediaSession.setActionHandler('previoustrack', () => this.playPrev());
      navigator.mediaSession.setActionHandler('nexttrack', () => this.playNext());
      navigator.mediaSession.setActionHandler('seekto', (details) => {
        if (details.seekTime) this.audio.currentTime = details.seekTime;
      });
    }

    this.syncPlaybackWithNative(this.isPlaying);
  }

  syncPlaybackWithNative(isPlaying) {
    const track = this.playlist[this.currentIndex];
    if (!track) return;
    const playing = (isPlaying !== undefined) ? isPlaying : this.isPlaying;
    const title = track.title || 'Apex Track';
    const artist = track.artist || 'Apex Music';
    const coverUrl = track.cover || '';
    const posSec = this.audio ? (this.audio.currentTime || 0) : 0;
    const durSec = this.audio ? (this.audio.duration || 0) : 0;

    if (window.AndroidBridge && typeof window.AndroidBridge.updatePlaybackState === 'function') {
      try {
        window.AndroidBridge.updatePlaybackState(playing, title, artist, coverUrl, posSec, durSec);
      } catch (e) {
        console.warn('syncPlaybackWithNative error:', e);
      }
    }
  }

  saveState() {
    try {
      const currentTrack = this.playlist[this.currentIndex];
      const curTime = this.audio ? Math.floor(this.audio.currentTime || 0) : 0;
      const state = {
        currentIndex: this.currentIndex,
        currentTrackId: currentTrack ? currentTrack.id : null,
        currentTime: curTime,
        repeatMode: this.repeatMode,
        isShuffle: this.isShuffle,
        activePlaylistFilter: this.activePlaylistFilter || 'all',
        sortMode: this.sortMode || 'default',
        playbackRate: this.playbackRate || 1.0,
        customPlaylists: this.customPlaylists || [],
        favorites: Array.from(this.favorites || []),
        playlist: this.playlist.map((t) => {
          let cleanUrl = t.url || '';
          if (cleanUrl.startsWith('blob:') || cleanUrl.startsWith('data:')) {
            cleanUrl = t.path ? ('local-audio://' + t.path) : '';
          }
          let cleanCover = t.cover || '';
          if (cleanCover.startsWith('blob:') || cleanCover.startsWith('data:')) {
            cleanCover = '';
          }
          return {
            id: t.id,
            title: t.title,
            artist: t.artist,
            durationStr: t.durationStr || t.duration,
            format: t.format,
            url: cleanUrl,
            path: t.path || '',
            cover: cleanCover,
            isLocal: !!t.isLocal,
            isDownloaded: !!t.isDownloaded,
            isDemo: !!t.isDemo,
            isYt: !!t.isYt,
            videoId: t.videoId || ''
          };
        })
      };
      const jsonStr = JSON.stringify(state);
      localStorage.setItem('apex_player_state', jsonStr);
      if (window.AndroidBridge && typeof window.AndroidBridge.savePlayerState === 'function') {
        window.AndroidBridge.savePlayerState(jsonStr);
      }
    } catch (e) {
      console.warn('saveState error:', e);
    }
  }

  loadStateFromStorage() {
    let savedStr = null;
    if (window.AndroidBridge && typeof window.AndroidBridge.getSavedPlayerState === 'function') {
      try {
        savedStr = window.AndroidBridge.getSavedPlayerState();
      } catch (e) {}
    }
    if (!savedStr) {
      try {
        savedStr = localStorage.getItem('apex_player_state');
      } catch (e) {}
    }
    if (savedStr) {
      try {
        const state = JSON.parse(savedStr);
        if (state && Array.isArray(state.playlist) && state.playlist.length > 0) {
          this.playlist = state.playlist.map((t) => {
            let cleanUrl = t.url || '';
            if (cleanUrl.startsWith('blob:') || cleanUrl.startsWith('data:')) {
              cleanUrl = t.path ? ('local-audio://' + t.path) : '';
            }
            return {
              ...t,
              url: cleanUrl,
              blobUrl: null
            };
          });

          this.currentIndex = Math.max(0, Math.min(state.currentIndex || 0, this.playlist.length - 1));
          this.repeatMode = state.repeatMode || 0;
          this.isShuffle = !!state.isShuffle;

          if (Array.isArray(state.customPlaylists)) {
            this.customPlaylists = state.customPlaylists;
          }
          if (Array.isArray(state.favorites)) {
            this.favorites = new Set(state.favorites);
          }
          if (state.activePlaylistFilter) {
            this.activePlaylistFilter = state.activePlaylistFilter;
          }
          if (state.sortMode) {
            this.sortMode = state.sortMode;
          }
          if (state.playbackRate && this.audio) {
            this.playbackRate = state.playbackRate;
            try { this.audio.playbackRate = this.playbackRate; } catch (e) {}
            const speedBadge = document.getElementById('speed-badge');
            if (speedBadge) speedBadge.textContent = `${this.playbackRate}x`;
          }

          const btnShuffle = document.getElementById('btn-shuffle');
          if (btnShuffle) btnShuffle.classList.toggle('active', this.isShuffle);
          const btnRepeat = document.getElementById('btn-repeat');
          const repeatBadge = document.getElementById('repeat-badge');
          if (btnRepeat && repeatBadge) {
            if (this.repeatMode === 0) {
              btnRepeat.classList.remove('active');
              repeatBadge.classList.add('hidden');
            } else if (this.repeatMode === 1) {
              btnRepeat.classList.add('active');
              repeatBadge.classList.add('hidden');
            } else {
              btnRepeat.classList.add('active');
              repeatBadge.classList.remove('hidden');
            }
          }

          this.renderPlaylistUI();
          this.loadTrack(this.currentIndex, false);

          if (state.currentTime && state.currentTime > 0) {
            const resumePos = state.currentTime;
            this.savedResumeTime = resumePos;
            const applyResume = () => {
              if (this.savedResumeTime) {
                try { this.audio.currentTime = this.savedResumeTime; } catch(e){}
                this.savedResumeTime = null;
              }
            };
            this.audio.addEventListener('loadedmetadata', applyResume, { once: true });
            this.audio.addEventListener('canplay', applyResume, { once: true });

            const timeCurrent = document.getElementById('time-current');
            if (timeCurrent) {
              const m = Math.floor(resumePos / 60);
              const s = Math.floor(resumePos % 60);
              timeCurrent.textContent = `${m}:${s < 10 ? '0' : ''}${s}`;
            }
          }
          return true;
        }
      } catch (e) {
        console.warn('loadStateFromStorage error:', e);
      }
    }
    return false;
  }

  // ==========================================
  // YOUTUBE EXTRACTION & DOWNLOAD LOGIC
  // ==========================================
  async handleYouTubeSearch(query) {
    query = (query || '').trim();
    if (!query) {
      this.showToast('Введите ссылку или название трека');
      return;
    }

    const loader = document.getElementById('yt-loader');
    const loaderText = document.getElementById('yt-loader-text');
    const resultCard = document.getElementById('yt-result-card');

    loader.classList.remove('hidden');
    resultCard.classList.add('hidden');
    loaderText.textContent = `Поиск на YouTube: "${query}"...`;

    try {
      if (window.AndroidBridge && typeof window.AndroidBridge.searchYouTube === 'function') {
        const nativeRes = await callNativeSearch(query);
        loader.classList.add('hidden');

        if (!nativeRes || !nativeRes.success) {
          throw new Error((nativeRes && nativeRes.error) || 'Ничего не найдено');
        }

        // Direct URL / Single Video result
        if (nativeRes.id) {
          this.currentYtResult = nativeRes;
          this.displayYouTubeResult(nativeRes);
          this.showToast('Трек найден! Нажмите Слушать или Скачать');
          return;
        }

        // Search with multiple items
        if (nativeRes.items && nativeRes.items.length > 0) {
          const top = nativeRes.topTrack || nativeRes.items[0];
          this.currentYtResult = top;
          this.displayYouTubeResult(top);
          this.renderSearchResults(nativeRes.items);
          this.showToast(`Найдено треков: ${nativeRes.items.length}`);
          return;
        }

        throw new Error('Ничего не найдено');
      }

      // Browser Fallback (if testing in standard browser without AndroidBridge)
      const videoId = this.extractVideoId(query);
      if (videoId) {
        loaderText.textContent = 'Получение данных трека...';
        const oembedUrl = `https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=${videoId}&format=json`;
        const res = await fetch(oembedUrl);
        const data = await res.json();
        loader.classList.add('hidden');

        const trackInfo = {
          id: videoId,
          title: data.title || 'YouTube Track',
          artist: data.author_name || 'YouTube',
          duration: '3:30',
          thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
          url: `https://www.youtube.com/watch?v=${videoId}`,
          isYt: true
        };
        this.currentYtResult = trackInfo;
        this.displayYouTubeResult(trackInfo);
        this.showToast('Трек готов!');
      } else {
        throw new Error('Для поиска по ключевым словам требуется запуск в приложении на телефоне');
      }

    } catch (err) {
      loader.classList.add('hidden');
      console.warn('YouTube search error:', err);
      this.showToast(`Ошибка: ${err.message}`);
    }
  }

  renderSearchResults(items) {
    const container = document.getElementById('yt-history-list');
    if (!container || !items || items.length === 0) return;

    container.innerHTML = '<div style="font-size:12px;color:rgba(255,255,255,0.5);margin-bottom:8px;padding-left:4px;">Все найденные треки (нажмите для выбора):</div>';

    items.forEach((item) => {
      const row = document.createElement('div');
      row.className = 'track-row';
      const durStr = item.durationStr || item.duration || '3:30';
      const artist = item.artist || item.author || 'YouTube';

      row.innerHTML = `
        ${this.coverThumbHtml(item.thumbnail, '<span style="color:#ff0033;">▶</span>')}
        <div class="track-meta">
          <div class="track-meta-title">${item.title}</div>
          <div class="track-meta-artist">${artist}</div>
        </div>
        <div class="track-duration">${durStr}</div>
      `;

      row.addEventListener('click', () => {
        this.currentYtResult = item;
        this.displayYouTubeResult(item);
        this.showToast('Выбран трек: ' + item.title);
      });

      container.appendChild(row);
    });
  }

  displayYouTubeResult(data) {
    const card = document.getElementById('yt-result-card');
    document.getElementById('yt-thumb').src = data.thumbnail || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400';
    document.getElementById('yt-title').textContent = data.title;
    document.getElementById('yt-channel').textContent = data.artist;
    document.getElementById('yt-duration').textContent = data.durationStr || data.duration || '3:30';
    card.classList.remove('hidden');
  }

  async playCurrentYtTrack() {
    if (!this.currentYtResult) return;
    const vidId = this.currentYtResult.id || this.extractVideoId(this.currentYtResult.url);
    const title = this.currentYtResult.title || 'YouTube Track';
    const artist = this.currentYtResult.artist || 'YouTube Music';

    // 1. Check if track is already in local playlist
    const localIdx = this.playlist.findIndex((t) =>
      t.isLocal && t.title && (
        t.title.toLowerCase() === title.toLowerCase() ||
        t.title.toLowerCase().includes(title.toLowerCase()) ||
        title.toLowerCase().includes(t.title.toLowerCase())
      )
    );

    if (localIdx !== -1) {
      await this.loadTrack(localIdx, true);
      this.switchTab('view-player');
      this.showToast('▶ Воспроизведение: ' + this.playlist[localIdx].title);
      return;
    }

    // 2. Auto-download in background and auto-play immediately when completed!
    this.pendingPlayTitle = title;
    this.showToast('🚀 Загрузка и подготовка трека в плеер...');

    const loader = document.getElementById('yt-loader');
    const loaderText = document.getElementById('yt-loader-text');
    if (loader && loaderText) {
      loader.classList.remove('hidden');
      loaderText.textContent = `Загрузка в высоком качестве: "${title}"...`;
    }

    await this.downloadCurrentYtTrack(true);
  }

  async downloadCurrentYtTrack(isSilent = false) {
    if (!this.currentYtResult) return;
    const vidId = this.currentYtResult.id || this.extractVideoId(this.currentYtResult.url);
    const title = this.currentYtResult.title || 'Music Track';
    const artist = this.currentYtResult.artist || 'YouTube';

    const loader = document.getElementById('yt-loader');
    const loaderText = document.getElementById('yt-loader-text');

    if (window.AndroidBridge && typeof window.AndroidBridge.downloadMp3Async === 'function') {
      if (loader && loaderText) {
        loader.classList.remove('hidden');
        loaderText.textContent = `Конвертация "${title}" в MP3...`;
      }
      try {
        const res = await callNativeDownloadMp3(vidId, title, artist);
        if (loader) loader.classList.add('hidden');
        if (res && res.success) {
          if (!isSilent) {
            this.showToast('Скачивание началось! Трек автоматически появится в плеере.');
          }
        } else {
          this.showToast('Ошибка: ' + ((res && res.error) || 'Не удалось скачать MP3'));
        }
      } catch (e) {
        if (loader) loader.classList.add('hidden');
        this.showToast('Ошибка скачивания: ' + e.message);
      }
    } else {
      window.open(`https://loader.to/ajax/download.php?format=mp3&url=https://www.youtube.com/watch?v=${vidId}`, '_blank');
      this.showToast('Открытие загрузчика MP3...');
    }

    this.saveYtToHistory(this.currentYtResult);
  }

  addCurrentYtToPlaylist() {
    if (!this.currentYtResult) return;
    const title = this.currentYtResult.title || 'YouTube Track';

    if (this.playlist.some((t) => t.title && t.title.toLowerCase() === title.toLowerCase())) {
      this.showToast('Трек уже в плейлисте');
      return;
    }

    this.downloadCurrentYtTrack(true);
    this.showToast('Трек скачивается и будет добавлен в плейлист автоматически');
  }


  saveYtToHistory(data) {
    if (!this.ytHistory.some((item) => item.title === data.title)) {
      this.ytHistory.unshift({
        title: data.title,
        artist: data.artist,
        duration: data.duration,
        thumbnail: data.thumbnail,
        audioUrl: data.audioUrl
      });
      if (this.ytHistory.length > 20) this.ytHistory.pop();
      try {
        localStorage.setItem('apex_yt_history', JSON.stringify(this.ytHistory));
      } catch (e) {}
      this.renderYtHistoryUI();
    }
  }

  loadYtHistoryFromStorage() {
    try {
      const saved = localStorage.getItem('apex_yt_history');
      if (saved) {
        this.ytHistory = JSON.parse(saved);
        this.renderYtHistoryUI();
      }
    } catch (e) {}
  }

  renderYtHistoryUI() {
    const list = document.getElementById('yt-history-list');
    if (!list) return;
    if (this.ytHistory.length === 0) {
      list.innerHTML = '<div class="empty-history-text">Здесь появятся найденные треки</div>';
      return;
    }

    list.innerHTML = '';
    this.ytHistory.forEach((item) => {
      const row = document.createElement('div');
      row.className = 'track-row';
      row.innerHTML = `
        ${this.coverThumbHtml(item.thumbnail, '<span style="color:#ff0033;">▶</span>')}
        <div class="track-meta">
          <div class="track-meta-title">${item.title}</div>
          <div class="track-meta-artist">${item.artist}</div>
        </div>
        <div class="track-duration">${item.duration || 'MP3'}</div>
      `;
      row.addEventListener('click', () => {
        this.currentYtResult = item;
        this.displayYouTubeResult(item);
      });
      list.appendChild(row);
    });
  }

  handleFilesAdded(files) {
    if (!files || files.length === 0) return;
    let addedCount = 0;
    Array.from(files).forEach((file) => {
      const ext = file.name.split('.').pop().toLowerCase();
      let title = file.name.replace(/\.[^/.]+$/, '');
      let artist = 'Локальный файл';

      if (title.includes(' - ')) {
        const parts = title.split(' - ');
        artist = parts[0].trim();
        title = parts.slice(1).join(' - ').trim();
      }

      const url = URL.createObjectURL(file);
      const trackObj = {
        id: 'local-' + Date.now() + '-' + Math.random(),
        title: title,
        artist: artist,
        format: ext.toUpperCase(),
        url: url,
        file: file
      };

      this.playlist.push(trackObj);
      addedCount++;
    });

    this.renderPlaylistUI();
    this.showToast(`Добавлено треков: ${addedCount}`);

    if (addedCount > 0 && !this.isPlaying) {
      this.loadTrack(this.playlist.length - addedCount, true);
    }
  }

  handleExternalAudioUri(uriString) {
    this.showToast('Загрузка внешнего аудио...');
    fetch(uriString)
      .then((res) => res.blob())
      .then((blob) => {
        const parts = uriString.split('/');
        const name = parts[parts.length - 1] || 'Music Track.mp3';
        const file = new File([blob], name);
        this.handleFilesAdded([file]);
      })
      .catch((err) => {
        this.showToast('Ошибка загрузки: ' + err.message);
      });
  }

  renderPlaylistsChips() {
    const wrap = document.getElementById('playlists-chips');
    if (!wrap) return;

    const totalCount = this.playlist.length;
    const favCount = this.playlist.filter(t => this.favorites.has(t.id)).length;
    const downCount = this.playlist.filter(t => t.isLocal || t.isDownloaded).length;
    const demoCount = this.playlist.filter(t => t.isDemo).length;

    const items = [
      { id: 'all', name: 'Все треки', icon: '🎵', count: totalCount },
      { id: 'favorites', name: 'Избранное', icon: '💖', count: favCount },
      { id: 'downloads', name: 'На телефоне', icon: '📥', count: downCount },
      { id: 'demo', name: 'Демо', icon: '⚡', count: demoCount }
    ];

    (this.customPlaylists || []).forEach(pl => {
      const count = (pl.trackIds || []).filter(id => this.playlist.some(t => t.id === id)).length;
      items.push({
        id: pl.id,
        name: pl.name,
        icon: pl.icon || '🎵',
        count: count,
        isCustom: true
      });
    });

    let html = '';
    items.forEach(it => {
      const isActive = this.activePlaylistFilter === it.id;
      html += `
        <button class="pl-chip ${isActive ? 'active' : ''}" data-pl-id="${it.id}">
          <span>${it.icon} ${it.name}</span>
          <span class="pl-chip-count">${it.count}</span>
        </button>
      `;
    });

    html += `
      <button id="btn-chip-new-pl" class="pl-chip pl-chip-new">
        <span>➕ Новый</span>
      </button>
    `;

    wrap.innerHTML = html;

    wrap.querySelectorAll('.pl-chip[data-pl-id]').forEach(btn => {
      btn.addEventListener('click', (e) => {
        const id = e.currentTarget.getAttribute('data-pl-id');
        this.activePlaylistFilter = id;
        this.renderPlaylistsChips();
        this.renderPlaylistUI();
        this.saveState();
      });
    });

    const btnNew = document.getElementById('btn-chip-new-pl');
    if (btnNew) {
      btnNew.addEventListener('click', () => {
        const modal = document.getElementById('modal-create-playlist');
        const input = document.getElementById('input-playlist-name');
        if (modal) modal.classList.remove('hidden');
        if (input) {
          input.value = '';
          input.focus();
        }
      });
    }
  }

  getFilteredTracks() {
    let tracks = [...this.playlist];

    // Filter by collection / playlist
    if (this.activePlaylistFilter === 'favorites') {
      tracks = tracks.filter(t => this.favorites.has(t.id));
    } else if (this.activePlaylistFilter === 'downloads') {
      tracks = tracks.filter(t => t.isLocal || t.isDownloaded);
    } else if (this.activePlaylistFilter === 'demo') {
      tracks = tracks.filter(t => t.isDemo);
    } else if (this.activePlaylistFilter && this.activePlaylistFilter.startsWith('pl-')) {
      const pl = (this.customPlaylists || []).find(p => p.id === this.activePlaylistFilter);
      if (pl && Array.isArray(pl.trackIds)) {
        tracks = tracks.filter(t => pl.trackIds.includes(t.id));
      }
    }

    // Filter by search query
    if (this.searchQuery && this.searchQuery.trim()) {
      const q = this.searchQuery.trim().toLowerCase();
      tracks = tracks.filter(t =>
        (t.title && t.title.toLowerCase().includes(q)) ||
        (t.artist && t.artist.toLowerCase().includes(q))
      );
    }

    // Sort
    if (this.sortMode === 'title-asc') {
      tracks.sort((a, b) => (a.title || '').localeCompare(b.title || ''));
    } else if (this.sortMode === 'artist-asc') {
      tracks.sort((a, b) => (a.artist || '').localeCompare(b.artist || ''));
    }

    return tracks;
  }

  renderPlaylistUI() {
    const listEl = document.getElementById('track-list');
    const titleEl = document.getElementById('playlist-current-title');
    const countEl = document.getElementById('playlist-count');
    const btnDelete = document.getElementById('btn-delete-active-playlist');
    if (!listEl) return;

    this.renderPlaylistsChips();

    const filtered = this.getFilteredTracks();

    // Set Title
    let displayTitle = 'Все треки';
    let isCustom = false;
    if (this.activePlaylistFilter === 'favorites') {
      displayTitle = '💖 Избранное';
    } else if (this.activePlaylistFilter === 'downloads') {
      displayTitle = '📥 На телефоне';
    } else if (this.activePlaylistFilter === 'demo') {
      displayTitle = '⚡ Демо-треки';
    } else if (this.activePlaylistFilter && this.activePlaylistFilter.startsWith('pl-')) {
      const pl = (this.customPlaylists || []).find(p => p.id === this.activePlaylistFilter);
      if (pl) {
        displayTitle = `${pl.icon || '🎵'} ${pl.name}`;
        isCustom = true;
      }
    }

    if (titleEl) titleEl.textContent = displayTitle;
    if (countEl) countEl.textContent = `${filtered.length} треков`;
    if (btnDelete) btnDelete.classList.toggle('hidden', !isCustom);

    listEl.innerHTML = '';

    if (filtered.length === 0) {
      const emptyDiv = document.createElement('div');
      emptyDiv.style.textAlign = 'center';
      emptyDiv.style.padding = '36px 16px';
      emptyDiv.style.color = 'var(--text-muted)';
      emptyDiv.style.fontSize = '13px';
      emptyDiv.innerHTML = `
        <div style="font-size:32px;margin-bottom:8px;">🎵</div>
        <div>В этом разделе пока нет треков</div>
        <div style="font-size:11px;margin-top:4px;">Добавьте музыку с телефона или скачайте с YouTube</div>
      `;
      listEl.appendChild(emptyDiv);
      return;
    }

    filtered.forEach((track) => {
      const originalIdx = this.playlist.indexOf(track);
      const isCur = originalIdx === this.currentIndex;
      const isFav = this.favorites.has(track.id);

      const row = document.createElement('div');
      row.className = `track-row ${isCur ? 'active' : ''}`;

      row.innerHTML = `
        ${this.coverThumbHtml(track.cover, isCur && this.isPlaying ? '▶' : originalIdx + 1)}
        <div class="track-meta">
          <div class="track-meta-title">${track.title}</div>
          <div class="track-meta-artist">${track.artist}</div>
        </div>
        <div class="track-duration">${track.format || 'AUDIO'}</div>
        <button class="btn-row-fav ${isFav ? 'is-fav' : ''}" title="${isFav ? 'Удалить из избранного' : 'В избранное'}">
          ${isFav ? '💖' : '🤍'}
        </button>
        <button class="btn-track-menu" title="Опции">⋮</button>
      `;

      // Click row to play
      row.addEventListener('click', (e) => {
        if (e.target.closest('.btn-row-fav') || e.target.closest('.btn-track-menu')) return;
        this.loadTrack(originalIdx, true);
        this.switchTab('view-player');
      });

      // Quick favorite
      const favBtn = row.querySelector('.btn-row-fav');
      if (favBtn) {
        favBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          this.toggleFavoriteTrack(track);
        });
      }

      // Track menu
      const menuBtn = row.querySelector('.btn-track-menu');
      if (menuBtn) {
        menuBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          this.openTrackMenu(track);
        });
      }

      listEl.appendChild(row);
    });
  }

  cyclePlaybackSpeed() {
    const speeds = [1.0, 1.25, 1.5, 2.0, 0.75];
    const curIdx = speeds.indexOf(this.playbackRate);
    const nextSpeed = speeds[(curIdx + 1) % speeds.length];
    this.playbackRate = nextSpeed;
    if (this.audio) {
      try { this.audio.playbackRate = this.playbackRate; } catch (e) {}
    }
    const speedBadge = document.getElementById('speed-badge');
    if (speedBadge) speedBadge.textContent = `${this.playbackRate}x`;
    this.showToast(`Скорость: ${this.playbackRate}x`);
    this.saveState();
  }

  seekRelative(seconds) {
    const current = this.playlist[this.currentIndex];
    if (current && current.isYt && !current.isLocal && !current.blobUrl) {
      if (this.ytPlayer && typeof this.ytPlayer.getCurrentTime === 'function') {
        const cur = this.ytPlayer.getCurrentTime() || 0;
        this.ytPlayer.seekTo(Math.max(0, cur + seconds), true);
        this.showToast(`${seconds > 0 ? '+' : ''}${seconds} сек`);
      }
      return;
    }
    if (this.audio && this.audio.duration) {
      this.audio.currentTime = Math.max(0, Math.min(this.audio.duration, this.audio.currentTime + seconds));
      this.showToast(`${seconds > 0 ? '+' : ''}${seconds} сек`);
    }
  }

  openTrackMenu(track) {
    this.selectedTrackForMenu = track;
    const modal = document.getElementById('modal-track-menu');
    const titleEl = document.getElementById('track-menu-title');
    const favText = document.getElementById('menu-fav-text');
    const favIcon = document.getElementById('menu-fav-icon');
    const btnRemoveFromPl = document.getElementById('menu-btn-remove-from-pl');

    if (titleEl) titleEl.textContent = track.title;
    const isFav = this.favorites.has(track.id);
    if (favText) favText.textContent = isFav ? 'Удалить из избранного' : 'В избранное';
    if (favIcon) favIcon.textContent = isFav ? '💔' : '💖';

    if (btnRemoveFromPl) {
      const isCustomActive = this.activePlaylistFilter && this.activePlaylistFilter.startsWith('pl-');
      btnRemoveFromPl.classList.toggle('hidden', !isCustomActive);
    }

    if (modal) modal.classList.remove('hidden');
  }

  toggleFavoriteTrack(track) {
    if (!track) return;
    if (this.favorites.has(track.id)) {
      this.favorites.delete(track.id);
      this.showToast(`Удалено из избранного: ${track.title}`);
    } else {
      this.favorites.add(track.id);
      this.showToast(`💖 Добавлено в избранное: ${track.title}`);
    }
    if (this.currentIndex >= 0 && this.playlist[this.currentIndex] && this.playlist[this.currentIndex].id === track.id) {
      this.updateFavoriteButton(this.favorites.has(track.id));
    }
    this.saveState();
    this.saveFavoritesToStorage();
    this.renderPlaylistUI();
  }

  openAddToPlaylistModal(track) {
    this.selectedTrackForMenu = track;
    const menuModal = document.getElementById('modal-track-menu');
    if (menuModal) menuModal.classList.add('hidden');

    const modal = document.getElementById('modal-add-to-playlist');
    const listEl = document.getElementById('user-playlists-list');
    if (!modal || !listEl) return;

    if (!this.customPlaylists || this.customPlaylists.length === 0) {
      listEl.innerHTML = '<div style="color:var(--text-muted);text-align:center;padding:12px;font-size:12px;">Нет созданных плейлистов.<br>Нажмите "Создать новый плейлист" выше.</div>';
    } else {
      listEl.innerHTML = '';
      this.customPlaylists.forEach(pl => {
        const hasTrack = (pl.trackIds || []).includes(track.id);
        const item = document.createElement('div');
        item.className = 'sheet-pl-item';
        item.innerHTML = `
          <span>${pl.icon || '🎵'} ${pl.name}</span>
          <span style="color:${hasTrack ? 'var(--accent-cyan)' : 'var(--text-muted)'};font-weight:700;">
            ${hasTrack ? '✓ В плейлисте' : '+ Добавить'}
          </span>
        `;
        item.addEventListener('click', () => {
          if (!pl.trackIds) pl.trackIds = [];
          if (hasTrack) {
            pl.trackIds = pl.trackIds.filter(id => id !== track.id);
            this.showToast(`Удалено из "${pl.name}"`);
          } else {
            pl.trackIds.push(track.id);
            this.showToast(`Добавлено в "${pl.name}"`);
          }
          this.saveState();
          this.openAddToPlaylistModal(track);
          this.renderPlaylistsChips();
        });
        listEl.appendChild(item);
      });
    }

    modal.classList.remove('hidden');
  }

  createCustomPlaylist(name, emoji = '🎵') {
    name = (name || '').trim();
    if (!name) {
      this.showToast('Введите название плейлиста');
      return;
    }
    const newPl = {
      id: 'pl-' + Date.now() + '-' + Math.random().toString(36).substring(2, 6),
      name: name,
      icon: emoji || '🎵',
      trackIds: []
    };
    if (this.selectedTrackForMenu) {
      newPl.trackIds.push(this.selectedTrackForMenu.id);
    }
    this.customPlaylists.push(newPl);
    this.activePlaylistFilter = newPl.id;

    const createModal = document.getElementById('modal-create-playlist');
    const addModal = document.getElementById('modal-add-to-playlist');
    if (createModal) createModal.classList.add('hidden');
    if (addModal) addModal.classList.add('hidden');

    this.saveState();
    this.renderPlaylistsChips();
    this.renderPlaylistUI();
    this.showToast(`Плейлист "${name}" создан!`);
  }

  deleteActivePlaylist() {
    if (!this.activePlaylistFilter || !this.activePlaylistFilter.startsWith('pl-')) return;
    const pl = this.customPlaylists.find(p => p.id === this.activePlaylistFilter);
    const plName = pl ? pl.name : 'плейлист';
    this.customPlaylists = this.customPlaylists.filter(p => p.id !== this.activePlaylistFilter);
    this.activePlaylistFilter = 'all';
    this.saveState();
    this.renderPlaylistsChips();
    this.renderPlaylistUI();
    this.showToast(`Плейлист "${plName}" удален`);
  }

  removeTrackFromActivePlaylist(track) {
    if (!track || !this.activePlaylistFilter || !this.activePlaylistFilter.startsWith('pl-')) return;
    const pl = this.customPlaylists.find(p => p.id === this.activePlaylistFilter);
    if (pl && Array.isArray(pl.trackIds)) {
      pl.trackIds = pl.trackIds.filter(id => id !== track.id);
      this.saveState();
      this.renderPlaylistUI();
      this.showToast(`Удалено из плейлиста: ${track.title}`);
    }
    const modal = document.getElementById('modal-track-menu');
    if (modal) modal.classList.add('hidden');
  }

  playAllInActivePlaylist() {
    const tracks = this.getFilteredTracks();
    if (tracks.length === 0) {
      this.showToast('Плейлист пуст');
      return;
    }
    const originalIdx = this.playlist.indexOf(tracks[0]);
    if (originalIdx !== -1) {
      this.loadTrack(originalIdx, true);
      this.switchTab('view-player');
      this.showToast(`▶ Воспроизведение: ${tracks[0].title}`);
    }
  }

  setPreset(presetKey) {
    const preset = this.presets[presetKey];
    if (!preset) return;

    const ids = ['band-60', 'band-250', 'band-1000', 'band-4000', 'band-14000'];
    preset.forEach((val, i) => {
      const slider = document.getElementById(ids[i]);
      const label = document.getElementById(`val-${ids[i]}`);
      if (slider) slider.value = val;
      if (label) label.textContent = `${val > 0 ? '+' : ''}${val}dB`;
      if (this.eqFilters[i]) this.eqFilters[i].gain.value = val;
    });

    document.querySelectorAll('.eq-preset-chip').forEach((c) => {
      c.classList.toggle('active', c.getAttribute('data-preset') === presetKey);
    });

    this.showToast(`Эквалайзер: ${presetKey.toUpperCase()}`);
  }

  setSleepTimer(minutes) {
    if (this.sleepTimerInterval) {
      clearInterval(this.sleepTimerInterval);
      this.sleepTimerInterval = null;
    }

    const badge = document.getElementById('timer-badge');
    if (minutes <= 0) {
      this.sleepTimerEnd = null;
      badge.classList.add('hidden');
      this.showToast('Таймер сна отключен');
      return;
    }

    this.sleepTimerEnd = Date.now() + minutes * 60 * 1000;
    badge.textContent = `${minutes}m`;
    badge.classList.remove('hidden');
    this.showToast(`Таймер сна: ${minutes} мин.`);

    this.sleepTimerInterval = setInterval(() => {
      const remaining = Math.max(0, this.sleepTimerEnd - Date.now());
      if (remaining <= 0) {
        clearInterval(this.sleepTimerInterval);
        this.pauseAudio();
        badge.classList.add('hidden');
        this.showToast('Таймер сна: музыка остановлена');
      } else {
        const minsLeft = Math.ceil(remaining / 60000);
        badge.textContent = `${minsLeft}m`;
      }
    }, 10000);
  }

  switchTab(tabId) {
    document.querySelectorAll('.tab-view').forEach((view) => {
      view.classList.remove('active');
    });
    document.querySelectorAll('.nav-tab').forEach((tab) => {
      tab.classList.remove('active');
    });

    const targetView = document.getElementById(tabId);
    if (targetView) targetView.classList.add('active');

    const navId = tabId.replace('view-', 'nav-');
    const targetNav = document.getElementById(navId);
    if (targetNav) targetNav.classList.add('active');
  }

  updateFavoriteButton(isFav) {
    const iconOutline = document.getElementById('icon-fav-outline');
    const iconFilled = document.getElementById('icon-fav-filled');
    if (isFav) {
      iconOutline.classList.add('hidden');
      iconFilled.classList.remove('hidden');
    } else {
      iconOutline.classList.remove('hidden');
      iconFilled.classList.add('hidden');
    }
  }

  toggleFavorite() {
    const track = this.playlist[this.currentIndex];
    if (!track) return;
    if (this.favorites.has(track.id)) {
      this.favorites.delete(track.id);
      this.updateFavoriteButton(false);
      this.showToast('Удалено из избранного');
    } else {
      this.favorites.add(track.id);
      this.updateFavoriteButton(true);
      this.showToast('Добавлено в избранное ❤️');
    }
    this.saveFavoritesToStorage();
  }

  saveFavoritesToStorage() {
    try {
      localStorage.setItem('apex_favorites', JSON.stringify(Array.from(this.favorites)));
    } catch (e) {}
  }

  loadFavoritesFromStorage() {
    try {
      const saved = localStorage.getItem('apex_favorites');
      if (saved) {
        this.favorites = new Set(JSON.parse(saved));
      }
    } catch (e) {}
  }

  handleBackAction() {
    const modals = [
      'modal-create-playlist',
      'modal-track-menu',
      'modal-add-to-playlist',
      'modal-sort-menu',
      'modal-sleep-timer',
      'modal-server-ip'
    ];
    for (const mId of modals) {
      const el = document.getElementById(mId);
      if (el && !el.classList.contains('hidden')) {
        el.classList.add('hidden');
        return true;
      }
    }
    const currentActiveTab = document.querySelector('.tab-view.active');
    if (currentActiveTab && currentActiveTab.id !== 'view-player') {
      this.switchTab('view-player');
      return true;
    }
    return false;
  }

  showToast(msg) {
    const toast = document.getElementById('toast-msg');
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.remove('hidden');
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => {
      toast.classList.add('hidden');
    }, 2400);
  }

  renderVisualizer() {
    requestAnimationFrame(() => this.renderVisualizer());
    if (!this.ctx || !this.canvas) return;

    const w = this.canvas.width;
    const h = this.canvas.height;
    this.ctx.clearRect(0, 0, w, h);

    if (!this.analyser) return;

    const bufferLen = this.analyser.frequencyBinCount;
    const data = new Uint8Array(bufferLen);

    if (this.isPlaying) {
      if (this.visMode === 'wave') {
        this.analyser.getByteTimeDomainData(data);
        this.ctx.lineWidth = 2.5;
        this.ctx.strokeStyle = '#00f2fe';
        this.ctx.beginPath();
        const sliceWidth = w / bufferLen;
        let x = 0;
        for (let i = 0; i < bufferLen; i++) {
          const v = data[i] / 128.0;
          const y = (v * h) / 2;
          if (i === 0) this.ctx.moveTo(x, y);
          else this.ctx.lineTo(x, y);
          x += sliceWidth;
        }
        this.ctx.stroke();
      } else if (this.visMode === 'radial') {
        this.analyser.getByteFrequencyData(data);
        const centerX = w / 2;
        const centerY = h * 0.38;
        const radius = 130;
        const bars = 48;
        for (let i = 0; i < bars; i++) {
          const rad = (i * 2 * Math.PI) / bars;
          const val = (data[i * 2] / 255) * 45;
          const x1 = centerX + Math.cos(rad) * radius;
          const y1 = centerY + Math.sin(rad) * radius;
          const x2 = centerX + Math.cos(rad) * (radius + val);
          const y2 = centerY + Math.sin(rad) * (radius + val);

          this.ctx.strokeStyle = i % 2 === 0 ? '#00f2fe' : '#7f00ff';
          this.ctx.lineWidth = 3;
          this.ctx.beginPath();
          this.ctx.moveTo(x1, y1);
          this.ctx.lineTo(x2, y2);
          this.ctx.stroke();
        }
      } else {
        this.analyser.getByteFrequencyData(data);
        const bars = 44;
        const barW = w / bars - 3;
        for (let i = 0; i < bars; i++) {
          const val = (data[i * 2] / 255) * 110;
          const grad = this.ctx.createLinearGradient(0, h, 0, h - val);
          grad.addColorStop(0, 'rgba(127, 0, 255, 0.15)');
          grad.addColorStop(0.5, 'rgba(0, 242, 254, 0.6)');
          grad.addColorStop(1, '#00f2fe');

          this.ctx.fillStyle = grad;
          this.ctx.fillRect(i * (barW + 3), h - val - 68, barW, val);
        }
      }
    }
  }

  initEventListeners() {
    document.getElementById('btn-play-pause').addEventListener('click', () => this.togglePlayPause());
    document.getElementById('btn-next').addEventListener('click', () => this.playNext());
    document.getElementById('btn-prev').addEventListener('click', () => this.playPrev());

    const btnSpeed = document.getElementById('btn-speed');
    if (btnSpeed) {
      btnSpeed.addEventListener('click', () => this.cyclePlaybackSpeed());
    }

    const btnReplay10 = document.getElementById('btn-replay-10');
    if (btnReplay10) {
      btnReplay10.addEventListener('click', () => this.seekRelative(-10));
    }

    const btnForward10 = document.getElementById('btn-forward-10');
    if (btnForward10) {
      btnForward10.addEventListener('click', () => this.seekRelative(10));
    }

    const btnShuffle = document.getElementById('btn-shuffle');
    btnShuffle.addEventListener('click', () => {
      this.isShuffle = !this.isShuffle;
      btnShuffle.classList.toggle('active', this.isShuffle);
      this.showToast(this.isShuffle ? 'Случайный порядок вкл' : 'Случайный порядок выкл');
    });

    const btnRepeat = document.getElementById('btn-repeat');
    const repeatBadge = document.getElementById('repeat-badge');
    btnRepeat.addEventListener('click', () => {
      this.repeatMode = (this.repeatMode + 1) % 3;
      if (this.repeatMode === 0) {
        btnRepeat.classList.remove('active');
        repeatBadge.classList.add('hidden');
        this.audio.loop = false;
        this.showToast('Повтор выключен');
      } else if (this.repeatMode === 1) {
        btnRepeat.classList.add('active');
        repeatBadge.classList.add('hidden');
        this.audio.loop = false;
        this.showToast('Повтор всего плейлиста');
      } else {
        btnRepeat.classList.add('active');
        repeatBadge.classList.remove('hidden');
        this.audio.loop = true;
        this.showToast('Повтор текущего трека');
      }
    });

    document.getElementById('btn-favorite').addEventListener('click', () => this.toggleFavorite());

    const seekBar = document.getElementById('seek-bar');
    const progressFill = document.getElementById('progress-fill');
    const timeCurrent = document.getElementById('time-current');
    const timeTotal = document.getElementById('time-total');

    const formatSecs = (sec) => {
      if (isNaN(sec)) return '0:00';
      const m = Math.floor(sec / 60);
      const s = Math.floor(sec % 60);
      return `${m}:${s < 10 ? '0' : ''}${s}`;
    };

    const vinylDisc = document.getElementById('vinyl-disc');
    if (vinylDisc) {
      vinylDisc.addEventListener('click', () => this.togglePlayPause());
    }

    this.audio.addEventListener('error', (e) => {
      console.warn('Audio error:', this.audio.error);
    });

    let lastSyncTime = 0;
    this.audio.addEventListener('timeupdate', () => {
      if (!this.audio.duration) return;
      const pct = (this.audio.currentTime / this.audio.duration) * 100;
      seekBar.value = pct;
      progressFill.style.width = pct + '%';
      timeCurrent.textContent = formatSecs(this.audio.currentTime);
      timeTotal.textContent = formatSecs(this.audio.duration);

      const now = Date.now();
      if (now - lastSyncTime > 10000) {
        lastSyncTime = now;
        this.syncPlaybackWithNative();
        this.saveState();
      }
    });

    this.audio.addEventListener('play', () => {
      this.isPlaying = true;
      this.updatePlayStateUI(true);
      this.syncPlaybackWithNative(true);
      this.saveState();
    });

    this.audio.addEventListener('pause', () => {
      this.isPlaying = false;
      this.updatePlayStateUI(false);
      this.syncPlaybackWithNative(false);
      this.saveState();
    });

    window.addEventListener('beforeunload', () => this.saveState());
    document.addEventListener('visibilitychange', () => { if (document.hidden) this.saveState(); });
    window.addEventListener('pagehide', () => this.saveState());

    seekBar.addEventListener('input', (e) => {
      const current = this.playlist[this.currentIndex];
      const val = parseFloat(e.target.value);
      if (current && current.isYt) {
        if (this.ytPlayer && typeof this.ytPlayer.getDuration === 'function') {
          const dur = this.ytPlayer.getDuration() || 0;
          this.ytPlayer.seekTo((val / 100) * dur, true);
        }
      } else if (this.audio.duration) {
        this.audio.currentTime = (val / 100) * this.audio.duration;
      }
    });

    this.audio.addEventListener('ended', () => {
      if (this.repeatMode === 2) {
        this.audio.currentTime = 0;
        this.playAudio();
      } else {
        this.playNext();
      }
    });

    // Navigation Tabs
    document.getElementById('nav-player').addEventListener('click', () => this.switchTab('view-player'));
    document.getElementById('nav-library').addEventListener('click', () => this.switchTab('view-library'));
    document.getElementById('nav-youtube').addEventListener('click', () => {
      this.switchTab('view-youtube');
      this.updateOnlineStatus();
    });
    document.getElementById('nav-equalizer').addEventListener('click', () => this.switchTab('view-equalizer'));
    document.getElementById('btn-eq-toggle').addEventListener('click', () => this.switchTab('view-equalizer'));

    // YouTube Tab Controls
    const ytQuery = document.getElementById('yt-query');
    const btnYtSearch = document.getElementById('btn-yt-search');
    const btnYtPaste = document.getElementById('btn-yt-paste');

    btnYtSearch.addEventListener('click', () => {
      this.handleYouTubeSearch(ytQuery.value);
    });

    ytQuery.addEventListener('keypress', (e) => {
      if (e.key === 'Enter') {
        this.handleYouTubeSearch(ytQuery.value);
      }
    });

    btnYtPaste.addEventListener('click', async () => {
      try {
        const text = await navigator.clipboard.readText();
        if (text) {
          ytQuery.value = text;
          this.handleYouTubeSearch(text);
        }
      } catch (err) {
        this.showToast('Вставьте ссылку вручную в поле');
      }
    });

    document.querySelectorAll('.yt-tag-pill').forEach((pill) => {
      pill.addEventListener('click', (e) => {
        const q = e.currentTarget.getAttribute('data-q');
        ytQuery.value = q;
        this.handleYouTubeSearch(q);
      });
    });

    document.getElementById('btn-yt-play').addEventListener('click', () => this.playCurrentYtTrack());
    document.getElementById('btn-yt-download').addEventListener('click', () => this.downloadCurrentYtTrack());
    document.getElementById('btn-yt-add-playlist').addEventListener('click', () => this.addCurrentYtToPlaylist());

    // Sleep Timer Modal
    const timerModal = document.getElementById('modal-sleep-timer');
    document.getElementById('btn-sleep-timer').addEventListener('click', () => {
      timerModal.classList.remove('hidden');
    });
    document.getElementById('close-timer-modal').addEventListener('click', () => {
      timerModal.classList.add('hidden');
    });

    document.querySelectorAll('.timer-opt').forEach((opt) => {
      opt.addEventListener('click', (e) => {
        const mins = parseInt(e.currentTarget.getAttribute('data-minutes'), 10);
        this.setSleepTimer(mins);
        timerModal.classList.add('hidden');
      });
    });

    // File Input
    const fileInput = document.getElementById('music-file-input');
    fileInput.addEventListener('change', (e) => {
      this.handleFilesAdded(e.target.files);
    });

    // Search input with dynamic filtering
    const searchInput = document.getElementById('search-input');
    const btnClearSearch = document.getElementById('btn-clear-search');
    if (searchInput) {
      searchInput.addEventListener('input', (e) => {
        this.searchQuery = e.target.value;
        if (btnClearSearch) {
          btnClearSearch.classList.toggle('hidden', !this.searchQuery);
        }
        this.renderPlaylistUI();
      });
    }
    if (btnClearSearch) {
      btnClearSearch.addEventListener('click', () => {
        if (searchInput) searchInput.value = '';
        this.searchQuery = '';
        btnClearSearch.classList.add('hidden');
        this.renderPlaylistUI();
      });
    }

    // Playlist Toolbar Buttons
    const btnPlayAll = document.getElementById('btn-playlist-play-all');
    if (btnPlayAll) {
      btnPlayAll.addEventListener('click', () => this.playAllInActivePlaylist());
    }
    const btnSort = document.getElementById('btn-sort-tracks');
    if (btnSort) {
      btnSort.addEventListener('click', () => {
        const modal = document.getElementById('modal-sort-menu');
        if (modal) modal.classList.remove('hidden');
      });
    }
    const btnDelPl = document.getElementById('btn-delete-active-playlist');
    if (btnDelPl) {
      btnDelPl.addEventListener('click', () => {
        if (confirm('Удалить этот плейлист? Сами аудиофайлы останутся в медиатеке.')) {
          this.deleteActivePlaylist();
        }
      });
    }

    // Modal Create Playlist
    const modalCreatePl = document.getElementById('modal-create-playlist');
    const closeCreatePl = document.getElementById('close-create-playlist-modal');
    if (closeCreatePl) {
      closeCreatePl.addEventListener('click', () => modalCreatePl.classList.add('hidden'));
    }
    document.querySelectorAll('.emoji-opt').forEach((btn) => {
      btn.addEventListener('click', (e) => {
        document.querySelectorAll('.emoji-opt').forEach(b => b.classList.remove('active'));
        e.currentTarget.classList.add('active');
        this.activeEmoji = e.currentTarget.getAttribute('data-emoji') || '🎵';
        const display = document.getElementById('chosen-playlist-emoji');
        if (display) display.textContent = this.activeEmoji;
      });
    });
    const btnConfirmPl = document.getElementById('btn-confirm-create-playlist');
    if (btnConfirmPl) {
      btnConfirmPl.addEventListener('click', () => {
        const input = document.getElementById('input-playlist-name');
        const name = input ? input.value : '';
        this.createCustomPlaylist(name, this.activeEmoji || '🎵');
      });
    }
    const inputPlName = document.getElementById('input-playlist-name');
    if (inputPlName) {
      inputPlName.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
          this.createCustomPlaylist(inputPlName.value, this.activeEmoji || '🎵');
        }
      });
    }

    // Modal Track Menu
    const modalTrackMenu = document.getElementById('modal-track-menu');
    const closeTrackMenu = document.getElementById('close-track-menu-modal');
    if (closeTrackMenu) {
      closeTrackMenu.addEventListener('click', () => modalTrackMenu.classList.add('hidden'));
    }
    const menuBtnPlayNext = document.getElementById('menu-btn-play-next');
    if (menuBtnPlayNext) {
      menuBtnPlayNext.addEventListener('click', () => {
        if (this.selectedTrackForMenu) {
          const t = this.selectedTrackForMenu;
          const curIdx = this.currentIndex;
          const oldIdx = this.playlist.indexOf(t);
          if (oldIdx !== -1) {
            this.playlist.splice(oldIdx, 1);
            this.playlist.splice(curIdx + 1, 0, t);
            this.renderPlaylistUI();
            this.saveState();
            this.showToast(`⏭️ Следующим: ${t.title}`);
          }
        }
        modalTrackMenu.classList.add('hidden');
      });
    }
    const menuBtnToggleFav = document.getElementById('menu-btn-toggle-fav');
    if (menuBtnToggleFav) {
      menuBtnToggleFav.addEventListener('click', () => {
        if (this.selectedTrackForMenu) {
          this.toggleFavoriteTrack(this.selectedTrackForMenu);
        }
        modalTrackMenu.classList.add('hidden');
      });
    }
    const menuBtnAddToPl = document.getElementById('menu-btn-add-to-pl');
    if (menuBtnAddToPl) {
      menuBtnAddToPl.addEventListener('click', () => {
        if (this.selectedTrackForMenu) {
          this.openAddToPlaylistModal(this.selectedTrackForMenu);
        }
      });
    }
    const menuBtnRemoveFromPl = document.getElementById('menu-btn-remove-from-pl');
    if (menuBtnRemoveFromPl) {
      menuBtnRemoveFromPl.addEventListener('click', () => {
        if (this.selectedTrackForMenu) {
          this.removeTrackFromActivePlaylist(this.selectedTrackForMenu);
        }
      });
    }

    // Modal Add to Playlist
    const modalAddToPl = document.getElementById('modal-add-to-playlist');
    const closeAddToPl = document.getElementById('close-add-to-pl-modal');
    if (closeAddToPl) {
      closeAddToPl.addEventListener('click', () => modalAddToPl.classList.add('hidden'));
    }
    const btnQuickCreatePl = document.getElementById('btn-quick-create-pl');
    if (btnQuickCreatePl) {
      btnQuickCreatePl.addEventListener('click', () => {
        modalAddToPl.classList.add('hidden');
        const modalCreate = document.getElementById('modal-create-playlist');
        const input = document.getElementById('input-playlist-name');
        if (modalCreate) modalCreate.classList.remove('hidden');
        if (input) {
          input.value = '';
          input.focus();
        }
      });
    }

    // Modal Sort Menu
    const modalSort = document.getElementById('modal-sort-menu');
    const closeSort = document.getElementById('close-sort-modal');
    if (closeSort) {
      closeSort.addEventListener('click', () => modalSort.classList.add('hidden'));
    }
    document.querySelectorAll('.sort-opt').forEach((btn) => {
      btn.addEventListener('click', (e) => {
        const mode = e.currentTarget.getAttribute('data-sort');
        this.sortMode = mode;
        this.renderPlaylistUI();
        this.saveState();
        modalSort.classList.add('hidden');
        const labels = {
          'default': 'По порядку добавления',
          'title-asc': 'По названию (А-Я)',
          'artist-asc': 'По исполнителю'
        };
        this.showToast(`Сортировка: ${labels[mode] || mode}`);
      });
    });

    document.querySelectorAll('.eq-preset-chip').forEach((chip) => {
      chip.addEventListener('click', (e) => {
        const presetKey = e.currentTarget.getAttribute('data-preset');
        this.setPreset(presetKey);
      });
    });

    const bandIds = ['band-60', 'band-250', 'band-1000', 'band-4000', 'band-14000'];
    bandIds.forEach((id, index) => {
      const slider = document.getElementById(id);
      const valLabel = document.getElementById(`val-${id}`);
      slider.addEventListener('input', (e) => {
        const val = parseFloat(e.target.value);
        valLabel.textContent = `${val > 0 ? '+' : ''}${val}dB`;
        if (this.eqFilters[index]) {
          this.eqFilters[index].gain.value = val;
        }
      });
    });

    document.querySelectorAll('.vis-btn').forEach((btn) => {
      btn.addEventListener('click', (e) => {
        document.querySelectorAll('.vis-btn').forEach((b) => b.classList.remove('active'));
        e.currentTarget.classList.add('active');
        this.visMode = e.currentTarget.getAttribute('data-mode');
        this.showToast(`Визуализатор: ${e.currentTarget.textContent}`);
      });
    });
  }
}

window.addEventListener('DOMContentLoaded', () => {
  new ApexMusicPlayer();
});
