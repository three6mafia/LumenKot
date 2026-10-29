#!/usr/bin/env python3
"""Local synthetic fixtures for emulator smoke tests; never used by the shipped app.
Generate /tmp/lumenkot-smoke.mp4 with ffmpeg; run python3 tools/smoke_server.py.
Point a debug emulator's custom mirror to http://127.0.0.1:8765, official mode off.
"""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import json
import urllib.parse

HOST = 'http://127.0.0.1:8765'
CARD = f'''<div class="b-content__inline_item" data-id="42"><div class="b-content__inline_item-cover"><img src="{HOST}/poster.png"><span class="info">Тестовые данные</span></div><div class="b-content__inline_item-link"><a href="/films/42-native.html">Проверка Kotlin</a><div>2026 · Smoke test</div></div></div>'''
FILM = '''<meta property="og:type" content="video.movie"><div class="b-post__title"><h1>Проверка Kotlin</h1></div><div class="b-post__origtitle">Native playback fixture</div><div id="user-favorites-holder" data-post_id="42"></div><div class="b-post__description_text">Синтетические данные для проверки нативного интерфейса и плеера.</div><li class="b-translator__item active" data-translator_id="1" title="Тестовый перевод" data-camrip="0" data-ad="0" data-director="0"></li><div class="hd-label-row"><input value="1"><label>Избранное</label></div>'''

SEASONS = '<li class="b-simple_season__item" data-tab_id="1">Сезон 1</li>'
EPISODES = '<div id="simple-episodes-list-1"><li class="b-simple_episode__item" data-episode_id="1">Серия 1</li></div>'

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        # Deliberately omit query strings and POST bodies.
        print(self.command, urllib.parse.urlsplit(self.path).path, flush=True)
    def send(self, data, content='text/html; charset=utf-8', cookie=False):
        if isinstance(data, dict):
            data = json.dumps(data, ensure_ascii=False).encode(); content = 'application/json'
        elif isinstance(data, str): data = data.encode()
        self.send_response(200)
        self.send_header('Content-Type', content)
        self.send_header('Content-Length', str(len(data)))
        if cookie:
            self.send_header('Set-Cookie', 'dle_user_id=1; Path=/; HttpOnly')
            self.send_header('Set-Cookie', 'dle_password=fixture; Path=/; HttpOnly')
        self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == '/video.mp4': self.send(Path('/tmp/lumenkot-smoke.mp4').read_bytes(), 'video/mp4')
        elif path == '/poster.png': self.send((Path(__file__).resolve().parents[1]/'app/src/main/res/drawable/app_icon.png').read_bytes(), 'image/png')
        elif path.startswith('/series/') and path.endswith('.html'): self.send(FILM.replace('video.movie', 'video.tv_show')+SEASONS+EPISODES)
        elif path.endswith('.html'): self.send(FILM)
        elif path.startswith('/ajax/get_comments'):
            self.send({'comments': '<div class="comments-tree-item" data-id="1"><b class="name">Тест</b><span class="date">Сегодня</span><div class="text"><div>Нативные комментарии</div></div></div>', 'navigation': ''})
        elif path.startswith('/favorites') and '/page/' not in path: self.send('<div class="b-favorites_content__cats_list_item" data-cat_id="1"><b class="name">Избранное</b></div>'+CARD)
        elif path.startswith('/user/'): self.send('<title>Fixture user</title><input id="email" value="fixture@example.invalid">')
        elif path == '/continue': self.send('<div class="b-videosaves__list_item"><div class="title"><a href="/films/42-native.html">Проверка Kotlin</a></div></div>')
        else: self.send(CARD)
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        path = urllib.parse.urlsplit(self.path).path.rstrip('/')
        if path == '/ajax/get_cdn_series' and b'get_episodes' in body: self.send({'seasons': SEASONS, 'episodes': EPISODES})
        elif path == '/ajax/get_cdn_series': self.send({'success': True, 'url': f'[720p]{HOST}/video.mp4', 'subtitle': ''})
        elif path == '/ajax/login': self.send({'success': True}, cookie=True)
        elif path.endswith('get_newest_slider_content.php'): self.send(CARD)
        else: self.send({'success': True, 'count': 1, 'num': 10})

if __name__ == '__main__': ThreadingHTTPServer(('127.0.0.1', 8765), Handler).serve_forever()
