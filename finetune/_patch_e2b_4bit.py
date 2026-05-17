"""One-shot patcher: add LOAD_IN_4BIT support to evaluate_e2b_video_capability_local.ipynb.

- Cell 1 (config): expose LOAD_IN_4BIT env switch (defaults to auto on CUDA + ≤ 6 GB VRAM).
- Cell 2 (deps):   add bitsandbytes to optional install list.
- Cell 6 (load):   honor LOAD_IN_4BIT via BitsAndBytesConfig.
"""
from __future__ import annotations

import json
from pathlib import Path

NB = Path(__file__).resolve().parent / "evaluate_e2b_video_capability_local.ipynb"


def _set_source(cell, lines: list[str]) -> None:
    cell["source"] = [(line + "\n") for line in lines[:-1]] + [lines[-1]]


def patch():
    nb = json.loads(NB.read_text(encoding="utf-8"))
    cells = nb["cells"]

    # Cell 1 — append LOAD_IN_4BIT config below REQUIRE_GPU.
    cell1 = cells[1]
    src = "".join(cell1["source"])
    if "LOAD_IN_4BIT" not in src:
        marker = 'REQUIRE_GPU = os.environ.get("REQUIRE_GPU", "0") == "1"\n'
        addition = (
            'LOAD_IN_4BIT = os.environ.get("LOAD_IN_4BIT", "auto").lower()  '
            '# "auto" | "1" | "0"\n'
        )
        new_src = src.replace(marker, marker + addition)
        # also extend the diagnostic prints at the bottom
        if 'Frames / video' in new_src and 'Load in 4-bit' not in new_src:
            new_src = new_src.replace(
                'print("Realtime sim    :", RUN_REALTIME_SIM)',
                'print("Load in 4-bit   :", LOAD_IN_4BIT)\n'
                'print("Realtime sim    :", RUN_REALTIME_SIM)',
            )
        cell1["source"] = new_src.splitlines(keepends=True)

    # Cell 2 — append bitsandbytes to the install list.
    cell2 = cells[2]
    src = "".join(cell2["source"])
    if '"bitsandbytes"' not in src:
        new_src = src.replace(
            '"protobuf",',
            '"protobuf",\n        "bitsandbytes",',
        )
        cell2["source"] = new_src.splitlines(keepends=True)

    # Cell 6 — model load. Replace block to add LOAD_IN_4BIT path.
    cell6 = cells[6]
    src = "".join(cell6["source"])
    if "BitsAndBytesConfig" not in src:
        old = (
            'from transformers import AutoProcessor, AutoModelForImageTextToText, AutoModelForCausalLM\n'
        )
        new = (
            'from transformers import AutoProcessor, AutoModelForImageTextToText, AutoModelForCausalLM, BitsAndBytesConfig\n'
        )
        src = src.replace(old, new)

        inject = (
            'def _resolve_load_in_4bit() -> bool:\n'
            '    if LOAD_IN_4BIT == "1":\n'
            '        return True\n'
            '    if LOAD_IN_4BIT == "0":\n'
            '        return False\n'
            '    # "auto": enable on CUDA when total VRAM <= 6 GB.\n'
            '    if not torch.cuda.is_available():\n'
            '        return False\n'
            '    try:\n'
            '        total_gb = torch.cuda.get_device_properties(0).total_memory / (1024 ** 3)\n'
            '    except Exception:\n'
            '        return False\n'
            '    return total_gb <= 6.5\n'
            '\n'
            'use_4bit = _resolve_load_in_4bit()\n'
            'print("Resolved 4-bit load:", use_4bit)\n'
            '\n'
            'bnb_config = None\n'
            'if use_4bit:\n'
            '    if not torch.cuda.is_available():\n'
            '        raise RuntimeError("LOAD_IN_4BIT requires CUDA. bitsandbytes 4-bit is GPU-only.")\n'
            '    bnb_config = BitsAndBytesConfig(\n'
            '        load_in_4bit=True,\n'
            '        bnb_4bit_quant_type="nf4",\n'
            '        bnb_4bit_compute_dtype=torch.bfloat16,\n'
            '        bnb_4bit_use_double_quant=True,\n'
            '    )\n'
            '\n'
        )
        src = src.replace(
            'torch_dtype = torch.bfloat16 if torch.cuda.is_available() else torch.float32\n',
            inject + 'torch_dtype = torch.bfloat16 if torch.cuda.is_available() else torch.float32\n',
        )

        # Add quantization_config into both from_pretrained calls.
        old_load_image = (
            '    model = AutoModelForImageTextToText.from_pretrained(\n'
            '        MODEL_ID_OR_PATH,\n'
            '        torch_dtype=torch_dtype,\n'
            '        device_map="auto" if torch.cuda.is_available() else None,\n'
            '        **common_kwargs,\n'
            '    )\n'
        )
        new_load_image = (
            '    model = AutoModelForImageTextToText.from_pretrained(\n'
            '        MODEL_ID_OR_PATH,\n'
            '        torch_dtype=torch_dtype,\n'
            '        device_map="auto" if torch.cuda.is_available() else None,\n'
            '        quantization_config=bnb_config,\n'
            '        **common_kwargs,\n'
            '    )\n'
        )
        src = src.replace(old_load_image, new_load_image)

        old_load_causal = (
            '    model = AutoModelForCausalLM.from_pretrained(\n'
            '        MODEL_ID_OR_PATH,\n'
            '        torch_dtype=torch_dtype,\n'
            '        device_map="auto" if torch.cuda.is_available() else None,\n'
            '        **common_kwargs,\n'
            '    )\n'
        )
        new_load_causal = (
            '    model = AutoModelForCausalLM.from_pretrained(\n'
            '        MODEL_ID_OR_PATH,\n'
            '        torch_dtype=torch_dtype,\n'
            '        device_map="auto" if torch.cuda.is_available() else None,\n'
            '        quantization_config=bnb_config,\n'
            '        **common_kwargs,\n'
            '    )\n'
        )
        src = src.replace(old_load_causal, new_load_causal)

        cell6["source"] = src.splitlines(keepends=True)

    NB.write_text(json.dumps(nb, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"Patched {NB}")


if __name__ == "__main__":
    patch()
