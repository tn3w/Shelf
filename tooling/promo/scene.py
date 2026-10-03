import math
import os
import random
import sys
from pathlib import Path

import bmesh
import bpy
from mathutils import Matrix, Quaternion, Vector

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "out"
FRAMES = OUT / os.environ.get("PROMO_FRAMES", "frames")
FONTS = {
    "bold": "/usr/share/fonts/gsfonts/NimbusSans-Bold.otf",
    "regular": "/usr/share/fonts/gsfonts/NimbusSans-Regular.otf",
    "symbols": "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
}
FPS = int(os.environ.get("PROMO_FPS", 30))
HEIGHT = int(os.environ.get("PROMO_HEIGHT", 1080))
SAMPLES = int(os.environ.get("PROMO_SAMPLES", 64))
EXTENSION = os.environ.get("PROMO_FORMAT", "png")
SUFFIX = os.environ.get("PROMO_SUFFIX", "")
CAPTIONS_VISIBLE = os.environ.get("PROMO_CAPTIONS", "1") == "1"
DURATION = 58
LIGHTS_OUT = 40.0

PHONE = dict(width=0.93, height=2.0, depth=0.1, corner=0.105, bezel=0.035)
TABLET = dict(width=1.78, height=2.3, depth=0.075, corner=0.12, bezel=0.09)

TITANIUM = (0.62, 0.6, 0.57, 1)
TEAL = (0.045, 0.16, 0.17, 1)
BLACK = (0.004, 0.004, 0.005, 1)
GOLD = (1.0, 0.62, 0.16, 1)
CREAM = (0.93, 0.85, 0.66, 1)

WALL_COLORS = [
    (0.0, (0.93, 0.95, 0.94)),
    (11.5, (0.93, 0.95, 0.94)),
    (12.8, (0.84, 0.92, 0.83)),
    (21.2, (0.84, 0.92, 0.83)),
    (22.5, (0.95, 0.9, 0.8)),
    (29.2, (0.95, 0.9, 0.8)),
    (30.6, (0.82, 0.89, 0.95)),
    (39.0, (0.82, 0.89, 0.95)),
    (41.6, (0.012, 0.02, 0.05)),
    (45.5, (0.012, 0.02, 0.05)),
    (46.8, (0.05, 0.026, 0.014)),
    (51.5, (0.05, 0.026, 0.014)),
    (53.2, (0.012, 0.045, 0.05)),
]

CAMERA_KEYS = [
    (0.0, (0, 0, -0.8, 28, 8, 11.5, 0, 85, 0.2)),
    (3.0, (0, 0, 0, 14, 5, 10.5, 0, 85, 0.2)),
    (5.4, (0, 0, 0, -10, 3, 10.0, 0, 85, 0.2)),
    (8.5, (0, 0, 0.1, -24, 4, 9.6, 0, 85, 0.2)),
    (11.8, (0, 0, 0.1, 12, -4, 8.8, 0, 85, 0.2)),
    (13.6, (0, 0, 0.5, 30, 12, 6.4, 3, 85, 0.12)),
    (15.4, (0, 0, 0.7, 5, 2, 3.0, 0, 85, 0.0)),
    (17.6, (0, 0, 0.3, 8, 4, 4.2, 0, 85, 0.0)),
    (19.2, (0, 0, 0.0, 22, 6, 8.2, 0, 85, 0.2)),
    (21.8, (0, 0, 0, 0, 10, 9.5, 0, 85, 0.2)),
    (24.2, (0, 0, 0, 20, 36, 9.8, 0, 70, 0.2)),
    (27.2, (0, 0, 0, -30, 22, 8.2, -4, 70, 0.2)),
    (29.6, (0, 0, 0, 0, 4, 12.0, 0, 85, 0.2)),
    (32.2, (0, 0, 0, 24, 6, 11.5, 0, 85, 0.2)),
    (34.4, (0.1, 0, 0.3, 8, -2, 4.6, 0, 85, 0.0)),
    (37.0, (0, 0, 0.4, -14, 4, 3.8, 0, 85, 0.0)),
    (38.4, (0, 0, 0, -6, 2, 9.5, 0, 85, 0.2)),
    (39.8, (0, 0, 0, -4, 2, 12.0, 0, 85, 0.2)),
    (42.2, (0, 0, 0, 20, 8, 13.5, 0, 85, 0.2)),
    (44.6, (0, 0, 0, 6, 4, 12.5, 0, 85, 0.2)),
    (46.8, (0, 0, 0.3, 0, 2, 7.0, 0, 85, 0.0)),
    (47.6, (0, 0, 0.45, 0, 2, 3.6, 0, 85, 0.0)),
    (48.8, (0, 0, 0.45, -4, 2, 3.4, 0, 85, 0.0)),
    (49.8, (0, 0, 0, -16, 3, 7.0, 0, 85, 0.2)),
    (53.0, (0, 0, 0, 0, 0, 11.0, 0, 85, 0.0)),
    (58.0, (0, 0, 0, 0, 0, 13.0, 0, 85, 0.0)),
]

DEVICE_KEYS = [
    (0.0, (0, 0, -3.6, 16, -8, 232), False),
    (1.4, (0, 0, -0.9, 8, -3, 196), False),
    (2.6, (0, 0, 0.05, -3, 2, 181), True),
    (3.1, (0, 0, 0.1, -4, 2, 180), True),
    (4.0, (0.15, 0, 0.35, 10, -4, 78), False),
    (5.0, (0, 0, 0.1, -3, 1, -6), False),
    (5.8, (0, 0, 0, 0, 0, 0), True),
    (8.2, (0, 0, 0.05, -4, 0, -16), False),
    (10.6, (0.05, 0, -0.05, 3, 0, 12), False),
    (12.0, (0, 0, 0, 0, 0, 0), False),
    (14.0, (-0.1, 0, 0.1, -14, 0, 14), False),
    (16.0, (0, 0, 0, -3, 0, -4), False),
    (19.0, (0, 0, 0.1, 6, 0, 18), False),
    (21.4, (0, 0, 0, 0, 0, 0), True),
    (22.0, (0, 0, 0, 0, 0, 0), True),
    (23.2, (0, 0.4, 0.6, -20, 0, 180), False),
    (24.6, (0, 0, 0.2, 8, 0, 360), False),
    (25.4, (0, 0, 0.1, 0, 0, 360), True),
    (26.6, (0, 0, 0.7, 190, 0, 360), False),
    (27.9, (0, 0, 0, 360, 0, 360), True),
    (29.2, (0, 0, 0.1, 360, 0, 360), True),
    (30.4, (0, 0, 0.25, 368, -4, 540), False),
    (31.7, (0, 0, 0.05, 360, 0, 720), True),
    (34.0, (0, 0, 0, 354, 0, 718), False),
    (36.5, (0.1, 0, 0, 364, 0, 735), False),
    (39.2, (0, 0, 0.1, 360, 0, 720), True),
    (41.5, (0, 0, 0.35, 352, 0, 706), False),
    (44.2, (0, 0, 0.2, 364, 0, 728), False),
    (45.8, (0, 0, 0.4, 350, 6, 900), False),
    (46.8, (0, 0, 0, 360, 0, 1080), True),
    (49.5, (0, 0, 0.05, 362, 0, 1070), False),
    (51.5, (0, 0, 0.1, 354, 0, 1090), False),
    (52.4, (0, 0, 0.2, 360, 0, 1080), False),
    (53.6, (0, 0, 0.9, 372, 0, 1260), False),
]

