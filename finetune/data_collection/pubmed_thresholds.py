"""pubmed_thresholds.py — pull threshold values from PubMed abstracts.

Strategy:
  1. Search PubMed via NCBI E-utilities (no API key for <3 req/s).
  2. Fetch abstracts for matched PMIDs.
  3. Regex-scan abstracts for "<number> degrees" / "<number>°" patterns
     near biomech keywords (knee, trunk, hip, FPPA, valgus, flexion).
  4. Save normalized {metric, value, range, source_pmid, citation} entries
     to knowledge_base/thresholds.json.

These threshold numbers are FACTS — not protected by copyright. We cite
PMIDs so anyone can verify the source. The output is reference data
used to calibrate `prototype/exercises/core.py` thresholds, not direct
training data.
"""
from __future__ import annotations

import argparse
import json
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import List

THIS_DIR = Path(__file__).resolve().parent
KB_DIR = THIS_DIR / "knowledge_base"
KB_DIR.mkdir(exist_ok=True)
TH_PATH = KB_DIR / "thresholds.json"

E_BASE = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"
HEADERS = {"User-Agent": "GemmaFit-research/0.1 (Kaggle Gemma 4 Impact Challenge)"}

# Default queries — covers the four MVP exercises plus key biomechanics.
DEFAULT_QUERIES = [
    "squat trunk lean kinematics",
    "squat depth knee flexion",
    "knee valgus FPPA frontal plane",
    "deadlift hip hinge spine",
    "push-up scapular kinematics",
    "lunge knee tracking biomechanics",
    "joint range of motion squat",
    "angular velocity rapid movement injury",
]

# Pattern: "<number>°" / "<number> deg" / "<number> degrees" / "<number>-deg".
# Unicode-aware (covers °, º, ˚, ⁰).
DEG_PAT = re.compile(
    r"(?P<lo>\d{1,3}(?:\.\d+)?)\s*[-]?\s*"
    r"(?:[°º˚⁰]|degrees?|deg(?:ree)?s?)\b"
    r"(?:\s*(?:to|[-–~])\s*(?P<hi>\d{1,3}(?:\.\d+)?)"
    r"\s*[-]?\s*(?:[°º˚⁰]|degrees?|deg(?:ree)?s?)?)?",
    re.IGNORECASE,
)

BIOMECH_TARGETS = {
    "trunk": "trunk_lean_deg",
    "torso": "trunk_lean_deg",
    "spine": "spine_flexion_deg",
    "lumbar": "lumbar_flexion_deg",
    "knee flex": "knee_flexion_deg",
    "knee angle": "knee_flexion_deg",
    "fppa": "knee_valgus_fppa_deg",
    "valgus": "knee_valgus_fppa_deg",
    "hip flex": "hip_flexion_deg",
    "hip angle": "hip_flexion_deg",
    "elbow flex": "elbow_flexion_deg",
    "ankle dorsiflex": "ankle_dorsiflexion_deg",
    "shoulder flex": "shoulder_flexion_deg",
    "neck flex": "neck_flexion_deg",
}


def http_get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"))


def http_get_text(url: str) -> str:
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.read().decode("utf-8", errors="replace")


def search_pmids(query: str, retmax: int = 20) -> List[str]:
    q = urllib.parse.quote_plus(query)
    url = f"{E_BASE}/esearch.fcgi?db=pubmed&term={q}&retmax={retmax}&retmode=json"
    js = http_get_json(url)
    return js.get("esearchresult", {}).get("idlist", [])


