"""V4 original black/silver glass PC study with detailed board and single tower.
Blender --background --python build_scene.py -- --render-stills
--render-stills renders only frames 1/120; --render-preview renders 42 sample frames.
This is an original authored visual interpretation, not a manufacturer CAD model.
"""
import argparse
import json
import math
import random
import sys
from pathlib import Path
import bpy
import bmesh
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector, Quaternion


def arguments():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--resolution", type=int, default=2560)
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--render-stills", action="store_true")
    parser.add_argument("--render-preview", action="store_true")
    parser.add_argument("--render-details", action="store_true")
    args = parser.parse_args(argv)
    if not 256 <= args.resolution <= 8192 or not 1 <= args.samples <= 8192:
        parser.error("resolution must be 256..8192; samples must be 1..8192")
    return args


def material(name, color, metal=0.0, roughness=0.4, brushed=False, glass=False, emission=0.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    shader = mat.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = (*color, 1)
    shader.inputs["Metallic"].default_value = metal
    shader.inputs["Roughness"].default_value = roughness
    if emission:
        shader.inputs["Emission Color"].default_value = (*color,1)
        shader.inputs["Emission Strength"].default_value = emission
    if glass:
        shader.inputs["Transmission Weight"].default_value = 1
        shader.inputs["IOR"].default_value = 1.47
    if brushed:
        nodes, links = mat.node_tree.nodes, mat.node_tree.links
        tex, mapping = nodes.new("ShaderNodeTexCoord"), nodes.new("ShaderNodeMapping")
        mapping.inputs["Scale"].default_value = (75, 3, 5)
        noise = nodes.new("ShaderNodeTexNoise")
        noise.inputs["Scale"].default_value, noise.inputs["Detail"].default_value = 9, 2
        bump = nodes.new("ShaderNodeBump")
        bump.inputs["Strength"].default_value, bump.inputs["Distance"].default_value = .075, .009
        links.new(tex.outputs["Generated"], mapping.inputs["Vector"])
        links.new(mapping.outputs["Vector"], noise.inputs["Vector"])
        links.new(noise.outputs["Fac"], bump.inputs["Height"])
        links.new(bump.outputs["Normal"], shader.inputs["Normal"])
    return mat


def finish(obj, name, mat, parent=None, bevel=.018):
    obj.name = name
    if mat:
        obj.data.materials.append(mat)
    if parent:
        obj.parent = parent
        # Geometry is authored in assembled world coordinates. Preserve those
        # coordinates while allowing each independent rig to use its own pivot.
        obj.matrix_parent_inverse = parent.matrix_world.inverted()
    obj["camera_fit"] = True
    if bevel:
        modifier = obj.modifiers.new("Machined edge highlights", "BEVEL")
        modifier.width, modifier.segments, modifier.limit_method = bevel, 3, "ANGLE"
        try:
            modifier.harden_normals = True
            normal = obj.modifiers.new("Weighted surface normals", "WEIGHTED_NORMAL")
            normal.keep_sharp = True
        except (AttributeError, RuntimeError):
            pass
    return obj


def box(name, position, dimensions, mat, parent=None, bevel=.018):
    bpy.ops.mesh.primitive_cube_add(size=1, location=position)
    obj = bpy.context.object
    obj.dimensions = dimensions
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    return finish(obj, name, mat, parent, bevel)


def profile_prism(name, position, outline, depth, mat, parent=None, axis="Z", bevel=.008):
    """Extruded authored polygon, for chamfered cast-metal faces and edge trim."""
    center = Vector(position)
    def point(x,y,z):
        return center+Vector((x,z,y) if axis=="Y" else (z,x,y) if axis=="X" else (x,y,z))
    count = len(outline)
    vertices = [point(x,y,z) for z in (-depth/2,depth/2) for x,y in outline]
    faces = [tuple(reversed(range(count))),tuple(range(count,count*2))]
    faces += [(i,(i+1)%count,(i+1)%count+count,i+count) for i in range(count)]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices,[],faces)
    mesh.update()
    bm = bmesh.new()
    bm.from_mesh(mesh)
    bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces))
    bm.to_mesh(mesh)
    bm.free()
    obj = bpy.data.objects.new(name,mesh)
    bpy.context.collection.objects.link(obj)
    return finish(obj,name,mat,parent,bevel)


def torus(name, position, major, minor, mat, parent=None, axis=(0,0,1)):
    bpy.ops.mesh.primitive_torus_add(major_radius=major,minor_radius=minor,
                                   major_segments=64,minor_segments=10,location=position)
    obj = finish(bpy.context.object,name,mat,parent,0)
    obj.rotation_mode,obj.rotation_quaternion = "QUATERNION",Vector(axis).to_track_quat("Z","Y")
    for face in obj.data.polygons:
        face.use_smooth = True
    return obj


def perforated_plate(name, position, outline, holes, thickness, mat, parent):
    """Closed outer profile plus opposite-wound holes: real filled curve cutouts."""
    curve = bpy.data.curves.new(name,"CURVE")
    curve.dimensions = "2D"
    # Fill enum is dimension-dependent: 2D uses BOTH, not the 3D FULL enum.
    curve.fill_mode = "BOTH"
    curve.resolution_u,curve.extrude = 1,thickness/2
    curve.bevel_depth,curve.bevel_resolution = .001,1
    loops = [outline]
    loops += [[(x+radius*math.cos(-i*math.tau/20),y+radius*math.sin(-i*math.tau/20))
               for i in range(20)] for x,y,radius in holes]
    for loop in loops:
        spline = curve.splines.new("POLY")
        spline.points.add(len(loop)-1)
        for p,(x,y) in zip(spline.points,loop):
            p.co = (x,y,0,1)
        spline.use_cyclic_u = True
    obj = bpy.data.objects.new(name,curve)
    bpy.context.collection.objects.link(obj)
    obj.location = position
    return finish(obj,name,mat,parent,0)


def screw_head(name,position,radius,parent,m,axis=(-1,0,0)):
    cylinder(name,position,radius,.015,m["screw"],parent,axis,20)
    orientation = Vector(axis).to_track_quat("Z","Y")
    for angle in (0,math.pi/2):
        obj = box(name+".recess",Vector(position)+Vector(axis)*.008,(radius*1.28,.008,.003),
                  m["rubber"],parent,.001)
        obj.rotation_mode,obj.rotation_quaternion = "QUATERNION",orientation@Quaternion((0,0,1),angle)


def smd_group(name,center,rows,columns,parent,m,pitch=.055):
    """Small known component banks, not a random decorative scatter."""
    x,y,z = center
    for row in range(rows):
        for column in range(columns):
            yy,zz = y+column*pitch,z+row*pitch
            box(name+".resistor",(x,yy,zz),(.010,.029,.013),m["plastic"],parent,.001)
            for side in (-1,1):
                box(name+".solder",(x+.001,yy+side*.017,zz),(.010,.009,.014),m["screw"],parent,.001)


def open_pcie_slot(name,center,length,parent,m,steel=False):
    x,y,z = center
    box(name+".recessed-floor",(x+.052,y,z),(.013,length,.082),m["plastic"],parent,.003)
    for side in (-1,1):
        box(name+".split-wall",(x,y,z+side*.034),(.090,length,.014),m["plastic"],parent,.004)
        if steel:
            box(name+".steel-armour",(x-.005,y,z+side*.043),(.083,length-.025,.012),m["brushed"],parent,.003)
        for i in range(28):
            yy = y-length*.44+i*length*.88/27
            box(name+".internal-contact",(x+.039,yy,z+side*.020),(.008,.011,.017),m["contacts"],parent,.001)
    for end in (-1,1):
        box(name+".end-wall",(x,y+end*length/2,z),(.090,.025,.082),m["plastic"],parent,.003)
    box(name+".key",(x+.038,y-length*.17,z),(.022,.022,.051),m["plastic"],parent,.002)
    profile_prism(name+".release-latch",(x-.028,y+length/2+.055,z),
                  [(-.080,-.037),(.055,-.037),(.095,.013),(.045,.047),(-.080,.047)],
                  .045,m["plastic"],parent,"X",.004)


def open_dimm_slot(name,y,parent,m):
    box(name+".recessed-floor",(.744,y,3.50),(.018,.130,1.49),m["plastic"],parent,.004)
    for side in (-1,1):
        box(name+".split-wall",(.685,y+side*.0485,3.50),(.120,.033,1.49),m["plastic"],parent,.005)
        for i in range(22):
            box(name+".inner-contact",(.726,y+side*.031,2.86+i*.061),(.014,.009,.025),
                m["contacts"],parent,.001)
    box(name+".key-stop",(.735,y,3.48),(.018,.050,.025),m["plastic"],parent,.002)
    for z in (2.735,4.265):
        profile_prism(name+".chamfered-end-latch",(.632,y,z),
                      [(-.075,-.035),(.075,-.035),(.075,.030),(.042,.066),(-.042,.066),(-.075,.030)],
                      .130,m["plastic"],parent,"X",.006)
        cylinder(name+".latch-pivot",(.641,y,z),.018,.14,m["screw"],parent,(0,1,0),16)