CAPTIONS = [
    ("Books, offline.", 0.9, 3.8, -0.22, 0.0, 0.11),
    ("Your whole library.", 5.0, 8.8, -0.22, 0.05, 0.1),
    ("Find anything.", 12.6, 14.8, -0.27, 0.05, 0.085),
    ("Typos included.", 18.6, 21.4, -0.27, 0.05, 0.085),
    ("But there is more.", 22.4, 25.6, -0.22, 0.05, 0.1),
    ("Every genre. Every series.", 26.0, 29.4, -0.22, 0.05, 0.075),
    ("Read anything.", 31.7, 33.5, -0.26, 0.05, 0.09),
    ("EPUB  PDF  FB2  CBZ  TXT", 38.0, 39.8, -0.22, 0.05, 0.05),
    ("Lights out.", 40.9, 44.6, -0.22, 0.05, 0.11),
    ("One more page.", 49.2, 51.0, -0.22, 0.05, 0.09),
    ("Every day.", 51.0, 52.8, -0.22, 0.05, 0.09),
    ("Shelf", 54.0, 58.0, 0.0, -0.2, 0.13),
    ("Free. Open source. Offline.", 54.8, 58.0, 0.0, -0.31, 0.04),
    ("github.com/tn3w/Shelf", 55.4, 58.0, 0.0, -0.39, 0.036),
]
CAPTION_GAP = 1.2

LIGHT_RIG = [
    ("key", (-4, -7, 6), (5, 5), 700, 220),
    ("fill", (6, -6, 2), (6, 6), 160, 20),
    ("rim_left", (-4.5, 3, 1), (0.8, 7), 600, 350),
    ("rim_right", (4.5, 3, 1), (0.8, 7), 600, 350),
    ("top", (0, -2, 8), (4, 4), 220, 40),
]


def ease(value):
    value = min(max(value, 0.0), 1.0)
    return value * value * value * (value * (value * 6 - 15) + 10)


def ramp(time, start, end):
    return ease((time - start) / (end - start))


def pulse(time, start, end, fade=0.5):
    return ramp(time, start, start + fade) * (1 - ramp(time, end - fade, end))


def mix(first, second, amount):
    if isinstance(first, (tuple, list)):
        return tuple(mix(a, b, amount) for a, b in zip(first, second))
    return first + (second - first) * amount


def stops(keys):
    def evaluate(time):
        if time <= keys[0][0]:
            return keys[0][1]
        for (start, first), (end, second) in zip(keys, keys[1:]):
            if time <= end:
                return mix(first, second, ramp(time, start, end))
        return keys[-1][1]

    return evaluate


def tangents(keys):
    result = []
    for index, key in enumerate(keys):
        rest = len(key) > 2 and key[2]
        if rest or index in (0, len(keys) - 1):
            result.append(tuple(0.0 for _ in key[1]))
            continue
        before, after = keys[index - 1], keys[index + 1]
        span = after[0] - before[0]
        result.append(tuple((b - a) / span for a, b in zip(before[1], after[1])))
    return result


def flow(keys):
    slopes = tangents(keys)

    def evaluate(time):
        if time <= keys[0][0]:
            return tuple(keys[0][1])
        if time >= keys[-1][0]:
            return tuple(keys[-1][1])
        index = max(i for i in range(len(keys) - 1) if keys[i][0] <= time)
        start, end = keys[index][0], keys[index + 1][0]
        step, s = end - start, (time - start) / (end - start)
        h00, h10 = 2 * s**3 - 3 * s**2 + 1, s**3 - 2 * s**2 + s
        h01, h11 = -2 * s**3 + 3 * s**2, s**3 - s**2
        return tuple(
            h00 * p0 + h10 * step * m0 + h01 * p1 + h11 * step * m1
            for p0, p1, m0, m1 in zip(
                keys[index][1], keys[index + 1][1], slopes[index], slopes[index + 1]
            )
        )

    return evaluate


camera_track = flow([(time, values) for time, values in CAMERA_KEYS])
device_track = flow(DEVICE_KEYS)
wall_track = stops(WALL_COLORS)


def darkness(time):
    return ramp(time, LIGHTS_OUT, LIGHTS_OUT + 1.6)


def morph(time):
    return ramp(time, 30.0, 31.8) * (1 - ramp(time, 44.8, 46.6))


def screen_mix(time):
    return ramp(time, 30.5, 31.6) * (1 - ramp(time, 45.0, 46.4))


def device_scale(time):
    return 1 - ramp(time, 52.8, 53.9)


def rotation_matrix(pitch, roll, yaw):
    return (
        Matrix.Rotation(math.radians(yaw), 4, "Z")
        @ Matrix.Rotation(math.radians(pitch), 4, "X")
        @ Matrix.Rotation(math.radians(roll), 4, "Y")
    )


def device_pose(time):
    x, y, z, pitch, roll, yaw = device_track(time)
    bob = math.sin(time * 1.45) * 0.025
    sway = math.sin(time * 0.9) * 1.2
    quaternion = rotation_matrix(pitch + sway, roll, yaw).to_quaternion()
    return Vector((x, y, z + bob)), quaternion


def anchor(camera, gap, x, y):
    position, quaternion, distance, lens, shift = camera
    depth = distance + gap
    view_width = 36 / lens * depth
    view_height = view_width * 9 / 16
    right = quaternion @ Vector((1, 0, 0))
    up = quaternion @ Vector((0, 1, 0))
    forward = quaternion @ Vector((0, 0, -1))
    offset = right * (x + shift) * view_width + up * y * view_height
    return position + forward * depth + offset, view_height


