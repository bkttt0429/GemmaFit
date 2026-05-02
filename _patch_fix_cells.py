"""Restore §1.5 hardware detection code at cell 6, and write new wired
pre-flight to cell 24 (the actual §5.5 location)."""
import json
from pathlib import Path

NB = Path("c:/Users/ken/OneDrive/桌面/GemmaFit/finetune/train_gemma4_pipeline.ipynb")
nb = json.loads(NB.read_text(encoding="utf-8"))


def code(text):
    return {"cell_type": "code", "execution_count": None,
            "metadata": {}, "outputs": [],
            "source": text.splitlines(keepends=True)}


HW_CODE = r'''import os, subprocess, platform
import torch

print('=' * 60)
print('  Hardware detection')
print('=' * 60)

# ── 1. GPU ────────────────────────────────────────────────────────────────
n_gpu = torch.cuda.device_count()
gpus = []
total_vram = 0
for i in range(n_gpu):
    p = torch.cuda.get_device_properties(i)
    vram_gb = p.total_memory / 1e9
    total_vram += vram_gb
    gpus.append({'name': p.name, 'vram_gb': vram_gb,
                 'compute': f'{p.major}.{p.minor}'})

print(f'\nGPU count: {n_gpu}')
for i, g in enumerate(gpus):
    print(f'  [{i}] {g["name"]:30s}  {g["vram_gb"]:5.1f} GB  cc{g["compute"]}')
print(f'  Total VRAM: {total_vram:.1f} GB')

if n_gpu == 0:
    raise SystemExit('❌ No GPU detected. Runtime → Change runtime type → GPU.')

# ── 2. Decide profile ─────────────────────────────────────────────────────
gpu0 = gpus[0]['name']
vram = gpus[0]['vram_gb']

if 'A100' in gpu0:
    profile = 'A100'
    RECOMMENDED_MODEL = 'unsloth/gemma-4-E4B-it'
    RECOMMENDED_SEQ_LEN = 2048
    RECOMMENDED_BATCH = 4
    GRAD_ACCUM = 4
    EST_STEPS_PER_SEC = 6.0
elif 'L4' in gpu0:
    profile = 'L4'
    RECOMMENDED_MODEL = 'unsloth/gemma-4-E4B-it'
    RECOMMENDED_SEQ_LEN = 2048
    RECOMMENDED_BATCH = 2
    GRAD_ACCUM = 8
    EST_STEPS_PER_SEC = 3.5
elif 'V100' in gpu0:
    profile = 'V100'
    RECOMMENDED_MODEL = 'unsloth/gemma-4-E4B-it'
    RECOMMENDED_SEQ_LEN = 1536
    RECOMMENDED_BATCH = 2
    GRAD_ACCUM = 8
    EST_STEPS_PER_SEC = 2.5
elif 'T4' in gpu0:
    profile = 'T4'
    if n_gpu >= 2:
        RECOMMENDED_MODEL = 'unsloth/gemma-4-E4B-it'
        RECOMMENDED_SEQ_LEN = 1024
        RECOMMENDED_BATCH = 2
        GRAD_ACCUM = 8
        EST_STEPS_PER_SEC = 1.5
    else:
        RECOMMENDED_MODEL = 'unsloth/gemma-4-E2B-it'
        RECOMMENDED_SEQ_LEN = 1024
        RECOMMENDED_BATCH = 2
        GRAD_ACCUM = 8
        EST_STEPS_PER_SEC = 1.0
elif 'H100' in gpu0:
    profile = 'H100'
    RECOMMENDED_MODEL = 'unsloth/gemma-4-E4B-it'
    RECOMMENDED_SEQ_LEN = 4096
    RECOMMENDED_BATCH = 8
    GRAD_ACCUM = 2
    EST_STEPS_PER_SEC = 12.0
else:
    profile = 'unknown'
    RECOMMENDED_MODEL = 'unsloth/gemma-4-E2B-it'
    RECOMMENDED_SEQ_LEN = 1024
    RECOMMENDED_BATCH = 1
    GRAD_ACCUM = 16
    EST_STEPS_PER_SEC = 1.0

DEVICE_MAP = 'balanced' if n_gpu > 1 else None

# ── 3. CPU / RAM / disk ───────────────────────────────────────────────────
import psutil
cpu_cnt = os.cpu_count() or 0
ram_gb = psutil.virtual_memory().total / 1e9
disk = psutil.disk_usage('/content')
disk_free_gb = disk.free / 1e9

print(f'\nCPU: {cpu_cnt} cores  |  RAM: {ram_gb:.1f} GB')
print(f'Disk free in /content: {disk_free_gb:.1f} GB')

# ── 4. Drive ──────────────────────────────────────────────────────────────
drive_ok = os.path.ismount('/content/drive') or os.path.exists('/content/drive/MyDrive')
print(f'Drive mounted: {"yes" if drive_ok else "NO — run §1 first"}')

# ── 5. Recommendations ────────────────────────────────────────────────────
print()
print('=' * 60)
print(f'  Profile: {profile}')
print('=' * 60)
print(f'  RECOMMENDED_MODEL    = {RECOMMENDED_MODEL!r}')
print(f'  RECOMMENDED_SEQ_LEN  = {RECOMMENDED_SEQ_LEN}')
print(f'  RECOMMENDED_BATCH    = {RECOMMENDED_BATCH}')
print(f'  GRAD_ACCUM           = {GRAD_ACCUM}    (effective batch = {RECOMMENDED_BATCH * GRAD_ACCUM})')
print(f'  DEVICE_MAP           = {DEVICE_MAP!r}')
print(f'  EST_STEPS_PER_SEC    = {EST_STEPS_PER_SEC}')
print(f'  est. 5000 steps      = {5000 / EST_STEPS_PER_SEC / 60:.0f} min '
      f'({5000 / EST_STEPS_PER_SEC / 3600:.1f} hr)')

# ── 6. Warnings ───────────────────────────────────────────────────────────
warnings = []
if vram < 14:
    warnings.append(f'⚠ VRAM {vram:.1f} GB is tight for E4B even in 4-bit. Consider E2B.')
if disk_free_gb < 15:
    warnings.append(f'⚠ Only {disk_free_gb:.1f} GB free in /content. GGUF conversion needs ~10 GB temp space.')
if ram_gb < 10:
    warnings.append(f'⚠ RAM {ram_gb:.1f} GB is low. May OOM on dataset preprocessing.')
if not drive_ok:
    warnings.append('⚠ Drive not mounted — checkpoints will be lost on disconnect.')
if profile == 'unknown':
    warnings.append(f'⚠ Unknown GPU "{gpu0}". Using safe defaults; tune manually.')

if warnings:
    print('\n--- Warnings ---')
    for w in warnings: print(f'  {w}')
else:
    print('\n🟢 No warnings.')
'''


