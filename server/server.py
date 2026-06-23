#!/usr/bin/env python3
"""
Serwer HTTP do streamowania i pobierania plików multimedialnych.

Uruchomienie:
    python server.py [KATALOG] [PORT] [OPCJE]

Przykłady:
    python server.py                          # bieżący katalog, port 8080
    python server.py D:\\Filmy                 # katalog D:\\Filmy, port 8080
    python server.py D:\\Filmy 9090            # katalog D:\\Filmy, port 9090
    python server.py D:\\Filmy 8080 --download # tryb chunkowany (domyślnie 500MB na żądanie)
    python server.py D:\\Filmy 8080 --down1gb  # tryb chunkowany (min. 1GB na żądanie)
    python server.py D:\\Filmy 8080 --down     # ciągłe ładowanie całego pliku (jak pobieranie)

Opcje:
    --download  Tryb chunkowany: serwer wysyła fragmenty (domyślnie min. 500MB na żądanie).
                Klient musi requestować kolejne fragmenty przy przewijaniu / gdy bufor spadnie.
    --down1gb   Jak --download, ale zawsze min. 1GB na żądanie (na start też 1GB),
                żeby utrzymywać ~1GB "do przodu".
    --down      Ciągłe ładowanie: serwer wysyła cały plik od razu. Wideo ładuje się cały czas
                (gra, pauza, stop) aż do końca — bez dzielenia na 100MB.

Endpointy:
    GET /                       → przeglądarka plików (HTML) — pobieranie z przeglądarki
    GET /api/files              → lista plików w katalogu głównym (JSON)
    GET /api/files?path=subdir  → lista plików w podkatalogu (JSON)
    GET /media/<ścieżka>        → streaming pliku z obsługą Range (przewijanie)
    GET /download/<ścieżka>     → pobieranie pliku (Content-Disposition: attachment)

Otwórz http://IP:PORT w przeglądarce na dowolnym urządzeniu, żeby przeglądać
i pobierać pliki bez instalowania aplikacji.
"""

import http.server
import json
import os
import sys
import time
import urllib.parse
import mimetypes
import socket

# Windows (często cp1250/cp1252): nie wywalaj się na znaki spoza strony kodowej.
try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ============================================================================
# Konfiguracja
# ============================================================================

# Tryb progresywnego pobierania (--download/--down1gb): max bajtów na jedno żądanie
# Klient pobiera fragment, odtwarza i gdy bufor spadnie — requestuje kolejny fragment.
PROGRESSIVE_CHUNK_SIZE_DEFAULT = 500 * 1024 * 1024  # 500 MB
PROGRESSIVE_CHUNK_SIZE_1GB = 1024 * 1024 * 1024     # 1 GB

# Rozmiar porcji wysyłanych do klienta (większy = mniej syscalli = szybszy transfer)
# 4 MB - dobry kompromis: znacznie mniej syscalli niż 1 MB, a wciąż mała latencja
# pierwszego bajtu przy seekach (Range requests).
SEND_CHUNK_SIZE = 4 * 1024 * 1024  # 4 MB

MEDIA_EXTENSIONS = {
    '.mp3', '.mp4', '.avi', '.mkv', '.flv', '.wmv',
    '.mov', '.m4v', '.flac', '.wav', '.ogg', '.aac',
    '.wma', '.m4a', '.webm', '.3gp', '.ts', '.m2ts',
    '.m3u8', '.jpg', '.jpeg', '.png', '.gif', '.bmp',
    '.webp', '.srt', '.sub', '.ass', '.txt', '.nfo'
}


