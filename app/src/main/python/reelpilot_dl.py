"""ReelPilot download engine — runs yt-dlp inside the app via Chaquopy."""
import json
import os
from yt_dlp import YoutubeDL

try:
    from com.reelpilot.app.python import ProgressBridge
except ImportError:
    ProgressBridge = None


def _notify(status, pct, speed):
    if ProgressBridge is not None:
        try:
            ProgressBridge.onProgress(status, pct, speed)
        except Exception:
            pass


def download(url, out_dir):
    """Download a video to out_dir. Returns a JSON string with the result."""
    opts = {
        # Single pre-merged file — avoids needing ffmpeg
        "format": "best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/best",
        "outtmpl": os.path.join(out_dir, "%(id)s.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "socket_timeout": 30,
        "retries": 5,
        "max_filesize": 512 * 1024 * 1024,  # 512 MB safety cap
        "progress_hooks": [],
    }

    def hook(d):
        try:
            if d.get("status") == "downloading":
                downloaded = d.get("downloaded_bytes", 0) or 0
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                pct = int(downloaded * 100 / total) if total else 0
                speed = d.get("speed") or 0
                _notify("downloading", pct, int(speed))
            elif d.get("status") == "finished":
                _notify("finished", 100, 0)
        except Exception:
            pass

    opts["progress_hooks"].append(hook)

    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if info is None:
                return json.dumps({"ok": False, "error": "No video found at that link"})
            if "entries" in info:
                entries = [e for e in info["entries"] if e]
                if not entries:
                    return json.dumps({"ok": False, "error": "Playlist was empty"})
                info = entries[0]
            filepath = ydl.prepare_filename(info)
            if not os.path.exists(filepath):
                # Some extractors change extension after merge; probe common ones
                for ext in (".mp4", ".mkv", ".webm"):
                    cand = os.path.splitext(filepath)[0] + ext
                    if os.path.exists(cand):
                        filepath = cand
                        break
            if not os.path.exists(filepath):
                return json.dumps({"ok": False, "error": "Downloaded file was not found on disk"})
            return json.dumps({
                "ok": True,
                "filepath": filepath,
                "title": info.get("title") or "video",
                "duration": info.get("duration") or 0,
                "size": os.path.getsize(filepath),
            })
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})
