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
    parser.add_argument("--desktop-only-from", type=Path, metavar="EXISTING_PUBLIC_DIR",
                        help="Stage native-size desktop frames and metadata in a separate --public-dir, reusing the existing manifest and mobile assets")
    return parser.parse_args()


def web_image(source, destination, width, quality=88, *, native_size=None):
    with Image.open(source) as original:
        if native_size is not None and original.size != native_size:
            raise RuntimeError(f"Expected {native_size} render: {source}")
        rgb = original.convert("RGB")
        if rgb.width != width:
            rgb = rgb.resize((width, round(width*rgb.height/rgb.width)), Image.Resampling.LANCZOS)
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(destination.suffix+".tmp")
        rgb.save(temporary, "WEBP", quality=quality, method=6)
        if native_size is not None:
            with Image.open(temporary) as encoded:
                if encoded.format != "WEBP" or encoded.size != native_size:
                    raise RuntimeError(f"Invalid staged WebP: {temporary}")
                encoded.load()
        temporary.replace(destination)
        return destination.stat().st_size


def write_json(destination, value, *, compact=False):
    formatting = {"ensure_ascii": False, "separators": (",", ":")} if compact else {"indent": 2}
    temporary = destination.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, **formatting)+"\n", encoding="utf-8")
    temporary.replace(destination)


def main():
    args = arguments()
    master, public, art = args.master_dir.resolve(), args.public_dir.resolve(), args.art_dir.resolve()
    if not features.check("webp"):
        raise RuntimeError("The bundled image codec needs WebP support")
    meta = json.loads((master/"web-render-metadata.json").read_text(encoding="utf-8"))
    if meta["steps"] != 480 or meta["unique_pose_count"] != 240 or (not meta["complete"] and not args.follow_render):
        raise RuntimeError("All 240 unique poses must exist for the 480-position timeline")
    base_public = args.desktop_only_from.resolve() if args.desktop_only_from else None
    base_manifest, native_size = None, None
    frames = list(range(240))+list(reversed(range(240)))
    if base_public:
        if public == base_public or base_public in public.parents:
            raise RuntimeError("Use a staging directory outside the existing public assets")
        base_manifest = json.loads((base_public/"manifest.json").read_text(encoding="utf-8"))
        if base_manifest["version"] != 4 or base_manifest["frames"] != frames:
            raise RuntimeError("The existing manifest does not match the approved timeline")
        native_size = (meta["width"], meta["height"])
        if not 1 <= native_size[0] <= 4096 or native_size[1] != round(native_size[0]*.75):
            raise RuntimeError("Expected a 4:3 desktop master supported by the web player")
    desktop_width, desktop_height = native_size or (1600, 1200)
    desktop_quality, mobile_quality = 88, 87
    public.mkdir(parents=True, exist_ok=True)
    poses = {}
    desktop_bytes, mobile_bytes = 0, 0
    identity = {key: meta[key] for key in ("source_sha256", "steps", "width", "height", "samples", "unique_pose_count")}
    while True:
        current = json.loads((master/"web-render-metadata.json").read_text(encoding="utf-8"))
        if any(current.get(key) != value for key, value in identity.items()):
            raise RuntimeError("The rendered source/settings changed during web encoding")
        # The renderer appends metadata only after a PNG has finished saving.
        indexes = [row["index"] for row in current["renders"]]
        ready = set(indexes)
        if len(ready) != len(indexes) or any(type(i) is not int or not 0 <= i < 240 for i in ready):
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
            desktop_bytes += web_image(source, public/desktop, desktop_width, desktop_quality, native_size=native_size)
            if not base_public:
                mobile_bytes += web_image(source, public/mobile, 960, mobile_quality)
            poses[index] = {"id": pose_id, "desktop": desktop, "mobile": mobile}
            if len(poses) == 1 or len(poses) % 40 == 0:
                print(f"Encoded {len(poses)}/240 {'desktop' if base_public else 'responsive'} poses", flush=True)
        if current["complete"]:
            meta = current
            break
        if not args.follow_render:
            raise RuntimeError("Rendering became incomplete")
        time.sleep(1)
    poses = [poses[index] for index in range(240)]
    if base_public:
        if base_manifest["poses"] != poses:
            raise RuntimeError("The existing manifest does not match the staged pose paths")
        manifest = base_manifest
        mobile_bytes = sum((base_public/pose["mobile"]).stat().st_size for pose in poses)
    else:
        poster_source = art/"assembled-v4.png"
        web_image(poster_source, public/"poster-desktop.webp", 1600, 91)
        web_image(poster_source, public/"poster-mobile.webp", 960, 89)
        web_image(art/"exploded-v4.png", public/"intro-desktop-hd.webp", 2560, 91)
        web_image(poster_source, public/"finished-desktop-hd.webp", 2560, 91)
        for filename in ("motherboard-detail-v4.png", "cooler-detail-v4.png"):
            if (art/filename).is_file():
                web_image(art/filename, public/(filename.removesuffix(".png")+".webp"), 1600, 91)
        references = json.loads((art/"references.json").read_text(encoding="utf-8"))["references"]
        manifest = {"version": 4, "frameCount": len(frames), "fps": 60,
                    "nominalDurationSeconds": 8,
                    "variants": {"desktop": {"poster": "poster-desktop.webp"},
                                 "mobile": {"width": 960, "height": 720, "poster": "poster-mobile.webp"}},
                    "poses": poses, "frames": frames,
                    "references": [{"product": r["product"], "feature": r["feature"], "url": r["url"]}
                                   for r in references]}
    manifest["variants"]["desktop"].update(width=desktop_width, height=desktop_height)
    delivery = {"version": 4, "timelinePositions": len(frames), "uniqueImagesPerVariant": len(poses),
                "desktopImageBytes": desktop_bytes, "mobileImageBytes": mobile_bytes,
                "desktopQuality": desktop_quality, "mobileQuality": mobile_quality,
                "sourceRenderSamples": meta["samples"], "sourceRenderResolution": [meta["width"], meta["height"]],
                "validation": meta["validation"], "reverseSharesPixels": True,
                "notADevicePerformanceGuarantee": True}
    if base_public:
        previous = json.loads((art/"web-delivery-v4.json").read_text(encoding="utf-8"))
        delivery["mobileSourceRenderSamples"] = previous.get("mobileSourceRenderSamples", previous["sourceRenderSamples"])
        delivery["mobileSourceRenderResolution"] = previous.get("mobileSourceRenderResolution", previous["sourceRenderResolution"])
    write_json((public if base_public else art)/"web-delivery-v4.json", delivery)
    write_json(public/"manifest.json", manifest, compact=True)
    print(json.dumps(delivery, indent=2), flush=True)


if __name__ == "__main__":
    main()
