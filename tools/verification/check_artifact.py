"""Validate the distributable, resource references and translated messages (Python 3.11+)."""
import json
from pathlib import Path
import re
import sys
import tomllib
import zipfile

root = Path(__file__).resolve().parents[2]
resources = root / "src/main/resources"
languages = {code: json.loads((resources / f"assets/recursivefactory/lang/{code}.json").read_text(encoding="utf-8"))
             for code in ("en_us", "zh_cn")}
assert languages["en_us"].keys() == languages["zh_cn"].keys(), "translation keys differ"
for key, english in languages["en_us"].items():
    assert re.findall(r"%(?:\d+\$)?[sd]", english) == re.findall(r"%(?:\d+\$)?[sd]", languages["zh_cn"][key]), key
for source in (root / "src/main/java").rglob("*.java"):
    for key in re.findall(r'Component\.translatable\("([^"]+)"\s*[,)]', source.read_text(encoding="utf-8")):
        if ".recursivefactory." in key:
            assert key in languages["en_us"], (source, key)
for path in resources.rglob("*.json"):
    json.loads(path.read_text(encoding="utf-8"))
for path in (resources / "assets/recursivefactory/blockstates").glob("*.json"):
    for model in re.findall(r'"model"\s*:\s*"recursivefactory:([^"]+)"', path.read_text(encoding="utf-8")):
        assert (resources / "assets/recursivefactory/models" / (model + ".json")).is_file(), model
print("PASS resource JSON, model references, translations and format arguments")

version = re.search(r"^mod_version=(.+)$", (root / "gradle.properties").read_text(), re.M).group(1).strip()
artifact = Path(sys.argv[1]) if len(sys.argv) > 1 else root / f"build/libs/recursivefactory-{version}.jar"
with zipfile.ZipFile(artifact) as jar:
    names = jar.namelist()
    assert not any("Probe" in name or "FactoryPrinter" in name or "factory_printer" in name for name in names)
    descriptor = jar.read("META-INF/neoforge.mods.toml").decode("utf-8")
    assert "${" not in descriptor
    metadata = tomllib.loads(descriptor)
    assert metadata["mods"][0]["version"] == version
    for name in names:
        if name.endswith(".json"):
            json.loads(jar.read(name))
    for source in (root / "src/main/java").rglob("*.java"):
        name = str(source.relative_to(root / "src/main/java").with_suffix(".class")).replace("\\", "/")
        assert name in names, name
print(f"PASS {artifact.name}: {len(names)} entries; correct version; no probes or removed printer")