def motherboard_details(board,m):
    # Larger cast/shield profiles establish functional zones before small SMD.
    profile_prism("Motherboard.rear-IO-VRM-base",(.665,1.37,3.38),
                  [(-.25,-.61),(.23,-.61),(.29,-.53),(.29,.53),
                   (.18,.64),(-.20,.64),(-.25,.52)],.11,m["black"],board,"X",.010)
    for y in (1.14,1.62):
        profile_prism("Motherboard.IO-stepped-shoulder",(.505,y,3.38),
                      [(-.036,-.60),(.036,-.60),(.036,.51),(-.012,.61),(-.036,.54)],
                      .29,m["black"],board,"X",.010)
    for z in (2.80,3.97):
        profile_prism("Motherboard.IO-angular-endcap",(.505,1.38,z),
                      [(-.26,-.064),(.26,-.064),(.21,.064),(-.22,.064)],
                      .29,m["black"],board,"X",.010)
        box("Motherboard.IO-thin-silver-edge",(.348,1.38,z),(.012,.43,.014),m["brushed"],board,.002)
    for i in range(12):
        box("Motherboard.IO-fin-window",(.495,1.185+i*.034,3.37),(.29,.012,1.02),m["black"],board,.003)
    profile_prism("Motherboard.top-VRM-stepped-base",(.666,.40,4.29),
                  [(-.60,-.18),(.49,-.18),(.60,-.10),(.60,.16),(-.48,.16),(-.60,.045)],
                  .10,m["black"],board,"X",.010)
    for i in range(5):
        box("Motherboard.top-VRM-open-fin",(.475,.40,4.11+i*.061),(.30,1.07,.018),m["black"],board,.004)
    profile_prism("Motherboard.top-VRM-angular-cap",(.48,.40,4.45),
                  [(-.58,-.10),(.48,-.10),(.58,-.015),(.48,.08),(-.49,.08),(-.58,.01)],
                  .31,m["black"],board,"X",.008)
    box("Motherboard.top-VRM-silver-accent",(.316,.37,4.446),(.013,.91,.012),m["brushed"],board,.002)
    for z in (2.83,3.95):
        screw_head("Motherboard.IO-captive-bolt",(.335,1.55,z),.023,board,m)
    for y in (-.10,.83):
        screw_head("Motherboard.top-VRM-bolt",(.302,y,4.425),.021,board,m)
    for i in range(9):
        z = 3.00+i*.142
        pcb_chip("Motherboard.VRM-phase-choke",(.625,1.054,z),(.16,.088,.097),board,m)
        cylinder("Motherboard.VRM-capacitor-can",(.651,.862,z),.027,.080,m["silver"],board,(-1,0,0),20)
        smd_group("Motherboard.VRM-driver-network",(.726,1.01,z-.05),1,2,board,m,pitch=.047)
    for i in range(6):
        # Fit between mounting bars as well as above the retention hinge.
        y = .028+i*.144
        pcb_chip("Motherboard.top-VRM-phase",(.641,y,4.053),(.15,.115,.094),board,m)
    # Chipset shield: chamfered plate, stepped terrace and diagonal metal trim.
    chipset_outline = [(-.42,-.23),(.32,-.23),(.43,-.12),(.43,.19),(.31,.26),(-.32,.26),(-.42,.13)]
    profile_prism("Motherboard.chipset-cast-shield",(.647,-1.30,1.72),
                  [(y*.66,z) for y,z in chipset_outline],.17,m["black"],board,"X",.010)
    profile_prism("Motherboard.chipset-stepped-face",(.547,-1.30,1.72),
                  [(-.23,-.15),(.13,-.15),(.22,-.035),(.16,.16),(-.17,.16),(-.23,.075)],
                  .035,m["black"],board,"X",.005)
    profile_prism("Motherboard.chipset-diagonal-inlay",(.522,-1.30,1.72),
                  [(-.20,-.085),(-.16,-.12),(.18,.105),(.14,.14)],.011,m["brushed"],board,"X",.002)
    for y,z in ((-1.51,1.59),(-1.10,1.88)):
        screw_head("Motherboard.chipset-captive-screw",(.545,y,z),.020,board,m)
    for z,length,cy in ((1.67,1.20,.25),(2.74,1.19,.49)):
        profile_prism("Motherboard.secondary-M2-shield",(.674,cy,z),
                      [(-length/2,-.075),(length/2-.06,-.075),(length/2,.005),
                       (length/2,.075),(-length/2+.06,.075),(-length/2,-.005)],
                      .097,m["black"],board,"X",.006)
        for i in range(4):
            box("Motherboard.M2-shield-fin",(.617,cy,z-.04+i*.026),(.028,length-.16,.007),m["black"],board,.002)
        screw_head("Motherboard.M2-captive-fastener",(.608,cy+length/2-.085,z),.019,board,m)
    for z,length in ((1.50,1.73),(1.89,1.74),(2.15,1.20)):
        open_pcie_slot("Motherboard.PCIe",(.68,-.10,z),length,board,m,steel=z==1.89)
    for i,y in enumerate((-.70,-.93,-1.16,-1.39)):
        open_dimm_slot(f"DIMM{i+1}",y,board,m)
    # Controller/audio banks occupy believable zones, with traces limited to them.
    for name,y,z,dims in [
        ("embedded-controller",.80,2.22,(.060,.20,.18)),
        ("audio-codec",1.28,1.73,(.048,.15,.21)),
        ("clock",-.39,2.92,(.048,.13,.12)),
        ("IO-controller",-.96,2.19,(.055,.18,.17)),
    ]:
        pcb_chip("Motherboard."+name,(.704,y,z),dims,board,m,pins=True)
        smd_group("Motherboard."+name+"-network",(.727,y-.13,z-.145),2,3,board,m,pitch=.052)
    for z in (1.47,1.60,1.91):
        cylinder("Motherboard.audio-capacitor",(.689,1.43,z),.031,.060,m["silver"],board,(-1,0,0),20)
    for name,y,z,rows,cols in [
        ("front-panel",-.56,1.35,2,5),("USB-header",.56,1.38,2,5),
        ("fan-header",-.45,4.42,1,4),("audio-header",1.25,1.35,2,5),
    ]:
        connector("Motherboard."+name,(.694,y,z),rows,cols,.036,(-1,0,0),board,m)
    for z in (2.17,2.42):
        # Split SATA receptacles with an actual open throat.
        for side in (-1,1):
            box("Motherboard.SATA-sidewall",(.66,-1.51,z+side*.050),(.14,.18,.022),m["plastic"],board,.004)
        box("Motherboard.SATA-floor",(.739,-1.51,z),(.018,.18,.12),m["plastic"],board,.003)
        box("Motherboard.SATA-end",(.675,-1.607,z),(.14,.018,.12),m["plastic"],board,.003)
        for i in range(7):
            box("Motherboard.SATA-inner-contact",(.718,-1.565+i*.015,z),(.012,.007,.022),m["contacts"],board,.001)
    for i in range(20):
        z = 2.85+i*.014
        tube("PCB.memory-bus",[ (.739,.03,z),(.739,-.20,z),(.739,-.37,z+.065),(.739,-.63,z+.065)],
             .0010,m["trace"],board,rounded=False)
    for y,z in ((.32,2.03),(.69,2.90),(-.41,2.62),(1.27,2.47)):
        smd_group("PCB.functional-SMD-bank",(.728,y,z),2,4,board,m,pitch=.045)
        for i in range(3):
            box("PCB.fine-silkscreen",(.739,y-.040,z+i*.038),(.003,.045,.005),m["silk"],board,.001)
    for y in (-1.44,1.42):
        for z in (1.45,4.38):
            torus("PCB.mount-keepout-ring",(.737,y,z),.039,.003,m["silk"],board,(-1,0,0))
            screw_head("PCB.mounting-screw",(.718,y,z),.027,board,m)


def cylinder(name, position, radius, depth, mat, parent=None, axis=(0, 0, 1), vertices=32):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=position)
    obj = bpy.context.object
    obj.rotation_mode, obj.rotation_quaternion = "QUATERNION", Vector(axis).to_track_quat("Z", "Y")
    for face in obj.data.polygons:
        face.use_smooth = len(face.vertices) == 4
    return finish(obj, name, mat, parent, min(.008, radius / 5))


def tube(name, points, radius, mat, parent=None, rounded=True):
    curve = bpy.data.curves.new(name, "CURVE")
    curve.dimensions, curve.resolution_u, curve.bevel_resolution = "3D", 12, 3
    curve.bevel_depth, curve.use_fill_caps = radius, True
    spline = curve.splines.new("BEZIER" if rounded else "POLY")
    if rounded:
        spline.bezier_points.add(len(points) - 1)
        for point, position in zip(spline.bezier_points, points):
            point.co = position
            point.handle_left_type = point.handle_right_type = "AUTO"
    else:
        spline.points.add(len(points) - 1)
        for point, position in zip(spline.points, points):
            point.co = (*position, 1)
    obj = bpy.data.objects.new(name, curve)
    bpy.context.collection.objects.link(obj)
    return finish(obj, name, mat, parent, 0)


def rig(name, parent=None, origin=(0, 0, 0)):
    obj = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(obj)
    obj.empty_display_type, obj.empty_display_size = "PLAIN_AXES", .24
    obj.parent = parent
    obj.location = origin
    obj["rest_location"] = list(origin)
    bpy.context.view_layer.update()
    return obj


