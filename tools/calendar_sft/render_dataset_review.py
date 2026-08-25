"""Render read-only Markdown pages for manual calendar-SFT dataset review.

The renderer validates input JSONL with the same contract used by the training
pipeline. It never changes those sources; its Markdown output belongs under
the ignored build directory and can safely be regenerated.
"""

from __future__ import annotations

import argparse
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
import shutil
from typing import Any

from dataset_contract import DatasetContractError, file_sha256, load_jsonl
from prepare_dataset import DEFAULT_HOLDOUT, DEFAULT_TRAIN, DEFAULT_VALIDATION, ROOT


DEFAULT_OUTPUT_DIR = ROOT / "build" / "calendar_sft_review"
SPLIT_SOURCES = {
    "train": DEFAULT_TRAIN,
    "validation": DEFAULT_VALIDATION,
    "holdout": DEFAULT_HOLDOUT,
}


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--split",
        choices=("all", *SPLIT_SOURCES),
        default="all",
        help="render one split or all three separate splits (default: all)",
    )
    parser.add_argument(
        "--category",
        action="append",
        default=[],
        help="render only this category; may be specified more than once",
    )
    parser.add_argument(
        "--page-size",
        type=positive_int,
        default=50,
        help="records per Markdown page (default: 50)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="generated review directory (default: build/calendar_sft_review)",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="replace an existing generated review directory",
    )
    return parser.parse_args()


def source_digest(paths: tuple[Path, ...]) -> str:
    digest = sha256()
    for path in paths:
        digest.update(path.relative_to(ROOT).as_posix().encode("utf-8"))
        digest.update(file_sha256(path).encode("ascii"))
    return digest.hexdigest()


def load_review_rows(paths: tuple[Path, ...], categories: set[str]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for path in paths:
        validated_rows = load_jsonl(path)
        source_name = path.relative_to(ROOT).as_posix()
        for line_number, row in enumerate(validated_rows, start=1):
            if categories and row["category"] not in categories:
                continue
            rows.append({"source": source_name, "line": line_number, "row": row})
    return rows


def fenced(title: str, content: str, language: str = "") -> str:
    return f"#### {title}\n\n````{language}\n{content}\n````\n"


def render_record(split: str, ordinal: int, item: dict[str, Any]) -> str:
    row = item["row"]
    messages = row["messages"]
    result = [
        f"### {split}-{ordinal:04d} — {row['category']}",
        "",
        f"Источник: `{item['source']}`, строка {item['line']}.",
        "",
        fenced("SYSTEM", messages[0]["content"], "text"),
    ]
    for index, message in enumerate(messages[1:-1], start=1):
        title = "USER" if index == 1 else f"USER — контекст {index}"
        result.append(fenced(title, message["content"], "text"))
    response = json.loads(messages[-1]["content"])
    result.append(fenced("ОЖИДАЕМЫЙ JSON", json.dumps(response, ensure_ascii=False, indent=2), "json"))
    return "\n".join(result)


def write_split_pages(output_dir: Path, split: str, rows: list[dict[str, Any]], page_size: int) -> list[str]:
    split_dir = output_dir / split
    split_dir.mkdir(parents=True, exist_ok=False)
    pages: list[str] = []
    for start in range(0, len(rows), page_size):
        end = min(start + page_size, len(rows))
        page_name = f"page-{start // page_size + 1:03d}.md"
        page_path = split_dir / page_name
        content = [
            f"# {split}: записи {start + 1}–{end} из {len(rows)}",
            "",
            "Это только витрина для чтения. Не редактируйте этот файл: исправления вносятся в исходный JSONL или генератор.",
            "",
        ]
        content.extend(render_record(split, ordinal, item) for ordinal, item in enumerate(rows[start:end], start=start + 1))
        page_path.write_text("\n".join(content), encoding="utf-8")
        pages.append((Path(split) / page_name).as_posix())
    return pages


def write_index(
    output_dir: Path,
    selected_splits: list[str],
    reviewed: dict[str, list[dict[str, Any]]],
    pages: dict[str, list[str]],
    page_size: int,
    categories: set[str],
) -> None:
    lines = [
        "# Calendar SFT: ручная вычитка",
        "",
        "Эта папка создана автоматически для чтения. Она не является обучающим датасетом и не должна редактироваться вручную.",
        "",
        f"Размер страницы: {page_size}. Категории: {', '.join(sorted(categories)) if categories else 'все'}.",
        "",
        "## Разделы",
        "",
    ]
    for split in selected_splits:
        rows = reviewed[split]
        counts = Counter(item["row"]["category"] for item in rows)
        lines.extend((
            f"### {split} — {len(rows)} записей",
            "",
            "Страницы: " + ", ".join(f"[{Path(page).name}]({page})" for page in pages[split]) + ".",
            "",
            "Категории: " + ", ".join(f"`{name}`: {count}" for name, count in sorted(counts.items())) + ".",
            "",
        ))
    lines.extend((
        "## Правило работы",
        "",
        "- Сначала вычитывайте `train`, затем отдельно `validation`; `holdout` не добавляйте в обучение и не используйте как few-shot пример.",
        "- Ошибка в `seed` исправляется в соответствующем seed JSONL. Ошибка шаблона исправляется в генераторе, затем candidates пересоздаются и отдельно проверяются.",
        "- После исправления заново выполните рендер с `--overwrite` и проверки датасета.",
        "",
    ))
    (output_dir / "index.md").write_text("\n".join(lines), encoding="utf-8")


def ensure_output_directory(output_dir: Path, overwrite: bool) -> None:
    if not output_dir.exists():
        return
    if not overwrite:
        raise DatasetContractError(
            f"output directory already exists: {output_dir}; use --overwrite to replace only this generated review",
        )
    if output_dir == output_dir.parent or output_dir == ROOT:
        raise DatasetContractError(f"unsafe output directory: {output_dir}")
    shutil.rmtree(output_dir)


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    selected_splits = list(SPLIT_SOURCES) if args.split == "all" else [args.split]
    categories = set(args.category)
    try:
        reviewed = {
            split: load_review_rows(SPLIT_SOURCES[split], categories)
            for split in selected_splits
        }
        if any(not rows for rows in reviewed.values()):
            empty = next(split for split, rows in reviewed.items() if not rows)
            raise DatasetContractError(f"{empty}: no rows match the selected categories")
        ensure_output_directory(output_dir, args.overwrite)
        output_dir.mkdir(parents=True, exist_ok=False)
        pages = {
            split: write_split_pages(output_dir, split, rows, args.page_size)
            for split, rows in reviewed.items()
        }
        write_index(output_dir, selected_splits, reviewed, pages, args.page_size, categories)
        manifest = {
            "format_version": 1,
            "page_size": args.page_size,
            "category_filter": sorted(categories),
            "splits": {
                split: {
                    "rows": len(reviewed[split]),
                    "source_digest": source_digest(SPLIT_SOURCES[split]),
                    "sources": [
                        {
                            "path": path.relative_to(ROOT).as_posix(),
                            "rows": len(load_jsonl(path)),
                            "sha256": file_sha256(path),
                        }
                        for path in SPLIT_SOURCES[split]
                    ],
                }
                for split in selected_splits
            },
        }
        (output_dir / "review_manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except DatasetContractError as error:
        print(f"Dataset review failed: {error}")
        return 2

    total = sum(len(rows) for rows in reviewed.values())
    print(f"Rendered {total} records to {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
