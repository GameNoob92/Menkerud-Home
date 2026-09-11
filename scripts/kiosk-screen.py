#!/usr/bin/env python3
"""Menkerud Home - kiosk screen helper. Runs inside the kiosk user's GNOME session (no root, Python 3 stdlib only).

A web page cannot switch a display off or on, so index.html asks this tiny local service instead:

    GET /off      -> screen dark   (backlight to 0 through logind; the panel and its touch controller stay powered)
    GET /on       -> screen back   (backlight restored)
    GET /status   -> {"off": bool, "method": "backlight"|"dpms", "brightness": n, "max": n, "power": 0..3, "idle_ms": n}
    GET /version  -> {"head": "000b6d4", "date": "<commit date, ISO>", "subject": "...", "repo": "/var/www/menkerud-home"}
    POST /update  -> `git pull --ff-only` in the checkout nginx serves (this file lives in it), for the page's
                     «Hent oppdatering» button -> {"ok": bool, "before", "after", "changed": bool, "helper_restart": bool}.
                     If the pull changed this very file, the helper restarts itself two seconds later (systemctl --user).

Why the backlight and not DPMS: a real DPMS-off (Mutter PowerSaveMode 3) also powers down the USB touch
controller of the Asus Vivo AIO, so no touch ever arrives and the screen can never wake by touch. Backlight 0 is
just as black, and touches keep flowing. DPMS is only the fallback on machines without a backlight device.

While the page has asked for "off", the helper watches Mutter's idle time: the first touch (or key) after the
request restores the backlight, so a touch always brings the picture back - the page then asks for /on as well.

Listens on 127.0.0.1:7777 only (override with MENKERUD_SCREEN_PORT).
Install (as the kiosk user, no sudo) - see SETUP.md section 3:
    mkdir -p ~/.config/systemd/user
    cp /var/www/menkerud-home/call-backend/deploy/menkerud-screen.service ~/.config/systemd/user/
    systemctl --user daemon-reload && systemctl --user enable --now menkerud-screen.service
"""
import json
import os
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
try:
    import pwd                        # Unix only; the /version and /update endpoints also work where it is missing (dev PC)
except ImportError:
    pwd = None

PORT = int(os.environ.get('MENKERUD_SCREEN_PORT', '7777'))
REPO = os.environ.get('MENKERUD_REPO') or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # the git checkout nginx serves
SERVICE = 'menkerud-screen.service'
BACKLIGHT_DIR = '/sys/class/backlight'
DISPLAY = ['org.gnome.Mutter.DisplayConfig', '/org/gnome/Mutter/DisplayConfig', 'org.gnome.Mutter.DisplayConfig', 'PowerSaveMode']
IDLE = ['org.gnome.Mutter.IdleMonitor', '/org/gnome/Mutter/IdleMonitor/Core', 'org.gnome.Mutter.IdleMonitor', 'GetIdletime']


def run(args):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=5)
        return r.returncode, r.stdout.strip()
    except Exception:
        return 1, ''


def read_int(path):
    try:
        with open(path) as f:
            return int(f.read().strip())
    except Exception:
        return -1


# --- display power (Mutter, session bus) ---------------------------------------------------------
def get_power():
    _, out = run(['busctl', '--user', 'get-property', *DISPLAY])   # "i 0"
    try:
        return int(out.split()[-1])
    except Exception:
        return -1


def set_power(mode):
    run(['busctl', '--user', 'set-property', *DISPLAY, 'i', str(mode)])


def idle_ms():
    _, out = run(['busctl', '--user', 'call', *IDLE])              # "t 60502"
    try:
        return int(out.split()[-1])
    except Exception:
        return -1


# --- backlight (logind SetBrightness on our own graphical session, system bus) --------------------
def backlight_name():
    try:
        names = sorted(os.listdir(BACKLIGHT_DIR))
        return names[0] if names else None
    except Exception:
        return None


BL = backlight_name()
_session_path = None


def find_session_path():
    """logind only lets a user set the brightness on their own session; find our seat0 (graphical) session."""
    user = os.environ.get('USER') or (pwd.getpwuid(os.getuid()).pw_name if pwd else '')
    _, out = run(['loginctl', 'list-sessions', '--no-legend'])
    for line in out.splitlines():
        f = line.split()
        if len(f) >= 4 and f[2] == user and f[3] == 'seat0':
            _, path = run(['busctl', 'call', 'org.freedesktop.login1', '/org/freedesktop/login1',
                           'org.freedesktop.login1.Manager', 'GetSession', 's', f[0]])   # o "/org/freedesktop/login1/session/_31"
            m = re.search(r'"([^"]+)"', path)
            if m:
                return m.group(1)
    return None


def get_brightness():
    return read_int(f'{BACKLIGHT_DIR}/{BL}/brightness') if BL else -1


def max_brightness():
    return read_int(f'{BACKLIGHT_DIR}/{BL}/max_brightness') if BL else -1


