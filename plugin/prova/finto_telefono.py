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
        if p.startswith('/auto?'):
            # dati dell'auto dal plugin Meteo: si scrivono decodificati, cosi' la prova li puo' controllare
            from urllib.parse import urlparse, parse_qs
            q = parse_qs(urlparse(p).query)
            with open('/tmp/finto_telefono_auto.txt', 'a') as f: f.write(q['n'][0] + ' = ' + q['v'][0] + '\n')
            return self.manda(200, 'text/plain', b'OK\n')
        if p == '/ponte':
            # avvio cambia a ogni richiesta dopo la terza: la prova vede il Meteo rimandare i dati dell'auto
            H.giri = getattr(H, 'giri', 0) + 1
            avvio = 1000 if H.giri < 3 else 2000
            return self.manda(200, 'text/plain', ('IDRIVEPONTE 2.18 meteo=111 notizie=222 n=8 locali=333 carburanti=444 traffico=555 '
                                                  'auto=666 vettura=777 avvio=%d\n' % avvio).encode())
        if p in ('/meteo.png', '/carburanti.png', '/traffico.png', '/auto.png'): return self.manda(200, 'image/png', open(D + p, 'rb').read())
        if p == '/vettura/notizie.txt':
            # Stato vettura: due righe con la versione dopo il TAB
            return self.manda(200, 'text/plain; charset=iso-8859-1', b'NOTIZIE 777 2\nRiepilogo\t1001\nPorte, finestrini e tetto\t1002\n')
        if p.startswith('/vettura/'):
            f = {'/vettura/notizia/0.png': '/auto.png', '/vettura/notizia/1.png': '/porte.png'}.get(p, '/auto.png')
            return self.manda(200, 'image/png', open(D + f, 'rb').read())
        if p.startswith('/locali/'): p = p[7:]
        if p == '/notizie.txt' or self.path == '/locali/notizie.txt': return self.manda(200, 'text/plain; charset=utf-8', ('NOTIZIE 222 8\n' + '\n'.join(TITOLI) + '\n').encode())
        for pre, nome in (('/notizia/', 'notizia'), ('/mini/', 'mini'), ('/anteprima/', 'anteprima')):
            if p.startswith(pre):
                f = D + '/' + nome + p[len(pre):]
                if os.path.exists(f): return self.manda(200, 'image/png', open(f, 'rb').read())
        self.manda(404, 'text/plain', b'no\n')
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', 8765), H).serve_forever()
