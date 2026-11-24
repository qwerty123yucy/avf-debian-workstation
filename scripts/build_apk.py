#!/usr/bin/env python3
"""Reproducible APK builder for AVF Linux (com.avf.linux).

Supports both standard Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT + javac)
and offline Termux/proot build tools (/opt/termux-build-tools + ecj).
"""

import os
import shutil
import struct
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app" / "src" / "main"
STUBS = ROOT / "app" / "compile-stubs"
BUILD = ROOT / "build"
STAGE = BUILD / "apk-stage"
SIGNING = ROOT / "signing"
KEYSTORE = SIGNING / "avf-linux.jks"
KEY_ALIAS = os.environ.get("AVF_KS_ALIAS", "avflinux")
VMNET_SO = BUILD / "libvmnet.so"
OUT_APK = BUILD / "avf-linux.apk"


def load_key_pass() -> str:
    if "AVF_KS_PASS" in os.environ:
        return os.environ["AVF_KS_PASS"]
    env_file = SIGNING / "keystore.env"
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("AVF_KS_PASS="):
                return line.split("=", 1)[1].strip()
    return "android"


def latest_subdir(parent: Path) -> Path | None:
    if not parent.is_dir():
        return None
    dirs = sorted([p for p in parent.iterdir() if p.is_dir()], key=lambda p: p.name)
    return dirs[-1] if dirs else None


def discover_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    sdk_dir = Path(sdk) if sdk else None
    bt_dir = latest_subdir(sdk_dir / "build-tools") if sdk_dir else None
    plat_dir = latest_subdir(sdk_dir / "platforms") if sdk_dir else None
    opt_dir = Path("/opt/termux-build-tools")

    def find_bin(name: str) -> str:
        if bt_dir and (bt_dir / name).is_file():
            return str(bt_dir / name)
        found = shutil.which(name)
        if found:
            return found
        raise FileNotFoundError(f"Required tool '{name}' not found (set ANDROID_HOME or install build-tools)")

    android_jar = None
    for cand in (
        Path(os.environ["ANDROID_JAR"]) if "ANDROID_JAR" in os.environ else None,
        (plat_dir / "android.jar") if plat_dir else None,
        opt_dir / "android.jar",
    ):
        if cand and cand.is_file():
            android_jar = cand
            break
    if not android_jar:
        raise FileNotFoundError("android.jar not found (set ANDROID_HOME or ANDROID_JAR)")

    d8_cmd = None
    if bt_dir and (bt_dir / "d8").is_file():
        d8_cmd = [str(bt_dir / "d8")]
    elif (opt_dir / "r8.jar").is_file():
        d8_cmd = ["java", "-cp", str(opt_dir / "r8.jar"), "com.android.tools.r8.D8"]
    elif shutil.which("d8"):
        d8_cmd = [shutil.which("d8")]
    else:
        raise FileNotFoundError("Neither d8 nor r8.jar found")

    apksigner_cmd = None
    if bt_dir and (bt_dir / "apksigner").is_file():
        apksigner_cmd = [str(bt_dir / "apksigner")]
    elif (opt_dir / "apksigner-root" / "usr" / "share" / "java").is_dir():
        apksigner_cmd = [
            "java", "-cp", str(opt_dir / "apksigner-root" / "usr" / "share" / "java" / "*"),
            "com.android.apksigner.ApkSignerTool",
        ]
    elif shutil.which("apksigner"):
        apksigner_cmd = [shutil.which("apksigner")]
    else:
        raise FileNotFoundError("apksigner not found")

    javac_bin = shutil.which("javac")
    ecj_jar = Path("/usr/share/java/eclipse-jdt-core-compiler-batch.jar")
    javac_ok = False
    if javac_bin:
        try:
            javac_ok = subprocess.run(
                [javac_bin, "-version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            ).returncode == 0
        except OSError:
            javac_ok = False
    if javac_ok:
        compile_fn = lambda cp, out_dir, srcs: [
            javac_bin, "-source", "8", "-target", "8", "-encoding", "UTF-8",
            "-Xlint:none", "-classpath", cp, "-d", str(out_dir), *[str(s) for s in srcs],
        ]
    elif ecj_jar.is_file():
        compile_fn = lambda cp, out_dir, srcs: [
            "java", "-jar", str(ecj_jar), "-1.8", "-encoding", "UTF-8", "-nowarn",
            "-classpath", cp, "-d", str(out_dir), *[str(s) for s in srcs],
        ]
    else:
        raise FileNotFoundError("Neither working javac nor ecj found")

    return {
        "aapt2": find_bin("aapt2"),
        "zipalign": find_bin("zipalign"),
        "android_jar": android_jar,
        "d8": d8_cmd,
        "apksigner": apksigner_cmd,
        "compile": compile_fn,
    }


def run(cmd):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], check=True)


def ensure_keystore(key_pass: str):
    SIGNING.mkdir(parents=True, exist_ok=True)
    if KEYSTORE.is_file():
        return
    run([
        "keytool", "-genkeypair", "-v",
        "-keystore", KEYSTORE,
        "-storepass", key_pass,
        "-keypass", key_pass,
        "-alias", KEY_ALIAS,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=AVF Linux, OU=Local, O=AVF Linux, L=Local, ST=NA, C=US",
    ])


