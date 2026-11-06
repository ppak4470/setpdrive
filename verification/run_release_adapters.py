"""Exercise the signed, non-debuggable contest APK on a local Pleos emulator."""
from pathlib import Path
import json
import os
import subprocess
import threading
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_HOME', str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')))
ADB = str(SDK / 'platform-tools/adb.exe')
SERIAL = 'emulator-5554'
PACKAGE = 'kr.stepdrive.app.contest'
CLIENT = 'stepdrive-release-verification'
NO_WINDOW = subprocess.CREATE_NO_WINDOW

def adb(*args, timeout=15):
    return subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, text=True,
                          encoding='utf-8', errors='replace', timeout=timeout,
                          creationflags=NO_WINDOW, check=True)

def api(path, values, method='PUT'):
    url = 'http://127.0.0.1:8088' + path + '?' + urllib.parse.urlencode(values)
    with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=5) as result:
        return result.read()

def parked():
    api('/vehicle/controls', dict(speed=0, accelerator=0, brake=30, steering=0))
    api('/vehicle/gear', dict(gear='Park'))
    api('/vehicle/turn-signal', dict(mode='Off'))

assert adb('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() == '1'
user = adb('shell', 'am', 'get-current-user').stdout.strip()
assert user.isdigit()
permissions = adb('shell', 'dumpsys', 'package', PACKAGE).stdout
active = permissions.split(f'User {user}:', 1)[1]
for permission in ('android.car.permission.CAR_SPEED', 'pleos.car.permission.FUSED_LOCATION',
                   'android.permission.RECORD_AUDIO', 'android.permission.POST_NOTIFICATIONS'):
    assert f'{permission}: granted=true' in active, f'User must handle permission: {permission}'
stop = threading.Event()
errors = []

def heartbeat():
    while not stop.is_set():
        try:
            api('/browser/session', dict(client=CLIENT), 'POST')
        except Exception as exc:
            errors.append(type(exc).__name__)
        stop.wait(0.8)

thread = threading.Thread(target=heartbeat, daemon=True)
thread.start()
process = None
try:
    time.sleep(1)
    parked()
    adb('shell', 'am', 'force-stop', '--user', user, PACKAGE)
    log_path = ROOT / 'emulator-release-adapters.log'
    with log_path.open('w', encoding='utf-8') as log:
        process = subprocess.Popen([ADB, '-s', SERIAL, 'shell', 'am', 'instrument', '--user', user,
            '-w', '-e', 'mode', 'adapters',
            PACKAGE + '.test/kr.stepdrive.app.validation.ValidationInstrumentation'],
            stdout=log, stderr=subprocess.STDOUT, creationflags=NO_WINDOW)
        process.wait(timeout=200)
    output = log_path.read_text(encoding='utf-8')
    passed = sorted(set(line.strip() for line in output.splitlines() if line.startswith('PASS ')))
    assert process.returncode == 0 and len(passed) == 6 and 'FAIL' not in output, output
    (ROOT / 'emulator-release-summary.json').write_text(json.dumps({
        'package': PACKAGE, 'mode': 'adapters', 'android_user': int(user),
        'passed': passed, 'count': len(passed), 'heartbeat_errors': errors,
        'debuggable': False, 'privacy_permissions': 'selected by user',
    }, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': len(passed), 'checks': passed}, indent=2), flush=True)
finally:
    if process is not None and process.poll() is None:
        adb('shell', 'am', 'force-stop', '--user', user, PACKAGE)
        process.terminate()
    try:
        parked()
    finally:
        stop.set()
        thread.join(timeout=5)
        api('/browser/session', dict(client=CLIENT, closing=1), 'POST')