def fetch_abstracts(pmids: List[str]) -> dict:
    """Returns {pmid: {"title": str, "abstract": str, "year": str}}.

    PubMed structured abstracts are split into multiple <AbstractText
    Label="BACKGROUND|METHODS|RESULTS|CONCLUSIONS"> elements; we
    concatenate all of them so RESULTS-section numeric values are kept.
    """
    if not pmids: return {}
    ids = ",".join(pmids)
    url = f"{E_BASE}/efetch.fcgi?db=pubmed&id={ids}&retmode=xml"
    xml = http_get_text(url)
    out = {}
    for art in re.finditer(r"<PubmedArticle>(.*?)</PubmedArticle>", xml, re.S):
        body = art.group(1)
        pmid_m = re.search(r"<PMID[^>]*>(\d+)</PMID>", body)
        if not pmid_m: continue
        pmid = pmid_m.group(1)
        title = ""
        tm = re.search(r"<ArticleTitle>(.*?)</ArticleTitle>", body, re.S)
        if tm: title = tm.group(1)
        # concatenate ALL <AbstractText> segments (with optional Label="")
        segs = re.findall(
            r"<AbstractText[^>]*>(.*?)</AbstractText>", body, re.S)
        abstract = " ".join(segs).strip()
        if not abstract: continue
        ym = re.search(r"<PubDate>.*?<Year>(\d{4})</Year>", body, re.S)
        out[pmid] = {
            "title": _strip_xml(title),
            "abstract": _strip_xml(abstract),
            "year": ym.group(1) if ym else "",
        }
    return out


def _strip_xml(s: str) -> str:
    s = re.sub(r"<[^>]+>", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def extract_thresholds(pmid: str, meta: dict) -> List[dict]:
    """Find degree values mentioned near biomech keywords."""
    text = (meta["abstract"] + " " + meta["title"]).lower()
    findings = []
    for m in DEG_PAT.finditer(text):
        lo = float(m.group("lo"))
        hi = float(m.group("hi")) if m.group("hi") else None
        # require value in plausible range for body angle
        if lo > 360: continue
        # context window ±100 chars (papers often mention body part further away)
        s, e = m.start(), m.end()
        ctx_l = text[max(0, s - 100):s]
        ctx_r = text[e:e + 50]
        ctx = ctx_l + " " + ctx_r
        # determine which body part this is about
        metric = None
        for kw, name in BIOMECH_TARGETS.items():
            if kw in ctx:
                metric = name
                break
        if metric is None:
            continue
        findings.append({
            "metric": metric,
            "value_deg": lo,
            "range_max_deg": hi,
            "context": _strip_xml(text[max(0, s - 80):min(len(text), e + 40)]),
            "source_pmid": pmid,
            "year": meta["year"],
            "citation": f"PMID:{pmid} ({meta['year']}) — {meta['title'][:120]}",
        })
    return findings


def merge_into_kb(new_entries: list) -> dict:
    if TH_PATH.exists():
        kb = json.loads(TH_PATH.read_text(encoding="utf-8"))
    else:
        kb = {"version": 1, "thresholds": []}
    # dedupe by (metric, value_deg, source_pmid)
    seen = {(e["metric"], e["value_deg"], e["source_pmid"]) for e in kb["thresholds"]}
    added = 0
    for e in new_entries:
        key = (e["metric"], e["value_deg"], e["source_pmid"])
        if key in seen: continue
        kb["thresholds"].append(e)
        seen.add(key)
        added += 1
    kb["thresholds"].sort(key=lambda e: (e["metric"], e["value_deg"]))
    TH_PATH.write_text(json.dumps(kb, ensure_ascii=False, indent=2),
                       encoding="utf-8")
    return {"added": added, "total": len(kb["thresholds"])}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--query", action="append",
                    help="Query string (can be repeated). Default: all queries.")
    ap.add_argument("--per-query", type=int, default=20,
                    help="Max PMIDs per query (default 20).")
    args = ap.parse_args()

    queries = args.query or DEFAULT_QUERIES
    all_findings = []
    for q in queries:
        print(f"\n--- query: {q!r} ---")
        pmids = search_pmids(q, retmax=args.per_query)
        print(f"  {len(pmids)} PMIDs")
        if not pmids: continue
        time.sleep(0.4)
        abstracts = fetch_abstracts(pmids)
        print(f"  {len(abstracts)} abstracts pulled")
        per_q = 0
        for pmid, meta in abstracts.items():
            findings = extract_thresholds(pmid, meta)
            if findings:
                all_findings.extend(findings)
                per_q += len(findings)
        print(f"  +{per_q} threshold mentions")
        time.sleep(0.4)

    summary = merge_into_kb(all_findings)
    print(f"\nKB updated: +{summary['added']} new, {summary['total']} total")
    print(f"  -> {TH_PATH}")


if __name__ == "__main__":
    main()
