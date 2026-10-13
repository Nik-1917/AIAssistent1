"""Verify full-system input isolation and reject invalid three-format comparisons."""
from copy import deepcopy
import json
import unittest
from unittest.mock import patch

import comparison as addon


class ComparisonChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.packs = addon.baseline.independent_inputs()

    def test_full_current_system_and_both_transports_for_every_independent_case(self):
        proof = addon.validate_packs(self.packs)
        self.assertEqual(proof["cases_per_format"], 824)
        self.assertTrue(proof["full_system_prompt"])
        provider = addon.PROVIDER.read_text(encoding="utf-8")
        self.assertIn('"cегодня $currentDateTime день недели $dayOfWeek ответ JSON\\n$CALENDAR_CONTRACT"', provider)
        engine = addon.ENGINE.read_text(encoding="utf-8")
        for line in ('append("<|im_start|>$role\\n")', "append(message.content)",
                     'append("\\n<|im_end|>\\n")', 'append("<|im_start|>assistant\\n")'):
            self.assertIn(line, engine)
        for pack in self.packs.values():
            cached = self.gold(pack)
            scored = addon.verify_cached(pack, cached, "a" * 64)
            self.assertEqual(scored["report"]["failed_ids"], [])

    @staticmethod
    def gold(pack):
        return dict(binding=pack["binding"], model_sha256="a" * 64,
                    answers=[dict(case_id=case["id"], case_sha256=addon.case_digest(case),
                                  raw_output=json.dumps(case["expected"], ensure_ascii=False))
                             for case in pack["cases"]])

    def test_missing_contract_rejected_even_after_rebinding_case_hash(self):
        changed = deepcopy(self.packs)
        pack = changed["android_runtime"]
        pack["cases"][0]["system"] = pack["cases"][0]["system"].split("\n", 1)[0]
        pack["binding"]["cases_sha256"] = addon.case_digest(pack["cases"])
        with self.assertRaisesRegex(ValueError, "full system prompt"):
            addon.validate_packs(changed)

    def test_changed_date_time_anchor_rejected_even_after_rebinding_case_hash(self):
        changed = deepcopy(self.packs)
        pack = changed["android_runtime"]
        pack["cases"][0]["source_system"] = "Сегодня дата и время:2031-01-01 (среда) 09:15 Europe/Samara ответ JSON"
        pack["binding"]["cases_sha256"] = addon.case_digest(pack["cases"])
        with self.assertRaisesRegex(ValueError, "full system prompt"):
            addon.validate_packs(changed)

    def test_android_newline_and_appended_gold_are_rejected(self):
        for mutation in ("newline", "gold"):
            with self.subTest(mutation=mutation):
                changed = deepcopy(self.packs)
                item = changed["android_runtime"]["prompts"][0]
                if mutation == "newline":
                    item["prompt"] = item["prompt"].replace("\n<|im_end|>", "<|im_end|>", 1)
                else:
                    item["prompt"] += json.dumps(changed["android_runtime"]["cases"][0]["expected"], ensure_ascii=False)
                with self.assertRaisesRegex(ValueError, "transport changed"):
                    addon.validate_packs(changed)

    def test_changed_model_and_reordered_or_missing_answers_rejected(self):
        pack = self.packs["android_runtime"]
        cached = self.gold(pack)
        with self.assertRaisesRegex(ValueError, "different model"):
            addon.verify_cached(pack, cached, "b" * 64)
        reordered = deepcopy(cached)
        reordered["answers"][0], reordered["answers"][1] = reordered["answers"][1], reordered["answers"][0]
        with self.assertRaisesRegex(ValueError, "reordered"):
            addon.verify_cached(pack, reordered, "a" * 64)
        missing = deepcopy(cached)
        missing["answers"].pop()
        with self.assertRaisesRegex(ValueError, "incomplete"):
            addon.verify_cached(pack, missing, "a" * 64)

    def test_identical_prompts_and_decoding_are_required_for_comparison(self):
        plan = dict(packs=self.packs, settings=addon.settings())
        inputs = dict(packs=deepcopy(self.packs), **plan["settings"])
        addon.assert_same_inputs(inputs, plan)
        changed = deepcopy(inputs)
        changed["packs"]["android_runtime"]["prompts"][0]["prompt"] += " "
        with self.assertRaisesRegex(ValueError, "prompts"):
            addon.assert_same_inputs(changed, plan)
        changed = deepcopy(inputs)
        changed["decoding"]["temperature"] = 0.5
        with self.assertRaisesRegex(ValueError, "settings differ"):
            addon.assert_same_inputs(changed, plan)

    def test_build_gate_requires_all_original_stages_to_complete(self):
        self.assertFalse(addon.parent_ready(dict(status="RUNNING")))
        with self.assertRaisesRegex(RuntimeError, "failed"):
            addon.parent_ready(dict(status="FAILED"))
        complete = dict(status="COMPLETE", steps=[dict(stage=name, exit_code=0) for name in addon.STAGES])
        self.assertTrue(addon.parent_ready(complete))
        complete["steps"].pop()
        with self.assertRaisesRegex(ValueError, "completion"):
            addon.parent_ready(complete)

    def test_three_format_report_rescores_bound_outputs_and_identifies_one_lost_pass(self):
        plan = dict(packs=self.packs, settings=addon.settings(),
                    system_prompt_attestation=addon.validate_packs(self.packs))
        artifacts = {name: dict(file=str(addon.OUT / (name + ".gguf")), sha256=letter * 64, bytes=123)
                     for name, letter in (("f16", "a"), ("q4", "b"), ("q8_0", "c"))}
        base = dict(intermediate=artifacts["f16"], model=artifacts["q4"], adapter_sha256="d" * 64,
                    selected_step=1014)
        q8 = dict(base, model=artifacts["q8_0"], prepared_inputs_sha256="e" * 64,
                  baseline_manifest_sha256="f" * 64, tools={})
        plan["tools"] = {}
        files = {addon.OUT / "release/gguf_manifest.json": q8}
        for name, artifact in artifacts.items():
            folder = (addon.OUT / "evaluation_q8_0") if name == "q8_0" else (addon.RUN / ("evaluation_" + name))
            files[folder / "inputs.json"] = dict(packs=self.packs, model=artifact["file"],
                                                model_sha256=artifact["sha256"], **plan["settings"])
            rows = []
            for transport, pack in self.packs.items():
                cached = self.gold(pack)
                cached["model_sha256"] = artifact["sha256"]
                if name == "q4" and transport == "android_runtime":
                    cached["answers"][0]["raw_output"] = "invalid"
                scored = addon.verify_cached(pack, cached, artifact["sha256"])
                files[folder / ("answers_" + transport + ".json")] = cached
                files[folder / ("report_" + transport + ".json")] = scored
                rows.extend(scored["cases"])
            files[folder / "report.json"] = dict(execution_status="COMPLETE", total=824,
                                                 model_sha256=artifact["sha256"], cases=rows)
        hashes = {addon.PREPARED: "e" * 64, addon.RUN / "release/gguf_manifest.json": "f" * 64,
                  **{addon.Path(a["file"]): a["sha256"] for a in artifacts.values()}}
        with patch.object(addon, "baseline_artifacts", return_value=base), \
                patch.object(addon, "read", side_effect=lambda path: files[addon.Path(path)]), \
                patch.object(addon, "digest", side_effect=lambda path: hashes[addon.Path(path)]), \
                patch.object(addon.profile, "verify_preservation", return_value={"status": "PASS"}), \
                patch.object(addon, "write") as save:
            addon.compare(plan)
        result = save.call_args.args[1]
        failed_id = self.packs["android_runtime"]["cases"][0]["id"]
        self.assertEqual(result["formats"]["f16"]["full_passes"], 824)
        self.assertEqual(result["formats"]["q4"]["full_passes"], 823)
        self.assertEqual(result["formats"]["q8_0"]["full_passes"], 824)
        self.assertEqual(result["paired_changes"]["f16_to_q4"]["lost_passes"], [failed_id])
        self.assertEqual(result["paired_changes"]["q4_to_q8_0"]["gained_passes"], [failed_id])
        self.assertEqual(result["formats"]["q4"]["transports"]["android_runtime"]["full_passes"], 411)
        self.assertEqual(result["physical_device_inference"], "NOT_RUN")


if __name__ == "__main__":
    unittest.main()
