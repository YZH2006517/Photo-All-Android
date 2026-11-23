"""
Photo All problem reports: a Tencent Cloud SCF web function.

The app posts a report (JSON) when the user taps 发送给开发者. This function checks it,
keeps it in a COS bucket (one text file per report, by day), pushes a short note to
WeChat (Server酱 or a WeCom group robot) and answers with the report's number.

Only the Python standard library: nothing to install. The COS upload is signed with the
function's own temporary keys (its execution role), so no key lives in the app or here.

Environment variables (函数配置 → 环境变量):
  COS_BUCKET       bucket name with its APPID, e.g. photoall-reports-1250000000
  COS_REGION       e.g. ap-guangzhou
  SERVERCHAN_KEY   Server酱 SendKey (SCT...), for a WeChat push; or
  WECOM_WEBHOOK    a WeCom group robot's webhook URL
  APP_TOKEN        optional: the app sends it as X-PhotoAll-Token; others are refused
  DAILY_LIMIT      reports a day in all (default 500); PER_IP_LIMIT a day per address (default 30)
  DRY_RUN          1: no COS and no push; reports written under ./out (local testing)

Run locally:  DRY_RUN=1 python app.py   (listens on :9000, as SCF web functions do)
"""
import datetime
import hashlib
import hmac
import json
import os
import random
import string
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 64 * 1024
DAILY_LIMIT = int(os.environ.get("DAILY_LIMIT", "500"))
PER_IP_LIMIT = int(os.environ.get("PER_IP_LIMIT", "30"))
DRY_RUN = os.environ.get("DRY_RUN") == "1"
CHINA = datetime.timezone(datetime.timedelta(hours=8))

# Counters of this instance (an instance lives minutes to hours: a rough limit, enough
# against a script; COS keeps every report that got through).
_day = ""
_count = 0
_per_ip: dict[str, int] = {}
_kinds: dict[str, int] = {}


def _today() -> str:
    return datetime.datetime.now(CHINA).strftime("%Y-%m-%d")


def _roll_day():
    global _day, _count, _per_ip, _kinds
    d = _today()
    if d != _day:
        _day, _count, _per_ip, _kinds = d, 0, {}, {}


def _report_id() -> str:
    now = datetime.datetime.now(CHINA)
    tail = "".join(random.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(4))
    return now.strftime("%m%d-%H%M") + "-" + tail


# ---- COS (XML API, signature v5) -------------------------------------------------------

def _cos_put(key: str, body: bytes, content_type: str = "text/plain; charset=utf-8"):
    bucket = os.environ["COS_BUCKET"]
    region = os.environ["COS_REGION"]
    sid = os.environ["TENCENTCLOUD_SECRETID"]
    skey = os.environ["TENCENTCLOUD_SECRETKEY"]
    token = os.environ.get("TENCENTCLOUD_SESSIONTOKEN", "")
    host = f"{bucket}.cos.{region}.myqcloud.com"
    path = "/" + key
    start = int(time.time()) - 60
    key_time = f"{start};{start + 600}"
    sign_key = hmac.new(skey.encode(), key_time.encode(), hashlib.sha1).hexdigest()
    signed = {"host": host}
    header_list = ";".join(sorted(signed))
    header_str = "&".join(f"{k}={urllib.parse.quote(v, safe='')}" for k, v in sorted(signed.items()))
    http_string = f"put\n{path}\n\n{header_str}\n"
    string_to_sign = f"sha1\n{key_time}\n{hashlib.sha1(http_string.encode()).hexdigest()}\n"
    signature = hmac.new(sign_key.encode(), string_to_sign.encode(), hashlib.sha1).hexdigest()
    auth = (f"q-sign-algorithm=sha1&q-ak={sid}&q-sign-time={key_time}&q-key-time={key_time}"
            f"&q-header-list={header_list}&q-url-param-list=&q-signature={signature}")
    req = urllib.request.Request(f"https://{host}{urllib.parse.quote(path)}", data=body, method="PUT")
    req.add_header("Authorization", auth)
    req.add_header("Content-Type", content_type)
    if token:
        req.add_header("x-cos-security-token", token)
    with urllib.request.urlopen(req, timeout=8) as r:
        r.read()


