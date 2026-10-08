"""Encode the rendered originals into responsive web image sequences.

Only codec conversion and responsive resizing are performed. No geometry,
lighting, branding or depicted content is added by this encoder.
"""
import argparse
import json
import time
from pathlib import Path

from PIL import Image, features


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--master-dir", type=Path, required=True)
    parser.add_argument("--public-dir", type=Path, required=True)
    parser.add_argument("--art-dir", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--follow-render", action="store_true",
                        help="Encode completed originals as they arrive; publish only after all renders finish")
    return parser.parse_args()


def web_image(source, destination, width, quality=88):
    with Image.open(source) as original:
        rgb = original.convert("RGB")
        if rgb.width != width:
            rgb = rgb.resize((width, round(width*rgb.height/rgb.width)), Image.Resampling.LANCZOS)
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(destination.suffix+".tmp")
        rgb.save(temporary, "WEBP", quality=quality, method=6)
        temporary.replace(destination)
        return destination.stat().st_size


def main():
    args = arguments()
    master, public, art = args.master_dir.resolve(), args.public_dir.resolve(), args.art_dir.resolve()
    if not features.check("webp"):
        raise RuntimeError("The bundled image codec needs WebP support")
    meta = json.loads((master/"web-render-metadata.json").read_text(encoding="utf-8"))
    if meta["steps"] != 480 or meta["unique_pose_count"] != 240 or (not meta["complete"] and not args.follow_render):
        raise RuntimeError("All 240 unique poses must exist for the 480-position timeline")
    references = json.loads((art/"references.json").read_text(encoding="utf-8"))["references"]
    public.mkdir(parents=True, exist_ok=True)
    poses = {}
    desktop_bytes, mobile_bytes = 0, 0
    identity = {key: meta[key] for key in ("source_sha256", "steps", "width", "height", "samples", "unique_pose_count")}
    while True:
        current = json.loads((master/"web-render-metadata.json").read_text(encoding="utf-8"))
        if any(current.get(key) != value for key, value in identity.items()):
            raise RuntimeError("The rendered source/settings changed during web encoding")
        # The renderer appends metadata only after a PNG has finished saving.
        ready = {row["index"] for row in current["renders"]}
        if any(not isinstance(i, int) or not 0 <= i < 240 for i in ready):
            raise RuntimeError("Invalid rendered pose index")
        if current["complete"] and ready != set(range(240)):
            raise RuntimeError("Completed metadata must contain every rendered pose")
        for index in sorted(ready - poses.keys()):
            source = master/f"pose-{index:04}.png"
            if not source.is_file():
                raise RuntimeError(f"Missing rendered original: {source.name}")
            pose_id = f"pose-{index:04}"
            desktop = f"desktop/{pose_id}.webp"
            mobile = f"mobile/{pose_id}.webp"
            desktop_bytes += web_image(source, public/desktop, 1600, 88)
            mobile_bytes += web_image(source, public/mobile, 960, 87)
            poses[index] = {"id": pose_id, "desktop": desktop, "mobile": mobile}
            if len(poses) == 1 or len(poses) % 40 == 0:
                print(f"Encoded {len(poses)}/240 responsive poses", flush=True)
        if current["complete"]:
            meta = current
            break
        if not args.follow_render:
            raise RuntimeError("Rendering became incomplete")
        time.sleep(1)
    poses = [poses[index] for index in range(240)]
    poster_source = art/"assembled-v4.png"
    web_image(poster_source, public/"poster-desktop.webp", 1600, 91)
    web_image(poster_source, public/"poster-mobile.webp", 960, 89)
    for filename in ("motherboard-detail-v4.png", "cooler-detail-v4.png"):
        if (art/filename).is_file():
            web_image(art/filename, public/(filename.removesuffix(".png")+".webp"), 1600, 91)
    frames = list(range(240))+list(reversed(range(240)))
    manifest = {"version": 4, "frameCount": len(frames), "fps": 60,
                "nominalDurationSeconds": 8,
                "variants": {"desktop": {"width": 1600, "height": 1200, "poster": "poster-desktop.webp"},
                             "mobile": {"width": 960, "height": 720, "poster": "poster-mobile.webp"}},
                "poses": poses, "frames": frames,
                "references": [{"product": r["product"], "feature": r["feature"], "url": r["url"]}
                               for r in references]}
    temporary = public/"manifest.tmp"
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":"))+"\n", encoding="utf-8")
    temporary.replace(public/"manifest.json")
    delivery = {"version": 4, "timelinePositions": len(frames), "uniqueImagesPerVariant": len(poses),
                "desktopImageBytes": desktop_bytes, "mobileImageBytes": mobile_bytes,
                "desktopQuality": 88, "mobileQuality": 87,
                "sourceRenderSamples": meta["samples"], "sourceRenderResolution": [meta["width"], meta["height"]],
                "validation": meta["validation"], "reverseSharesPixels": True,
                "notADevicePerformanceGuarantee": True}
    (art/"web-delivery-v4.json").write_text(json.dumps(delivery, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(delivery, indent=2), flush=True)


if __name__ == "__main__":
    main()