def camera_pose(time):
    tx, ty, tz, azimuth, elevation, distance, roll, lens, shift = camera_track(time)
    azimuth += math.sin(time * 0.5) * 0.8
    elevation += math.sin(time * 0.37 + 1) * 0.5
    azimuth, elevation = math.radians(azimuth), math.radians(elevation)
    target = Vector((tx, ty, tz)) + Vector(device_track(time)[:3]) * 0.8 * device_scale(time)
    offset = Vector(
        (
            math.sin(azimuth) * math.cos(elevation),
            -math.cos(azimuth) * math.cos(elevation),
            math.sin(elevation),
        )
    )
    position = target + offset * distance
    quaternion = (target - position).to_track_quat("-Z", "Y")
    quaternion = quaternion @ Quaternion((0, 0, -1), math.radians(roll))
    return position, quaternion, distance, lens, -shift


def outline(width, height, corner, steps=12):
    half_width, half_height = width / 2, height / 2
    centers = [
        (half_width - corner, half_height - corner, 0),
        (corner - half_width, half_height - corner, 90),
        (corner - half_width, corner - half_height, 180),
        (half_width - corner, corner - half_height, 270),
    ]
    points = []
    for center_x, center_z, start in centers:
        for step in range(steps + 1):
            angle = math.radians(start + 90 * step / steps)
            points.append(
                (center_x + corner * math.cos(angle), center_z + corner * math.sin(angle))
            )
    return points


def profile(depth, fillet, steps=6):
    half = depth / 2
    front = [
        (fillet * (1 - math.sin(a)), -half + fillet * (1 - math.cos(a)))
        for a in (math.pi / 2 * i / steps for i in range(steps + 1))
    ]
    back = [(inset, -y) for inset, y in reversed(front)]
    return front + back


def fill_slab(mesh, width, height, depth, corner, fillet):
    bm = bmesh.new()
    rings = []
    for inset, y in profile(depth, fillet):
        points = outline(width - 2 * inset, height - 2 * inset, corner - inset)
        rings.append([bm.verts.new((x, y, z)) for x, z in points])
    for lower, upper in zip(rings, rings[1:]):
        for index in range(len(lower)):
            following = (index + 1) % len(lower)
            bm.faces.new((lower[index], lower[following], upper[following], upper[index]))
    front = bm.faces.new(rings[0])
    back = bm.faces.new(rings[-1][::-1])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    for face in bm.faces:
        face.smooth = True
        face.material_index = 0
    front.material_index, back.material_index = 2, 1
    for plate in (front, back):
        for edge in plate.edges:
            edge.smooth = False
    bm.to_mesh(mesh)
    bm.free()


def fill_screen(mesh, width, height, corner, y):
    bm = bmesh.new()
    layer = bm.loops.layers.uv.new("UVMap")
    points = outline(width, height, corner)
    face = bm.faces.new([bm.verts.new((x, y, z)) for x, z in points])
    if face.normal.y > 0:
        face.normal_flip()
    for loop in face.loops:
        x, _, z = loop.vert.co
        loop[layer].uv = (x / width + 0.5, z / height + 0.5)
    bm.to_mesh(mesh)
    bm.free()


def principled(name, color, **inputs):
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    node = material.node_tree.nodes["Principled BSDF"]
    node.inputs["Base Color"].default_value = color
    for key, value in inputs.items():
        node.inputs[key.replace("_", " ")].default_value = value
    return material


def emissive(name, color, strength=1.0):
    return principled(
        name, BLACK, Emission_Color=color, Emission_Strength=strength, Roughness=0.5
    )


def glass(name, color, roughness=0.02):
    return principled(
        name, color, Transmission_Weight=1.0, Roughness=roughness, IOR=1.45
    )


def link_object(obj, parent=None):
    bpy.context.scene.collection.objects.link(obj)
    obj.parent = parent
    return obj


def make_empty(name, parent=None):
    return link_object(bpy.data.objects.new(name, None), parent)


def make_mesh_object(name, mesh, materials, parent=None):
    for material in materials:
        mesh.materials.append(material)
    return link_object(bpy.data.objects.new(name, mesh), parent)


def primitive(kind, name, material, parent=None, location=(0, 0, 0), scale=(1, 1, 1),
              rotation=(0, 0, 0), **options):
    operators = {
        "cube": bpy.ops.mesh.primitive_cube_add,
        "sphere": bpy.ops.mesh.primitive_uv_sphere_add,
        "cylinder": bpy.ops.mesh.primitive_cylinder_add,
        "torus": bpy.ops.mesh.primitive_torus_add,
        "cone": bpy.ops.mesh.primitive_cone_add,
    }
    operators[kind](**options)
    obj = bpy.context.object
    bpy.context.scene.collection.objects.unlink(obj)
    obj.name = name
    obj.data.materials.append(material)
    for polygon in obj.data.polygons:
        polygon.use_smooth = True
    obj.location, obj.scale = location, scale
    obj.rotation_euler = [math.radians(angle) for angle in rotation]
    return link_object(obj, parent)


def box(name, material, size, parent=None, location=(0, 0, 0), bevel=0.0):
    obj = primitive("cube", name, material, parent, location, size, size=1)
    if bevel:
        modifier = obj.modifiers.new("bevel", "BEVEL")
        modifier.width, modifier.segments = bevel, 3
    return obj


