"""Original 32px industrial sprites and colour-preserving Create assembly recipes.

Requires Pillow. No upstream textures are copied. Run from any working directory.
"""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/recursivefactory"
RECIPES = ROOT / "src/main/resources/data/recursivefactory/recipe"
COLORS = "white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split()
STEEL = {"dark": "#292d35", "shade": "#4c545c", "mid": "#737e83", "light": "#bdc8c4"}
BRASS = {"dark": "#67492e", "shade": "#a5763d", "mid": "#cfaa60", "light": "#f3d68c"}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def recipes():
    for color, name in enumerate(COLORS):
        component = {"recursivefactory:color": color}
        write_json(RECIPES / f"factory_frame_{name}.json", {
            "type": "minecraft:crafting_shaped", "category": "building", "group": "factory_frame",
            "pattern": ["C C", "O O", "COC"],
            "key": {"C": {"item": f"minecraft:{name}_concrete"}, "O": {"item": "minecraft:obsidian"}},
            "result": {"id": "recursivefactory:factory_frame", "count": 1, "components": component}})
        half = {"id": "recursivefactory:incomplete_factory", "components": component}
        ingredient = {"item": "recursivefactory:incomplete_factory"}
        sequence = [{"type": "create:deploying", "ingredients": [ingredient, {"item": item}], "results": [half]}
                    for item in ("create:precision_mechanism", "minecraft:ender_eye")]
        sequence.append({"type": "create:pressing", "ingredients": [ingredient], "results": [half]})
        write_json(RECIPES / f"recursive_factory_{name}.json", {
            "type": "create:sequenced_assembly",
            "ingredient": {"type": "neoforge:components", "items": "recursivefactory:factory_frame", "components": component},
            "loops": 1, "sequence": sequence, "transitional_item": half,
            "results": [{"id": "recursivefactory:recursive_factory", "count": 1, "components": component}]})


def polygon(draw, points, fill, outline=None):
    draw.polygon(points, fill=fill)
    if outline:
        draw.line(points + [points[0]], fill=outline, width=1)


def gear(draw, cx, cy):
    for dx, dy in ((-4, -1), (3, -1), (-1, -4), (-1, 3)):
        draw.rectangle((cx + dx, cy + dy, cx + dx + 2, cy + dy + 2), fill=BRASS["shade"])
    draw.ellipse((cx - 3, cy - 3, cx + 3, cy + 3), fill=BRASS["mid"], outline=BRASS["dark"])
    draw.line((cx - 2, cy - 2, cx + 1, cy - 2), fill=BRASS["light"])
    draw.rectangle((cx - 1, cy - 1, cx + 1, cy + 1), fill=STEEL["dark"])
    draw.point((cx, cy), fill=STEEL["light"])


