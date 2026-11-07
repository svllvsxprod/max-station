#!/usr/bin/env python3
"""One-time loopback page that imports an encrypted API key into Max Station."""

import argparse
import json
import os
import re
import secrets
import subprocess
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

ADB = os.environ.get("MAXSTATION_ADB", "adb")
URI = "content://org.yanalice.voice.setup"

PAGE = """<!doctype html>
<html lang="ru"><meta charset="utf-8"><title>Max Station Voice</title>
<h1>Max Station Voice</h1>
<p>Передача ключа PROVIDER_NAME в защищённое хранилище колонки.</p>
<form>
<label>Ключ PROVIDER_NAME <input type="password" autocomplete="off" spellcheck="false" required></label>
<button type="submit">Сохранить на колонке</button>
</form>
<p role="status"></p>
<script>
const form = document.querySelector("form");
const input = document.querySelector("input");
const status = document.querySelector("[role=status]");
form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const text = input.value.trim();
    input.value = "";
    if (!new RegExp(KEY_PATTERN).test(text)) {
        status.textContent = "Формат ключа не распознан.";
        return;
    }
    try {
        const spki = Uint8Array.from(atob(PUBLIC_KEY), char => char.charCodeAt(0));
        const key = await crypto.subtle.importKey(
            "spki", spki, {name: "RSA-OAEP", hash: "SHA-256"}, false, ["encrypt"]);
        const cipher = await crypto.subtle.encrypt(
            {name: "RSA-OAEP"}, key, new TextEncoder().encode(text));
        const body = btoa(String.fromCharCode(...new Uint8Array(cipher)));
        const result = await fetch(location.pathname, {method: "POST", body});
        status.textContent = result.ok ? "Ключ сохранён на Max Station." : "Передача не удалась.";
        if (result.ok) form.hidden = true;
    } catch {
        status.textContent = "Передача не удалась.";
    }
});
</script></html>
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("device")
    parser.add_argument("--openai", action="store_true")
    args = parser.parse_args()
    route = "/" + secrets.token_urlsafe(24)

    def call(method, *extra):
        return subprocess.run(
            [str(ADB), "-s", args.device, "shell", "content", "call",
             "--uri", URI, "--method", method, *extra],
            check=True, capture_output=True, text=True, timeout=12,
        ).stdout

    suffix = "-openai" if args.openai else ""
    response = call("prepare" + suffix)
    match = re.search(r"publicKey=([A-Za-z0-9+/=]+)", response)
    if not match:
        raise SystemExit("The column did not prepare the import.")
    pattern = r"^sk-[A-Za-z0-9_-]{32,350}$" if args.openai else r"^gsk_[A-Za-z0-9_-]{30,180}$"
    page = (PAGE.replace("PUBLIC_KEY", json.dumps(match[1]))
            .replace("KEY_PATTERN", json.dumps(pattern))
            .replace("PROVIDER_NAME", "OpenAI" if args.openai else "Groq")).encode()
    done = False

    class SetupHandler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def reply(self, code, body=b""):
            self.send_response(code)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Frame-Options", "DENY")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.reply(200, page) if self.path == route else self.reply(404)

        def do_POST(self):
            nonlocal done
            if self.path != route or self.headers.get("Origin") != origin:
                self.reply(403)
                return
            if self.headers.get("Content-Length") != "344":
                self.reply(400)
                return
            cipher = self.rfile.read(344).decode("ascii", errors="replace")
            if not re.fullmatch(r"[A-Za-z0-9+/]{342}==", cipher):
                self.reply(400)
                return
            try:
                done = "saved=true" in call("store" + suffix, "--arg", cipher)
            except (subprocess.SubprocessError, OSError):
                pass
            self.reply(200 if done else 502)

    try:
        with HTTPServer(("127.0.0.1", 0), SetupHandler) as server:
            server.timeout = 0.5
            origin = f"http://127.0.0.1:{server.server_port}"
            print(origin + route, flush=True)
            deadline = time.monotonic() + 300
            while not done and time.monotonic() < deadline:
                server.handle_request()
    finally:
        if not done:
            call("abort")


if __name__ == "__main__":
    main()
