from pathlib import Path
import json
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/eslyric"
INDEX = json.loads((ASSETS / "sources.json").read_text(encoding="utf-8"))
MANIFEST = json.loads((ASSETS / "upstream/archive/version.json").read_text(encoding="utf-8"))
LEGACY_ZIP = ASSETS / "library-searchers.zip"

assert INDEX["upstream"]["repository"] == "https://github.com/ESLyric/scripts"
assert INDEX["upstream"]["commit"] == "32244ac30ffb8f6fd9ff6d03a1dcce389b5c1600"
assert len(MANIFEST["sources"]) == 27, "Pinned upstream manifest changed unexpectedly"
assert len(INDEX["sources"]) == 30, "Expected 27 upstream and 3 bundled Library searchers"

legacy = {}
with zipfile.ZipFile(LEGACY_ZIP) as archive:
    names = set(archive.namelist())
    required = {
        "searcher/kuwo.js",
        "searcher/netease_ex.js",
        "searcher/qqmusic_ex.js",
        "README.txt",
    }
    missing = required - names
    assert not missing, f"Missing Library searchers: {sorted(missing)}"
    assert "lib/parser_ext.so" not in names, "Windows parser extension must not be packaged"
    for name in required:
        if name.endswith(".js"):
            legacy[name] = archive.read(name).decode("utf-8")

expected_upstream = {item["uri"] for item in MANIFEST["sources"]}
actual_upstream = {
    item["uri"].removeprefix("upstream/searcher/")
    for item in INDEX["sources"]
    if item["uri"].startswith("upstream/searcher/")
}
assert expected_upstream == actual_upstream, "Bundled upstream searcher set differs from pinned manifest"

checked = 0
for item in INDEX["sources"]:
    uri = item["uri"]
    if uri.startswith("upstream/"):
        path = ASSETS / uri
        assert path.is_file(), f"Missing bundled source: {uri}"
        source = path.read_text(encoding="utf-8-sig")
    elif uri.startswith("library/"):
        key = uri.removeprefix("library/")
        assert key in legacy, f"Missing bundled Library source: {uri}"
        source = legacy[key]
    else:
        raise AssertionError(f"Unsupported source path: {uri}")

    imports = []
    for line in source.splitlines():
        stripped = line.strip()
        if stripped.startswith("import ") and " from " in stripped:
            module = stripped.rsplit(" from ", 1)[1].strip().rstrip(";").strip("'\"")
            imports.append(module)
    unsupported = [module for module in imports if module not in {
        "crypto-es/lib/index.js",
        "himalaya/src/index.js",
    }]
    assert not unsupported, f"{uri} has unsupported imports: {unsupported}"

    result = subprocess.run(
        ["node", "--input-type=module", "--check"],
        input=source,
        text=True,
        capture_output=True,
    )
    if result.returncode:
        raise SystemExit(f"JavaScript syntax check failed for {uri}:\n{result.stderr}")
    checked += 1

runtime = (ROOT / "app/src/main/assets/js/runtime.js").read_text(encoding="utf-8")
assert "raw.githubusercontent.com/ESLyric/scripts" not in runtime
repository = (ROOT / "app/src/main/java/com/iqonli/ezxlrc/engine/ScriptRepository.kt").read_text(encoding="utf-8")
assert "HttpURLConnection" not in repository
assert "raw.githubusercontent.com" not in repository
print(f"Validated {checked} bundled searchers; {len(expected_upstream)} upstream + {len(legacy)} Library sources.")
