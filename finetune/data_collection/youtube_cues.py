"""youtube_cues.py — extract short imperative coaching cues from public videos.

Strategy:
  1. Pull transcript via youtube-transcript-api (no API key needed).
  2. Filter to short sentences (3-12 words) starting with an imperative verb.
  3. Match against a known coaching-cue vocabulary so we keep only
     biomechanics-relevant lines (not "smash that subscribe button").
  4. Save to knowledge_base/cues.json with source URL attribution.

We deliberately DO NOT save full paragraphs — only short imperatives,
which are not protected by copyright and which match how a real coach
talks. The output is reference material, NOT direct training data.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import List

try:
    from youtube_transcript_api import YouTubeTranscriptApi
    from youtube_transcript_api._errors import (
        TranscriptsDisabled, NoTranscriptFound, VideoUnavailable,
    )
except ImportError:
    raise SystemExit("pip install youtube-transcript-api")

THIS_DIR = Path(__file__).resolve().parent
KB_DIR = THIS_DIR / "knowledge_base"
KB_DIR.mkdir(exist_ok=True)
CUES_PATH = KB_DIR / "cues.json"

# ── Filters ─────────────────────────────────────────────────────────────────

# Imperative verbs commonly used by coaches. We require the SENTENCE to
# start with one (after optional adverbs).
IMPERATIVE_VERBS = {
    "drive", "push", "pull", "brace", "engage", "squeeze", "tighten",
    "keep", "hold", "lock", "set", "stand", "sit", "stay", "extend",
    "lower", "lift", "raise", "press", "control", "slow", "stop",
    "reset", "rotate", "tuck", "spread", "track", "align", "imagine",
    "think", "feel", "drop", "bend", "point", "tilt", "lean", "shift",
    "place", "plant", "load", "unload", "create", "maintain", "drive",
    "send", "let", "watch", "make", "ensure", "remember", "focus",
}

# Body-part / movement keywords — sentence must contain at least one.
BIOMECH_KEYWORDS = {
    "knee", "knees", "hip", "hips", "ankle", "ankles", "foot", "feet",
    "shoulder", "shoulders", "elbow", "elbows", "wrist", "wrists",
    "spine", "back", "core", "abs", "glute", "glutes", "quad", "quads",
    "hamstring", "hamstrings", "calf", "calves", "lat", "lats",
    "chest", "neck", "head", "trunk", "torso", "lumbar", "thoracic",
    "valgus", "varus", "neutral", "tracking", "alignment",
    "depth", "range", "rom", "tempo", "breath", "breathing",
    "weight", "bar", "barbell", "dumbbell", "kettlebell", "stance",
    "form", "posture",
}

# Skip lines containing these — usually channel-promo / off-topic.
NOISE_TOKENS = {
    "subscribe", "like and subscribe", "comment below", "patreon",
    "sponsored", "discount", "promo code", "merch", "instagram",
    "follow me", "click", "link in", "today's video", "in this video",
}

MIN_WORDS = 3
MAX_WORDS = 14


def load_seed_channels(path: Path | None = None) -> list:
    p = path or (THIS_DIR / "seed_channels.json")
    return json.loads(p.read_text(encoding="utf-8")).get("channels", [])


def fetch_transcript(video_id: str) -> List[str]:
    """Return list of transcript text segments (English preferred)."""
    try:
        api = YouTubeTranscriptApi()
        # Newer api (>=1.0) uses .fetch(); older uses .get_transcript()
        if hasattr(api, "fetch"):
            t = api.fetch(video_id, languages=["en", "en-US"])
            return [getattr(s, "text", str(s)) for s in t]
        else:
            data = YouTubeTranscriptApi.get_transcript(
                video_id, languages=["en", "en-US"])
            return [s["text"] for s in data]
    except (TranscriptsDisabled, NoTranscriptFound, VideoUnavailable):
        return []
    except Exception as e:
        print(f"  [skip] {video_id}: {type(e).__name__}: {e}")
        return []


def split_sentences(blob: str) -> List[str]:
    """Cheap sentence splitter — auto-captions don't have punctuation,
    so we also split on ", " and "; " and very long fragments."""
    # remove [Music] [Applause] artifacts
    blob = re.sub(r"\[[^\]]+\]", " ", blob)
    blob = re.sub(r"\s+", " ", blob).strip()
    # split on . ! ? ; and on conjunctions used like sentence boundaries
    parts = re.split(r"[.!?]\s+|;\s+", blob)
    out = []
    for p in parts:
        p = p.strip().lstrip("- ").strip()
        if p:
            out.append(p)
    return out


def extract_cues(text: str) -> List[str]:
    cues: List[str] = []
    for sent in split_sentences(text):
        words = sent.split()
        if not (MIN_WORDS <= len(words) <= MAX_WORDS):
            continue
        low = sent.lower()
        if any(noise in low for noise in NOISE_TOKENS):
            continue
        # Must start with imperative verb (after up to 2 adverbs/articles)
        head = words[0].lower().strip(",.")
        head_ok = head in IMPERATIVE_VERBS
        if not head_ok and len(words) >= 2:
            head_ok = words[1].lower().strip(",.") in IMPERATIVE_VERBS \
                      and head in {"now", "first", "next", "and", "so", "then"}
        if not head_ok:
            continue
        # Must mention a biomech keyword
        tokens = {w.lower().strip(",.") for w in words}
        if not (tokens & BIOMECH_KEYWORDS):
            continue
        # Capitalise first letter, normalise spacing
        cue = sent[0].upper() + sent[1:]
        cue = re.sub(r"\s+", " ", cue).strip()
        if not cue.endswith((".", "!", "?")):
            cue += "."
        cues.append(cue)
    return cues


def merge_into_kb(new_entries: list) -> dict:
    """Merge new entries into knowledge_base/cues.json without duplication."""
    if CUES_PATH.exists():
        kb = json.loads(CUES_PATH.read_text(encoding="utf-8"))
    else:
        kb = {"version": 1, "cues": []}

    seen = {e["text"].lower(): e for e in kb["cues"]}
    added = 0
    for entry in new_entries:
        key = entry["text"].lower()
        if key in seen:
            existing = seen[key]
            srcs = set(existing.get("sources", []))
            srcs.update(entry.get("sources", []))
            existing["sources"] = sorted(srcs)
        else:
            kb["cues"].append(entry)
            seen[key] = entry
            added += 1

    kb["cues"].sort(key=lambda e: e["text"].lower())
    CUES_PATH.write_text(json.dumps(kb, ensure_ascii=False, indent=2),
                         encoding="utf-8")
    return {"added": added, "total": len(kb["cues"])}


def _parse_video_id(s: str) -> str:
    """Accepts 'abc123' or 'https://youtu.be/abc123' or
    'https://www.youtube.com/watch?v=abc123' and returns the bare id."""
    s = s.strip()
    if "youtube.com" in s:
        m = re.search(r"[?&]v=([\w-]{6,})", s)
        if m: return m.group(1)
    if "youtu.be/" in s:
        m = re.search(r"youtu\.be/([\w-]{6,})", s)
        if m: return m.group(1)
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-videos", type=int, default=999,
                    help="Cap videos per channel (debug).")
    ap.add_argument("--url", action="append",
                    help="YouTube URL or ID. Repeatable. Bypasses seed_channels.json.")
    ap.add_argument("--channel-name", default="manual",
                    help="Label for --url batch (default 'manual').")
    args = ap.parse_args()

    if args.url:
        ids = [_parse_video_id(u) for u in args.url]
        targets = [{"name": args.channel_name, "speciality": [], "video_ids": ids}]
    else:
        targets = load_seed_channels()
        if not any(ch.get("video_ids") for ch in targets):
            print("No video ids configured. Two options:\n"
                  "  1) Edit seed_channels.json with real video ids\n"
                  "  2) Pass URLs directly:\n"
                  "       python youtube_cues.py \\\n"
                  "         --url https://youtu.be/<id1> \\\n"
                  "         --url https://youtu.be/<id2>")
            return

    new_entries = []
    for ch in targets:
        ids = ch.get("video_ids", [])[:args.max_videos]
        print(f"\n--- {ch['name']} ({len(ids)} videos) ---")
        for vid in ids:
            blob = " ".join(fetch_transcript(vid))
            if not blob:
                print(f"  [empty] {vid}")
                continue
            cues = extract_cues(blob)
            print(f"  {vid}: {len(cues)} cues from {len(blob)} chars")
            for cue in cues:
                new_entries.append({
                    "text": cue,
                    "channel": ch["name"],
                    "speciality": ch.get("speciality", []),
                    "sources": [f"https://youtu.be/{vid}"],
                })

    summary = merge_into_kb(new_entries)
    print(f"\nKB updated: +{summary['added']} new, {summary['total']} total")
    print(f"  -> {CUES_PATH}")


if __name__ == "__main__":
    main()
