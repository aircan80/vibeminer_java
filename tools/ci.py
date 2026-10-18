"""CI planning and package-integrity helpers. Development tooling, not app code."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import zipfile

BUILD_FILES = ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
               "gradle/wrapper/gradle-wrapper.properties")
TARGETS = {"windows-x64", "macos-arm64", "macos-x64", "linux-x64"}
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")


def version_check(version):
    match = VERSION.fullmatch(version)
    if not match or any(int(match[i]) > limit for i, limit in [(1, 255), (2, 255), (3, 65535)]):
        raise ValueError("Use a package-compatible version such as 0.1.0 or 0.1.0-rc.1")
    return ".".join(match[i] for i in (1, 2, 3))


def plan(root, event, ref, sha, run_number, publish="none", version=""):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid source commit")
    if publish not in {"none", "prerelease", "release"}:
        raise ValueError("Invalid publication mode")
    root = Path(root)
    indicators = [*(root / p for p in BUILD_FILES), root / "settings.gradle.kts",
                  root / "settings.gradle", root / "build.gradle.kts", root / "build.gradle"]
    available = all((root / p).is_file() for p in BUILD_FILES)
    available &= any((root / p).is_file() for p in ("settings.gradle.kts", "settings.gradle"))
    available &= any((root / p).is_file() for p in ("build.gradle.kts", "build.gradle"))
    if any(p.exists() for p in indicators) and not available:
        raise ValueError("Incomplete Gradle bootstrap; restore the wrapper and build/settings files")
    if ref.startswith("refs/tags/"):
        if event != "push" or not ref.startswith("refs/tags/v"):
            raise ValueError("Release tags must be pushed v-prefixed versions")
        version = ref.removeprefix("refs/tags/v")
        publish = "prerelease" if "-" in version else "release"
    elif publish != "none" and (event != "workflow_dispatch" or ref != "refs/heads/main"):
        raise ValueError("Manual publication must use the main branch")
    if publish != "none":
        package_version = version_check(version)
        if publish == "release" and "-" in version:
            raise ValueError("A stable release cannot use a prerelease version")
        if not available:
            raise ValueError("The Java application bootstrap does not exist; no application release can be published")
    else:
        if not run_number.isdigit() or not 1 <= int(run_number) <= 65535:
            raise ValueError("CI run number exceeds the current native package version range")
        version, package_version = f"0.0.0-dev.{run_number}", f"0.0.{run_number}"
    return {"app_available": str(available).lower(), "publish": publish,
            "version": version, "package_version": package_version, "tag": f"v{version}"}


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def check_archive_name(name, base=None):
    normalized = name.replace("\\", "/")
    path = PurePosixPath(normalized)
    private = {".agent", ".agents", ".git", ".aws"}
    if path.is_absolute() or re.match(r"^[a-zA-Z]:", normalized) or any(ord(c) < 32 for c in name):
        raise ValueError(f"Unsafe package entry: {name}")
    parts = list(PurePosixPath(base).parts) if base is not None else []
    for part in path.parts:
        if part == "..":
            if base is None or not parts:
                raise ValueError(f"Unsafe package entry: {name}")
            parts.pop()
        elif part != ".":
            parts.append(part)
    if any(part.casefold() in private or part.casefold() == ".env" or
           part.casefold().startswith(".env.") for part in parts):
        raise ValueError(f"Unsafe package entry: {name}")


def check_archive_structure(names, links):
    """Reject extraction through linked directories, independently of entry order.

    Each link is (entry name, target, relative-to-parent). Runtime leaf links
    remain valid, but no entry or link target may traverse a linked ancestor.
    """
    def parts(name):
        return tuple(part.casefold() for part in PurePosixPath(name.replace("\\", "/")).parts)

    linked_paths = {parts(name) for name, _, _ in links}
    for name in names:
        path = parts(name)
        if any(path[:depth] in linked_paths for depth in range(1, len(path))):
            raise ValueError(f"Unsafe linked ancestor in package entry: {name}")
    for name, target, relative_to_parent in links:
        resolved = list(parts(name)[:-1]) if relative_to_parent else []
        # Check before resolving '..': lexical normalization must not hide a
        # linked directory whose real parent depth differs during extraction.
        for component in parts(target):
            if tuple(resolved) in linked_paths:
                raise ValueError(f"Unsafe linked ancestor in package link: {name}")
            if component == "..":
                resolved.pop()  # check_archive_name already proved bounds.
            else:
                resolved.append(component)


def collect(source, output, target, version, sha):
    version_check(version)
    if target not in TARGETS or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid target or commit")
    source, output = Path(source), Path(output)
    extensions = (".tar.gz", ".zip", ".dmg", ".pkg", ".msi", ".exe", ".deb", ".rpm", ".AppImage")
    packages = []
    seen = set()
    for path in sorted(source.iterdir()) if source.exists() else []:
        extension = next((e for e in extensions if path.name.endswith(e)), None)
        if path.is_symlink() or not path.is_file() or not extension or extension in seen or not path.stat().st_size:
            raise ValueError(f"Unexpected or duplicate package input: {path.name}")
        seen.add(extension)
        if extension == ".zip":
            with zipfile.ZipFile(path) as archive:
                links = []
                entries = archive.infolist()
                for info in entries:
                    check_archive_name(info.filename)
                    mode = info.external_attr >> 16
                    if stat.S_ISLNK(mode):
                        if info.file_size > 4096:
                            raise ValueError("Unsafe oversized symbolic link")
                        target_name = archive.read(info).decode("utf-8")
                        check_archive_name(target_name, PurePosixPath(info.filename.replace("\\", "/")).parent)
                        links.append((info.filename, target_name, True))
                    elif stat.S_IFMT(mode) not in {0, stat.S_IFREG, stat.S_IFDIR}:
                        raise ValueError("Unsafe special file in ZIP package")
                check_archive_structure([info.filename for info in entries], links)
        elif extension == ".tar.gz":
            with tarfile.open(path) as archive:
                links = []
                entries = archive.getmembers()
                for member in entries:
                    check_archive_name(member.name)
                    if member.issym() or member.islnk():
                        check_archive_name(member.linkname, PurePosixPath(member.name.replace("\\", "/")).parent if member.issym() else ".")
                        links.append((member.name, member.linkname, member.issym()))
                    elif not member.isfile() and not member.isdir():
                        raise ValueError("Unsafe special file in tar package")
                check_archive_structure([member.name for member in entries], links)
        packages.append((path, extension))
    required = ".tar.gz" if target == "linux-x64" else ".zip"
    if required not in seen:
        raise ValueError(f"Missing required {required} portable runtime package for {target}")
    output.mkdir(parents=True, exist_ok=True)
    files = []
    for path, extension in packages:
        destination = output / f"VibeMiner-{version}-{target}{extension}"
        if destination.exists():
            raise ValueError("Refusing to overwrite a collected package")
        # Copy only declared package files; never recursively copy the repository.
        import shutil
        shutil.copyfile(path, destination)
        files.append({"name": destination.name, "bytes": destination.stat().st_size,
                      "sha256": digest(destination)})
    manifest = output / f"VibeMiner-{version}-{target}.build.json"
    manifest.write_text(json.dumps({"version": version, "target": target, "commit": sha,
                                   "signing": "unsigned", "files": files}, indent=2) + "\n")
    manifest.with_suffix(".sha256").write_text("".join(f"{f['sha256']}  {f['name']}\n" for f in files))
    return manifest


def verify_release(directory, version, sha, targets=None):
    directory = Path(directory)
    targets = TARGETS if targets is None else targets
    found, expected = set(), set()
    for manifest in directory.glob("*.build.json"):
        data = json.loads(manifest.read_text())
        target = data.get("target")
        if data.get("version") != version or data.get("commit") != sha or target not in targets or target in found:
            raise ValueError("Package identity does not match the release")
        required = ".tar.gz" if target == "linux-x64" else ".zip"
        if not data.get("files") or not any(f["name"].endswith(required) for f in data["files"]):
            raise ValueError("Required portable payload missing from manifest")
        found.add(target)
        expected.update({manifest.name, manifest.with_suffix(".sha256").name})
        for record in data["files"]:
            name = record["name"]
            if Path(name).name != name or not name.startswith(f"VibeMiner-{version}-{target}"):
                raise ValueError("Unsafe release filename")
            path = directory / name
            if path.is_symlink() or not path.is_file() or digest(path) != record["sha256"] or path.stat().st_size != record["bytes"]:
                raise ValueError("Package integrity verification failed")
            expected.add(name)
        checksums = "".join(f"{f['sha256']}  {f['name']}\n" for f in data["files"])
        if manifest.with_suffix(".sha256").read_text() != checksums:
            raise ValueError("Checksum manifest integrity verification failed")
    if found != targets:
        raise ValueError("Missing required platform packages")
    if {p.name for p in directory.iterdir()} != expected:
        raise ValueError("Unexpected release artifact files")
    return [str(directory / name) for name in sorted(expected)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("plan", "collect", "verify-release"))
    parser.add_argument("--source", default=".")
    parser.add_argument("--output", default="dist-upload")
    parser.add_argument("--target")
    args = parser.parse_args()
    if args.command == "plan":
        result = plan(args.source, os.environ["GITHUB_EVENT_NAME"], os.environ["GITHUB_REF"],
                      os.environ["GITHUB_SHA"], os.environ["GITHUB_RUN_NUMBER"],
                      os.environ.get("PUBLISH_MODE", "none"), os.environ.get("RELEASE_VERSION", ""))
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write("".join(f"{key}={value}\n" for key, value in result.items()))
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write("Application packaging enabled.\n" if result["app_available"] == "true" else
                          "Java bootstrap not present yet. Repository checks run; no native application packages or releases are produced. See issue #13.\n")
    elif args.command == "collect":
        collect(args.source, args.output, args.target, os.environ["APP_VERSION"], os.environ["GITHUB_SHA"])
    else:
        files = verify_release(args.source, os.environ["APP_VERSION"], os.environ["GITHUB_SHA"])
        Path(args.output).write_text("\n".join(files) + "\n")


if __name__ == "__main__":
    main()
