#!/usr/bin/env python3
"""Experimental Gemma4 vision LiteRT-LM exporter patch.

This runner is intentionally opt-in. It adapts the public Tinman-Lab
Gemma4 LiteRT-LM workaround for GemmaFit's merged HF checkpoint path:

  - use the built-in litert-torch Gemma4 text export path
  - add Gemma4 vision encoder / vision adapter exportables
  - skip vision quantization, keeping the vision encoder FP32
  - patch Gemma4 LiteRT-LM protobuf metadata

The upstream public exporter may change at any time. Treat this as an
experiment for image_text_to_text only, not as a stable production path.
"""

from __future__ import annotations

import argparse
import dataclasses
import gc
import json
import os
from pathlib import Path
from typing import Any


def _parse_prefill_lengths(value: str) -> list[int]:
    value = value.strip()
    if value.startswith("["):
        parsed = json.loads(value)
        if not isinstance(parsed, list):
            raise ValueError(f"prefill_lengths must be a list: {value}")
        return [int(item) for item in parsed]
    return [int(item.strip()) for item in value.split(",") if item.strip()]


def _total_ram_gb() -> float | None:
    meminfo = Path("/proc/meminfo")
    if not meminfo.exists():
        return None
    for line in meminfo.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("MemTotal:"):
            parts = line.split()
            if len(parts) >= 2:
                return int(parts[1]) / 1024 / 1024
    return None