class Device:
    def __init__(self):
        self.root = make_empty("device")
        self.frame = principled(
            "frame", TITANIUM, Metallic=1.0, Roughness=0.28
        )
        self.back = principled(
            "back", TEAL, Roughness=0.42, Coat_Weight=0.35, Coat_Roughness=0.2
        )
        self.front = principled("front", BLACK, Roughness=0.04, Specular_IOR_Level=0.8)
        self.body = bpy.data.meshes.new("body")
        self.body_object = make_mesh_object(
            "body", self.body, [self.frame, self.back, self.front], self.root
        )
        self.screen = bpy.data.meshes.new("screen")
        self.screen_material = self.screen_material_nodes()
        self.screen_object = make_mesh_object(
            "screen", self.screen, [self.screen_material], self.root
        )
        self.bump_root = make_empty("bump", self.root)
        self.build_bump()
        self.dims = None

    def screen_material_nodes(self):
        material = principled(
            "screen", BLACK, Roughness=0.05, Specular_IOR_Level=0.8,
            Emission_Strength=0.95,
        )
        tree = material.node_tree
        textures = []
        for name in ("phone", "tablet"):
            node = tree.nodes.new("ShaderNodeTexImage")
            node.image = bpy.data.images.load(str(OUT / f"{name}{SUFFIX}.mp4"))
            node.image.source = "MOVIE"
            node.image_user.frame_start = 1
            node.image_user.frame_duration = FPS * DURATION
            node.image_user.use_auto_refresh = True
            node.interpolation = "Cubic"
            textures.append(node)
        blend = tree.nodes.new("ShaderNodeMix")
        blend.data_type = "RGBA"
        tree.links.new(textures[0].outputs["Color"], blend.inputs["A"])
        tree.links.new(textures[1].outputs["Color"], blend.inputs["B"])
        principled_node = tree.nodes["Principled BSDF"]
        tree.links.new(blend.outputs["Result"], principled_node.inputs["Emission Color"])
        self.blend = blend
        return material

    def build_bump(self):
        mesh = bpy.data.meshes.new("plateau")
        fill_slab(mesh, 0.46, 0.46, 0.04, 0.11, 0.012)
        plateau = make_mesh_object(
            "plateau", mesh, [self.frame, self.frame, self.frame], self.bump_root
        )
        plateau.location = (0, 0.02, 0)
        lens = principled("lens", BLACK, Roughness=0.05, Specular_IOR_Level=1.0)
        ring = principled("ring", TITANIUM, Metallic=1.0, Roughness=0.2)
        for x, z in ((-0.105, 0.105), (0.105, 0.105), (0.0, -0.105)):
            primitive("cylinder", "lens", lens, self.bump_root, (x, 0.05, z),
                      (0.085, 0.085, 0.02), (90, 0, 0), vertices=32)
            primitive("torus", "ring", ring, self.bump_root, (x, 0.045, z),
                      (1, 1, 1), (90, 0, 0), major_radius=0.092, minor_radius=0.012)

    def rebuild(self, time):
        amount = morph(time)
        dims = tuple(round(mix(PHONE[key], TABLET[key], amount), 4) for key in PHONE)
        if dims == self.dims:
            return
        self.dims = dims
        width, height, depth, corner, bezel = dims
        self.body.clear_geometry()
        fill_slab(self.body, width, height, depth, corner, 0.03)
        self.screen.clear_geometry()
        fill_screen(
            self.screen, width - 2 * bezel, height - 2 * bezel,
            max(corner - bezel, 0.02), -depth / 2 - 0.002,
        )
        back_x = width / 2 - 0.37
        self.bump_root.location = (back_x, depth / 2, height / 2 - 0.37)
        scale = 1 - ramp(amount, 0.0, 0.45)
        self.bump_root.scale = (scale,) * 3
        self.bump_root.hide_render = scale < 0.01
        for child in self.bump_root.children:
            child.hide_render = scale < 0.01

    def update(self, time):
        self.rebuild(time)
        self.blend.inputs["Factor"].default_value = screen_mix(time)
        self.screen_material.node_tree.nodes["Principled BSDF"].inputs[
            "Emission Strength"
        ].default_value = mix(0.95, 0.8, darkness(time))
        self.root.hide_render = device_scale(time) < 0.01


class Caption:
    def __init__(self, index, body, start, end, x, y, height):
        self.body, self.start, self.end = body, start, end
        self.x, self.y, self.height = x, y, height
        curve = bpy.data.curves.new(f"caption{index}", "FONT")
        curve.body = body
        curve.font = bpy.data.fonts.load(FONTS["bold" if height > 0.06 else "regular"])
        curve.align_x, curve.align_y = "CENTER", "CENTER"
        self.material = bpy.data.materials.new(f"caption{index}")
        self.material.use_nodes = True
        tree = self.material.node_tree
        tree.nodes.clear()
        self.emission = tree.nodes.new("ShaderNodeEmission")
        clear = tree.nodes.new("ShaderNodeBsdfTransparent")
        self.fade = tree.nodes.new("ShaderNodeMixShader")
        output = tree.nodes.new("ShaderNodeOutputMaterial")
        tree.links.new(clear.outputs[0], self.fade.inputs[1])
        tree.links.new(self.emission.outputs[0], self.fade.inputs[2])
        tree.links.new(self.fade.outputs[0], output.inputs[0])
        curve.materials.append(self.material)
        self.object = link_object(bpy.data.objects.new(f"caption{index}", curve))
        self.object.rotation_mode = "QUATERNION"

    def visible(self, time):
        return self.start <= time <= self.end

    def place(self, time, camera):
        rise = (1 - ramp(time, self.start, self.start + 0.8)) * 0.03
        location, view_height = anchor(camera, CAPTION_GAP, self.x, self.y - rise)
        self.object.location = location
        self.object.rotation_quaternion = camera[1]
        self.object.scale = (view_height * self.height,) * 3

    def update(self, time):
        alpha = pulse(time, self.start, self.end, 0.7)
        self.object.hide_render = alpha <= 0.001 or not CAPTIONS_VISIBLE
        self.fade.inputs[0].default_value = alpha
        color = mix((0.07, 0.075, 0.08), (0.96, 0.96, 0.98), darkness(time))
        self.emission.inputs["Color"].default_value = (*color, 1)


def build_world_and_wall():
    scene = bpy.context.scene
    scene.world = bpy.data.worlds.new("world")
    scene.world.use_nodes = True
    wall_material = principled("wall", (0.9, 0.9, 0.9, 1), Roughness=0.95)
    bpy.ops.mesh.primitive_plane_add(size=90, rotation=(math.pi / 2, 0, 0))
    wall = bpy.context.object
    wall.location = (0, 9, 0)
    wall.data.materials.append(wall_material)
    return scene.world.node_tree.nodes["Background"], wall_material.node_tree.nodes[
        "Principled BSDF"
    ]


def build_lights():
    lights = []
    for name, position, size, bright, dim in LIGHT_RIG:
        data = bpy.data.lights.new(name, "AREA")
        data.shape, data.size, data.size_y = "RECTANGLE", size[0], size[1]
        obj = link_object(bpy.data.objects.new(name, data))
        obj.location = position
        obj.rotation_euler = (-Vector(position)).to_track_quat("-Z", "Y").to_euler()
        lights.append((data, bright, dim))
    return lights


def build_camera():
    data = bpy.data.cameras.new("camera")
    data.sensor_width = 36
    data.dof.use_dof = True
    data.dof.aperture_fstop = 4.0
    data.clip_start, data.clip_end = 0.5, 200
    obj = link_object(bpy.data.objects.new("camera", data))
    obj.rotation_mode = "QUATERNION"
    bpy.context.scene.camera = obj
    return obj


