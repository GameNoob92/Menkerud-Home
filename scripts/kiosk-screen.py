#!/usr/bin/env python3
"""Menkerud Home - kiosk screen helper. Runs inside the kiosk user's GNOME session (no root, Python 3 stdlib only).

A web page cannot switch a display off or on, so index.html asks this tiny local service instead:

    GET /off      -> display off  (Mutter PowerSaveMode 3)      used by the power button and the night window
    GET /on       -> display on   (PowerSaveMode 0)             used when the page should be visible again, e.g. an incoming call
    GET /status   -> {"power": 0..3, "idle_ms": n, "wanted_off": bool}

It listens on 127.0.0.1:7777 only (override with MENKERUD_SCREEN_PORT). While the page has asked for "off", the helper
also watches Mutter's idle time: the first touch (or key) after the request switches the display back on, so a
touch always brings the picture back even if the compositor did not wake it by itself.

Install (as the kiosk user, no sudo) - see SETUP.md section 3:
    mkdir -p ~/.config/systemd/user
    cp /var/www/menkerud-home/call-backend/deploy/menkerud-screen.service ~/.config/systemd/user/
    systemctl --user daemon-reload && systemctl --user enable --now menkerud-screen.service
"""
import json
import os
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get('MENKERUD_SCREEN_PORT', '7777'))
DISPLAY = ['org.gnome.Mutter.DisplayConfig', '/org/gnome/Mutter/DisplayConfig', 'org.gnome.Mutter.DisplayConfig', 'PowerSaveMode']
IDLE = ['org.gnome.Mutter.IdleMonitor', '/org/gnome/Mutter/IdleMonitor/Core', 'org.gnome.Mutter.IdleMonitor', 'GetIdletime']


def busctl(*args):
    try:
        return subprocess.run(['busctl', '--user', *args], capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        return ''


def get_power():
    out = busctl('get-property', *DISPLAY)          # "i 0"
    try:
        return int(out.split()[-1])
    except Exception:
        return -1


def set_power(mode):
    busctl('set-property', *DISPLAY, 'i', str(mode))


def idle_ms():
    out = busctl('call', *IDLE)                      # "t 60502"
    try:
        return int(out.split()[-1])
    except Exception:
        return -1


state = {'wanted_off': False, 'off_at': 0.0}
lock = threading.Lock()


def request_off():
    with lock:
        state['wanted_off'] = True
        state['off_at'] = time.time()
    set_power(3)


def request_on():
    with lock:
        state['wanted_off'] = False
    set_power(0)


def watcher():
    """Wake on touch while the page wants the display off; re-blank if it came on by itself with nobody touching it."""
    while True:
        time.sleep(1)
        with lock:
            wanted, off_at = state['wanted_off'], state['off_at']
        if not wanted:
            continue
        power, idle = get_power(), idle_ms()
        if idle < 0 or power < 0:
            continue
        last_input = time.time() - idle / 1000.0
        if last_input > off_at + 0.5:                 # someone touched it after the "off" request
            request_on()
        elif power == 0 and time.time() - off_at > 3:  # nobody touched it, yet the display is on: keep it off
            set_power(3)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body=b''):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        path = self.path.split('?', 1)[0]
        if path == '/off':
            request_off(); self._send(204)
        elif path == '/on':
            request_on(); self._send(204)
        elif path == '/status':
            with lock:
                wanted = state['wanted_off']
            self._send(200, json.dumps({'power': get_power(), 'idle_ms': idle_ms(), 'wanted_off': wanted}).encode())
        else:
            self._send(404, b'{"error":"unknown path"}')

    def log_message(self, *args):      # keep the journal quiet
        pass


if __name__ == '__main__':
    threading.Thread(target=watcher, daemon=True).start()
    ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