def fan(name, position, radius, axis, parent, mats, led=True):
    """Seven swept axial blades, curved quad surfaces and rubber mounting pads."""
    orientation, center = Vector(axis).to_track_quat("Z", "Y"), Vector(position)
    def world(local):
        return center + orientation @ Vector(local)
    for i, (offset, dims) in enumerate([
        ((radius+.04, 0, 0), (.085, radius*2+.16, .09)),
        ((-radius-.04, 0, 0), (.085, radius*2+.16, .09)),
        ((0, radius+.04, 0), (radius*2+.16, .085, .09)),
        ((0, -radius-.04, 0), (radius*2+.16, .085, .09)),
    ]):
        frame = box(f"{name}.frame.{i}", world(offset), dims, mats["black"], parent, .025)
        frame.rotation_mode, frame.rotation_quaternion = "QUATERNION", orientation
    bpy.ops.mesh.primitive_torus_add(major_radius=radius*.96, minor_radius=.025,
                                   major_segments=48, minor_segments=8, location=position)
    ring = finish(bpy.context.object, name+".rim", mats["black"], parent, 0)
    ring.rotation_mode, ring.rotation_quaternion = "QUATERNION", orientation
    if led:
        torus(name+".ice-blue-diffuser",world((0,0,.038)),radius*.95,.008,
              mats["led"],parent,axis)
    for i in range(7):
        angle = i * math.tau / 7
        vertices = []
        for j in range(9):
            t = j / 8
            r, sweep = radius * (.23 + .65*t), -.42*t + .08*math.sin(math.pi*t)
            width = .22 + .10*math.sin(math.pi*t)
            for side in (-1, 1):
                a = angle + sweep + side*width
                vertices.append(world((r*math.cos(a), r*math.sin(a), .03 + .025*t + side*.010)))
        mesh = bpy.data.meshes.new(f"{name}.blade.{i}")
        mesh.from_pydata(vertices, [], [(2*j,2*j+1,2*j+3,2*j+2) for j in range(8)])
        mesh.update()
        for face in mesh.polygons:
            face.use_smooth = True
        blade = bpy.data.objects.new(mesh.name, mesh)
        bpy.context.collection.objects.link(blade)
        finish(blade, blade.name, mats["fan_blade"], parent, 0)
        blade.modifiers.new("Blade thickness", "SOLIDIFY").thickness = .017
    cylinder(name+".hub", world((0, 0, .06)), radius*.22, .10, mats["black"], parent, axis)
    cylinder(name+".hub-cap", world((0, 0, .12)), radius*.08, .012, mats["silver"], parent, axis)
    for x in (-radius, radius):
        for y in (-radius, radius):
            cylinder(name+".rubber-corner", world((x,y,.01)), .064, .072, mats["rubber"], parent, axis, 16)
            cylinder(name+".mount", world((x, y, .06)), .025, .025, mats["screw"], parent, axis, 12)


def connector(name, center, rows, columns, pitch, axis, parent, m):
    """Generic keyed plug/socket: no claim of exact electrical pinout."""
    orientation, center = Vector(axis).to_track_quat("Z","Y"), Vector(center)
    def part(suffix, local, dims, mat):
        obj = box(name+"."+suffix, center+orientation@Vector(local), dims, mat, parent, .005)
        obj.rotation_mode, obj.rotation_quaternion = "QUATERNION", orientation
        return obj
    width, height = columns*pitch+.05, rows*pitch+.05
    part("housing", (0,0,0), (width,height,.14), m["plastic"])
    part("retaining-latch", (0,height/2+.018,-.010), (width*.38,.035,.095), m["black"])
    for row in range(rows):
        for column in range(columns):
            x, y = (column-(columns-1)/2)*pitch, (row-(rows-1)/2)*pitch
            part(f"pin-opening-{row}-{column}", (x,y,.073), (pitch*.68,pitch*.68,.008), m["rubber"])
            part(f"contact-{row}-{column}", (x,y,.079), (pitch*.27,pitch*.30,.006), m["contacts"])


def pcb_chip(name, position, dimensions, parent, m, pins=False):
    box(name, position, dimensions, m["plastic"], parent, .007)
    if pins:
        x,y,z = position
        for side in (-1,1):
            for i in range(5):
                box(name+".lead", (x,y+side*(dimensions[1]/2+.012),z+(i-2)*dimensions[2]/5),
                    (.018,.025,.012), m["screw"], parent, .002)


def sleeve_bundle(name, points, rows, columns, board_axis, parent, m,
                  offset_axis=(0, 0, 1), pitch=.028):
    """Fan sleeves between each plug's actual local two-row contact layout."""
    axis, count = Vector(offset_axis), rows*columns
    board_orientation = Vector(board_axis).to_track_quat("Z","Y")
    psu_orientation = Vector((0,1,0)).to_track_quat("Z","Y")
    for i in range(count):
        row,column = divmod(i,columns)
        grid = Vector((column-(columns-1)/2,row-(rows-1)/2,0))
        board_offset = board_orientation@(grid*.040)
        psu_offset = psu_orientation@(grid*.038)
        bundle_offset = axis*((i-(count-1)/2)*pitch)
        offsets = []
        for index in range(len(points)):
            if index == 0:
                offset = board_offset
            elif index == 1:
                offset = board_offset*.65+bundle_offset*.35
            elif index == len(points)-2:
                offset = psu_offset*.70+bundle_offset*.30
            elif index == len(points)-1:
                offset = psu_offset
            else:
                offset = bundle_offset
            offsets.append(offset)
        tube(f"{name}.sleeve.{i:02}", [Vector(p)+offset for p,offset in zip(points,offsets)],
             .0105,m["sleeve"],parent)
    for index in (1, len(points)-2):
        point = Vector(points[index])
        dimensions = [.070,.070,.070]
        # Keep the comb around the actual fanned wire extents at this point.
        wire_offsets = []
        for i in range(count):
            row,column = divmod(i,columns)
            grid = Vector((column-(columns-1)/2,row-(rows-1)/2,0))
            bundle_offset = axis*((i-(count-1)/2)*pitch)
            wire_offsets.append(board_orientation@(grid*.040)*.65+bundle_offset*.35 if index==1
                                else psu_orientation@(grid*.038)*.70+bundle_offset*.30)
        dimensions = [max(p[i] for p in wire_offsets)-min(p[i] for p in wire_offsets)+.035
                      for i in range(3)]
        box(name+".low-profile-comb", point, dimensions, m["plastic"], parent, .007)