class StreamingHandler(http.server.BaseHTTPRequestHandler):
    """Handler HTTP z obsługą API plików i streamingu z Range."""

    root_dir = '.'
    progressive_download = False  # tryb --download: limit 100MB na żądanie
    continuous_download = False   # tryb --down: ciągłe ładowanie + logi prędkości
    progressive_chunk_size = PROGRESSIVE_CHUNK_SIZE_DEFAULT  # --download/--down1gb

    # Wyłącza algorytm Nagle'a — bez tego TCP może buforować małe pakiety
    # przed wysłaniem, co na Wi-Fi dodaje losowe opóźnienia rzędu dziesiątek-
    # -setek ms. Przy streamingu wideo to się czuje jako mikro-zacięcia.
    disable_nagle_algorithm = True

    def log_message(self, format, *args):
        """Logowanie z lepszym formatem."""
        print(f"[{self.log_date_time_string()}] {args[0]}")

    def _send_file_with_progress(self, file_path, start, content_length, full_path):
        """Wysyła fragment pliku.

        WAŻNE: logowanie progresu NIE odbywa się już w tej samej pętli co wfile.write().
        print()/flush() do konsoli Windows jest zaskakująco wolny i blokujący — wywoływany
        co sekundę w środku pętli wysyłki potrafił wprowadzać mikro-zacięcia w streamie
        wideo (objaw: "ścina losowo w trakcie odtwarzania" niezależnie od prędkości sieci).
        Teraz log drukowany jest tylko na starcie i na końcu transferu, więc gorąca pętla
        robi wyłącznie read() + write() — żadnego I/O na konsolę pomiędzy.
        """
        file_name = os.path.basename(file_path)
        total_file_size = os.path.getsize(full_path)
        total_file_mb = total_file_size / (1024 * 1024)
        bytes_sent = 0
        start_time = time.time()

        with open(full_path, 'rb') as f:
            f.seek(start)
            remaining = content_length
            while remaining > 0:
                chunk_size = min(SEND_CHUNK_SIZE, remaining)
                chunk = f.read(chunk_size)
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    break
                bytes_sent += len(chunk)
                remaining -= len(chunk)

        # Logowanie PO zakończeniu transferu — zero wpływu na streaming w trakcie.
        elapsed = time.time() - start_time
        if elapsed > 0 and bytes_sent > 0:
            speed_avg = bytes_sent / elapsed / (1024 * 1024)
            sent_mb = bytes_sent / (1024 * 1024)
            print(f"  ✅ {file_name}: {sent_mb:.1f}/{total_file_mb:.1f} MB w {elapsed:.1f}s "
                  f"(śr. {speed_avg:.1f} MB/s)")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = urllib.parse.unquote(parsed.path)
        query = urllib.parse.parse_qs(parsed.query)

        if path == '/api/files':
            self.handle_file_list(query)
        elif path.startswith('/media/'):
            self.handle_media_stream(path[7:])
        elif path.startswith('/download/'):
            self.handle_download(path[10:])
        elif path == '/' or path.startswith('/browse'):
            self.handle_browser(query)
        else:
            self.send_error(404, 'Nie znaleziono')

    # ========================================================================
    # /api/files — lista plików
    # ========================================================================

    def handle_file_list(self, query):
        """Zwraca listę plików/katalogów jako JSON."""
        sub_path = query.get('path', [''])[0]
        full_path = os.path.normpath(os.path.join(self.root_dir, sub_path))

        # Bezpieczeństwo: nie wychodź poza root
        if not os.path.abspath(full_path).startswith(os.path.abspath(self.root_dir)):
            self.send_error(403, 'Zabroniony dostęp')
            return

        if not os.path.isdir(full_path):
            self.send_error(404, 'Katalog nie istnieje')
            return

        items = []
        try:
            for entry in sorted(os.listdir(full_path), key=str.lower):
                entry_path = os.path.join(full_path, entry)

                # Pomiń pliki ukryte
                if entry.startswith('.'):
                    continue

                if os.path.isdir(entry_path):
                    items.append({
                        'name': entry,
                        'is_dir': True,
                        'size': 0
                    })
                else:
                    items.append({
                        'name': entry,
                        'is_dir': False,
                        'size': os.path.getsize(entry_path)
                    })
        except PermissionError:
            self.send_error(403, 'Brak uprawnień')
            return

        response = json.dumps(items, ensure_ascii=False)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Content-Length', str(len(response.encode('utf-8'))))
        self.end_headers()
        self.wfile.write(response.encode('utf-8'))

    # ========================================================================
    # /media/<path> — streaming pliku z Range
    # ========================================================================

    def handle_media_stream(self, file_path):
        """Serwuje plik z obsługą HTTP Range (przewijanie w odtwarzaczu)."""
        full_path = os.path.normpath(os.path.join(self.root_dir, file_path))

        # Bezpieczeństwo
        if not os.path.abspath(full_path).startswith(os.path.abspath(self.root_dir)):
            self.send_error(403, 'Zabroniony dostęp')
            return

        if not os.path.isfile(full_path):
            self.send_error(404, 'Plik nie istnieje')
            return

        file_size = os.path.getsize(full_path)
        content_type = mimetypes.guess_type(full_path)[0] or 'application/octet-stream'

        # Obsługa Range header (przewijanie)
        range_header = self.headers.get('Range')

        if range_header:
            # Parsuj Range: bytes=START-END
            try:
                range_str = range_header.replace('bytes=', '')
                parts = range_str.split('-')
                start = int(parts[0]) if parts[0] else 0
                end = int(parts[1]) if parts[1] else file_size - 1
            except (ValueError, IndexError):
                start = 0
                end = file_size - 1

            # Ogranicz
            end = min(end, file_size - 1)

            # Tryb --download/--down1gb: wymuś minimum danych "do przodu" w każdej odpowiedzi
            # (jeśli klient poprosi o mniej, i tak dostanie co najmniej `progressive_chunk_size`).
            if self.progressive_download:
                min_end = start + self.progressive_chunk_size - 1
                end = max(end, min_end)
                end = min(end, file_size - 1)
            content_length = end - start + 1

            self.send_response(206)  # Partial Content
            self.send_header('Content-Range', f'bytes {start}-{end}/{file_size}')
            self.send_header('Content-Length', str(content_length))
            self.send_header('Content-Type', content_type)
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()

            # Wyślij fragment pliku
            self._send_file_with_progress(file_path, start, content_length, full_path)
        else:
            # Pełny plik (bez Range) — w trybie --download/--down1gb wysyłamy tylko pierwszy fragment
            if self.progressive_download:
                start, end = 0, min(file_size - 1, self.progressive_chunk_size - 1)
                content_length = end - start + 1
                self.send_response(206)  # Partial Content
                self.send_header('Content-Range', f'bytes {start}-{end}/{file_size}')
                self.send_header('Content-Length', str(content_length))
                self.send_header('Content-Type', content_type)
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self._send_file_with_progress(file_path, start, content_length, full_path)
            else:
                self.send_response(200)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(file_size))
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self._send_file_with_progress(file_path, 0, file_size, full_path)

    # ========================================================================
    # /download/<path> — pobieranie pliku (attachment)
    # ========================================================================

    def handle_download(self, file_path):
        """Serwuje plik z nagłówkiem Content-Disposition: attachment (pobieranie)."""
        full_path = os.path.normpath(os.path.join(self.root_dir, file_path))

        if not os.path.abspath(full_path).startswith(os.path.abspath(self.root_dir)):
            self.send_error(403, 'Zabroniony dostęp')
            return

        if not os.path.isfile(full_path):
            self.send_error(404, 'Plik nie istnieje')
            return

        file_size = os.path.getsize(full_path)
        file_name = os.path.basename(full_path)
        content_type = mimetypes.guess_type(full_path)[0] or 'application/octet-stream'

        # Content-Disposition: attachment wymusza pobieranie w przeglądarce
        encoded_name = urllib.parse.quote(file_name)

        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(file_size))
        self.send_header('Content-Disposition',
                         f'attachment; filename="{file_name}"; filename*=UTF-8\'\'{encoded_name}')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        print(f"  📥 Pobieranie: {file_name} ({file_size / (1024*1024):.1f} MB)")
        self._send_file_with_progress(file_path, 0, file_size, full_path)

    # ========================================================================
    # / — przeglądarka plików (HTML)
    # ========================================================================

    def handle_browser(self, query):
        """Strona z przeglądarką plików — do pobierania z dowolnej przeglądarki."""
        sub_path = query.get('path', [''])[0]
        full_path = os.path.normpath(os.path.join(self.root_dir, sub_path))

        if not os.path.abspath(full_path).startswith(os.path.abspath(self.root_dir)):
            self.send_error(403, 'Zabroniony dostęp')
            return

        if not os.path.isdir(full_path):
            self.send_error(404, 'Katalog nie istnieje')
            return

        items = []
        try:
            for entry in sorted(os.listdir(full_path), key=str.lower):
                if entry.startswith('.'):
                    continue
                entry_path = os.path.join(full_path, entry)
                is_dir = os.path.isdir(entry_path)
                size = 0 if is_dir else os.path.getsize(entry_path)
                items.append((entry, is_dir, size))
        except PermissionError:
            self.send_error(403, 'Brak uprawnień')
            return

        # Buduj breadcrumb
        breadcrumb_html = '<a href="/">🏠 Root</a>'
        if sub_path:
            parts = sub_path.split('/')
            for i, part in enumerate(parts):
                link_path = '/'.join(parts[:i+1])
                breadcrumb_html += f' / <a href="/?path={urllib.parse.quote(link_path)}">{part}</a>'

        # Buduj listę plików
        rows_html = ''
        for name, is_dir, size in items:
            rel_path = f'{sub_path}/{name}' if sub_path else name

            if is_dir:
                link = f'/?path={urllib.parse.quote(rel_path)}'
                icon = '📁'
                size_str = ''
                download_btn = ''
            else:
                icon = self._file_icon(name)
                size_str = self._format_size(size)
                dl_url = f'/download/{urllib.parse.quote(rel_path)}'
                download_btn = f'<a href="{dl_url}" class="btn-dl">Pobierz</a>'
                link = ''

            if is_dir:
                name_cell = f'<a href="{link}" class="dir-link">{icon} {name}</a>'
            else:
                name_cell = f'<span class="file-name">{icon} {name}</span>'

            rows_html += f'''<tr>
                <td class="name-col">{name_cell}</td>
                <td class="size-col">{size_str}</td>
                <td class="action-col">{download_btn}</td>
            </tr>\n'''

        # Przycisk "W górę"
        back_btn = ''
        if sub_path:
            parent = '/'.join(sub_path.split('/')[:-1])
            parent_url = f'/?path={urllib.parse.quote(parent)}' if parent else '/'
            back_btn = f'<a href="{parent_url}" class="btn-back">⬆ W górę</a>'

        file_count = sum(1 for _, d, _ in items if not d)
        dir_count = sum(1 for _, d, _ in items if d)

        html = f'''<!DOCTYPE html>
<html lang="pl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pobieranie plików</title>
<style>
    * {{ margin: 0; padding: 0; box-sizing: border-box; }}
    body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
           background: #121212; color: #e0e0e0; }}
    .header {{ background: #1a1a2e; padding: 20px; border-bottom: 2px solid #4a90d9; }}
    .header h1 {{ color: #4a90d9; font-size: 1.4em; margin-bottom: 8px; }}
    .breadcrumb {{ color: #aaa; font-size: 0.9em; }}
    .breadcrumb a {{ color: #6db3f2; text-decoration: none; }}
    .breadcrumb a:hover {{ text-decoration: underline; }}
    .toolbar {{ padding: 12px 20px; background: #1e1e2e; display: flex;
                align-items: center; gap: 16px; flex-wrap: wrap; }}
    .toolbar .stats {{ color: #888; font-size: 0.85em; }}
    .btn-back {{ display: inline-block; padding: 6px 14px; background: #333;
                 color: #ccc; text-decoration: none; border-radius: 6px; font-size: 0.9em; }}
    .btn-back:hover {{ background: #444; }}
    .file-list {{ width: 100%; border-collapse: collapse; }}
    .file-list tr {{ border-bottom: 1px solid #2a2a2a; }}
    .file-list tr:hover {{ background: #1e1e2e; }}
    .file-list td {{ padding: 12px 16px; vertical-align: middle; }}
    .name-col {{ word-break: break-word; }}
    .size-col {{ white-space: nowrap; color: #888; font-size: 0.85em; text-align: right;
                 min-width: 80px; }}
    .action-col {{ text-align: right; min-width: 90px; }}
    .dir-link {{ color: #6db3f2; text-decoration: none; font-weight: 500; }}
    .dir-link:hover {{ text-decoration: underline; }}
    .file-name {{ color: #ccc; }}
    .btn-dl {{ display: inline-block; padding: 6px 16px; background: #2d6a4f;
               color: #fff; text-decoration: none; border-radius: 6px; font-size: 0.85em;
               font-weight: 500; transition: background 0.2s; }}
    .btn-dl:hover {{ background: #40916c; }}
    .empty {{ padding: 40px; text-align: center; color: #666; }}
    @media (max-width: 600px) {{
        .file-list td {{ padding: 10px 8px; }}
        .size-col {{ display: none; }}
        .header {{ padding: 14px; }}
        .header h1 {{ font-size: 1.2em; }}
    }}
</style>
</head>
<body>
    <div class="header">
        <h1>📥 Pobieranie plików</h1>
        <div class="breadcrumb">{breadcrumb_html}</div>
    </div>
    <div class="toolbar">
        {back_btn}
        <span class="stats">{dir_count} katalogów, {file_count} plików</span>
    </div>
    <table class="file-list">
        {rows_html if rows_html else '<tr><td colspan="3" class="empty">Katalog jest pusty</td></tr>'}
    </table>
</body>
</html>'''

        data = html.encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    @staticmethod
    def _file_icon(name):
        ext = os.path.splitext(name)[1].lower()
        if ext in ('.mp4', '.mkv', '.avi', '.mov', '.wmv', '.flv', '.webm', '.m4v', '.3gp', '.ts', '.m2ts'):
            return '🎬'
        if ext in ('.mp3', '.flac', '.wav', '.ogg', '.aac', '.wma', '.m4a'):
            return '🎵'
        if ext in ('.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp'):
            return '🖼️'
        if ext in ('.srt', '.sub', '.ass'):
            return '💬'
        if ext in ('.txt', '.nfo'):
            return '📄'
        return '📎'

    @staticmethod
    def _format_size(size_bytes):
        if size_bytes < 1024:
            return f'{size_bytes} B'
        if size_bytes < 1024 * 1024:
            return f'{size_bytes / 1024:.0f} KB'
        if size_bytes < 1024 * 1024 * 1024:
            return f'{size_bytes / (1024*1024):.1f} MB'
        return f'{size_bytes / (1024*1024*1024):.2f} GB'


