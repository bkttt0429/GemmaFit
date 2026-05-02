"""gemma_local.py — local Gemma 4 GGUF inference via llama-cpp-python.

Provides `gemma_feedback(report)` with the same dict shape as
`mock_gemma_feedback`, so the dashboard can swap in real Gemma without
changing the rest of the pipeline.

Falls back to mock if llama-cpp-python is not installed or model load
fails. Model handle is cached per (model_path) so we only pay the load
cost once per Streamlit session.
"""

from __future__ import annotations

import json
import os
from typing import Optional

from exercises.core import (
    StructuredMotionReport,
    mock_gemma_feedback,
)

try:
    from llama_cpp import Llama
    HAS_LLAMA = True
    LLAMA_ERR = None
except Exception as e:
    HAS_LLAMA = False
    LLAMA_ERR = str(e)
    Llama = None  # type: ignore


_MODEL_CACHE: dict = {}


SYSTEM_PROMPT = (
    "You are GemmaFit, a concise pose-based movement quality coach. "
    "You read a structured motion report from a single-camera pose system "
    "and respond with ONE short coaching sentence (under 25 words). "
    "Use plain language. Never give medical diagnosis. "
    "If a quality flag is CRITICAL or WARNING, address it directly. "
    "If everything is OK, give brief positive reinforcement."
)


def _build_user_prompt(report: StructuredMotionReport) -> str:
    payload = {
        "exercise": report.exercise,
        "exercise_confidence": round(report.exercise_confidence, 2),
        "phase": report.phase,
        "rep": report.rep,
        "metrics": {k: v for k, v in (report.metrics or {}).items()},
        "quality_flags": [
            {"id": f.id, "status": f.status,
             "value": round(f.value, 1) if isinstance(f.value, (int, float)) else f.value,
             "joint": f.joint}
            for f in (report.quality_flags or [])
        ],
        "not_applicable": [
            {"id": n.id, "reason": n.reason} for n in (report.not_applicable or [])
        ],
    }
    return (
        "Motion report:\n"
        f"{json.dumps(payload, ensure_ascii=False)}\n\n"
        "Reply with one short coaching sentence."
    )


def get_llama(model_path: str,
              n_ctx: int = 2048,
              n_threads: Optional[int] = None,
              n_gpu_layers: int = 0):
    """Load a Llama instance once and cache it."""
    if not HAS_LLAMA:
        raise RuntimeError(f"llama-cpp-python not available: {LLAMA_ERR}")
    if not os.path.exists(model_path):
        raise FileNotFoundError(model_path)
    if model_path in _MODEL_CACHE:
        return _MODEL_CACHE[model_path]
    llm = Llama(
        model_path=model_path,
        n_ctx=n_ctx,
        n_threads=n_threads,
        n_gpu_layers=n_gpu_layers,
        verbose=False,
    )
    _MODEL_CACHE[model_path] = llm
    return llm


def gemma_feedback(report: StructuredMotionReport,
                   model_path: str,
                   max_tokens: int = 80,
                   temperature: float = 0.4) -> dict:
    """Generate coaching feedback using local Gemma 4 GGUF.

    On any failure (missing lib, missing model, inference error) returns
    the mock_gemma_feedback output with `source` updated to indicate
    the fallback. Output dict shape matches mock_gemma_feedback.
    """
    if not HAS_LLAMA:
        out = mock_gemma_feedback(report)
        out["source"] = "mock_gemma_feedback (llama-cpp-python missing)"
        return out

    try:
        llm = get_llama(model_path)
        user = _build_user_prompt(report)
        resp = llm.create_chat_completion(
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": user},
            ],
            max_tokens=max_tokens,
            temperature=temperature,
            stop=["\n\n"],
        )
        msg = resp["choices"][0]["message"]["content"].strip()
        if not msg:
            raise RuntimeError("empty response from Gemma")

        # Determine level from worst quality flag (same logic as mock)
        priority = {"CRITICAL": 0, "WARNING": 1, "MONITOR": 2,
                    "LOW_CONFIDENCE": 3, "OK": 99}
        actives = [f for f in (report.quality_flags or [])
                   if f.status != "OK"]
        actives.sort(key=lambda f: priority.get(f.status, 99))
        level = actives[0].status.lower() if actives else "ok"

        return {
            "source":      "gemma_4_local",
            "frame":       report.frame,
            "level":       level,
            "message":     msg,
            "safety_note": "Pose-based coaching only — not medical diagnosis.",
            "model_path":  os.path.basename(model_path),
        }
    except Exception as e:
        out = mock_gemma_feedback(report)
        out["source"] = f"mock_gemma_feedback (gemma fallback: {type(e).__name__})"
        out["error"] = str(e)
        return out