def build_pc(m):
    root = rig("PC_ASSEMBLY_ROOT")
    origins = {
        "glass":(-1.17,0,2.51), "motherboard":(.77,.05,2.91), "cpu":(.606,.40,3.53),
        "cpu_cooler":(-.11,.40,3.56), "cpu_cooler_fan":(-.635,.40,3.56),
        "ram_1":(.48,-.93,3.50), "ram_2":(.48,-1.39,3.50),
        "storage":(.66,.70,2.48), "storage_heatsink":(.55,.70,2.48),
        "gpu_pcb":(.11,.05,1.89), "gpu_backplate":(.11,-.05,2.015),
        "gpu_heatsink":(.11,-.05,1.70), "gpu_shroud":(.11,-.05,1.46),
        "gpu_fans":(.11,-.05,1.46), "psu":(.02,.90,.69),
        "cable_eps":(.51,1.14,4.30), "cable_atx":(.51,-1.50,3.18),
        "cable_gpu":(.16,-1.12,1.90),
        "front_panel":(.15,-2.18,2.50), "case_front_fans":(0,-1.92,2.90),
        "case_rear_fan":(.16,1.97,3.70),
    }
    # Every moving assembly is a sibling. Rest coordinates and explicit world
    # displacements avoid silently adding motherboard/GPU movement twice.
    groups = {key:rig("RIG_"+key.upper(),root,origin) for key,origin in origins.items()}
    chassis = rig("RIG_CHASSIS", root)
    # Generic authored visual proportions, not certified hardware dimensions.
    for name, p, d in [
        ("floor", (0,0,.18), (2.22,4.34,.12)), ("roof", (0,0,4.85), (2.22,4.34,.12)),
        ("right-back-panel", (1.10,0,2.50), (.08,4.24,4.62)),
        ("rear-frame-lower", (0,2.10,1.635), (2.18,.08,2.89)),
        ("rear-frame-upper", (0,2.10,4.57), (2.18,.08,.48)),
        ("rear-frame-left", (-.81,2.10,3.70), (.56,.08,1.25)),
        ("rear-frame-right", (.925,2.10,3.70), (.33,.08,1.25)),
    ]:
        box("Chassis."+name, p, d, m["black"], chassis, .045)
    for y in (-2.08, 2.08):
        profile_prism("Chassis.chamfered-silver-upright",(-1.08,y,2.50),
                      [(-.045,-.030),(-.025,-.05),(.025,-.05),(.045,-.030),
                       (.045,.030),(.025,.05),(-.025,.05),(-.045,.030)],
                      4.62,m["brushed"],chassis,bevel=.006)
    for x in (-.82,.82):
        for y in (-1.62,1.62):
            box("Chassis.foot", (x,y,.075), (.32,.50,.15), m["plastic"], chassis, .04)
    front = groups["front_panel"]
    # Tinted front window retains the approved panel/fan disassembly positions.
    box("Front.tinted-glass-window",(0,-2.205,2.90),(1.50,.035,3.54),m["glass_front"],front,.012)
    front_post = [(-.090,-.025),(-.060,-.075),(.060,-.075),(.090,-.025),(.090,.045),(-.090,.045)]
    for x in (-.835,.835):
        profile_prism("Front.faceted-black-pillar",(x,-2.18,2.50),front_post,4.62,m["black"],front,"Z",.008)
        profile_prism("Front.silver-angular-edge",(x,-2.255,2.50),
                      [(-.065,-.004),(-.040,-.020),(.030,-.020),(.055,-.004),(.030,.004),(-.040,.004)],
                      4.58,m["brushed"],front,"Z",.003)
    box("Front.upper-black-header",(.025,-2.18,4.685),(1.78,.12,.25),m["black"],front,.035)
    profile_prism("Front.faceted-lower-plinth",(.025,-2.18,.66),
                  [(-.89,-.385),(.89,-.385),(.89,.28),(.79,.375),(-.79,.375),(-.89,.28)],
                  .14,m["black"],front,"Y",.016)
    box("Front.air-channel", (-.96,-2.25,2.5), (.22,.035,4.30), m["plastic"], front)
    for z in range(55):
        box("Front.vent-slat", (-.96,-2.282,.43+z*.076), (.20,.018,.018), m["black"], front, .003)
    for y in range(24):
        box("Top.vent-slot", (0,-.95+y*.080,4.922), (1.46,.023,.007), m["plastic"], chassis, .002)
    cylinder("Front.power-button", (.75,-2.26,4.53), .068, .02, m["silver"], front, (0,-1,0))
    box("Front.IO-mount-island",(.385,-2.238,4.53),(.38,.027,.081),m["black"],front,.009)
    for x in (.28,.48):
        box("Front.io-port", (x,-2.254,4.53), (.10,.024,.044), m["plastic"], front, .005)
    for z in (.31,4.72):
        box("Case.side-LED-channel",(-1.128,0,z),(.022,4.03,.031),m["plastic"],chassis,.004)
        box("Case.side-ice-blue-edge",(-1.142,0,z),(.008,3.92,.009),m["led_soft"],chassis,.002)
        box("Front.LED-channel",(0,-2.251,z),(1.58,.024,.035),m["plastic"],front,.005)
        box("Front.ice-blue-L-edge",(0,-2.267,z),(1.47,.008,.009),m["led_soft"],front,.002)
    for z in (1.75,2.90,4.05):
        fan("Case.intake", (0,-1.92,z), .48, (0,-1,0), groups["case_front_fans"], m)
    fan("Case.exhaust", (.16,1.97,3.70), .48, (0,-1,0), groups["case_rear_fan"], m)
    glass = groups["glass"]
    box("Glass.side-panel", (-1.17,0,2.51), (.035,4.02,4.33), m["glass"], glass, .013)
    for z in (.37,4.65):
        box("Glass.silver-edge", (-1.19,0,z), (.032,4.04,.025), m["silver"], glass, .004)
        for y in (-1.90,1.90):
            cylinder("Glass.thumb-screw", (-1.205,y,z), .047, .036, m["screw"], glass, (-1,0,0))
    board = groups["motherboard"]
    box("Motherboard.PCB", (.77,.05,2.91), (.058,3.28,3.26), m["pcb"], board, .018)
    motherboard_details(board,m)
    cpu = groups["cpu"]
    box("CPU.socket-recessed-floor",(.716,.40,3.53),(.021,.79,.79),m["plastic"],board,.004)
    for side in (-1,1):
        box("CPU.socket-split-frame",(.705,.40+side*.41,3.53),(.09,.05,.87),m["plastic"],board,.005)
        box("CPU.socket-split-frame",(.705,.40,3.53+side*.41),(.09,.77,.05),m["plastic"],board,.005)
    for y in range(9):
        for z in range(9):
            box("CPU.socket-contact",(.699,.12+y*.07,3.25+z*.07),(.011,.023,.029),m["contacts"],board,.001)
    for side in (-1,1):
        box("CPU.socket-open-clamp",(.634,.40+side*.407,3.53),(.038,.045,.86),m["brushed"],board,.008)
        box("CPU.socket-open-clamp",(.634,.40,3.53+side*.407),(.038,.86,.045),m["brushed"],board,.008)
        profile_prism("CPU.retention-frame-ear",(.634,.40+side*.30,3.095),
                      [(-.080,-.025),(.065,-.025),(.085,.020),(.048,.068),(-.071,.052)],
                      .041,m["brushed"],board,"X",.005)
        cylinder("CPU.retention-hinge",(.647,.40+side*.29,3.965),.024,.21,m["screw"],board,(0,1,0),20)
        screw_head("CPU.retention-anchor",(.608,.40+side*.30,3.090),.022,board,m)
    box("CPU.retention-lever-catch",(.621,.955,3.925),(.06,.065,.035),m["screw"],board,.004)
    tube("CPU.socket-retention-lever",[(.619,.90,3.12),(.619,.94,3.12),
         (.619,.94,3.88),(.57,.91,3.94)],.014,m["silver"],board)
    for y in (-.12,.92):
        for z in (3.07,3.99):
            cylinder("CPU.cooler-fixed-spacer",(.666,y,z),.035,.15,m["screw"],board,(-1,0,0),20)
            screw_head("CPU.cooler-fixed-standoff-head",(.585,y,z),.027,board,m)
        profile_prism("CPU.cooler-fixed-mounting-bar",(.584,y,3.53),
                      [(-.065,-.515),(.065,-.515),(.083,-.46),(.083,.46),
                       (.065,.515),(-.065,.515),(-.083,.46),(-.083,-.46)],
                      .041,m["screw"],board,"X",.005)
    box("CPU.package-substrate",(.600,.40,3.53),(.038,.76,.76),m["substrate"],cpu,.018)
    box("CPU.silver-spreader",(.551,.40,3.53),(.068,.65,.65),m["silver"],cpu,.035)
    for y in range(5):
        for z in range(5):
            box("CPU.underside-contact",(.623,.16+y*.12,3.29+z*.12),(.006,.048,.048),m["contacts"],cpu,.002)
    cooler = groups["cpu_cooler"]
    box("Cooler.base", (.460,.40,3.53), (.15,.86,.52), m["silver"], cooler)
    box("Cooler.nickel-contact-plate",(.520,.40,3.53),(.028,.48,.48),m["brushed"],cooler,.006)
    profile_prism("Cooler.cast-mounting-bridge",(.366,.40,3.53),
                  [(-.585,-.083),(.585,-.083),(.585,.083),(.20,.11),
                   (-.20,.11),(-.585,.083)],.038,m["screw"],cooler,"X",.005)
    for y in (-.12,.92):
        cylinder("Cooler.spring-guide",(.470,y,3.53),.014,.28,m["screw"],cooler,(-1,0,0),16)
        for x in (.393,.552):
            torus("Cooler.spring-seat",(x,y,3.53),.028,.006,m["screw"],cooler,(-1,0,0))
        points = [(.400+i*.144/96,y+.031*math.cos(i*math.tau*6/96),
                   3.53+.031*math.sin(i*math.tau*6/96)) for i in range(97)]
        tube("Cooler.compression-spring",points,.0055,m["screw"],cooler,rounded=False)
        screw_head("Cooler.spring-mount-fastener",(.338,y,3.53),.028,cooler,m)
    fin_outline = [(-.395,-.49),(.395,-.49),(.455,-.43),(.455,-.16),(.425,-.13),
                   (.425,.13),(.455,.16),(.455,.43),(.395,.49),(-.395,.49),
                   (-.455,.43),(-.455,.28),(-.425,.25),(-.425,.10),(-.455,.07),
                   (-.455,-.07),(-.425,-.10),(-.425,-.25),(-.455,-.28),(-.455,-.43)]
    pipe_rows = (.02,.145,.27,.395,.52,.645,.77)
    holes = [(-.13,y-.40,.026) for y in pipe_rows]
    for i in range(49):
        perforated_plate(f"Cooler.notched-perforated-fin.{i:02}",(-.11,.40,3.01+i*.0235),
                         fin_outline,holes,.010,m["silver"],cooler)
    perforated_plate("Cooler.chamfered-perforated-top-cap",(-.11,.40,4.18),
                     fin_outline,holes,.032,m["black"],cooler)
    for y in pipe_rows:
        pipe = tube("Cooler.heatpipe",[(.46,y,3.34),(.37,y,3.01),(-.03,y,2.90),
                                     (-.24,y,2.96),(-.24,y,4.215)],.021,m["brushed"],cooler)
        # The return leg must be exactly vertical through all seven fin holes;
        # automatic Bezier tangents would bow it out of the perforations.
        entry,end = pipe.data.splines[0].bezier_points[3],pipe.data.splines[0].bezier_points[4]
        entry.handle_left_type = entry.handle_right_type = "FREE"
        entry.handle_left,entry.handle_right = (-.24,y,2.92),(-.24,y,3.40)
        end.handle_left_type = end.handle_right_type = "FREE"
        end.handle_left,end.handle_right = (-.24,y,3.78),(-.24,y,4.235)
        for z in (3.019,4.146,4.199):
            torus("Cooler.solder-interface-collar",(-.24,y,z),.023,.004,m["screw"],cooler)
        cylinder("Cooler.sealed-metal-pipe-end",(-.24,y,4.216),.024,.019,m["brushed"],cooler,(0,0,1),24)
        cylinder("Cooler.base-pipe-solder-joint",(.46,y,3.34),.027,.032,m["screw"],cooler,(-1,0,0),20)
    cooler_fan = groups["cpu_cooler_fan"]
    fan("Cooler.fan",(-.635,.40,3.56),.48,(-1,0,0),cooler_fan,m)
    for y in (-.12,.92):
        tube("Cooler.spring-wireclip",[(-.65,y,3.22),(-.42,y,3.18),
             (-.34,y,3.30),(-.34,y,3.85),(-.45,y,3.94),(-.65,y,3.90)],.008,m["screw"],cooler_fan)
        for z in (3.22,3.90):
            box("Cooler.fan-wireclip-anchor",(-.653,y,z),(.052,.056,.079),m["plastic"],cooler_fan,.008)
            cylinder("Cooler.fan-wireclip-eye",(-.683,y,z),.014,.010,m["rubber"],cooler_fan,(-1,0,0),16)
    for sy in (-1,1):
        for sz in (-1,1):
            profile_prism("Cooler.L-shaped-anti-vibration-pad",(-.680,.40+sy*.48,3.56+sz*.48),
                          [(sy*y,sz*z) for y,z in [(-.064,-.064),(.066,-.064),(.066,-.026),
                           (-.023,-.026),(-.023,.065),(-.064,.065)]],
                          .035,m["rubber"],cooler_fan,"X",.004)
    for i,y in enumerate((-.93,-1.39),1):
        ram = groups[f"ram_{i}"]
        box(f"RAM{i}.PCB",(.51,y,3.50),(.35,.036,1.42),m["pcb"],ram,.004)
        box(f"RAM{i}.low-profile-spreader",(.470,y,3.50),(.28,.105,1.42),m["black"],ram,.019)
        box(f"RAM{i}.silver-top-spine",(.315,y,3.50),(.025,.11,1.37),m["silver"],ram,.005)
        box(f"RAM{i}.recessed-ice-blue-spine",(.294,y,3.50),(.011,.061,1.19),m["led_soft"],ram,.004)
        for z in range(21):
            if z == 10:
                continue
            for side in (-1,1):
                box(f"RAM{i}.edge-contact",(.667,y+side*.021,2.84+z*.064),(.062,.007,.035),m["contacts"],ram,.002)
        for z in (2.93,3.20,3.48,3.76,4.04):
            box(f"RAM{i}.spreader-recess",(.466,y-.057,z),(.18,.008,.10),m["plastic"],ram,.004)
    gpu = groups["gpu_pcb"]
    box("GPU.compact-PCB",(.11,.05,1.89),(1.07,2.38,.050),m["pcb"],gpu,.008)
    box("GPU.processor-substrate",(.11,.03,1.825),(.54,.54,.055),m["substrate"],gpu,.009)
    box("GPU.silicon-die",(.11,.03,1.783),(.32,.32,.027),m["die"],gpu,.005)
    for x in (-.275,.495):
        for y in (-.45,-.15,.15,.45):
            box("GPU.VRAM-package",(x,y,1.822),(.205,.225,.080),m["plastic"],gpu,.006)
    for y in (-.63,.69):
        box("GPU.VRAM-package",(.11,y,1.822),(.22,.18,.080),m["plastic"],gpu,.006)
    for y in (.85,1.005,1.16):
        for x in (-.255,.115,.465):
            box("GPU.power-choke",(x,y,1.80),(.14,.12,.12),m["black"],gpu,.012)
            cylinder("GPU.silver-capacitor",(x-.085,y,1.81),.026,.10,m["silver"],gpu,(0,0,-1),20)
            box("GPU.power-MOSFET",(x+.09,y,1.837),(.045,.070,.045),m["plastic"],gpu,.003)
    for x in (-.32,-.16,.00,.16,.32,.48):
        for y in (-.91,-.78):
            box("GPU.SMD-network",(x,y,1.854),(.065,.035,.020),m["plastic"],gpu,.002)
    for i in range(27):
        box("GPU.PCIe-gold-finger",(.680,-.87+i*.056,1.89),(.084,.031,.013),m["contacts"],gpu,.002)
    box("GPU.PCIe-edge-substrate",(.660,-.14,1.89),(.07,1.59,.049),m["pcb"],gpu,.004)
    box("GPU.metal-PCI-bracket",(.11,1.57,1.77),(1.15,.055,.66),m["brushed"],gpu,.016)
    for x in (-.26,.05,.35):
        box("GPU.display-port-opening",(x,1.603,1.78),(.18,.011,.085),m["rubber"],gpu,.005)
    for x in (-.31,.52):
        for y in (-.34,.40):
            cylinder("GPU.processor-mount",(x,y,1.838),.027,.04,m["screw"],gpu,(0,0,-1),12)
    for i in range(12):
        y = -.62+i*.040
        tube("GPU.short-trace",[(.19,y,1.862),(.26,y,1.862),(.30,y+.04,1.862),(.43,y+.04,1.862)],
             .001,m["trace"],gpu,rounded=False)
    backplate = groups["gpu_backplate"]
    profile_prism("GPU.cast-angular-backplate",(.11,.405,2.015),
                  [(-.505,-1.11),(.505,-1.11),(.565,-1.05),(.565,1.05),
                   (.505,1.11),(-.505,1.11),(-.565,1.05),(-.565,-1.05)],
                  .048,m["black"],backplate,"Z",.012)
    for y in (.10,.45,.80):
        profile_prism("GPU.backplate-diagonal-metal-inlay",(.11,y,2.043),
                      [(-.34,-.12),(-.25,-.14),(.33,.12),(.24,.14)],
                      .009,m["brushed"],backplate,"Z",.003)
    for x in (-.45,.67):
        box("GPU.flow-through-vent-rim",(x,-1.155,2.015),(.075,.90,.048),m["brushed"],backplate,.008)
    for y in (-1.57,-.72):
        box("GPU.flow-through-vent-rim",(.11,y,2.015),(1.13,.065,.048),m["brushed"],backplate,.008)
    for y in (-1.40,-1.22,-1.04,-.86):
        box("GPU.open-flow-through-slat",(.11,y,2.015),(.99,.025,.046),m["black"],backplate,.005)
    for x in (-.42,.64):
        for y in (-.53,1.41):
            cylinder("GPU.backplate-fastener",(x,y,2.047),.022,.012,m["screw"],backplate,(0,0,1),12)
    sink = groups["gpu_heatsink"]
    for center,length,width in ((-1.03,1.03,1.06),(.015,.88,1.14),(1.01,.92,1.01)):
        for i in range(18):
            y = center-length/2+i*length/17
            box("GPU.fin-block",(.11,y,1.69),(width,.017,.30),m["silver"],sink,.002)
    box("GPU.cold-plate",(.11,.03,1.771),(.55,.62,.065),m["brushed"],sink,.009)
    for x in (-.22,.00,.22,.44):
        tube("GPU.nickel-U-heatpipe",[(x,-1.44,1.64),(x,-1.36,1.55),(x,-.35,1.55),
             (x,.10,1.735),(x,.55,1.55),(x,1.36,1.55),(x,1.45,1.68)],.030,m["silver"],sink)
    shroud = groups["gpu_shroud"]
    for x,sign in ((-.50,1),(.72,-1)):
        for y in (-1.075,-.05,.975):
            profile_prism("GPU.segmented-cast-frame",(x,y,1.48),
                          [(sign*a,b) for a,b in [(-.080,-.105),(-.080,.075),
                           (-.035,.155),(.065,.112),(.065,-.105)]],
                          1.03,m["black"],shroud,"Y",.012)
            profile_prism("GPU.angular-silver-shoulder",(x,y,1.48),
                          [(sign*a,b) for a,b in [(-.077,.075),(-.033,.151),
                           (.064,.108),(.060,.085),(-.032,.123),(-.058,.066)]],
                          .90,m["brushed"],shroud,"Y",.003)
    for y in (-1.64,1.54):
        box("GPU.rounded-shroud-end",(.11,y,1.48),(1.21,.10,.24),m["black"],shroud,.035)
    box("GPU.recessed-side-light-channel",(-.590,-.05,1.51),(.028,2.74,.078),m["plastic"],shroud,.009)
    box("GPU.indirect-ice-blue-lightbar",(-.608,-.05,1.528),(.010,2.43,.015),m["led_soft"],shroud,.003)
    for y in (-.54,.44):
        for side in (-1,1):
            profile_prism("GPU.interfan-silver-triangle",(.11,y,1.318),
                          [(side*.49,-.080),(side*.21,.004),(side*.49,.080)],
                          .045,m["brushed"],shroud,"Z",.006)
    for y in (-1.03,-.05,.93):
        torus("GPU.silver-circular-fan-bezel",(.11,y,1.328),.399,.018,
              m["brushed"],shroud,(0,0,-1))
        fan("GPU.axial-fan",(.11,y,1.40),.37,(0,0,-1),groups["gpu_fans"],m,led=False)
    connector("GPU.power-socket",(.16,-1.12,1.945),2,6,.037,(0,0,1),gpu,m)
    psu = groups["psu"]
    box("PSU.body", (.02,.90,.69), (1.74,1.78,.75), m["black"], psu, .045)
    box("PSU.cover", (-.96,-.03,.68), (.065,3.81,.79), m["black"], psu, .02)
    # A real opening in the top shroud lets the authored cable arcs pass through.
    box("PSU.cover-top-main",(-.39,-.03,1.11),(1.18,3.81,.055),m["black"],psu)
    box("PSU.cover-top-edge",(.77,-.03,1.11),(.14,3.81,.055),m["black"],psu)
    for y,length in ((-1.53,.70),(.65,2.47)):
        box("PSU.cover-top-bridge",(.45,y,1.11),(.48,length,.055),m["black"],psu)
    for i in range(18):
        box("PSU.cover-vent", (-.65,.52+i*.057,1.15), (.65,.020,.008), m["plastic"], psu, .001)
    for y in (-1.76,1.70):
        cylinder("PSU.cover-screw", (-1.01,y,.37), .026, .025, m["screw"], psu, (-1,0,0), 12)
    for name,center,cols in (("EPS8",(-.36,-.035,.77),4),("ATX24",(.48,-.035,.77),12),("GPU12",(.11,-.035,.47),6)):
        connector("PSU."+name+"-socket",center,2,cols,.038,(0,-1,0),psu,m)
    storage = groups["storage"]
    for x in (.638,.706):
        box("Motherboard.M2-split-socket-wall",(x,.175,2.48),(.020,.14,.30),m["plastic"],board,.003)
    for z in (2.32,2.64):
        box("Motherboard.M2-socket-end",(.675,.175,z),(.10,.14,.023),m["plastic"],board,.003)
    box("Motherboard.M2-socket-rear",(.675,.112,2.48),(.10,.014,.32),m["plastic"],board,.003)
    for i in range(8):
        box("Motherboard.M2-inner-contact",(.688,.183,2.373+i*.030),(.009,.067,.014),m["contacts"],board,.001)
    cylinder("Motherboard.M2-standoff",(.668,1.18,2.48),.032,.08,m["screw"],board,(-1,0,0),20)
    for y in (.46,.92):
        box("Motherboard.M2-thermal-pad",(.699,y,2.48),(.025,.27,.20),m["rubber"],board,.003)
    box("SSD.M2-2280-PCB",(.671,.70,2.48),(.028,1.00,.275),m["pcb"],storage,.004)
    for y in (.42,.72,1.00):
        box("SSD.flash-controller-package",(.631,y,2.48),(.055,.21,.19),m["plastic"],storage,.004)
    for z in range(8):
        box("SSD.edge-gold-contact",(.650,.219,2.373+z*.030),(.012,.070,.015),m["contacts"],storage,.001)
    cylinder("SSD.mount-hole-dark-center",(.650,1.155,2.48),.020,.007,m["rubber"],storage,(-1,0,0),20)
    for y in (.31,.57,.85):
        for z in (2.37,2.59):
            box("SSD.small-SMD",(.647,y,z),(.012,.038,.020),m["screw"],storage,.001)
    shield = groups["storage_heatsink"]
    profile_prism("SSD.chamfered-removable-heatshield",(.562,.70,2.48),
                  [(-.46,-.15),(.51,-.15),(.51,.10),(.46,.15),(-.51,.15),(-.51,-.10)],
                  .13,m["brushed"],shield,"X",.009)
    for y in range(14):
        box("SSD.heatsink-groove",(.491,.265+y*.067,2.48),(.015,.017,.28),m["black"],shield,.001)
    cylinder("SSD.captive-shield-screw",(.485,1.155,2.48),.025,.018,m["screw"],shield,(-1,0,0),12)
    connector("Motherboard.EPS8-socket",(.675,1.14,4.30),2,4,.046,(-1,0,0),board,m)
    connector("Motherboard.ATX24-socket",(.675,-1.50,3.18),2,12,.043,(-1,0,0),board,m)
    cable_specs = [
        ("EPS8","cable_eps",8,(.51,1.14,4.30),(1,0,0),(-.36,-.20,.77),4,
         [(.42,1.14,4.30),(.10,1.30,4.30),(.04,1.48,3.92),(.15,1.50,2.7),(.42,-.85,1.3),(-.36,-.28,.77)],(0,0,1)),
        ("ATX24","cable_atx",24,(.51,-1.50,3.18),(1,0,0),(.48,-.20,.77),12,
         [(.425,-1.50,3.18),(.10,-1.66,3.18),(.09,-1.72,2.70),(.40,-1.35,1.38),(.48,-.80,1.20),(.48,-.28,.77)],(0,0,1)),
        ("GPU12","cable_gpu",12,(.16,-1.12,2.11),(0,0,-1),(.11,-.20,.47),6,
         [(.16,-1.12,2.20),(.16,-1.49,2.37),(.15,-1.65,2.20),(.37,-1.31,1.35),(.49,-.8,1.21),(.11,-.28,.47)],(1,0,0)),
    ]
    for name,key,count,plug,axis,psu_plug,columns,points,offset_axis in cable_specs:
        cable = groups[key]
        connector(name+".board-end-plug",plug,2,columns,.040,axis,cable,m)
        connector(name+".PSU-end-plug",psu_plug,2,columns,.038,(0,1,0),cable,m)
        if count != 2*columns:
            raise RuntimeError("Cable count must match its two-row plug")
        sleeve_bundle(name,points,2,columns,axis,cable,m,offset_axis,pitch=.022 if count>12 else .026)
    return groups


