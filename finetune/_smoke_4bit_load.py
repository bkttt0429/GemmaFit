"""
_smoke_4bit_load.py — 4-bit BnB smoke test for evaluate_e2b_video_capability_local.ipynb.

Purpose: validate the 4-bit Gemma 4 E2B load + a single image-text generation
fits within ~4 GB VRAM BEFORE running the full 12-cell notebook. If this
script OOMs, the notebook will too.

Usage (gemmafit env):
    python finetune/_smoke_4bit_load.py
    python finetune/_smoke_4bit_load.py --max-new-tokens 64 --frames 1
    python finetune/_smoke_4bit_load.py --no-generate    # load only

Reports:
    - Resolved CUDA + VRAM
    - Model load latency, peak VRAM after load
    - Generation latency, peak VRAM after generate
    - First 200 chars of output

Exit code:
    0 = load + generate succeeded
    1 = OOM or load failure
    2 = transformers/HF auth issue (gated repo)
"""
from __future__ import annotations

import argparse
import os
import sys
import time
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DEFAULT_MODEL = os.environ.get("GEMMA_E2B_MODEL", "google/gemma-4-E2B-it")
DEFAULT_VIDEO = ROOT / "test_assets" / "videos" / "squat_wikimedia_01.webm"

DEFAULT_PROMPT = (
    "Describe what you see in this image in one short sentence. "
    "Do not diagnose, predict fall risk, or estimate force."
)


def _load_dotenv() -> None:
    env = ROOT / ".env"
    if not env.exists():
        return
    for line in env.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        v = v.strip().strip('"').strip("'")
        if k and k not in os.environ:
            os.environ[k] = v


def _grab_frame(video: Path, frame_idx: int = 30):
    import cv2
    from PIL import Image
    cap = cv2.VideoCapture(str(video))
    try:
        cap.set(cv2.CAP_PROP_POS_FRAMES, frame_idx)
        ok, frame = cap.read()
        if not ok or frame is None:
            return None
        return Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
    finally:
        cap.release()


def _vram_gb() -> float:
    import torch
    if not torch.cuda.is_available():
        return 0.0
    return round(torch.cuda.max_memory_allocated() / (1024 ** 3), 2)


