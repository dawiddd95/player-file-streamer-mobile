#!/usr/bin/env python3
"""
Prosty serwer HTTP do streamowania plików multimedialnych.

Uruchomienie:
    python server.py [KATALOG] [PORT]

Przykłady:
    python server.py                          # bieżący katalog, port 8080
    python server.py D:\\Filmy                 # katalog D:\\Filmy, port 8080
    python server.py D:\\Filmy 9090            # katalog D:\\Filmy, port 9090

Endpointy:
    GET /api/files              → lista plików w katalogu głównym (JSON)
    GET /api/files?path=subdir  → lista plików w podkatalogu (JSON)
    GET /media/<ścieżka>        → streaming pliku z obsługą Range (przewijanie)

Aplikacja mobilna File Streaming Player łączy się z tym serwerem.
"""

import http.server
import json
import os
import sys
import urllib.parse
import mimetypes
import socket

# ============================================================================
# Konfiguracja
# ============================================================================

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

    def log_message(self, format, *args):
        """Logowanie z lepszym formatem."""
        print(f"[{self.log_date_time_string()}] {args[0]}")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = urllib.parse.unquote(parsed.path)
        query = urllib.parse.parse_qs(parsed.query)

        if path == '/api/files':
            self.handle_file_list(query)
        elif path.startswith('/media/'):
            self.handle_media_stream(path[7:])  # usuń '/media/'
        elif path == '/':
            self.handle_index()
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
            content_length = end - start + 1

            self.send_response(206)  # Partial Content
            self.send_header('Content-Range', f'bytes {start}-{end}/{file_size}')
            self.send_header('Content-Length', str(content_length))
            self.send_header('Content-Type', content_type)
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()

            # Wyślij fragment pliku
            with open(full_path, 'rb') as f:
                f.seek(start)
                remaining = content_length
                while remaining > 0:
                    chunk_size = min(65536, remaining)
                    chunk = f.read(chunk_size)
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        break
                    remaining -= len(chunk)
        else:
            # Pełny plik (bez Range)
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(file_size))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()

            with open(full_path, 'rb') as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        break

    # ========================================================================
    # / — strona główna (info)
    # ========================================================================

    def handle_index(self):
        """Strona główna z informacjami o serwerze."""
        html = f"""<!DOCTYPE html>
<html>
<head><title>File Streaming Server</title>
<style>
    body {{ font-family: sans-serif; background: #1e1e1e; color: #fff; padding: 40px; }}
    h1 {{ color: #4a90d9; }}
    code {{ background: #333; padding: 2px 8px; border-radius: 4px; }}
    .info {{ color: #aaa; margin-top: 20px; }}
</style>
</head>
<body>
    <h1>🎬 File Streaming Server</h1>
    <p>Serwer działa poprawnie!</p>
    <p>Katalog: <code>{os.path.abspath(self.root_dir)}</code></p>
    <h3>Endpointy:</h3>
    <ul>
        <li><code>GET /api/files</code> — lista plików (JSON)</li>
        <li><code>GET /api/files?path=subdir</code> — lista w podkatalogu</li>
        <li><code>GET /media/nazwa_pliku.mp4</code> — streaming pliku</li>
    </ul>
    <p class="info">Połącz się z aplikacją <b>File Streaming Player</b> na telefonie,
    wpisując adres tego serwera.</p>
</body>
</html>"""

        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(html.encode('utf-8'))))
        self.end_headers()
        self.wfile.write(html.encode('utf-8'))


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
    root_dir = sys.argv[1] if len(sys.argv) > 1 else '.'
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080

    # Sprawdź katalog
    if not os.path.isdir(root_dir):
        print(f"❌ Katalog nie istnieje: {root_dir}")
        sys.exit(1)

    # Ustaw katalog dla handlera
    StreamingHandler.root_dir = os.path.abspath(root_dir)

    # Uruchom serwer
    local_ip = get_local_ip()
    server = http.server.HTTPServer(('0.0.0.0', port), StreamingHandler)

    print("=" * 60)
    print("🎬 File Streaming Server")
    print("=" * 60)
    print(f"📁 Katalog:  {os.path.abspath(root_dir)}")
    print(f"🌐 Adres:    http://{local_ip}:{port}")
    print(f"💻 Lokalnie: http://localhost:{port}")
    print()
    print("W aplikacji File Streaming Player na telefonie wpisz:")
    print(f"  http://{local_ip}:{port}")
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

