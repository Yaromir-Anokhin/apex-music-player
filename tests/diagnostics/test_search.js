async function parseYtMusicSearch() {
  const body = {
    context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'ru', gl: 'RU' } },
    query: 'Linkin Park Numb'
  };
  const res = await fetch('https://music.youtube.com/youtubei/v1/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const data = await res.json();
  const text = JSON.stringify(data);
  const regex = /"videoId":"([\w-]{11})"/g;
  let match;
  const ids = [];
  while ((match = regex.exec(text)) !== null && ids.length < 5) {
    if (!ids.includes(match[1])) ids.push(match[1]);
  }
  console.log('Video IDs found directly from YouTube Music:', ids);
}
parseYtMusicSearch();