PREFLIGHT_NEW = r'''import os, json, torch

print('=== Pre-flight check ===\n')

# 1. Model + GPU
assert 'model' in dir(), '❌ Model not loaded — re-run §5'
gpu_mem = torch.cuda.memory_allocated() / 1e9
assert gpu_mem > 0.5, f'❌ GPU memory unexpectedly low: {gpu_mem:.2f} GB'
print(f'✅ Model on GPU ({gpu_mem:.2f} GB allocated)')

# 2. Mixed stream produces samples
sample = next(iter(mixed.take(1)))
assert 'messages' in sample, '❌ Stream missing "messages" key'
assert len(sample['messages']) >= 2, '❌ Sample has <2 messages'
print(f'✅ Mixed stream produces valid samples ({len(sample["messages"])} msgs in first)')

# 3. Tokenizer chat template
formatted = tokenizer.apply_chat_template(
    sample['messages'], tokenize=False, add_generation_prompt=False)
assert len(formatted) > 50, f'❌ Empty chat template output: {formatted!r}'
print(f'✅ Chat template renders ({len(formatted)} chars)')

# 4. Drive is mounted + writable
test_file = f'{WORKDIR}/_preflight_test.txt'
with open(test_file, 'w') as f:
    f.write('ok')
os.remove(test_file)
print(f'✅ Drive writable: {WORKDIR}')

# 5. Eval set
assert 'eval_fmt' in dir(), '❌ eval_fmt not built — re-run the eval-split cell in §2'
assert len(eval_fmt) >= 10, f'❌ Eval set too small: {len(eval_fmt)}'
print(f'✅ Eval set ready ({len(eval_fmt)} examples)')

# 6. Effective config — pulls from §1.5 if available
batch  = globals().get('RECOMMENDED_BATCH', 4)
accum  = globals().get('GRAD_ACCUM', 4)
sps    = globals().get('EST_STEPS_PER_SEC', 6.0)
seq    = globals().get('RECOMMENDED_SEQ_LEN', 2048)
mdl    = globals().get('MODEL_NAME', 'unsloth/gemma-4-E4B-it')

print()
print('=== Effective training config ===')
print(f'  model                       = {mdl}')
print(f'  per_device_train_batch_size = {batch}')
print(f'  gradient_accumulation_steps = {accum}      (effective batch = {batch * accum})')
print(f'  max_seq_length              = {seq}')
print(f'  estimated steps/sec         = {sps}')
print(f'  → 5000 steps in ~{5000 / sps / 3600:.2f} hr')

print('\n🟢 All checks passed. Set RUN_TRAINING=True in §6 to start.')
'''


cells = nb["cells"]

# Cell 6: §1.5 hardware-detection code (currently has wrong content)
cells[6] = code(HW_CODE)
print("Cell 6 (§1.5 hardware-detection) restored")

# Cell 24: §5.5 pre-flight check (currently has old version w/o §1.5 wiring)
cells[24] = code(PREFLIGHT_NEW)
print("Cell 24 (§5.5 pre-flight) updated with §1.5 wiring")

NB.write_text(json.dumps(nb, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"\nFinal notebook: {len(cells)} cells")
