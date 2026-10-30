# finto iDrive Ponte: stesso protocollo dell'app, con le pagine generate dal test Robolectric
import http.server, os, sys
# cartella con le pagine generate dal test dell'app (app/app/build/pagine/<marca>), oppure la prima opzione
D = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'app', 'build', 'pagine', 'mini')
TITOLI = ['Nuova pista ciclabile lungo il porto', 'Prezzi dell\'energia in calo per il terzo mese',
          'Città più calde: è già inverno?', 'quarta', 'quinta', 'sesta', 'settima', 'ottava']
class H(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'
    def manda(self, codice, tipo, corpo):
        self.send_response(codice); self.send_header('Content-Type', tipo); self.send_header('Content-Length', str(len(corpo)))
        self.send_header('Connection', 'close'); self.end_headers(); self.wfile.write(corpo)
    def do_GET(self):
        p = self.path
        if p == '/ponte': return self.manda(200, 'text/plain', b'IDRIVEPONTE 2.2 meteo=111 notizie=222 n=8 locali=333 carburanti=444 traffico=555\n')
        if p in ('/meteo.png', '/carburanti.png', '/traffico.png'): return self.manda(200, 'image/png', open(D + p, 'rb').read())
        if p.startswith('/locali/'): p = p[7:]
        if p == '/notizie.txt' or self.path == '/locali/notizie.txt': return self.manda(200, 'text/plain; charset=utf-8', ('NOTIZIE 222 8\n' + '\n'.join(TITOLI) + '\n').encode())
        for pre, nome in (('/notizia/', 'notizia'), ('/mini/', 'mini'), ('/anteprima/', 'anteprima')):
            if p.startswith(pre):
                f = D + '/' + nome + p[len(pre):]
                if os.path.exists(f): return self.manda(200, 'image/png', open(f, 'rb').read())
        self.manda(404, 'text/plain', b'no\n')
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', 8765), H).serve_forever()