def configure_render(preview):
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = HEIGHT * 16 // 9
    scene.render.resolution_y = HEIGHT
    scene.render.resolution_percentage = 50 if preview else 100
    scene.render.fps = FPS
    scene.frame_start, scene.frame_end = 1, FPS * DURATION
    scene.view_settings.view_transform = "Standard"
    scene.render.use_motion_blur = not preview
    scene.render.motion_blur_shutter = 0.5 if FPS > 30 else 0.4
    scene.eevee.use_raytracing = True
    scene.eevee.taa_render_samples = 12 if preview else SAMPLES
    scene.render.image_settings.file_format = "JPEG" if EXTENSION == "jpg" else "PNG"
    scene.render.image_settings.quality = 97
    scene.render.image_settings.color_mode = "RGB"


class Show:
    def __init__(self, preview):
        configure_render(preview)
        self.device = Device()
        self.camera = build_camera()
        self.background, self.wall = build_world_and_wall()
        self.lights = build_lights()
        self.captions = [
            Caption(index, *caption) for index, caption in enumerate(CAPTIONS)
        ]
        self.props = build_props()

    def pose(self, time):
        position, quaternion = device_pose(time)
        self.device.root.location = position
        self.device.root.rotation_mode = "QUATERNION"
        self.device.root.rotation_quaternion = quaternion
        self.device.root.scale = (max(device_scale(time), 0.001),) * 3
        camera = camera_pose(time)
        self.camera.location, self.camera.rotation_quaternion = camera[:2]
        self.camera.data.lens = camera[3]
        self.camera.data.shift_x = camera[4]
        self.camera.data.dof.focus_distance = camera[2]
        for caption in self.captions:
            if caption.visible(time):
                caption.place(time, camera)
        for prop in self.props:
            prop.pose(time)

    def bake(self, frames):
        for frame in frames:
            self.pose((frame - 1) / FPS)
            self.keyframe_all(frame, (frame - 1) / FPS)

    def keyframe_all(self, frame, time):
        targets = [self.device.root, self.camera]
        targets += [caption.object for caption in self.captions]
        targets += [obj for prop in self.props if prop.active(time)
                    for obj in prop.moving]
        for obj in targets:
            channels = ["location", "scale"]
            channels.append(
                "rotation_quaternion" if obj.rotation_mode == "QUATERNION"
                else "rotation_euler"
            )
            for channel in channels:
                obj.keyframe_insert(channel, frame=frame)
        data = self.camera.data
        for channel in ("lens", "shift_x"):
            data.keyframe_insert(channel, frame=frame)
        data.dof.keyframe_insert("focus_distance", frame=frame)

    def dynamic(self, time):
        amount = darkness(time)
        color = wall_track(time)
        self.wall.inputs["Base Color"].default_value = (*color, 1)
        self.background.inputs["Color"].default_value = (*color, 1)
        self.background.inputs["Strength"].default_value = mix(0.9, 0.12, amount)
        finale = ramp(time, 52.6, 54.0)
        for data, bright, dim in self.lights:
            data.energy = mix(mix(bright, dim, amount), bright * 1.5, finale)
        self.device.update(time)
        for caption in self.captions:
            caption.update(time)
        for prop in self.props:
            prop.update(time)

    def render(self, frame, path):
        bpy.context.scene.frame_set(frame)
        self.dynamic((frame - 1) / FPS)
        bpy.context.scene.render.filepath = str(path)
        bpy.ops.render.render(write_still=True)


class Prop:
    def __init__(self, window, root, moving, motion=None, effect=None, grow=True,
                 gaps=()):
        self.window, self.root, self.gaps = window, root, gaps
        self.moving = [root] + [obj for obj in moving if obj is not root]
        self.motion, self.effect, self.grow = motion, effect, grow
        self.family = [root] + list(root.children_recursive)

    def active(self, time):
        return self.window[0] - 0.1 <= time <= self.window[1] + 0.1

    def presence(self, time):
        value = pulse(time, self.window[0], self.window[1], 0.7)
        for start, end in self.gaps:
            value *= 1 - pulse(time, start, end, 0.6)
        return value

    def pose(self, time):
        if not (self.motion and self.active(time)):
            return
        self.root.scale = (1, 1, 1)
        self.motion(time)
        if self.grow:
            self.root.scale = self.root.scale * max(self.presence(time), 0.001)

    def update(self, time):
        shown = self.presence(time) > 0.001
        for obj in self.family:
            obj.hide_render = not shown
        if self.effect and shown:
            self.effect(time)


def srgb(code):
    values = [int(code[index:index + 2], 16) / 255 for index in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in values]
    return (*linear, 1)


def place(obj, location, rotation=(0, 0, 0), scale=1.0):
    obj.location = location
    obj.rotation_euler = rotation
    obj.scale = (scale,) * 3


def build_snitch():
    root = make_empty("snitch")
    gold = principled("snitch gold", GOLD, Metallic=1.0, Roughness=0.18)
    silver = principled("snitch wing", (0.9, 0.88, 0.8, 1), Metallic=1.0, Roughness=0.3)
    primitive("sphere", "snitch body", gold, root, scale=(0.16, 0.16, 0.16),
              segments=48, ring_count=24)
    wings = []
    for side in (-1, 1):
        pivot = make_empty("wing", root)
        primitive("sphere", "wing blade", silver, pivot, (side * 0.32, 0, 0.03),
                  (0.34, 0.012, 0.07), segments=24, ring_count=12)
        wings.append((pivot, side))

    def path(time):
        if time < 9.9:
            progress = (time - 7.6) / 2.3
            return (mix(-6.5, 6.5, progress), 3.0, 1.0 + 0.8 * math.sin(progress * 9), 1.0)
        progress = (time - 9.9) / 2.1
        return (mix(7.0, -7.0, progress), -2.8, -0.3 + 0.9 * math.sin(progress * 7), 1.5)

    def motion(time):
        x, y, z, size = path(time)
        place(root, (x, y, z), (0, 0.3 * math.sin(time * 5), 0), size)
        for pivot, side in wings:
            pivot.rotation_euler = (0, side * math.sin(time * 42) * 0.75, 0)

    return Prop((7.6, 12.0), root, [root] + [pivot for pivot, _ in wings], motion)