class LimitedThreadingHTTPServer(http.server.ThreadingHTTPServer):
    """ThreadingHTTPServer z limitem wątków naraz.

    ExoPlayer (i VR z prefetchem playlisty) może otwierać kilka połączeń
    równolegle — np. główny stream + prefetch następnego pliku + audio
    w tle. Bez limitu, przy dużej playliście, liczba wątków rywalizujących
    o GIL i dysk może rosnąć w sposób, który losowo "ścina" aktywny strumień.
    Limit kolejki (request_queue_size) + krótszy timeout ogranicza to zjawisko.
    """
    daemon_threads = True
    request_queue_size = 16


def get_local_ip():
    """Próbuje ustalić lokalne IP komputera w sieci LAN."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return '127.0.0.1'


def main():
    # Argumenty
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    flags = [a for a in sys.argv[1:] if a.startswith('--')]

    root_dir = args[0] if len(args) > 0 else '.'
    port = int(args[1]) if len(args) > 1 else 8080
    # --download = chunkowany (domyślnie 500MB na żądanie, progresywne)
    # --down     = ciągłe ładowanie całego pliku + logi prędkości
    # --down1gb  = chunkowany jak --download, ale zawsze min. 1GB na żądanie (start też 1GB)
    use_progressive = '--download' in flags
    use_continuous = '--down' in flags
    use_progressive_1gb = '--down1gb' in flags

    if use_progressive_1gb:
        use_progressive = True

    # Sprawdź katalog
    if not os.path.isdir(root_dir):
        print(f"❌ Katalog nie istnieje: {root_dir}")
        sys.exit(1)

    # Ustaw katalog i tryb dla handlera
    StreamingHandler.root_dir = os.path.abspath(root_dir)
    StreamingHandler.progressive_download = use_progressive
    StreamingHandler.continuous_download = use_continuous
    StreamingHandler.progressive_chunk_size = (
        PROGRESSIVE_CHUNK_SIZE_1GB if use_progressive_1gb else PROGRESSIVE_CHUNK_SIZE_DEFAULT
    )

    # Uruchom serwer
    local_ip = get_local_ip()
    server = LimitedThreadingHTTPServer(('0.0.0.0', port), StreamingHandler)

    print("=" * 60)
    print("🎬 File Streaming Server")
    print("=" * 60)
    print(f"📁 Katalog:  {os.path.abspath(root_dir)}")
    if use_progressive:
        mb = StreamingHandler.progressive_chunk_size // (1024 * 1024)
        print(f"📥 Tryb:     Chunkowany (min {mb}MB na żądanie)")
    elif use_continuous:
        print(f"📥 Tryb:     Ciągłe ładowanie (cały plik + prędkość pobierania)")
    print(f"🌐 Adres:    http://{local_ip}:{port}")
    print(f"💻 Lokalnie: http://localhost:{port}")
    print()
    print("📱 Pobieranie plików z przeglądarki:")
    print(f"   Otwórz http://{local_ip}:{port} w przeglądarce na dowolnym urządzeniu")
    print()
    print("📺 Streaming z aplikacją File Streaming Player:")
    print(f"   Wpisz http://{local_ip}:{port} w aplikacji na telefonie")
    print()
    print("Aby zakończyć, naciśnij Ctrl+C")
    print("=" * 60)

    # Policz pliki multimedialne
    media_count = 0
    for root, dirs, files in os.walk(root_dir):
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in MEDIA_EXTENSIONS:
                media_count += 1

    print(f"📊 Znaleziono plików multimedialnych: {media_count}")
    print()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n🛑 Serwer zatrzymany.")
        server.server_close()


if __name__ == '__main__':
    main()
