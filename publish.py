#!/usr/bin/env python3
"""Publish APK ke GitHub: buat repo tgdrive-apk, push source (tanpa build/),
upload APK v1.2 signed, buat release v1.2."""
import base64
import json
import os
import subprocess
import sys
import urllib.request
import urllib.error

sys.path.insert(0, '/opt/hatch/skills/skill-creator/bin')
from dynamic_credentials import add_surrogate_to_request, read_response_body

HOST = 'api.github.com'
ME, REPO = 'diyansantoso26', 'tgdrive-apk'
SRC = os.path.expanduser('~/workspace/tgdrive-apk')
SKIP_DIRS = {'build', '.git', '.gradle', '__pycache__'}


def api(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f'https://{HOST}{path}', data=data, method=method)
    req.add_header('Accept', 'application/vnd.github+json')
    req.add_header('X-GitHub-Api-Version', '2022-11-28')
    if data:
        req.add_header('Content-Type', 'application/json')
    add_surrogate_to_request(req, 'custom.github', allowed_hosts=[HOST])
    try:
        resp = urllib.request.urlopen(req, timeout=180)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return {'http_error': 404}
        print(f'HTTP {e.code}: {e.read().decode()[:300]}')
        sys.exit(1)
    raw = read_response_body(resp)
    return json.loads(raw.decode()) if raw.strip() else {'ok': True}


def main():
    # 1. repo
    if api('GET', f'/repos/{ME}/{REPO}').get('http_error') == 404:
        r = api('POST', '/user/repos', {'name': REPO,
               'description': 'TG Drive — aplikasi Android (WebView wrapper)',
               'private': False, 'auto_init': False})
        print('repo dibuat:', r.get('html_url'))
    else:
        print('repo sudah ada')

    # 2. push source (tanpa build/)
    files = []
    for root, dirs, names in os.walk(SRC):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for n in names:
            full = os.path.join(root, n)
            files.append((os.path.relpath(full, SRC), full))
    print(f'{len(files)} file source')
    for rel, full in sorted(files):
        with open(full, 'rb') as f:
            content = base64.b64encode(f.read()).decode()
        cur = api('GET', f'/repos/{ME}/{REPO}/contents/{rel}')
        payload = {'message': f'tgdrive-apk: {rel}', 'content': content}
        if isinstance(cur, dict) and cur.get('sha'):
            payload['sha'] = cur['sha']
        api('PUT', f'/repos/{ME}/{REPO}/contents/{rel}', payload)
    print('source ter-push')

    # 3. ambil APK signed v1.2 dari VPS
    apk_local = '/tmp/tgdrive-v1.2.apk'
    subprocess.run(['scp', '-F', os.path.expanduser('~/.ssh/config_vps'),
                    'vps:/home/tgdrive/app/apk/tgdrive.apk', apk_local], check=True)
    print('APK diambil dari VPS:', os.path.getsize(apk_local), 'bytes')

    # 4. upload APK ke repo
    with open(apk_local, 'rb') as f:
        content = base64.b64encode(f.read()).decode()
    rel_path = 'releases/tgdrive-v1.2.apk'
    cur = api('GET', f'/repos/{ME}/{REPO}/contents/{rel_path}')
    payload = {'message': 'release v1.2: APK signed', 'content': content}
    if isinstance(cur, dict) and cur.get('sha'):
        payload['sha'] = cur['sha']
    r = api('PUT', f'/repos/{ME}/{REPO}/contents/{rel_path}', payload)
    print('APK ter-upload:', r['content']['size'], 'bytes')

    # 5. release v1.2
    rel = api('POST', f'/repos/{ME}/{REPO}/releases', {
        'tag_name': 'v1.2',
        'name': 'TG Drive Android v1.2',
        'body': ('Aplikasi Android TG Drive v1.2.\n\n'
                 '- Multi-select file (pilih banyak file sekaligus)\n'
                 '- Dukungan Android 7.0+ (minSdk 24)\n\n'
                 '**Cara install:** download `tgdrive-v1.2.apk` di folder `releases/` repo ini '
                 '(atau dari https://drive.gtg.my.id/apk/tgdrive.apk), izinkan "install dari sumber tidak dikenal", lalu install.'),
        'draft': False, 'prerelease': False,
    })
    print('release:', rel.get('html_url'))


if __name__ == '__main__':
    main()