def picture(name, material, width, height, parent, y):
    mesh = bpy.data.meshes.new(name)
    bm = bmesh.new()
    layer = bm.loops.layers.uv.new("UVMap")
    corners = [(-1, -1), (1, -1), (1, 1), (-1, 1)]
    face = bm.faces.new(
        [bm.verts.new((x * width / 2, y, z * height / 2)) for x, z in corners]
    )
    if face.normal.y > 0:
        face.normal_flip()
    for loop in face.loops:
        x, _, z = loop.vert.co
        loop[layer].uv = (x / width + 0.5, z / height + 0.5)
    bm.to_mesh(mesh)
    bm.free()
    return make_mesh_object(name, mesh, [material], parent)


def cover_material(name):
    material = principled(name, (1, 1, 1, 1), Roughness=0.4, Coat_Weight=0.3)
    tree = material.node_tree
    texture = tree.nodes.new("ShaderNodeTexImage")
    texture.image = bpy.data.images.load(str(OUT / "covers" / f"{name}.jpg"))
    tree.links.new(texture.outputs["Color"],
                   tree.nodes["Principled BSDF"].inputs["Base Color"])
    return material


def build_books():
    root = make_empty("books")
    pages = principled("pages", srgb("#e8dfc8"), Roughness=0.9)
    shelf = [
        ("hobbit", (-2.6, 2.4, 0.85), 0.0),
        ("dune", (-3.0, 3.0, -0.85), 1.7),
        ("circe", (-1.5, 1.5, -0.95), 3.1),
        ("nineteen", (-1.4, 3.0, 1.0), 4.4),
        ("prince", (1.0, 3.0, 0.95), 5.2),
        ("library", (1.0, 2.4, -0.95), 2.3),
    ]
    books = []
    for name, home, phase in shelf:
        book = make_empty(name, root)
        box("body", pages, (0.6, 0.1, 0.9), book, bevel=0.01)
        picture("cover", cover_material(name), 0.6, 0.9, book, -0.0508)
        books.append((book, home, phase))

    def motion(time):
        for book, home, phase in books:
            drift = time * 0.8 + phase
            place(book,
                  (home[0] + 0.12 * math.sin(drift), home[1], home[2] + 0.15 * math.cos(drift * 0.8)),
                  (0.12 * math.sin(drift * 0.7), 0.1 * math.sin(drift * 0.5),
                   0.55 * math.sin(drift * 0.45)), 0.85)

    return Prop((13.0, 21.8), root, [book for book, _, _ in books], motion)


def build_letters():
    root = make_empty("letters")
    paper = principled("paper", CREAM, Roughness=0.8)
    wax = principled("wax", srgb("#7a0f10"), Roughness=0.3)
    ink = principled("ink", (0.02, 0.02, 0.02, 1), Roughness=0.8)
    letters = []
    for index in range(9):
        letter = make_empty(f"letter{index}", root)
        box("sheet", paper, (0.64, 0.02, 0.44), letter, bevel=0.004)
        primitive("cylinder", "seal", wax, letter, (0, -0.016, -0.04),
                  (0.07, 0.07, 0.01), (90, 0, 0), vertices=24)
        for height in (0.07, 0.11):
            box("line", ink, (0.32, 0.004, 0.012), letter, (0, -0.012, height))
        letters.append(letter)

    def motion(time):
        for index, letter in enumerate(letters):
            progress = (time - 22.3 - 0.42 * index) / 3.4
            angle = math.tau * (0.5 * progress + index / 9)
            radius = 2.2 + 0.4 * math.sin(index * 1.7)
            location = (
                -0.9 + radius * math.cos(angle),
                3.0 + radius * math.sin(angle),
                -3.2 + 6.4 * progress,
            )
            spin = (
                0.5 * math.sin(time * 0.9 + index),
                0.6 * math.sin(time * 0.7 + index),
                0.7 * math.sin(time * 0.5 + index * 2),
            )
            shown = 0 < progress < 1
            place(letter, location, spin, 1.0 if shown else 0.0)

    return Prop((22.2, 29.8), root, letters, motion)


def build_alice():
    root = make_empty("alice")
    gold = principled("watch gold", GOLD, Metallic=1.0, Roughness=0.2)
    face = principled("watch face", srgb("#f4ecd8"), Roughness=0.4)
    ink = principled("watch ink", (0.02, 0.02, 0.02, 1), Roughness=0.5)
    watch = make_empty("watch", root)
    primitive("torus", "case", gold, watch, rotation=(90, 0, 0),
              major_radius=0.5, minor_radius=0.05, major_segments=64)
    primitive("cylinder", "face", face, watch, (0, 0.02, 0), (0.48, 0.48, 0.02),
              (90, 0, 0), vertices=64)
    primitive("cylinder", "crown", gold, watch, (0, 0, 0.58), (0.07, 0.07, 0.08))
    primitive("torus", "bow", gold, watch, (0, 0, 0.72), rotation=(90, 0, 0),
              major_radius=0.1, minor_radius=0.025)
    for tick in range(12):
        angle = math.tau * tick / 12
        box("tick", ink, (0.02, 0.01, 0.07), watch,
            (0.4 * math.sin(angle), -0.012, 0.4 * math.cos(angle)))
    hands = []
    for length in (0.3, 0.2):
        pivot = make_empty("hand", watch)
        box("hand blade", ink, (0.025, 0.012, length), pivot, (0, -0.03, length / 2))
        hands.append(pivot)
    bottle = make_empty("bottle", root)
    tint = principled("bottle glass", (0.7, 0.95, 0.95, 1), Alpha=0.22,
                     Roughness=0.0, Specular_IOR_Level=1.0)
    potion = emissive("potion", srgb("#c04fd0"), 1.4)
    primitive("cylinder", "body", tint, bottle, scale=(0.22, 0.22, 0.55), vertices=48)
    primitive("cylinder", "potion", potion, bottle, (0, 0, -0.06), (0.18, 0.18, 0.4),
              vertices=48)
    primitive("cylinder", "neck", tint, bottle, (0, 0, 0.42), (0.09, 0.09, 0.22))
    primitive("cylinder", "cork", principled("cork", srgb("#a0703e"), Roughness=0.9),
              bottle, (0, 0, 0.58), (0.07, 0.07, 0.1))
    label = principled("label", srgb("#f4ecd8"), Roughness=0.8)
    box("label", label, (0.3, 0.01, 0.2), bottle, (0, -0.225, -0.05))
    text = bpy.data.curves.new("drink", "FONT")
    text.body, text.size = "DRINK\nME", 0.07
    text.font = bpy.data.fonts.load(FONTS["bold"])
    text.align_x, text.align_y = "CENTER", "CENTER"
    text.materials.append(ink)
    note = link_object(bpy.data.objects.new("drink", text), bottle)
    note.location, note.rotation_euler = (0, -0.235, -0.05), (math.pi / 2, 0, 0)
    suits = [("♥", (0.75, 0.04, 0.06, 1)), ("♠", (0.03, 0.03, 0.03, 1)),
             ("♦", (0.75, 0.04, 0.06, 1)), ("♣", (0.03, 0.03, 0.03, 1)),
             ("♥", (0.75, 0.04, 0.06, 1))]
    cards = []
    for index, (symbol, color) in enumerate(suits):
        card = make_empty(f"card{index}", root)
        box("card", principled("card", (0.95, 0.95, 0.93, 1), Roughness=0.6),
            (0.36, 0.008, 0.52), card, bevel=0.006)
        mark = bpy.data.curves.new("suit", "FONT")
        mark.body, mark.size = symbol, 0.3
        mark.font = bpy.data.fonts.load(FONTS["symbols"])
        mark.align_x, mark.align_y = "CENTER", "CENTER"
        mark.materials.append(principled("suit", color, Roughness=0.4))
        suit = link_object(bpy.data.objects.new("suit", mark), card)
        suit.location, suit.rotation_euler = (0, -0.006, 0), (math.pi / 2, 0, 0)
        cards.append(card)

    def motion(time):
        local = time - 31.4
        place(watch, (-2.0, 2.0, 1.0 + 0.06 * math.sin(time * 1.3)),
              (0, 0, math.radians(18 * math.sin(time * 0.8))), 1.0)
        hands[0].rotation_euler = (0, -local * 3.2, 0)
        hands[1].rotation_euler = (0, -local * 0.27, 0)
        place(bottle, (-2.3, 1.6, -1.0 + 0.05 * math.sin(time * 1.1)),
              (0, 0.3 * math.sin(time * 0.7), math.radians(-14)), 1.0)
        for index, card in enumerate(cards):
            fall = (local * 0.55 + index * 0.37) % 2.0
            x = -1.2 + 0.7 * index + 0.2 * math.sin(time + index)
            place(card, (x, 2.0 + index * 0.3, 3.3 - 3.0 * fall),
                  (time * 0.8 + index, time * 0.6, time * 0.9 + index * 3), 1.0)

    moving = [watch, bottle, *hands, *cards]
    return Prop((31.4, 39.8), root, moving, motion)


