"""
download_models.py

從 HuggingFace 下載 Gemma 4 GGUF 模型（Unsloth 量化版，不需 API key）。
GGUF 格式可直接給 llama.cpp 使用。

模型尺寸（Q4_K_M）：
  E4B-it : ~4.98 GB  ← 主模型
  E2B-it : ~3.11 GB  ← 備援模型

用法：
  python download_models.py               # 下載兩個模型
  python download_models.py --model e4b   # 只下載 E4B
  python download_models.py --model e2b   # 只下載 E2B
  python download_models.py --quant Q5_K_M  # 換量化等級
"""

import argparse
import os
from pathlib import Path

MODELS_DIR = Path("models")
MODELS_DIR.mkdir(exist_ok=True)

QUANT_DEFAULT = "Q4_K_M"

REPOS = {
    "e4b": {
        "repo_id": "unsloth/gemma-4-E4B-it-GGUF",
        "filename_pattern": "{quant}",   # e.g. gemma-4-E4B-it-Q4_K_M.gguf
        "size_hint": "~4.98 GB",
        "out_name": "gemma4-e4b-{quant}.gguf",
    },
    "e2b": {
        "repo_id": "unsloth/gemma-4-E2B-it-GGUF",
        "filename_pattern": "{quant}",
        "size_hint": "~3.11 GB",
        "out_name": "gemma4-e2b-{quant}.gguf",
    },
}


def download(model_key: str, quant: str):
    try:
        from huggingface_hub import hf_hub_download, list_repo_files
    except ImportError:
        print("[錯誤] 請先安裝：pip install huggingface_hub")
        return

    info = REPOS[model_key]
    repo_id = info["repo_id"]
    size_hint = info["size_hint"]
    out_name = info["out_name"].format(quant=quant)
    out_path = MODELS_DIR / out_name

    if out_path.exists():
        mb = out_path.stat().st_size / 1024 / 1024
        print(f"[已存在] {out_path} ({mb:.0f} MB)，跳過下載。")
        return

    # 在 repo 內找符合量化等級的 .gguf 檔名
    print(f"\n[{model_key.upper()}] 搜尋 {repo_id} 中 {quant} 的檔案...")
    try:
        files = list(list_repo_files(repo_id))
    except Exception as e:
        print(f"  [錯誤] 無法列出檔案：{e}")
        return

    matched = [f for f in files if quant in f and f.endswith(".gguf")]
    if not matched:
        print(f"  [錯誤] 找不到 {quant} 的 GGUF 檔。可用檔案：")
        for f in files:
            if f.endswith(".gguf"):
                print(f"    {f}")
        return

    # 優先選單一檔案（非 split）
    single = [f for f in matched if "part" not in f.lower() and "-of-" not in f]
    target_file = single[0] if single else matched[0]

    print(f"  目標檔案：{target_file}")
    print(f"  預估大小：{size_hint}")
    print(f"  儲存位置：{out_path}")
    print(f"  開始下載...")

    try:
        downloaded = hf_hub_download(
            repo_id=repo_id,
            filename=target_file,
            local_dir=str(MODELS_DIR),
            local_dir_use_symlinks=False,
        )
        # 重新命名為統一命名格式
        src = Path(downloaded)
        if src != out_path:
            src.rename(out_path)
        mb = out_path.stat().st_size / 1024 / 1024
        print(f"  [完成] {out_path} ({mb:.0f} MB)")
    except Exception as e:
        print(f"  [錯誤] 下載失敗：{e}")


def main():
    parser = argparse.ArgumentParser(description="GemmaFit 模型下載器")
    parser.add_argument(
        "--model",
        choices=["e4b", "e2b", "both"],
        default="both",
        help="要下載的模型（預設 both）",
    )
    parser.add_argument(
        "--quant",
        default=QUANT_DEFAULT,
        help=f"量化等級（預設 {QUANT_DEFAULT}）",
    )
    args = parser.parse_args()

    print("=" * 50)
    print(f"GemmaFit 模型下載  量化：{args.quant}")
    print(f"儲存目錄：{MODELS_DIR.resolve()}")
    print("=" * 50)

    targets = ["e4b", "e2b"] if args.model == "both" else [args.model]
    for t in targets:
        download(t, args.quant)

    print("\n下載完成。llama.cpp 啟動指令：")
    quant = args.quant
    for t in targets:
        fname = REPOS[t]["out_name"].format(quant=quant)
        print(f"  ./llama-cli -m models/{fname} -f prompt.txt")


if __name__ == "__main__":
    main()
