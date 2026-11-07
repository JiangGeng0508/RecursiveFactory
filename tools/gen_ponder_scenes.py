"""Generate the three original Recursive Factory Ponder structures using standard-library NBT."""
import gzip
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]


def string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def payload(kind, value):
    if kind == 3:
        return struct.pack(">i", value)
    if kind == 8:
        return string(value)
    if kind == 9:
        child, items = value
        return bytes([child]) + struct.pack(">i", len(items)) + b"".join(payload(child, item) for item in items)
    if kind == 10:
        return b"".join(bytes([tag]) + string(key) + payload(tag, item) for key, (tag, item) in value.items()) + b"\0"
    raise ValueError(kind)


def scene(name, entries):
    palette, blocks = [], []
    for (x, y, z), block, properties in entries:
        state = {"Name": (8, block)}
        if properties:
            state["Properties"] = (10, {key: (8, value) for key, value in properties.items()})
        if state not in palette:
            palette.append(state)
        blocks.append({"pos": (9, (3, [x, y, z])), "state": (3, palette.index(state))})
    root = {"DataVersion": (3, 3955), "size": (9, (3, [9, 4, 9])),
            "palette": (9, (10, palette)), "blocks": (9, (10, blocks)), "entities": (9, (10, []))}
    target = ROOT / "src/main/resources/assets/recursivefactory/ponder" / (name + ".nbt")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(gzip.compress(b"\x0a\0\0" + payload(10, root), mtime=0))
    print(target.relative_to(ROOT))


def generate():
    base = [((x, 0, z), "minecraft:polished_andesite", {}) for x in range(9) for z in range(9)]
    entrance = "recursivefactory:recursive_factory"
    barrier = "recursivefactory:factory_barrier"
    scene("connections", base + [((2, 1, 4), entrance, {}), ((2, 1, 2), "minecraft:chest", {}),
        ((2, 1, 3), "minecraft:hopper", {"facing": "south"}), ((6, 1, 4), "minecraft:chest", {})]
        + [((x, 1, 3), barrier, {}) for x in range(5, 8)])
    scene("expansion", base + [((2, 1, 2), entrance, {}), ((3, 1, 2), entrance, {}),
        ((2, 2, 2), entrance, {}), ((6, 1, 6), entrance, {"color": "11"})]
        + [((x, 1, 5), barrier, {}) for x in range(5, 8)])
    scene("printing", base + [((2, 1, 4), "create:schematicannon", {}), ((2, 1, 3), "minecraft:chest", {}),
        ((6, 1, 4), entrance, {}), ((5, 1, 6), "create:andesite_casing", {}),
        ((6, 1, 6), "create:shaft", {"axis": "x"}), ((7, 1, 6), "create:cogwheel", {"axis": "x"})])


if __name__ == "__main__":
    generate()
