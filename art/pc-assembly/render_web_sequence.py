"""Render a dense, reversible web sequence from the approved original scene.

Run Blender with pc-assembly-v4.blend loaded, then --python this file -- ... .
The 480 timeline positions share 240 unique renders because the authored
assembly is symmetric. Mirror transforms are checked before pixels are reused.
PNG masters stay in the ignored local runtime; the web encoder is separate.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import sys
import time
from pathlib import Path

import bpy
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector


def arguments():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--resolution", type=int, default=1600)
    parser.add_argument("--samples", type=int, default=64)
    parser.add_argument("--steps", type=int, default=480)
    parser.add_argument("--pose-indices", help="Optional comma-separated benchmark poses")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args(argv)
    if args.steps < 4 or args.steps % 2 or args.resolution < 256 or args.samples < 1:
        parser.error("steps must be even and at least 4; resolution/samples must be positive")
    return args


def set_source_time(scene, source_time):
    integer = math.floor(source_time)
    scene.frame_set(integer, subframe=source_time-integer)
    bpy.context.view_layer.update()


def source_time_for_pose(index, steps):
    return 1 + 239 * index / (steps-1)


def bounds_by_parent(scene):
    set_source_time(scene, 1)
    grouped = {}
    for obj in scene.objects:
        if obj.type not in {"MESH", "CURVE"} or not obj.get("camera_fit", False):
            continue
        parent = obj.parent
        inverse = parent.matrix_world.inverted() if parent else None
        world = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
        grouped.setdefault(parent, []).extend(inverse @ p if inverse else p for p in world)
    result = {}
    for parent, points in grouped.items():
        lo = [min(p[i] for p in points) for i in range(3)]
        hi = [max(p[i] for p in points) for i in range(3)]
        result[parent] = [Vector((x, y, z)) for x in (lo[0], hi[0])
                          for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
    return result


def validate(scene, steps):
    """Check each fractional pose and its reflection, using the source camera."""
    rigs = [o for o in scene.objects if o.name.startswith("RIG_") and o.animation_data]
    if len(rigs) != 21:
        raise RuntimeError("Expected the 21 independently animated approved rigs")
    if any(o.animation_data for o in scene.objects if o not in rigs):
        raise RuntimeError("Unreviewed object animation cannot be mirrored implicitly")
    if any(m.node_tree and m.node_tree.animation_data for m in bpy.data.materials):
        raise RuntimeError("Animated material nodes cannot be mirrored implicitly")
    bounds = bounds_by_parent(scene)
    low_x, high_x, low_y, high_y = 1., 0., 1., 0.
    max_difference = 0.
    for index in range(steps // 2):
        source_time = source_time_for_pose(index, steps)
        set_source_time(scene, source_time)
        forward = {o.name: o.matrix_world.copy() for o in rigs}
        for current_time in (source_time, 241-source_time):
            set_source_time(scene, current_time)
            for parent, corners in bounds.items():
                for corner in corners:
                    p = parent.matrix_world @ corner if parent else corner
                    v = world_to_camera_view(scene, scene.camera, p)
                    low_x, high_x = min(low_x, v.x), max(high_x, v.x)
                    low_y, high_y = min(low_y, v.y), max(high_y, v.y)
                    if not (.04 < v.x < .96 and .04 < v.y < .96 and v.z > 0):
                        raise RuntimeError(f"Pose {index}, time {current_time}: camera clipping")
            if current_time != source_time:
                for obj in rigs:
                    difference = max(abs(obj.matrix_world[r][c]-forward[obj.name][r][c])
                                     for r in range(4) for c in range(4))
                    max_difference = max(max_difference, difference)
                    if difference > 5e-5:
                        raise RuntimeError(f"Reverse pose mismatch: {obj.name}, {difference}")
        if index % 40 == 0:
            print(f"Validated dense poses: {index*2}/{steps}", flush=True)
    set_source_time(scene, 1)
    return {"timeline_positions_checked": steps, "rig_count": len(rigs),
            "reverse_transform_max_difference": max_difference,
            "normalized_bounds": [low_x, high_x, low_y, high_y],
            "includes_curves": True, "all_frame_collision_validation": False}


def write_json(path, data):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, indent=2)+"\n", encoding="utf-8")
    temporary.replace(path)


def main():
    args = arguments()
    if not bpy.app.background or not bpy.data.filepath:
        raise RuntimeError("Load the saved original .blend in background Blender")
    source = Path(bpy.data.filepath).resolve()
    if source.name != "pc-assembly-v4.blend":
        raise RuntimeError("This renderer expects the reviewed v4 scene")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    identity = {"source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "source_scene": source.name, "steps": args.steps,
                "width": args.resolution, "height": round(args.resolution*.75),
                "samples": args.samples, "version": 4}
    config_path = output/"render-config.json"
    if config_path.exists():
        existing = json.loads(config_path.read_text(encoding="utf-8"))
        if existing != identity:
            raise RuntimeError("Existing masters belong to a different scene/settings; use a new directory")
        if not args.resume:
            raise RuntimeError("Existing render directory: explicitly pass --resume")
    else:
        write_json(config_path, identity)
    scene = bpy.context.scene
    scene.render.resolution_x, scene.render.resolution_y = identity["width"], identity["height"]
    scene.render.resolution_percentage = 100
    # Geometry is static; only the reviewed rigs move. Reuse Cycles data across
    # consecutive renders instead of rebuilding the unchanged meshes each time.
    scene.render.use_persistent_data = True
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode, scene.render.image_settings.color_depth = "RGBA", "8"
    scene.cycles.samples, scene.cycles.adaptive_threshold = args.samples, .025
    scene.cycles.use_denoising, scene.cycles.use_animated_seed = True, False
    helper_spec = importlib.util.spec_from_file_location("pc_scene_authoring", Path(__file__).with_name("build_scene.py"))
    helpers = importlib.util.module_from_spec(helper_spec)
    helper_spec.loader.exec_module(helpers)
    device = helpers.render_device(scene)
    validation = validate(scene, args.steps)
    unique_count = args.steps//2
    indices = (sorted({int(i) for i in args.pose_indices.split(",")}) if args.pose_indices
               else list(range(unique_count)))
    if any(i < 0 or i >= unique_count for i in indices):
        raise RuntimeError("Requested benchmark pose outside the sequence")
    metadata_path = output/"web-render-metadata.json"
    metadata = {**identity, "device": device, "fps": 60, "nominal_duration_seconds": 8,
                "unique_pose_count": unique_count, "validation": validation,
                "reverse_uses_identical_pixels": True, "complete": False, "renders": []}
    if args.resume and metadata_path.exists():
        old = json.loads(metadata_path.read_text(encoding="utf-8"))
        metadata["renders"] = [r for r in old.get("renders", []) if (output/r["file"]).is_file()]
    completed = {r["index"] for r in metadata["renders"]}
    expected = set(range(unique_count))
    metadata["complete"] = completed == expected
    write_json(metadata_path, metadata)
    for index in indices:
        filename = f"pose-{index:04}.png"
        if index in completed:
            print(f"Resume: pose {index+1}/{unique_count}", flush=True)
            continue
        set_source_time(scene, source_time_for_pose(index, args.steps))
        scene.render.filepath = str(output/filename)
        started = time.monotonic()
        bpy.ops.render.render(write_still=True)
        duration = time.monotonic()-started
        metadata["renders"].append({"index": index, "file": filename,
                                    "source_time": source_time_for_pose(index, args.steps),
                                    "seconds": round(duration, 3)})
        completed.add(index)
        metadata["renders"].sort(key=lambda row: row["index"])
        metadata["complete"] = completed == expected
        write_json(metadata_path, metadata)
        print(f"Web pose {index+1}/{unique_count}: {duration:.2f}s; completed {len(completed)}", flush=True)
    set_source_time(scene, 1)
    metadata["complete"] = completed == expected
    write_json(metadata_path, metadata)
    print("Dense web renders complete:", metadata["complete"], flush=True)


if __name__ == "__main__":
    main()