def repack_16k_aligned(src_apk: Path, dst_apk: Path):
    """Align .so entries to 16 KB page boundary (stored) and other stored entries to 4 bytes."""
    with zipfile.ZipFile(src_apk, "r") as zin, zipfile.ZipFile(dst_apk, "w") as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            zi = zipfile.ZipInfo(item.filename)
            zi.date_time = item.date_time
            zi.external_attr = item.external_attr
            if item.filename.endswith(".so"):
                zi.compress_type = zipfile.ZIP_STORED
                pos = zout.fp.tell()
                fn_len = len(item.filename.encode("utf-8"))
                pad = (16384 - ((pos + 30 + fn_len) % 16384)) % 16384
                if pad:
                    zi.extra = b"\x00" * pad
                zout.writestr(zi, data, compress_type=zipfile.ZIP_STORED)
            else:
                zi.compress_type = item.compress_type
                if item.compress_type == zipfile.ZIP_STORED:
                    pos = zout.fp.tell()
                    fn_len = len(item.filename.encode("utf-8"))
                    pad = (4 - ((pos + 30 + fn_len) % 4)) % 4
                    if pad:
                        zi.extra = b"\x00" * pad
                zout.writestr(zi, data, compress_type=item.compress_type)


def verify_so_16k(apk: Path):
    with zipfile.ZipFile(apk, "r") as zf:
        for info in zf.infolist():
            if not info.filename.endswith(".so"):
                continue
            with open(apk, "rb") as f:
                f.seek(info.header_offset)
                lh = f.read(30)
                _, _, _, _, _, _, _, _, _, fn_len, ex_len = struct.unpack("<IHHHHHIIIHH", lh)
                data_off = info.header_offset + 30 + fn_len + ex_len
                if data_off % 16384 != 0:
                    raise RuntimeError(f"{info.filename} not 16 KB aligned: offset={data_off}")
                print(f"[ok] {info.filename} data_offset={data_off} (16 KB aligned)")


def main():
    tools = discover_tools()
    key_pass = load_key_pass()
    ensure_keystore(key_pass)

    if STAGE.exists():
        shutil.rmtree(STAGE)
    gen_dir = STAGE / "gen"
    stubs_dir = STAGE / "stubs-classes"
    cls_dir = STAGE / "classes"
    dex_dir = STAGE / "dex"
    for d in (gen_dir, stubs_dir, cls_dir, dex_dir):
        d.mkdir(parents=True, exist_ok=True)

    res_zip = STAGE / "compiled_res.zip"
    base_apk = STAGE / "base-unaligned.apk"
    aligned_apk = STAGE / "aligned.apk"

    # 1. Compile and link resources with aapt2
    run([tools["aapt2"], "compile", "--dir", APP / "res", "-o", res_zip])
    run([
        tools["aapt2"], "link",
        "-I", tools["android_jar"],
        "--manifest", APP / "AndroidManifest.xml",
        "--java", gen_dir,
        "--auto-add-overlay",
        "-o", base_apk,
        res_zip,
    ])

    # 2. Compile compile-time-only AVF API stubs (never packaged into DEX)
    stub_files = sorted(STUBS.rglob("*.java"))
    run(tools["compile"](str(tools["android_jar"]), stubs_dir, stub_files))

    # 3. Compile application Java sources against android.jar + AVF stubs
    java_files = sorted(list(gen_dir.rglob("*.java")) + list((APP / "java").rglob("*.java")))
    run(tools["compile"](f"{tools['android_jar']}:{stubs_dir}", cls_dir, java_files))

    # 4. Convert app .class files to DEX with D8 (--min-api 30)
    class_files = sorted(cls_dir.rglob("*.class"))
    run([
        *tools["d8"],
        "--release", "--min-api", "30",
        "--lib", tools["android_jar"],
        "--lib", stubs_dir,
        "--output", dex_dir,
        *class_files,
    ])

    # 5. Append classes.dex and lib/arm64-v8a/libvmnet.so
    with zipfile.ZipFile(base_apk, "a", compression=zipfile.ZIP_DEFLATED) as zf:
        for dex in sorted(dex_dir.glob("classes*.dex")):
            zf.write(dex, arcname=dex.name)
        if VMNET_SO.is_file():
            zf.write(VMNET_SO, arcname="lib/arm64-v8a/libvmnet.so", compress_type=zipfile.ZIP_STORED)
        else:
            raise FileNotFoundError(
                f"Missing native netstack binary: {VMNET_SO}. Build vmnet/cmd/vmnet-host first."
            )

    # 6. 16 KB align .so + 4-byte align other entries
    repack_16k_aligned(base_apk, aligned_apk)

    # 7. Sign with v1/v2/v3
    run([
        *tools["apksigner"], "sign",
        "--ks", KEYSTORE,
        "--ks-pass", f"pass:{key_pass}",
        "--ks-key-alias", KEY_ALIAS,
        "--key-pass", f"pass:{key_pass}",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--v4-signing-enabled", "false",
        "--out", OUT_APK,
        aligned_apk,
    ])

    # 8. Verify alignment, 16 KB .so alignment, signature, and badging
    run([tools["zipalign"], "-c", "-p", "4", OUT_APK])
    verify_so_16k(OUT_APK)
    run([*tools["apksigner"], "verify", "--verbose", OUT_APK])
    run([tools["aapt2"], "dump", "badging", OUT_APK])
    shutil.rmtree(STAGE, ignore_errors=True)
    print(f"\nBuilt and verified: {OUT_APK} ({OUT_APK.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