# ---- WeChat ------------------------------------------------------------------------------

def _push(title: str, text: str):
    sc = os.environ.get("SERVERCHAN_KEY", "").strip()
    wecom = os.environ.get("WECOM_WEBHOOK", "").strip()
    try:
        if sc:
            data = urllib.parse.urlencode({"title": title[:32], "desp": text}).encode()
            urllib.request.urlopen(urllib.request.Request(f"https://sctapi.ftqq.com/{sc}.send", data=data), timeout=6).read()
        elif wecom:
            body = json.dumps({"msgtype": "markdown", "markdown": {"content": (f"**{title}**\n" + text)[:3800]}}).encode()
            req = urllib.request.Request(wecom, data=body, headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=6).read()
    except Exception as e:  # a push that fails never fails the report
        print("push failed:", e)


def _first_lines(text: str, n: int) -> list[str]:
    return [l for l in text.splitlines() if l.strip()][:n]


def _kind(text: str) -> str:
    """What makes two reports the same problem: the reason line and the first app frame."""
    lines = text.splitlines()
    reason = next((l for l in lines if "问题报告" in l), lines[0] if lines else "")
    frame = next((l.strip() for l in lines if "org.photoall" in l), "")
    model = next((l for l in lines if l.startswith("手机")), "")
    return hashlib.md5((reason.split("·")[-1] + frame + model).encode()).hexdigest()[:10]


# ---- the request -------------------------------------------------------------------------

def handle(body: bytes, ip: str, token: str) -> tuple[int, dict]:
    global _count
    _roll_day()
    want = os.environ.get("APP_TOKEN", "")
    if want and token != want:
        return 403, {"error": "token"}
    if len(body) > MAX_BODY:
        return 413, {"error": "too large"}
    try:
        data = json.loads(body.decode("utf-8"))
        text = str(data["text"])
    except Exception:
        return 400, {"error": "bad report"}
    if not text.strip():
        return 400, {"error": "empty"}
    if _count >= DAILY_LIMIT or _per_ip.get(ip, 0) >= PER_IP_LIMIT:
        return 429, {"error": "limit"}
    _count += 1
    _per_ip[ip] = _per_ip.get(ip, 0) + 1

    rid = _report_id()
    app = str(data.get("app", ""))[:40]
    title = str(data.get("title", "问题报告"))[:60]
    head = (f"报告编号：{rid}\n标题：{title}\n应用：{app}\n收到：{datetime.datetime.now(CHINA):%Y-%m-%d %H:%M:%S}\n"
            f"来源：{hashlib.sha1(ip.encode()).hexdigest()[:8]}\n\n")
    stored = (head + text).encode("utf-8")
    key = f"{_today()}/{rid}.txt"
    if DRY_RUN:
        os.makedirs(os.path.join("out", _today()), exist_ok=True)
        with open(os.path.join("out", key), "wb") as f:
            f.write(stored)
    else:
        try:
            _cos_put(key, stored)
        except Exception as e:
            print("cos failed:", e)
            return 502, {"error": "store"}

    # the same problem again: a push the 1st, 5th, 20th, 100th time only
    kind = _kind(text)
    n = _kinds.get(kind, 0) + 1
    _kinds[kind] = n
    if n in (1, 5, 20, 100) and not DRY_RUN:
        model = next((l for l in text.splitlines() if l.startswith("手机")), "")
        more = f"（今天同类第 {n} 份）" if n > 1 else ""
        lines = _first_lines(text, 18)
        _push(f"{title} {rid}", f"{model} {more}\n\n```\n" + "\n".join(lines) + "\n```\n\n文件：cos://{key}")
    return 200, {"id": rid}


class Handler(BaseHTTPRequestHandler):
    def _answer(self, code: int, payload: dict):
        out = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def do_GET(self):
        self._answer(200, {"ok": True})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            return self._answer(413, {"error": "too large"})
        body = self.rfile.read(length)
        ip = (self.headers.get("X-Forwarded-For") or self.client_address[0]).split(",")[0].strip()
        code, payload = handle(body, ip, self.headers.get("X-PhotoAll-Token", ""))
        self._answer(code, payload)

    def log_message(self, fmt, *args):
        print(fmt % args)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 9000), Handler).serve_forever()
