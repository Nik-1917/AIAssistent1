"""Audit every reviewed conversation with the pinned 0.6B tokenizer."""
import json
import run_context
from audit_v12_67_tokens import audit

if __name__ == "__main__":
    result = audit()
    print(json.dumps({key: result[key] for key in (
        "status", "conversations", "supervised_variants_checked", "max_tokens",
        "max_response_tokens", "truncated_records", "model_weights_loaded")}))
    raise SystemExit(0 if result["status"] == "PASS" else 1)