def icon(stage):
    im = Image.new("RGBA", (32, 32))
    d = ImageDraw.Draw(im)
    # Rear uprights and internal floor are drawn before the machinery and front frame.
    polygon(d, [(15, 3), (18, 4), (18, 19), (15, 20)], STEEL["shade"], STEEL["dark"])
    polygon(d, [(4, 21), (16, 15), (28, 21), (16, 28)], STEEL["dark"], "#181e25")
    for x, y in ((12, 21), (16, 23), (20, 21)):
        d.line((x - 1, y, x + 1, y), fill=STEEL["shade"])
    if stage:
        # A brass mounting ring holds the spatial core. Unfinished work exposes its gears.
        polygon(d, [(16, 8), (24, 12), (24, 20), (16, 24), (8, 20), (8, 12)], BRASS["dark"], "#27282b")
        polygon(d, [(16, 9), (22, 12), (16, 16), (10, 12)], BRASS["light"])
        polygon(d, [(9, 13), (15, 17), (15, 23), (9, 19)], BRASS["shade"])
        polygon(d, [(17, 17), (23, 13), (23, 19), (17, 23)], BRASS["mid"])
        if stage == 2:
            polygon(d, [(16, 11), (21, 14), (16, 17), (11, 14)], "#89d5b4", "#355d65")
            polygon(d, [(11, 15), (15, 18), (15, 22), (11, 19)], "#367c80")
            polygon(d, [(17, 18), (21, 15), (21, 19), (17, 22)], "#23545e")
            d.line((14, 14, 16, 13, 18, 14), fill="#d2eed0")
            d.line((15, 18, 15, 20), fill="#9de1bd")
        else:
            polygon(d, [(16, 12), (21, 15), (16, 18), (11, 15)], "#202d37")
            gear(d, 17, 17)
            d.line((10, 18, 10, 21, 14, 23), fill="#b67848")
    # Steel struts, bevels, brass corner brackets and one-pixel rivets.
    polygon(d, [(2, 9), (5, 10), (5, 23), (16, 29), (16, 31), (2, 24)], STEEL["shade"], STEEL["dark"])
    polygon(d, [(27, 10), (30, 9), (30, 24), (16, 31), (16, 28), (27, 22)], STEEL["mid"], STEEL["dark"])
    polygon(d, [(16, 1), (30, 8), (30, 11), (16, 18), (2, 11), (2, 8)], STEEL["dark"])
    polygon(d, [(16, 2), (28, 8), (16, 14), (4, 8)], STEEL["light"])
    polygon(d, [(16, 5), (23, 8), (16, 12), (9, 8)], "#26313a")
    d.line((3, 10, 16, 17, 29, 10), fill=STEEL["shade"])
    d.line((3, 12, 3, 22), fill=STEEL["light"])
    d.line((28, 12, 28, 22), fill=STEEL["light"])
    for x, y in ((3, 8), (15, 2), (27, 8), (3, 22), (15, 27), (27, 22)):
        d.rectangle((x, y, x + 2, y + 2), fill=BRASS["shade"])
        d.point((x, y), fill=BRASS["light"])
        d.point((x + 1, y + 1), fill=STEEL["dark"])
    if stage == 1:
        # Unsealed front braces make the transitional item recognisable at native resolution.
        d.line((14, 18, 14, 25), fill=BRASS["mid"], width=2)
        d.line((18, 18, 18, 25), fill=STEEL["light"])
    elif stage == 2:
        d.line((16, 18, 16, 26), fill=BRASS["light"])
        d.point((16, 24), fill="#68bcac")
    return im


def textures():
    target = ASSETS / "textures/item"
    target.mkdir(parents=True, exist_ok=True)
    for stage, name in enumerate(("factory_frame", "incomplete_factory", "recursive_factory")):
        icon(stage).save(target / f"{name}.png")
        write_json(ASSETS / f"models/item/{name}.json", {"parent": "minecraft:item/generated", "textures": {
            "layer0": f"recursivefactory:item/{name}", "layer1": "recursivefactory:item/factory_frame_paint"}})
    mask = Image.new("RGBA", (32, 32))
    d = ImageDraw.Draw(mask)
    polygon(d, [(5, 9), (12, 13), (12, 15), (5, 11)], "#b5b5b5")
    polygon(d, [(20, 13), (27, 9), (27, 11), (20, 15)], "#ededed")
    d.line((5, 9, 12, 13), fill="white")
    d.line((20, 13, 27, 9), fill="white")
    mask.save(target / "factory_frame_paint.png")
    metal = Image.new("RGB", (32, 32), STEEL["shade"])
    d = ImageDraw.Draw(metal)
    for y in range(32):
        for x in range(32):
            shade = (x * 17 + y * 7 + x * y) % 5
            metal.putpixel((x, y), (76 + shade * 3, 84 + shade * 3, 88 + shade * 3))
    for start in (0, 28):
        d.rectangle((start, 0, start + 3, 31), fill=BRASS["shade"])
        d.line((start, 0, start, 31), fill=BRASS["light"])
        d.rectangle((0, start, 31, start + 3), fill=BRASS["shade"])
        d.line((0, start, 31, start), fill=BRASS["light"])
    for x in (1, 9, 21, 29):
        for y in (1, 9, 21, 29):
            d.rectangle((x, y, x + 1, y + 1), fill=STEEL["dark"])
            d.point((x, y), fill=STEEL["light"])
    metal.save(ASSETS / "textures/block/factory_frame_metal.png")
    paint = Image.new("RGB", (16, 16))
    for y in range(16):
        for x in range(16):
            value = 175 + ((x * 7 + y * 3) % 5) * 8
            if x in (0, 15) or y in (0, 15): value = 115
            elif x in (1, 14) or y in (1, 14): value = 242
            paint.putpixel((x, y), (value, value, value))
    paint.save(ASSETS / "textures/block/factory_frame_paint.png")


def main():
    recipes()
    textures()
    print("Generated 16 frame recipes, 16 sequenced assemblies, original sprites and frame textures")


if __name__ == "__main__":
    main()
