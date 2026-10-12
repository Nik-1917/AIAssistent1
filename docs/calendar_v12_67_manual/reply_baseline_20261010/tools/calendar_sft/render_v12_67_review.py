"""Render retained literal V12.67 conversations into a fresh Markdown review folder."""
import argparse
import json
from pathlib import Path

from v12_67_contract import load_jsonl
from v12_67_training import DATA, ROOT, digest, read, verify_launch_inputs


def render(split="all", output=None, page_size=25):
    verify_launch_inputs()
    if not 1 <= page_size <= 100:
        raise ValueError("Page size must be between 1 and 100")
    manifest = read(DATA / "manifest.json")
    names = sorted(manifest["artifacts"])
    if split in ("train", "validation"):
        names = [split]
    elif split == "holdout":
        names = [name for name in names if name not in ("train", "validation")]
    elif split != "all":
        raise ValueError("Unknown split")
    output = Path(output or ROOT / "build/calendar_sft_v12_67_review").resolve()
    if not output.is_relative_to((ROOT / "build").resolve()):
        raise ValueError("Review output must stay inside the workspace build folder")
    output.mkdir(parents=True, exist_ok=False)
    retention = read(DATA / "retention_index.json")
    links, counts = ["# Вычитка V12.67\n", "Данные отображаются без изменения исходных текстов.\n"], {}
    for name in names:
        rows = load_jsonl(DATA / (name + ".jsonl"))
        counts[name] = len(rows)
        folder = output / name
        folder.mkdir()
        for start in range(0, len(rows), page_size):
            filename = f"page-{start // page_size + 1:03d}.md"
            parts = [f"# {name}: {start + 1}–{min(start + page_size, len(rows))}\n"]
            for index in range(start, min(start + page_size, len(rows))):
                row = rows[index]
                original = retention[name]["positions"][index]["source_line"]
                parts += [f"## {row.get('case_id', name + ':' + str(index + 1))}\n",
                          f"Категория: `{row['category']}`. V12.67: {index + 1}; V12.66: {original}.\n"]
                for message in row["messages"]:
                    content = message["content"]
                    parts += [f"### {message['role']}\n", "````text\n" + content + "\n````\n"]
            (folder / filename).write_text("\n".join(parts), encoding="utf-8")
            links.append(f"- [{name} {start + 1}–{min(start + page_size, len(rows))}]({name}/{filename})")
    (output / "index.md").write_text("\n".join(links) + "\n", encoding="utf-8")
    (output / "review_manifest.json").write_text(json.dumps(dict(
        source_manifest_sha256=digest(DATA / "manifest.json"), rows=counts,
        source_hashes={name: digest(DATA / (name + ".jsonl")) for name in names},
        page_size=page_size, conversations_changed=0), indent=2) + "\n", encoding="utf-8")
    return dict(output=str(output), rows=sum(counts.values()), sources=len(counts))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=("all", "train", "validation", "holdout"), default="all")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--page-size", type=int, default=25)
    args = parser.parse_args()
    print(json.dumps(render(args.split, args.output, args.page_size), ensure_ascii=False))
