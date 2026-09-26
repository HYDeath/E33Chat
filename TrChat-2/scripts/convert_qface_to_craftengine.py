"""Convert the supplied ItemsAdder QFace pack to CraftEngine 26.9 images/emoji.

Requires PyYAML. PNG bytes are copied unchanged; the source is never modified.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import zipfile

import yaml


def convert(source: Path, output: Path) -> dict:
    source = source.resolve()
    output = output.resolve()
    if output.is_relative_to(source):
        raise ValueError("Output must be outside the original QFace folder")
    if output.exists():
        raise ValueError(f"Output already exists: {output}")
    zip_path = output.with_suffix(".zip")
    if zip_path.exists():
        raise ValueError(f"ZIP already exists: {zip_path}")
    config = yaml.safe_load((source / "configs/qfaces.yml").read_text(encoding="utf-8"))
    namespace = config["info"]["namespace"]
    if not re.fullmatch(r"[a-z0-9_.-]+", namespace):
        raise ValueError("Invalid namespace")
    texture_root = (source / "resourcepack" / namespace / "textures").resolve()
    images, emojis, textures, symbols = {}, {}, [], set()
    for name, old in sorted(config["font_images"].items(), key=lambda item: int(item[0].split("_")[-1])):
        if not re.fullmatch(r"qface_\d+", name):
            raise ValueError(f"Unsupported image ID: {name}")
        relative = Path(old["path"])
        texture = (texture_root / relative).resolve()
        if not texture.is_relative_to(texture_root) or relative.suffix.lower() != ".png":
            raise ValueError(f"Invalid PNG path: {relative}")
        raw = texture.read_bytes()
        if raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
            raise ValueError(f"Invalid PNG header: {texture}")
        width, png_height = struct.unpack(">II", raw[16:24])
        if not (0 < width <= 256 and 0 < png_height <= 256):
            raise ValueError(f"Unsupported glyph dimensions: {texture}")
        height, ascent = int(old["scale_ratio"]), int(old["y_position"])
        if not 0 < height <= 256 or ascent > height:
            raise ValueError(f"Invalid glyph metrics: {name}")
        symbol = old["symbol"]
        if len(symbol) != 1 or symbol in symbols:
            raise ValueError(f"Invalid/duplicate codepoint: {name}")
        symbols.add(symbol)
        image_id = f"{namespace}:{name}"
        # A separate short font ID keeps this 376-entry E33 catalogue below 24 KB.
        # Retain the original codepoints without adding them to minecraft:default.
        images[image_id] = {
            "height": height, "ascent": ascent, "font": "qface:f",
            "file": f"{namespace}:font/{relative.as_posix()}", "char": symbol,
        }
        emojis[image_id] = {
            "image": image_id, "content": "<white><arg:emoji></white>",
            "keywords": [f":{name}:"], "chat_completion": bool(old.get("show_in_gui", True)),
        }
        if old.get("permission"):
            emojis[image_id]["permission"] = old["permission"]
        textures.append((texture, Path("resourcepack/assets") / namespace / "textures/font" / relative))
    if len(emojis) > 512:
        raise ValueError("Pack exceeds E33's 512-entry catalogue limit")
    output.mkdir(parents=True)
    (output / "configuration").mkdir()
    (output / "pack.yml").write_text(yaml.safe_dump({
        "author": "QFace pack conversion", "version": "1.0",
        "description": "QFace emojis for CraftEngine 26.9", "namespace": namespace,
    }, allow_unicode=True, sort_keys=False), encoding="utf-8")
    (output / "configuration/qfaces.yml").write_text(
        yaml.safe_dump({"images": images, "emoji": emojis}, allow_unicode=True, sort_keys=False),
        encoding="utf-8")
    for original, relative in textures:
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, destination)
        if hashlib.sha256(original.read_bytes()).digest() != hashlib.sha256(destination.read_bytes()).digest():
            raise ValueError(f"Texture copy mismatch: {original}")
    (output / "README.txt").write_text(
        "QFace / CraftEngine 26.9\n\n"
        "安装：将此文件夹放入 plugins/CraftEngine/resources/qface/。\n"
        "运行 /ce reload all，并让玩家接受更新后的服务器资源包。\n"
        "权限：沿用原包的 qface；LuckPerms 示例 /lp group default permission set qface true。\n"
        "发送示例：:qface_0:、:qface_100:；查看图片 /ce debug image "
        f"{namespace}:qface_0。\n"
        "E33Chat：服务端须安装配套 TrChat，客户端重进服务器后刷新 CE 表情列表。\n"
        "本包使用 qface:f 自定义字体，PNG 未改动。原 ItemsAdder 包不用删除。\n",
        encoding="utf-8")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.rglob("*")):
            if path.is_file():
                archive.write(path, Path("qface") / path.relative_to(output))
    return {"images": len(images), "emojis": len(emojis), "output": str(output), "zip": str(zip_path)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(convert(args.source, args.output), ensure_ascii=False))