def _reset_peak() -> None:
    import torch
    if torch.cuda.is_available():
        torch.cuda.reset_peak_memory_stats()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--video", type=Path, default=DEFAULT_VIDEO)
    ap.add_argument("--frames", type=int, default=1, help="Number of image inputs (1 keeps VRAM lowest)")
    ap.add_argument("--max-new-tokens", type=int, default=64)
    ap.add_argument("--no-generate", action="store_true",
                    help="Skip generate; just confirm load fits")
    ap.add_argument("--prompt", default=DEFAULT_PROMPT)
    args = ap.parse_args()

    _load_dotenv()
    hf_token = os.environ.get("HF_TOKEN") or os.environ.get("HUGGINGFACE_TOKEN") \
               or os.environ.get("HUGGING_FACE_HUB_TOKEN")

    print("=" * 60)
    print(f"Model         : {args.model}")
    print(f"Video         : {args.video}")
    print(f"Frames        : {args.frames}")
    print(f"Max new tokens: {args.max_new_tokens}")
    print(f"HF token      : {'set' if hf_token else 'MISSING'}")
    print("=" * 60)

    try:
        import torch
    except Exception as e:
        print(f"FATAL: torch import failed: {e}")
        return 1

    if not torch.cuda.is_available():
        print("FATAL: CUDA not available. Install CUDA torch first:")
        print("  pip install torch --index-url https://download.pytorch.org/whl/cu124")
        return 1

    total_vram_gb = round(torch.cuda.get_device_properties(0).total_memory / (1024 ** 3), 2)
    print(f"GPU           : {torch.cuda.get_device_name(0)}")
    print(f"Total VRAM GB : {total_vram_gb}")
    print()

    try:
        from transformers import (
            AutoProcessor,
            AutoModelForImageTextToText,
            AutoModelForCausalLM,
            BitsAndBytesConfig,
        )
        import bitsandbytes  # noqa: F401 — ensure available
    except Exception as e:
        print(f"FATAL: missing deps: {e}")
        return 2

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )

    common_kwargs = {
        "token": hf_token,
        "trust_remote_code": False,
    }

    print("[1/3] Loading processor...", flush=True)
    t0 = time.time()
    try:
        processor = AutoProcessor.from_pretrained(args.model, **common_kwargs)
    except Exception as e:
        print(f"FATAL: processor load failed: {type(e).__name__}: {e}")
        if "401" in str(e) or "gated" in str(e).lower():
            print("  → check that you've accepted the model license at "
                  f"https://huggingface.co/{args.model}")
            return 2
        return 1
    print(f"      processor loaded in {time.time() - t0:.1f}s")

    _reset_peak()
    print(f"[2/3] Loading model in 4-bit (NF4 + double quant + bf16 compute)...", flush=True)
    t0 = time.time()
    try:
        model = AutoModelForImageTextToText.from_pretrained(
            args.model,
            torch_dtype=torch.bfloat16,
            device_map="auto",
            quantization_config=bnb_config,
            **common_kwargs,
        )
        model_family = "AutoModelForImageTextToText"
    except Exception as e:
        print(f"      ImageText load failed ({type(e).__name__}); falling back to CausalLM")
        try:
            model = AutoModelForCausalLM.from_pretrained(
                args.model,
                torch_dtype=torch.bfloat16,
                device_map="auto",
                quantization_config=bnb_config,
                **common_kwargs,
            )
            model_family = "AutoModelForCausalLM"
        except torch.cuda.OutOfMemoryError:
            print("FATAL: OOM at model load. Try a smaller model or CPU offload.")
            return 1
        except Exception as e2:
            print(f"FATAL: model load failed: {type(e2).__name__}: {e2}")
            traceback.print_exc()
            return 1
    load_seconds = time.time() - t0
    peak_after_load = _vram_gb()
    print(f"      model loaded in {load_seconds:.1f}s as {model_family}")
    print(f"      peak VRAM after load: {peak_after_load} GB / {total_vram_gb} GB")
    model.eval()

    if args.no_generate:
        print()
        print("[3/3] --no-generate set; skipping generate.")
        print("PASS: 4-bit load fits in VRAM.")
        return 0

    frame = _grab_frame(args.video) if args.video.exists() else None
    if frame is None:
        print(f"      WARN: could not load a frame from {args.video}; using a 1px blank.")
        from PIL import Image
        frame = Image.new("RGB", (224, 224), (200, 200, 200))
    images = [frame] * max(1, args.frames)

    messages = [
        {
            "role": "user",
            "content": [{"type": "image", "image": img} for img in images]
                       + [{"type": "text", "text": args.prompt}],
        },
    ]

    _reset_peak()
    print(f"[3/3] Generating ({args.max_new_tokens} tokens)...", flush=True)
    t0 = time.time()
    try:
        try:
            inputs = processor.apply_chat_template(
                messages, add_generation_prompt=True, tokenize=True,
                return_dict=True, return_tensors="pt",
            )
        except Exception:
            inputs = processor(text=args.prompt, images=images, return_tensors="pt")

        device = next(model.parameters()).device
        inputs = {k: (v.to(device) if hasattr(v, "to") else v) for k, v in inputs.items()}

        gen_kwargs = {"max_new_tokens": args.max_new_tokens, "do_sample": False}
        tok = getattr(processor, "tokenizer", None)
        if tok is not None and tok.eos_token_id is not None:
            gen_kwargs["pad_token_id"] = tok.eos_token_id

        with torch.inference_mode():
            out = model.generate(**inputs, **gen_kwargs)

        input_len = inputs["input_ids"].shape[-1]
        new_tokens = out[:, input_len:]
        text = processor.batch_decode(new_tokens, skip_special_tokens=True)[0].strip()
    except torch.cuda.OutOfMemoryError:
        print("FATAL: OOM during generate. Try --frames 1 --max-new-tokens 32.")
        return 1
    except Exception as e:
        print(f"FATAL: generate failed: {type(e).__name__}: {e}")
        traceback.print_exc()
        return 1

    gen_seconds = time.time() - t0
    peak_after_gen = _vram_gb()
    tokens_per_s = (out.shape[-1] - input_len) / gen_seconds if gen_seconds > 0 else 0
    print(f"      generate complete in {gen_seconds:.2f}s ({tokens_per_s:.1f} tok/s)")
    print(f"      peak VRAM during generate: {peak_after_gen} GB / {total_vram_gb} GB")
    print()
    print("=" * 60)
    print(f"OUTPUT (first 300 chars):\n{text[:300]}")
    print("=" * 60)
    print("PASS: 4-bit load + generate fit on this GPU.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