def animate(groups):
    # Opening, normal extraction, then a readable authored exploded layout.
    # The board moves last; sockets, mounts and its metal clamp remain on it.
    # Deltas are relative to each assembled pivot, never added by another rig.
    paths = {
        "glass": (1,20,(-.90,0,0),(-1.65,3.40,.05),(0,0,1.0)),
        "front_panel": (4,25,(0,-.75,0),(.85,-1.75,-.05),(0,0,.92)),
        "case_front_fans": (28,45,(0,-.60,0),(.30,-1.25,.05),(0,0,0)),
        "case_rear_fan": (30,48,(0,.72,0),(-1.45,1.20,-.10),(0,0,0)),
        "cable_eps": (22,37,(-.45,0,0),(-3.70,1.60,.55),(0,0,0)),
        "cable_atx": (24,40,(-.50,0,0),(-3.95,2.40,.25),(0,0,0)),
        "cable_gpu": (22,38,(0,0,.35),(-3.65,1.70,.65),(0,0,0)),
        "gpu_pcb": (39,55,(-1.50,0,0),(-2.31,-.85,.21),(0,1.35,0)),
        "gpu_backplate": (55,71,(-1.65,0,.40),(-1.51,.25,.735),(0,1.10,0)),
        "gpu_heatsink": (54,72,(-1.90,0,-.20),(-3.26,-.70,-.45),(0,.38,0)),
        "gpu_shroud": (57,74,(-2.20,0,-.38),(-3.86,-2.15,.14),(0,1.38,0)),
        "gpu_fans": (63,80,(-2.45,0,-.48),(-4.11,-2.15,.14),(0,1.38,0)),
        "cpu_cooler_fan": (43,62,(-.85,0,0),(-2.60,.95,1.40),(0,0,.10)),
        "cpu_cooler": (54,74,(-1.20,0,0),(-2.54,.80,1.42),(0,0,0)),
        "cpu": (76,89,(-.65,0,0),(-3.206,.30,.27),(0,.18,0)),
        "ram_1": (77,91,(-.65,0,0),(-2.40,-.40,.80),(0,0,.12)),
        "ram_2": (80,94,(-.85,0,0),(-2.95,-.70,.38),(0,0,.28)),
        "storage_heatsink": (73,88,(-.55,0,0),(-3.80,-.10,.54),(0,0,.14)),
        "storage": (86,97,(-.60,0,0),(-2.91,-.15,.52),(0,0,.12)),
        "psu": (77,94,(-1.35,0,0),(-2.15,-1.10,-.10),(0,0,0)),
        "motherboard": (99,108,(-1.25,0,0),(-1.70,.25,.25),(0,0,0)),
    }
    gpu_separation = {
        "gpu_pcb":(-1.75,0,.08), "gpu_backplate":(-1.50,0,.65),
        "gpu_heatsink":(-1.50,0,-.43), "gpu_shroud":(-1.50,0,-.69),
        "gpu_fans":(-1.50,0,-.91),
    }
    for name in gpu_separation:
        _,_,_,end,rotation = paths[name]
        paths[name] = (39,55,(-1.50,0,0),end,rotation)
    if set(paths) != set(groups):
        raise RuntimeError("Every independent part must have one animation path")
    for name, (start,extract,pull,end,rotation) in paths.items():
        obj = groups[name]
        rest = Vector(obj["rest_location"])
        keys = [(1,(0,0,0),(0,0,0)),(start,(0,0,0),(0,0,0)),
                (extract,pull,(0,0,0)),(120,end,rotation)]
        if name in gpu_separation:
            # Whole card exits first, then parallel layers separate. No PCB/
            # backplate rotation begins until the normal separation at frame84.
            # One-frame shared hold also gives every AUTO_CLAMPED extraction
            # curve identical tangents; later PCB motion cannot bend that path.
            keys[3:3] = [(56,pull,(0,0,0)),(84,gpu_separation[name],(0,0,0))]
        frames = {frame:(delta,rot) for frame,delta,rot in keys}
        frames.update({241-frame:(delta,rot) for frame,delta,rot in reversed(keys)})
        for frame,(delta,rot) in sorted(frames.items()):
            obj.location, obj.rotation_euler = rest+Vector(delta), rot
            obj.keyframe_insert(data_path="location", frame=frame, group="Assembly motion")
            obj.keyframe_insert(data_path="rotation_euler", frame=frame, group="Assembly motion")
        action = obj.animation_data.action
        curves = list(getattr(action, "fcurves", []))
        if not curves:
            for layer in action.layers:
                for strip in layer.strips:
                    for bag in strip.channelbags:
                        curves.extend(bag.fcurves)
        for curve in curves:
            for key in curve.keyframe_points:
                key.interpolation = "BEZIER"
                key.handle_left_type = key.handle_right_type = "AUTO_CLAMPED"
    for frame, name in ((1,"ASSEMBLED"), (120,"EXPLODED"), (240,"REASSEMBLED")):
        bpy.context.scene.timeline_markers.new(name, frame=frame)
    bpy.context.scene.frame_set(1)
    return paths


