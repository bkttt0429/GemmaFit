# Data Collection — External References

These scripts extract **facts** (threshold numbers) and **short cue patterns**
(imperative coaching phrases) from public sources. The output is a
"knowledge base" of references that GemmaFit uses to:

1. Inform threshold tuning (`prototype/exercises/core.py`).
2. Guide hand-writing of fine-tune examples in own words.
3. Provide few-shot examples in prompts (with attribution).

## What goes in here

| File | Purpose | Effort |
| --- | --- | --- |
| `youtube_cues.py` | Extract short imperative cues from public YouTube channels. | 30 min run |
| `pubmed_thresholds.py` | Pull abstracts mentioning angle/threshold values from PubMed. | 30 min run |

## What does NOT go in here

| Source | Why skipped | If we needed it |
| --- | --- | --- |
| NSCA Essentials of Strength Training | Copyrighted book, OCR cost high, IP risk | Buy ebook, OCR with `pytesseract`, hand-clean |
| Starting Strength (Mark Rippetoe) | Same | Same |
| Reddit r/FormCheck | Reddit API now paywalled / rate-limited; comment quality very mixed | Reddit API + `praw`; manual filter |

## IP / safety rules

1. **Never copy raw text into training data.** Use it as reference only.
2. **Threshold numbers are facts** — not protected by copyright. Cite where they came from in code comments.
3. **Short imperative cues** ("drive your knees out", "brace your core") are
   short enough to fall outside copyright, but never quote a full paragraph.
4. **Always attribute** sources in `knowledge_base/source.json` next to the extracted fact.

## Output structure

```
finetune/data_collection/
├── README.md
├── youtube_cues.py
├── pubmed_thresholds.py
├── seed_channels.json          # what to query
└── knowledge_base/
    ├── cues.json               # short imperative phrases
    └── thresholds.json         # angle / ROM threshold values with citations
```

## Usage

```bash
cd finetune/data_collection
python youtube_cues.py --max-videos 20
python pubmed_thresholds.py --query "squat trunk lean"
```

Both scripts are idempotent — they merge new findings into the existing
knowledge base without overwriting.