def apply_gemma4_vision_patches() -> None:
    import torch
    from ai_edge_litert.internal import llm_model_type_pb2
    from litert_torch.generative.export_hf.core import export_lib
    from litert_torch.generative.export_hf.core import exportable_module as exportable_module_base
    from litert_torch.generative.export_hf.core import litert_lm_builder
    from litert_torch.generative.export_hf.model_ext import exportables as exportables_mod
    from litert_torch.generative.export_hf.model_ext import metadata_builder as metadata_builder_lib

    @export_lib.progress.task("Export vision encoder models")
    def _patched_export_vision_encoder_models(
        source_model_artifacts: Any,
        export_config: Any,
        exported_model_artifacts: Any,
    ) -> Any:
        """Export Gemma4 vision models without quantization."""
        model = source_model_artifacts.model
        image_processor = source_model_artifacts.image_processor
        model_config = source_model_artifacts.model_config
        tokenizer = source_model_artifacts.tokenizer
        work_dir = export_config.work_dir

        model.set_attn_implementation("eager")
        encoder_module_cls, adapter_module_cls = exportables_mod.get_vision_exportables(model_config)
        encode_module = encoder_module_cls(model, export_config)
        adapter_module = adapter_module_cls(model, export_config, tokenizer)

        converter = export_lib.converter_utils.Converter()
        sample_inputs = encode_module.get_sample_inputs(
            model_config,
            image_processor=image_processor,
        )
        for signature_name, (signature_inputs, _) in sample_inputs.items():
            converter.add_signature(
                signature_name,
                encode_module.eval(),
                sample_kwargs=signature_inputs,
            )
        lrt_model = converter.convert(strict_export=False)
        vision_encoder_path = os.path.join(work_dir, "vision_encoder.tflite")
        lrt_model.export(vision_encoder_path)
        gc.collect()

        converter = export_lib.converter_utils.Converter()
        sample_inputs = adapter_module.get_sample_inputs(
            model_config,
            image_processor=image_processor,
        )
        for signature_name, (signature_inputs, _) in sample_inputs.items():
            converter.add_signature(
                signature_name,
                adapter_module.eval(),
                sample_kwargs=signature_inputs,
            )
        lrt_model = converter.convert(strict_export=False)
        adapter_path = os.path.join(work_dir, "vision_adapter.tflite")
        lrt_model.export(adapter_path)
        gc.collect()

        return dataclasses.replace(
            exported_model_artifacts,
            vision_encoder_model_path=vision_encoder_path,
            vision_adapter_model_path=adapter_path,
        )

    export_lib.export_vision_encoder_models = _patched_export_vision_encoder_models

    class LiteRTExportableModuleForGemma4VisionEncoder(
        exportable_module_base.ExportableModuleBase
    ):
        """Gemma4 vision encoder exportable.

        Gemma4 consumes pre-patchified pixel values and position ids. The
        pooler is inlined to avoid boolean indexing in torch.export.
        """

        def __init__(self, model: Any, export_config: Any):
            super().__init__(export_config)
            self.vision_tower = model.model.vision_tower

        def forward(self, pixel_values: Any, pixel_position_ids: Any) -> dict[str, Any]:
            vt = self.vision_tower
            pooling_kernel_size = vt.config.pooling_kernel_size
            output_length = pixel_values.shape[-2] // (pooling_kernel_size * pooling_kernel_size)

            padding_positions = (pixel_position_ids == -1).all(dim=-1)
            inputs_embeds = vt.patch_embedder(
                pixel_values,
                pixel_position_ids,
                padding_positions,
            )
            output = vt.encoder(
                inputs_embeds=inputs_embeds,
                attention_mask=~padding_positions,
                pixel_position_ids=pixel_position_ids,
            )
            hidden_states = output.last_hidden_state
            hidden_states = hidden_states.masked_fill(padding_positions.unsqueeze(-1), 0.0)
            if hidden_states.shape[1] != output_length:
                hidden_states, _ = vt.pooler._avg_pool_by_positions(
                    hidden_states,
                    pixel_position_ids,
                    output_length,
                )
            hidden_states = hidden_states * vt.pooler.root_hidden_size
            return {"features": hidden_states}

        def get_sample_inputs(self, model_config: Any, **kwargs: Any) -> dict[str, Any]:
            image_processor = kwargs.get("image_processor")
            if image_processor is None:
                raise ValueError("image_processor is required for Gemma4 vision export.")

            from PIL import Image

            dummy_img = Image.new("RGB", (224, 224), color=(128, 128, 128))
            processed = image_processor(images=[dummy_img], return_tensors="pt")
            pixel_values = processed["pixel_values"]
            if "image_position_ids" in processed:
                position_ids = processed["image_position_ids"]
            elif "pixel_position_ids" in processed:
                position_ids = processed["pixel_position_ids"]
            else:
                raise KeyError(
                    "Gemma4 image processor did not return image_position_ids or pixel_position_ids"
                )
            max_patches = pixel_values.shape[1]
            inputs = {
                "pixel_values": pixel_values.to(torch.float32),
                "pixel_position_ids": position_ids.to(torch.int64),
            }
            return {f"vision_{max_patches}": (inputs, {})}

    class LiteRTExportableModuleForGemma4VisionAdapter(
        exportable_module_base.ExportableModuleBase
    ):
        """Gemma4 multimodal embedder exportable."""

        def __init__(self, model: Any, export_config: Any, tokenizer: Any):
            super().__init__(export_config)
            self.model = model
            self.tokenizer = tokenizer

        def forward(self, soft_tokens: Any) -> dict[str, Any]:
            image_features = self.model.model.embed_vision(soft_tokens)
            eoi_token = self.tokenizer.special_tokens_map["eoi_token"]
            eoi_ids = self.tokenizer.encode(eoi_token, add_special_tokens=False)
            eoi_emb = self.model.get_input_embeddings()(
                torch.tensor(eoi_ids, dtype=torch.long)[None, :]
            )
            mm_embedding = torch.concat([image_features, eoi_emb], axis=1)
            return {"mm_embedding": mm_embedding}

        def get_sample_inputs(self, model_config: Any, **kwargs: Any) -> dict[str, Any]:
            max_soft_tokens = getattr(model_config, "image_seq_length", 280)
            vision_config = getattr(model_config, "vision_config", None)
            vision_hidden = getattr(vision_config, "hidden_size", 768)
            inputs = {
                "soft_tokens": torch.zeros(
                    (1, max_soft_tokens, vision_hidden),
                    dtype=torch.float32,
                )
            }
            return {"vision_adapter": (inputs, {})}

    original_get_vision_exportables = exportables_mod.get_vision_exportables

    def _patched_get_vision_exportables(model_config: Any) -> Any:
        if model_config.model_type == "gemma4":
            print("[experimental-gemma4] using patched vision exportables")
            return (
                LiteRTExportableModuleForGemma4VisionEncoder,
                LiteRTExportableModuleForGemma4VisionAdapter,
            )
        return original_get_vision_exportables(model_config)

    exportables_mod.get_vision_exportables = _patched_get_vision_exportables
    if hasattr(export_lib, "model_ext_exportables"):
        export_lib.model_ext_exportables.get_vision_exportables = _patched_get_vision_exportables

    original_build_llm_metadata = litert_lm_builder.build_llm_metadata

    def _patched_build_llm_metadata(
        source_model_artifacts: Any,
        export_config: Any,
        chat_templates: Any,
        exported_model_artifacts: Any,
        litert_lm_model_type_override: str | None = None,
    ) -> Any:
        result = original_build_llm_metadata(
            source_model_artifacts,
            export_config,
            chat_templates,
            exported_model_artifacts,
            litert_lm_model_type_override,
        )
        model_type = litert_lm_model_type_override or source_model_artifacts.model.config.model_type
        if model_type == "gemma4":
            _set_gemma4_metadata_fields(result, source_model_artifacts)
        return result

    litert_lm_builder.build_llm_metadata = _patched_build_llm_metadata

    original_get_metadata_builder = metadata_builder_lib.get_metadata_builder

    def _gemma4_metadata_builder(
        source_model_artifacts: Any,
        export_config: Any,
        exported_model_artifacts: Any,
        llm_metadata: Any,
    ) -> Any:
        if export_config.task == "image_text_to_text" and export_config.export_vision_encoder:
            _set_gemma4_metadata_fields(llm_metadata, source_model_artifacts)
        return llm_metadata

    def _patched_get_metadata_builder(model_config: Any) -> Any:
        if model_config.model_type == "gemma4":
            return _gemma4_metadata_builder
        return original_get_metadata_builder(model_config)

    metadata_builder_lib.get_metadata_builder = _patched_get_metadata_builder

    def _set_gemma4_metadata_fields(llm_metadata: Any, source_model_artifacts: Any) -> None:
        config = source_model_artifacts.model.config
        tokenizer = source_model_artifacts.tokenizer
        token_map = getattr(tokenizer, "special_tokens_map", {})
        vision_config = config.vision_config
        max_soft_tokens = getattr(config, "image_seq_length", 280)

        gemma4 = llm_model_type_pb2.Gemma4()
        gemma4.start_of_image_token.token_str = token_map.get("boi_token", "<|image|>")
        gemma4.end_of_image_token.token_str = token_map.get("eoi_token", "<|end_of_image|>")
        gemma4.patch_width = vision_config.patch_size
        gemma4.patch_height = vision_config.patch_size
        gemma4.pooling_kernel_size = vision_config.pooling_kernel_size
        gemma4.max_num_patches = max_soft_tokens * vision_config.pooling_kernel_size**2
        llm_metadata.llm_model_type.CopyFrom(llm_model_type_pb2.LlmModelType(gemma4=gemma4))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--cache-length", type=int, default=2048)
    parser.add_argument("--prefill-lengths", default="[1024]")
    parser.add_argument("--quantization-recipe", default="dynamic_wi8_afp32")
    parser.add_argument("--min-ram-gb", type=float, default=0.0)
    parser.add_argument("--keep-temporary-files", action="store_true")
    args = parser.parse_args()

    ram_gb = _total_ram_gb()
    if ram_gb is not None:
        print(f"[experimental-gemma4] host RAM: {ram_gb:.1f} GB")
        if args.min_ram_gb and ram_gb < args.min_ram_gb:
            raise RuntimeError(
                f"Host RAM {ram_gb:.1f} GB is below requested minimum {args.min_ram_gb:.1f} GB"
            )

    apply_gemma4_vision_patches()

    from litert_torch.generative.export_hf import export as litert_export

    print("[experimental-gemma4] exporting image_text_to_text with FP32 vision")
    litert_export.export(
        model=args.model,
        output_dir=args.output_dir,
        task="image_text_to_text",
        quantization_recipe=args.quantization_recipe,
        cache_length=args.cache_length,
        prefill_lengths=_parse_prefill_lengths(args.prefill_lengths),
        bundle_litert_lm=True,
        use_jinja_template=True,
        externalize_embedder=True,
        export_vision_encoder=True,
        keep_temporary_files=args.keep_temporary_files,
    )


if __name__ == "__main__":
    main()