def asteroid_surface():
    material = principled("asteroid", srgb("#c9a66b"), Roughness=0.9)
    tree = material.node_tree
    noise = tree.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 5.0
    noise.inputs["Detail"].default_value = 9.0
    ramp_node = tree.nodes.new("ShaderNodeValToRGB")
    ramp_node.color_ramp.elements[0].color = srgb("#8a6a43")
    ramp_node.color_ramp.elements[1].color = srgb("#dcbb80")
    bump = tree.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.6
    tree.links.new(noise.outputs["Fac"], ramp_node.inputs["Fac"])
    tree.links.new(noise.outputs["Fac"], bump.inputs["Height"])
    shader = tree.nodes["Principled BSDF"]
    tree.links.new(ramp_node.outputs["Color"], shader.inputs["Base Color"])
    tree.links.new(bump.outputs["Normal"], shader.inputs["Normal"])
    return material


def surface_point(planet, direction, radius, lift=0.0):
    unit = Vector(direction).normalized()
    obj = make_empty("anchor", planet)
    obj.location = unit * (radius + lift)
    obj.rotation_euler = unit.to_track_quat("Z", "Y").to_euler()
    return obj


def build_volcanoes(planet, radius):
    rock = principled("volcano", srgb("#4a3a30"), Roughness=0.95)
    lava = emissive("lava", srgb("#ff6a1c"), 5.0)
    for direction, size, active in (((1, -0.5, 0.35), 0.1, True),
                                    ((-0.9, -0.6, -0.1), 0.075, True),
                                    ((0.2, -0.9, -0.55), 0.06, False)):
        anchor = surface_point(planet, direction, radius, size * 0.4)
        primitive("cone", "volcano", rock, anchor, scale=(size, size, size * 0.9),
                  radius1=1.0, radius2=0.35, depth=1, vertices=24)
        if active:
            primitive("cylinder", "crater", lava, anchor, (0, 0, size * 0.43),
                      (size * 0.34, size * 0.34, size * 0.04), vertices=16)


def build_rose(planet, radius):
    rose = make_empty("rose", planet)
    rose.location = (0, 0, radius)
    green = principled("stem", srgb("#3c8a45"), Roughness=0.6)
    petal = principled("petal", srgb("#d8203a"), Roughness=0.35, Coat_Weight=0.5,
                       Emission_Color=srgb("#ff2a4a"), Emission_Strength=0.7)
    primitive("cylinder", "stem", green, rose, (0, 0, 0.13), (0.01, 0.01, 0.26),
              vertices=12)
    for side, height in ((-1, 0.09), (1, 0.15)):
        primitive("sphere", "leaf", green, rose, (side * 0.045, 0, height),
                  (0.05, 0.012, 0.022), (0, side * 25, 0), segments=16, ring_count=8)
    for ring, count, lift, spread, size in ((0, 6, 0.28, 0.045, 0.05),
                                            (1, 5, 0.3, 0.022, 0.04)):
        for index in range(count):
            angle = math.tau * (index + 0.5 * ring) / count
            primitive("sphere", "petal", petal, rose,
                      (spread * math.cos(angle), spread * math.sin(angle), lift),
                      (size * 0.7, size * 0.35, size), (0, 0, math.degrees(angle) + 90),
                      segments=16, ring_count=10)
    primitive("sphere", "bud", petal, rose, (0, 0, 0.31), (0.03, 0.03, 0.035),
              segments=16, ring_count=10)
    light_data = bpy.data.lights.new("rose glow", "POINT")
    light_data.color, light_data.energy = (1.0, 0.35, 0.4), 6
    link_object(bpy.data.objects.new("rose glow", light_data), rose).location = (0, -0.1, 0.3)


