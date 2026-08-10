import hashlib
import hmac
import json
import os
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SECRET = os.environ["WEBHOOK_SECRET"].encode()
if not SECRET:
    # os.environ only catches the variable being absent. Compose substitutes an
    # unset ${DEPLOY_WEBHOOK_SECRET} to an empty string, which is present - and
    # an empty HMAC key is not a secret, since anyone who can reach this
    # endpoint can compute a valid signature for it.
    raise SystemExit("WEBHOOK_SECRET is empty; refusing to start")

# Must match deploy.sh's default. origin/main tracks upstream and carries none
# of the fork's commits.
DEPLOY_BRANCH = os.environ.get("DEPLOY_BRANCH", "teagan/main")
DEPLOY_LOCK = threading.Lock()

# GitHub push payloads are tens of KB. The body has to be read before the
# signature can be checked (the signature covers it), so this cap is what
# stops an unauthenticated caller from making us allocate whatever
# Content-Length it claims.
MAX_BODY_BYTES = 1024 * 1024


def verify_signature(body, signature_header):
    if not signature_header or not signature_header.startswith("sha256="):
        return False
    expected = hmac.new(SECRET, body, hashlib.sha256).hexdigest()
    try:
        return hmac.compare_digest(expected, signature_header.removeprefix("sha256="))
    except TypeError:
        # compare_digest rejects non-ASCII str; a hostile header shouldn't
        # become a 500.
        return False


def run_deploy():
    with DEPLOY_LOCK:
        print("[deploy] starting", flush=True)
        result = subprocess.run(
            ["/app/deploy.sh"], capture_output=True, text=True
        )
        print(result.stdout, flush=True)
        print(result.stderr, flush=True)
        print(f"[deploy] finished with exit code {result.returncode}", flush=True)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/webhook":
            self.send_response(404)
            self.end_headers()
            return

        try:
            length = int(self.headers.get("Content-Length", 0))
        except ValueError:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"bad content-length")
            return

        if length < 0 or length > MAX_BODY_BYTES:
            self.send_response(413)
            self.end_headers()
            self.wfile.write(b"payload too large")
            return

        body = self.rfile.read(length)

        if not verify_signature(body, self.headers.get("X-Hub-Signature-256")):
            self.send_response(401)
            self.end_headers()
            self.wfile.write(b"bad signature")
            return

        event = self.headers.get("X-GitHub-Event", "")
        if event == "ping":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"pong")
            return

        if event != "push":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ignored")
            return

        try:
            payload = json.loads(body)
        except ValueError:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"bad json")
            return

        if payload.get("ref") != f"refs/heads/{DEPLOY_BRANCH}":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ignored (not " + DEPLOY_BRANCH.encode() + b")")
            return

        self.send_response(202)
        self.end_headers()
        self.wfile.write(b"deploying")
        threading.Thread(target=run_deploy, daemon=True).start()

    # Without this a half-open connection pins a thread forever, and
    # ThreadingHTTPServer spawns one per connection.
    timeout = 30

    def log_message(self, fmt, *args):
        print(f"[http] {self.address_string()} {fmt % args}", flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 8000), Handler)
    print("listening on :8000", flush=True)
    server.serve_forever()