def studio(scene, m, groups):
    floor = box("Studio.satin-ground", (-1,0,-.055), (200,200,.08), m["floor"], bevel=0)
    floor["camera_fit"] = False
    world = bpy.data.worlds.new("Neutral dark studio")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (.10,.11,.13,1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = .22
    scene.world = world
    for name, pos, energy, size, target in [
        ("Key.softbox",(-4,-6,9),1400,5.0,(-.4,0,2.7)),
        ("Fill.softbox",(-6,-1,6),850,3.5,(-.4,0,2.6)),
        ("Rim.softbox",(4,3,8),1800,3.5,(0,0,2.6)),
        ("Top.strip",(0,-1,8),650,2.4,(0,0,2.5)),
    ]:
        data = bpy.data.lights.new(name, "AREA")
        data.energy, data.shape, data.size = energy, "DISK", size
        light = bpy.data.objects.new(name, data)
        bpy.context.collection.objects.link(light)
        light.location = pos
        light.rotation_euler = (Vector(target)-light.location).to_track_quat("-Z","Y").to_euler()
    data = bpy.data.cameras.new("Fixed product camera")
    camera = bpy.data.objects.new("Fixed product camera", data)
    bpy.context.collection.objects.link(camera)
    scene.camera = camera
    data.lens, data.sensor_width, data.clip_end = 58, 36, 300
    data.dof.use_dof = False
    # Conservative bounds for each rigid assembly include meshes AND cable
    # curves. Check every frame, including intermediate rotations, not just ends.
    scene.frame_set(1)
    bpy.context.view_layer.update()
    assembled = {}
    for obj in scene.objects:
        if obj.type in {"MESH","CURVE"} and obj.get("camera_fit",False):
            parent = obj.parent
            transform = parent.matrix_world.inverted() if parent else None
            corners = [obj.matrix_world@Vector(corner) for corner in obj.bound_box]
            assembled.setdefault(parent,[]).extend(transform@p if transform else p for p in corners)
    bounds = {}
    for parent,corners in assembled.items():
        low = [min(p[i] for p in corners) for i in range(3)]
        high = [max(p[i] for p in corners) for i in range(3)]
        bounds[parent] = [Vector((x,y,z)) for x in (low[0],high[0])
                          for y in (low[1],high[1]) for z in (low[2],high[2])]
    points = []
    for frame in range(1,241):
        scene.frame_set(frame)
        for parent,corners in bounds.items():
            points.extend(parent.matrix_world@p if parent else p for p in corners)
    target = Vector(tuple((min(p[i] for p in points)+max(p[i] for p in points))/2 for i in range(3)))
    direction = Vector((-1.0,-1.35,.67)).normalized()
    orientation = (-direction).to_track_quat("-Z","Y")
    right,up = orientation@Vector((1,0,0)),orientation@Vector((0,1,0))
    view = data.view_frame(scene=scene)
    tan_x = max(abs(p.x/p.z) for p in view)
    tan_y = max(abs(p.y/p.z) for p in view)
    distance = max(max((p-target).dot(direction)+abs((p-target).dot(right))/(tan_x*.84),
                       (p-target).dot(direction)+abs((p-target).dot(up))/(tan_y*.84)) for p in points)+.05
    camera.location, camera.rotation_euler = target+direction*distance, orientation.to_euler()
    bpy.context.view_layer.update()
    projected = [world_to_camera_view(scene,camera,p) for p in points]
    if not all(.06 < p.x < .94 and .06 < p.y < .94 and p.z > 0 for p in projected):
        raise RuntimeError("Fixed camera does not include all 240 assembly poses")
    scene.frame_set(1)
    return {"frames_checked":240,"includes_curves":True,"method":"Conservative per-assembly bounds",
            "normalized_bounds":[min(p.x for p in projected),max(p.x for p in projected),
                                 min(p.y for p in projected),max(p.y for p in projected)],
            "camera_fixed":True,"intersection_test_performed":False}


def render_device(scene):
    scene.cycles.device = "CPU"
    try:
        prefs = bpy.context.preferences.addons["cycles"].preferences
        for backend in ("OPTIX","CUDA"):
            try:
                prefs.compute_device_type = backend
                prefs.get_devices()
                enabled = [device for device in prefs.devices if device.type == backend]
                if enabled:
                    for device in prefs.devices:
                        device.use = device in enabled
                    scene.cycles.device = "GPU"
                    print("Cycles device:", backend, flush=True)
                    return backend
            except (TypeError,ValueError,RuntimeError):
                continue
    except (KeyError,AttributeError,RuntimeError):
        pass
    print("Cycles device: CPU", flush=True)
    return "CPU"


def subtle_glare(scene):
    """Blender4.5 compositor sockets keep glow restrained, with a legacy fallback."""
    scene.use_nodes = True
    nodes,links = scene.node_tree.nodes,scene.node_tree.links
    nodes.clear()
    layers = nodes.new("CompositorNodeRLayers")
    glare = nodes.new("CompositorNodeGlare")
    glare.name,glare.label = "Restrained ice-blue optical glow","Subtle glow; no streaks"
    glare.glare_type,glare.quality = "FOG_GLOW","HIGH"
    settings = {"Threshold":1.6,"Strength":.18,"Size":.30,"Clamp":False}
    if glare.inputs.get("Strength") is not None:
        for name,value in settings.items():
            if glare.inputs.get(name) is not None:
                glare.inputs[name].default_value = value
    else:
        glare.threshold,glare.size,glare.mix = 1.6,7,-.82
    composite = nodes.new("CompositorNodeComposite")
    links.new(layers.outputs["Image"],glare.inputs["Image"])
    links.new(glare.outputs["Image"],composite.inputs["Image"])
    return {"type":"FOG_GLOW","quality":"HIGH","socket_settings":settings}


def render_component_details(scene,groups,render):
    """Actual isolated 3D stills; restore the full-motion scene in a finally block."""
    camera = scene.camera
    saved_camera = camera.matrix_world.copy()
    saved_frame,saved_subframe = scene.frame_current,scene.frame_subframe
    saved_settings = (scene.render.resolution_x,scene.render.resolution_y,
                      scene.cycles.samples,scene.cycles.adaptive_threshold,scene.render.filepath)
    saved_visibility = {obj:obj.hide_render for obj in scene.objects}
    results = []
    try:
        scene.frame_set(120)
        scene.render.resolution_x,scene.render.resolution_y = 1600,1200
        scene.cycles.samples = min(saved_settings[2],128)
        for filename,names,direction in (
            ("motherboard-detail-v4.png",("motherboard",),(-1,-.27,.23)),
            ("cooler-detail-v4.png",("cpu_cooler","cpu_cooler_fan"),(-1,-1.05,.37)),
        ):
            selected = {groups[name] for name in names}
            for obj in scene.objects:
                if obj.type in {"MESH","CURVE","FONT"}:
                    obj.hide_render = (obj.parent not in selected and not obj.name.startswith("Studio."))
            bpy.context.view_layer.update()
            points = [obj.matrix_world@Vector(p) for obj in scene.objects
                      if obj.parent in selected and obj.type in {"MESH","CURVE"}
                      for p in obj.bound_box]
            target = Vector(tuple((min(p[i] for p in points)+max(p[i] for p in points))/2 for i in range(3)))
            direction = Vector(direction).normalized()
            orientation = (-direction).to_track_quat("-Z","Y")
            right,up = orientation@Vector((1,0,0)),orientation@Vector((0,1,0))
            view = camera.data.view_frame(scene=scene)
            tan_x = max(abs(p.x/p.z) for p in view)
            tan_y = max(abs(p.y/p.z) for p in view)
            distance = max(max((p-target).dot(direction)+abs((p-target).dot(right))/(tan_x*.82),
                               (p-target).dot(direction)+abs((p-target).dot(up))/(tan_y*.82))
                           for p in points)+.03
            camera.location,camera.rotation_euler = target+direction*distance,orientation.to_euler()
            bpy.context.view_layer.update()
            projected = [world_to_camera_view(scene,camera,p) for p in points]
            if not all(.07<p.x<.93 and .07<p.y<.93 and p.z>0 for p in projected):
                raise RuntimeError("Detail camera failed to include its selected assembly")
            result = render(120,filename)
            result.update({"isolated_rigs":list(names),"actual_3D_render":True})
            results.append(result)
    finally:
        for obj,value in saved_visibility.items():
            obj.hide_render = value
        (scene.render.resolution_x,scene.render.resolution_y,scene.cycles.samples,
         scene.cycles.adaptive_threshold,scene.render.filepath) = saved_settings
        camera.matrix_world = saved_camera
        scene.frame_set(saved_frame,subframe=saved_subframe)
        bpy.context.view_layer.update()
    restored = (all(obj.hide_render==value for obj,value in saved_visibility.items()) and
                max(abs(camera.matrix_world[r][c]-saved_camera[r][c]) for r in range(4) for c in range(4))<1e-6 and
                (scene.render.resolution_x,scene.render.resolution_y,scene.cycles.samples,
                 scene.cycles.adaptive_threshold,scene.render.filepath)==saved_settings and
                scene.frame_current==saved_frame and abs(scene.frame_subframe-saved_subframe)<1e-6)
    if not restored:
        raise RuntimeError("Detail render did not restore the fixed-camera scene")
    return results,restored


def main():
    args = arguments()
    if not bpy.app.background:
        raise RuntimeError("Run this authoring script with Blender --background")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.preferences.filepaths.save_version = 0
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples, scene.cycles.use_adaptive_sampling = args.samples, True
    scene.cycles.adaptive_threshold, scene.cycles.use_denoising = .02, True
    scene.cycles.max_bounces, scene.cycles.transmission_bounces = 12, 8
    scene.render.resolution_x, scene.render.resolution_y = args.resolution, round(args.resolution*.75)
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode, scene.render.image_settings.color_depth = "RGBA","8"
    scene.render.film_transparent = False
    scene.render.fps, scene.frame_start, scene.frame_end = 30,1,240
    scene.view_settings.view_transform, scene.view_settings.exposure = "AgX",.35
    scene["asset_status"] = "V4 authored glass PC study with detailed motherboard/single tower; not manufacturer CAD or verified hardware"
    scene["source_assets"] = "Original geometry/materials; no external images or model dependencies"
    m = {
        "black": material("Matte black anodized aluminium",(.012,.014,.018),.35,.46),
        "silver": material("Machined silver",(.52,.55,.58),.93,.26),
        "brushed": material("Fine brushed satin silver",(.40,.43,.46),.90,.32,brushed=True),
        "plastic": material("Dark technical polymer",(.008,.010,.013),0,.52),
        "rubber": material("Braided cable rubber",(.010,.012,.015),0,.53),
        "sleeve": material("Fine procedural black cable sleeves",(.009,.011,.014),0,.64,brushed=True),
        "fan_blade": material("Dark swept fan blades",(.019,.021,.025),.03,.36),
        "led": material("Ice-blue fan diffuser",(.10,.44,.82),0,.28,emission=3.8),
        "led_soft": material("Low-intensity ice-blue accent diffuser",(.10,.44,.82),0,.32,emission=2.4),
        "pcb": material("Dark green-black PCB soldermask",(.008,.017,.014),.03,.55),
        "substrate": material("Green processor package substrate",(.013,.085,.060),0,.43),
        "die": material("Polished silicon die",(.31,.39,.43),.45,.20),
        "silk": material("Subdued PCB silkscreen",(.23,.26,.24),0,.60),
        "trace": material("Subdued conductive tracks",(.038,.056,.047),.30,.58),
        "contacts": material("Subdued gold contacts",(.33,.22,.09),.80,.35),
        "screw": material("Steel fasteners",(.17,.18,.19),.85,.30),
        "glass": material("Light smoked side glass",(.88,.93,.96),0,.035,glass=True),
        "glass_front": material("Light smoked front glass",(.90,.95,.98),0,.025,glass=True),
        "floor": material("Dark studio ground",(.032,.035,.040),.10,.32),
    }
    groups = build_pc(m)
    paths = animate(groups)
    camera_checks = studio(scene,m,groups)
    glare_settings = subtle_glare(scene)
    backend = render_device(scene)
    scene.frame_set(1)
    original = {obj.name: [list(row) for row in obj.matrix_world] for obj in scene.objects}
    scene.frame_set(240)
    # Compare every object, including children; no reliance on only parent transforms.
    identical = all(max(abs(obj.matrix_world[r][c]-original[obj.name][r][c])
                        for r in range(4) for c in range(4)) < 1e-6 for obj in scene.objects)
    if not identical:
        raise RuntimeError("End pose differs from assembled pose")
    scene.frame_set(1)
    scene.frame_set(120)
    rig_metadata = {}
    for name,obj in groups.items():
        rest = list(obj["rest_location"])
        rig_metadata[name] = {
            "object":obj.name,"parent":obj.parent.name,"rest_location":rest,
            "exploded_displacement":[obj.location[i]-rest[i] for i in range(3)],
            "exploded_rotation_radians":list(obj.rotation_euler),
            "extraction_frames":[paths[name][0],paths[name][1]],
        }
    scene.frame_set(1)
    blend = output/"pc-assembly-v4.blend"
    bpy.ops.wm.save_as_mainfile(filepath=str(blend))
    rendered = []
    final_settings = (scene.render.resolution_x,scene.render.resolution_y,args.samples,
                      scene.cycles.adaptive_threshold)
    def render(frame,filename):
        nonlocal backend
        scene.frame_set(frame)
        scene.render.filepath = str(output/filename)
        try:
            bpy.ops.render.render(write_still=True)
        except Exception:
            if scene.cycles.device != "GPU":
                raise
            print("GPU render failed; retrying on CPU",flush=True)
            scene.cycles.device,scene.cycles.samples,backend = "CPU",min(scene.cycles.samples,48),"CPU fallback"
            bpy.ops.render.render(write_still=True)
        return {"frame":frame,"path":filename,"device":backend,"samples":scene.cycles.samples,
                "resolution":[scene.render.resolution_x,scene.render.resolution_y]}
    if args.render_stills:
        for frame,filename in ((1,"assembled-v4.png"),(120,"exploded-v4.png")):
            if scene.cycles.device == "CPU":
                scene.cycles.samples = min(args.samples,48)
            rendered.append(render(frame,filename))
    details,details_restored = [],True
    if args.render_details:
        details,details_restored = render_component_details(scene,groups,render)
    previews = []
    if args.render_preview:
        (output/"preview-frames-v4").mkdir(exist_ok=True)
        scene.render.resolution_x,scene.render.resolution_y = 800,600
        scene.cycles.samples,scene.cycles.adaptive_threshold = 32,.04
        frames = sorted(set(range(1,241,6))|{120,240})
        manifest = {"version":4,"resolution":[800,600],"samples":32,
                    "animation_frames":[1,240],"expected_frame_count":len(frames),"frames":[]}
        for frame in frames:
            filename = f"preview-frames-v4/frame-{frame:04}.png"
            previews.append(render(frame,filename))
            manifest["frames"].append({"frame":frame,"path":filename})
            # A valid partial manifest permits progress inspection while frames render.
            manifest["complete"] = len(manifest["frames"]) == len(frames)
            (output/"preview-manifest.json").write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf-8")
    (scene.render.resolution_x,scene.render.resolution_y,scene.cycles.samples,
     scene.cycles.adaptive_threshold) = final_settings
    scene.render.filepath = str(output/"assembled-v4.png")
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=str(blend))
    metadata = {
        "version":4,"status":scene["asset_status"],"blender":bpy.app.version_string,"engine":"CYCLES",
        "device":backend,"saved_blend_samples":scene.cycles.samples,
        "saved_blend_resolution":[scene.render.resolution_x,scene.render.resolution_y],
        "denoising":scene.cycles.use_denoising,"color_management":"AgX",
        "compositor":glare_settings,
        "art_direction":{"palette":"Black/silver with one restrained ice-blue hue",
                         "GPU":"Original three-fan cast-metal styling inspired by ROG Astral RTX5080",
                         "case":"Original front/side glass styling inspired by O11D EVO RGB",
                         "motherboard":"Functional zones, split sockets and stepped VRM/IO/chipset shielding",
                         "cooler":"Original single tower with 49 notched/perforated fins and seven heatpipes",
                         "animation":"V3 rig pivots and staged keyframe paths retained"},
        "rigs":rig_metadata,"independently_animated_rig_count":len(groups),"object_count":len(scene.objects),
        "frame1_matches_frame240":identical,"camera_validation":camera_checks,
        "rendered_stills":rendered,"preview_renders":previews,"animation_frames":[1,240],
        "detail_renders":details,"detail_scene_restored":details_restored,
        "fin_holes":{"count_per_fin":7,"fin_count":49,"geometry":"Opposite-wound filled 2D curve loops"},
        "limitations":[
            "Original generic geometry; no brand assets, product CAD, certified dimensions or exact electrical pinout",
            "Cable sleeves and plugs translate as rigid authored assemblies; no flexible cable simulation",
            "Staged visual extraction is not a collision or mechanical disassembly validation",
            "Front intake fans move as one carrier; individual fan geometry remains identifiable",
            "Manufacturing details are a visual interpretation, not a circuit, thermal or compatibility model",
            "GPU reference has four fans; this original study retains the approved three-fan structure",
            "RAM/cooler lighting is original art direction, not a feature claim for referenced products",
        ],
    }
    (output/"render-metadata-v4.json").write_text(json.dumps(metadata,indent=2)+"\n",encoding="utf-8")
    print("Saved authored scene:",blend,flush=True)


if __name__ == "__main__":
    main()