def build_bell_jar(planet, radius):
    jar = make_empty("jar", planet)
    jar.location = (0, 0, radius)
    glass_material = principled("dome", (1, 1, 1, 1), Alpha=0.12, Roughness=0.0,
                                Specular_IOR_Level=1.0)
    brass = principled("jar brass", GOLD, Metallic=1.0, Roughness=0.3)
    primitive("sphere", "dome", glass_material, jar, (0, 0, 0.28), (0.26, 0.26, 0.3),
              segments=48, ring_count=24)
    primitive("torus", "base", brass, jar, (0, 0, 0.005), major_radius=0.265,
              minor_radius=0.02, major_segments=48)
    primitive("sphere", "knob", brass, jar, (0, 0, 0.59), (0.028, 0.028, 0.028))


def build_asteroid(root):
    planet = make_empty("planet", root)
    radius = 0.62
    primitive("sphere", "rock", asteroid_surface(), planet, scale=(radius,) * 3,
              segments=96, ring_count=48)
    build_volcanoes(planet, radius)
    build_rose(planet, radius)
    build_bell_jar(planet, radius)
    return planet


def build_night():
    root = make_empty("night")
    star = emissive("star", (1, 1, 1, 1), 6.0)
    generator = random.Random(5)
    for index in range(110):
        size = generator.uniform(0.004, 0.014)
        location = (generator.uniform(-6, 5), generator.uniform(2, 8),
                    generator.uniform(-3.4, 3.6))
        primitive("sphere", "star", star, root, location, (size,) * 3,
                  segments=8, ring_count=6)
    planet = build_asteroid(root)
    comet = make_empty("comet", root)
    primitive("cone", "comet head", emissive("comet", (1, 1, 1, 1), 12.0), comet,
              (0, 0, 0), (0.025, 0.025, 1.2), (0, 90, 0), radius1=1, depth=1)

    def motion(time):
        place(planet, (-3.0, 2.4, 0.85 + 0.06 * math.sin(time * 1.2)),
              (0.4, 0, time * 0.2), 0.7)
        progress = (time - 42.8) / 0.6
        shown = 0 < progress < 1
        place(comet, (mix(4.5, -4.5, progress), 5.0, mix(3.2, -1.2, progress)),
              (0, 0, 0), 1.0 if shown else 0.0)
        comet.rotation_euler = (0, math.radians(-25), 0)
        star.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value = (
            5.0 + math.sin(time * 2.1)
        )

    return Prop((40.2, 46.8), root, [planet, comet], motion, grow=False)


def build_embers():
    root = make_empty("embers")
    glow = emissive("ember", srgb("#ff5a14"), 4.0)
    generator = random.Random(11)
    embers = []
    for index in range(55):
        size = generator.uniform(0.015, 0.05)
        ember = primitive("sphere", "ember", glow, root, scale=(size,) * 3,
                          segments=8, ring_count=6)
        embers.append((ember, generator.uniform(-3.2, 2.2), generator.uniform(0, 4),
                       generator.uniform(0.4, 0.9), generator.uniform(0, 9), size))
    light_data = bpy.data.lights.new("ember glow", "POINT")
    light_data.color = (1.0, 0.45, 0.15)
    light = link_object(bpy.data.objects.new("ember glow", light_data), root)

    def motion(time):
        for ember, x, y, speed, phase, size in embers:
            rise = (time * speed + phase) % 9.0
            place(ember, (x + 0.3 * math.sin(time + phase), y, -3.5 + rise),
                  scale=size * (1.0 + 0.4 * math.sin(time * 5 + phase)))
        light.location = (0.5, -1.0, -1.4)

    def effect(time):
        light_data.energy = 260 * pulse(time, 46.6, 53.0, 1.0)

    return Prop((46.6, 53.0), root, [e[0] for e in embers], motion, effect, False)


def build_logo():
    root = make_empty("logo")
    tile_material = principled("tile", srgb("#F6EFE6"), Roughness=0.45, Coat_Weight=0.4)
    mesh = bpy.data.meshes.new("tile")
    fill_slab(mesh, 1.5, 1.5, 0.12, 0.34, 0.03)
    make_mesh_object("tile", mesh, [tile_material] * 3, root)
    unit = 1.5 / 108

    def svg(x, y):
        return ((54 + (x - 54) * 1.3 - 54) * unit, -(54 + (y - 54) * 1.3 - 54) * unit)

    books = [
        ("#E4572E", 34, 34, 13, 38, 0),
        ("#2B2420", 49, 29, 12, 43, 0),
        ("#D9A441", 63, 38, 12, 34, 12),
        ("#2B2420", 29, 74, 50, 5, 0),
    ]
    for code, x, y, width, height, tilt in books:
        center = svg(x + width / 2, y + height / 2)
        if tilt:
            offset_x, offset_y = x + width / 2 - 66, y + height / 2 - 72
            angle = math.radians(tilt)
            rotated = (offset_x * math.cos(angle) - offset_y * math.sin(angle),
                       offset_x * math.sin(angle) + offset_y * math.cos(angle))
            center = svg(66 + rotated[0], 72 + rotated[1])
        material = principled("spine", srgb(code), Roughness=0.35, Coat_Weight=0.5)
        size = (width * 1.3 * unit, 0.09, height * 1.3 * unit)
        piece = box("spine", material, size, root, (center[0], -0.1, center[1]), 0.012)
        piece.rotation_euler = (0, math.radians(tilt), 0)

    def motion(time):
        grow = ramp(time, 53.0, 54.4)
        overshoot = grow + 0.08 * math.sin(grow * math.pi)
        sway = math.radians(8 * math.sin((time - 53) * 0.9))
        place(root, (0, 0, 0.5), (0, 0, sway), max(overshoot, 0.001))

    return Prop((53.0, 58.5), root, [root], motion, grow=False)


def build_props():
    return [
        build_snitch(), build_books(), build_letters(), build_alice(),
        build_night(), build_embers(), build_logo(),
    ]


def main():
    arguments = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    preview = "--preview" in arguments
    bpy.ops.wm.read_factory_settings(use_empty=True)
    show = Show(preview and "--final" not in arguments)
    if preview:
        times = [float(value) for value in arguments[arguments.index("--preview") + 1:] if value[0].isdigit()]
        for seconds in times:
            frame = round(seconds * FPS) + 1
            show.bake([frame - 1, frame, frame + 1])
            show.render(frame, OUT / "preview" / f"{seconds:05.1f}.png")
        return
    first, last = (int(value) for value in arguments[:2]) if arguments else (1, FPS * DURATION)
    FRAMES.mkdir(parents=True, exist_ok=True)
    show.bake(range(max(first - 1, 1), last + 2))
    for frame in range(first, last + 1):
        path = FRAMES / f"{frame:05d}.{EXTENSION}"
        if not path.exists():
            show.render(frame, path)


main()