def set_brightness(value):
    global _session_path
    if not BL:
        return False
    for _ in range(2):
        if not _session_path:
            _session_path = find_session_path()
        if not _session_path:
            return False
        rc, _ = run(['busctl', 'call', 'org.freedesktop.login1', _session_path, 'org.freedesktop.login1.Session',
                     'SetBrightness', 'ssu', 'backlight', BL, str(value)])
        if rc == 0:
            return True
        _session_path = None          # session changed (re-login) - look it up again
    return False


# --- state ----------------------------------------------------------------------------------------
state = {'wanted_off': False, 'off_at': 0.0, 'saved': 0, 'method': 'backlight' if BL else 'dpms'}
lock = threading.Lock()


def request_off():
    with lock:
        state['wanted_off'] = True
        state['off_at'] = time.time()
        cur = get_brightness()
        if cur > 0:
            state['saved'] = cur
    if get_power() != 0:
        set_power(0)                  # keep the panel (and its touch controller) powered
    if not set_brightness(0):
        with lock:
            state['method'] = 'dpms'
        set_power(3)                  # no backlight control on this machine: real DPMS off


def request_on():
    with lock:
        state['wanted_off'] = False
        saved = state['saved']
    if get_power() != 0:
        set_power(0)
    if BL:
        set_brightness(saved if saved > 0 else max_brightness())


def watcher():
    """Wake on the first touch after an /off request; re-darken if something else restored the screen meanwhile."""
    while True:
        time.sleep(1)
        with lock:
            wanted, off_at = state['wanted_off'], state['off_at']
        if not wanted:
            continue
        idle = idle_ms()
        if idle < 0:
            continue
        last_input = time.time() - idle / 1000.0
        if last_input > off_at + 0.5:                 # someone touched it after the "off" request
            request_on()
        elif time.time() - off_at > 3:
            if BL and get_brightness() > 0:
                set_brightness(0)
            elif not BL and get_power() == 0:
                set_power(3)


# --- updates (git pull in the checkout; same user owns it and runs this service) ----------------------
def git(*args, timeout=90):
    try:
        r = subprocess.run(['git', '-C', REPO, *args], capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout + r.stderr).strip()
    except Exception as e:
        return 1, str(e)


def version():
    rc, out = git('log', '-1', '--format=%h%n%cI%n%s', timeout=10)
    if rc != 0:
        return {'error': out or 'not a git checkout', 'repo': REPO}
    head, date, subject = (out.split('\n') + ['', '', ''])[:3]
    return {'head': head, 'date': date, 'subject': subject, 'repo': REPO}


update_lock = threading.Lock()


def update():
    """Fast-forward the checkout. Error texts are shown to the parents in a toast, hence Norwegian."""
    if not update_lock.acquire(blocking=False):
        return 409, {'ok': False, 'error': 'en oppdatering pågår allerede'}
    try:
        rc, before = git('rev-parse', '--short', 'HEAD', timeout=10)
        if rc != 0:
            return 500, {'ok': False, 'error': 'fant ikke git-mappa (' + REPO + ')', 'output': before}
        rc, out = git('pull', '--ff-only')
        if rc != 0:
            return 500, {'ok': False, 'error': 'git pull feilet – er nettet oppe?', 'output': out, 'before': before}
        _, after = git('rev-parse', '--short', 'HEAD', timeout=10)
        changed, restart = after != before, False
        if changed:
            _, files = git('diff', '--name-only', before, after, timeout=10)
            me = os.path.relpath(os.path.abspath(__file__), REPO).replace(os.sep, '/')
            restart = me in files.split()
            if restart:                   # answer first, then let systemd start the new version of this file
                threading.Timer(2.0, lambda: subprocess.Popen(['systemctl', '--user', 'restart', SERVICE])).start()
        return 200, {'ok': True, 'before': before, 'after': after, 'changed': changed, 'helper_restart': restart, 'output': out}
    finally:
        update_lock.release()


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body=b''):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
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
        elif path == '/version':
            self._send(200, json.dumps(version()).encode())
        elif path == '/status':
            with lock:
                wanted, method = state['wanted_off'], state['method']
            self._send(200, json.dumps({'off': wanted, 'method': method, 'brightness': get_brightness(), 'max': max_brightness(),
                                        'power': get_power(), 'idle_ms': idle_ms()}).encode())
        else:
            self._send(404, b'{"error":"unknown path"}')

    def do_POST(self):
        path = self.path.split('?', 1)[0]
        if path == '/update':
            code, body = update()
            self._send(code, json.dumps(body).encode())
        else:
            self._send(404, b'{"error":"unknown path"}')

    def do_OPTIONS(self):
        self._send(204)

    def log_message(self, *args):      # keep the journal quiet
        pass


if __name__ == '__main__':
    threading.Thread(target=watcher, daemon=True).start()
    ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
