# SPDX-License-Identifier: Apache-2.0
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class CrossOriginIsolatedHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
        super().end_headers()


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8765), CrossOriginIsolatedHandler).serve_forever()
