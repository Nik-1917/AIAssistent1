"""Offline token and supervision audit of every V12.67 suite; loads no model weights."""
from collections import Counter
import json
import os

os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1",
                  HF_HUB_DISABLE_TELEMETRY="1", TOKENIZERS_PARALLELISM="false")

from v12_67_contract import load_jsonl
from v12_67_training import (CONFIG, CONFIG_PATH, DATA, PROVIDER, SOURCE_LOCK, WORK,
                            digest, encode_variant, prompt, read, system_text,
                            verify_launch_inputs, verify_source, write)


def audit():
    from transformers import AutoTokenizer
    inputs = verify_launch_inputs()
    lock, source = verify_source(CONFIG["model"])
    tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True, trust_remote_code=False)
    sources = {name: load_jsonl(DATA / (name + ".jsonl"))
               for name in read(DATA / "manifest.json")["artifacts"]}
    sources["generation_dev"] = read(DATA / "generation_dev.json")
    stats = {}
    over_limit, response_over_limit = [], []
    supervised = 0
    for name, rows in sources.items():
        for variant in CONFIG["training_transports"]:
            lengths, responses, prompts = [], [], []
            for line, row in enumerate(rows, 1):
                source_messages = row["messages"]
                messages = [dict(message) for message in source_messages[:-1]]
                messages[0]["content"] = system_text(messages[0]["content"], variant)
                android = variant == "android_runtime"
                prefix = prompt(messages, android)
                completion = source_messages[-1]["content"] + ("\n" if android else "") + "<|im_end|>\n"
                prefix_ids = tokenizer.encode(prefix, add_special_tokens=False)
                ids = tokenizer.encode(prefix + completion, add_special_tokens=False)
                count, answer = len(ids), len(ids) - len(prefix_ids)
                lengths.append(count)
                prompts.append(len(prefix_ids))
                responses.append(answer)
                position = dict(suite=name, line=line, variant=variant, tokens=count, response_tokens=answer)
                if count > CONFIG["max_length"]:
                    over_limit.append(position)
                else:
                    encoded = encode_variant(tokenizer, row, variant)
                    if (encoded["input_ids"] != ids
                            or encoded["labels"] != [-100] * len(prefix_ids) + ids[len(prefix_ids):]):
                        raise ValueError("Assistant mask changed")
                    supervised += 1
                if answer > CONFIG["max_new_tokens"]:
                    response_over_limit.append(position)
            ordered = sorted(lengths)
            stats[name + ":" + variant] = dict(
                rows=len(rows), max_tokens=max(lengths), max_prompt_tokens=max(prompts),
                max_response_tokens=max(responses), median_tokens=ordered[len(ordered) // 2],
                p95_tokens=ordered[min(len(ordered) - 1, int(len(ordered) * .95))],
                intents=dict(Counter(json.loads(row["messages"][-1]["content"])["intent"] for row in rows)),
            )
    result = dict(version=CONFIG["version"], status="PASS" if not over_limit and not response_over_limit else "LIMIT_EXCEEDED",
                  source_revision=lock["revision"], source_lock_sha256=digest(SOURCE_LOCK),
                  configuration_sha256=digest(CONFIG_PATH), runtime_provider_sha256=digest(PROVIDER),
                  inputs=inputs, conversations=sum(len(rows) for rows in sources.values()),
                  transports=CONFIG["training_transports"], supervised_variants_checked=supervised,
                  max_length=CONFIG["max_length"], max_new_tokens=CONFIG["max_new_tokens"],
                  max_tokens=max(item["max_tokens"] for item in stats.values()),
                  max_response_tokens=max(item["max_response_tokens"] for item in stats.values()),
                  truncated_records=0, model_weights_loaded=False, statistics=stats,
                  over_limit=over_limit, response_over_limit=response_over_limit)
    write(WORK / "token_audit.json", result)
    return result


if __name__ == "__main__":
    result = audit()
    print(json.dumps({key: result[key] for key in (
        "status", "conversations", "supervised_variants_checked", "max_tokens",
        "max_response_tokens", "truncated_records", "model_weights_loaded")}, ensure_ascii=False))
    raise SystemExit(0 if result["status"] == "PASS" else 1)
