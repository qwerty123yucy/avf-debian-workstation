#!/usr/bin/env python3
"""Compile BinderKeepAlive.java into a deterministic read-only DEX JAR and
generate the self-contained scripts/binder-keepalive CLI wrapper.
"""

import base64
import hashlib
import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
SRC_JAVA = Path(__file__).resolve().parent / "BinderKeepAlive.java"
OUT_CLI = ROOT / "scripts" / "binder-keepalive"


def discover_build_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    sdk_dir = Path(sdk) if sdk else None
    opt_dir = Path("/opt/termux-build-tools")

    android_jar = None
    if "ANDROID_JAR" in os.environ and Path(os.environ["ANDROID_JAR"]).is_file():
        android_jar = Path(os.environ["ANDROID_JAR"])
    elif sdk_dir and (sdk_dir / "platforms").is_dir():
        plats = sorted([p for p in (sdk_dir / "platforms").iterdir() if p.is_dir()])
        if plats and (plats[-1] / "android.jar").is_file():
            android_jar = plats[-1] / "android.jar"
    elif (opt_dir / "android.jar").is_file():
        android_jar = opt_dir / "android.jar"
    if not android_jar:
        raise FileNotFoundError("android.jar not found")

    if (opt_dir / "r8.jar").is_file():
        d8_cmd = ["java", "-cp", str(opt_dir / "r8.jar"), "com.android.tools.r8.D8"]
    elif shutil.which("d8"):
        d8_cmd = [shutil.which("d8")]
    else:
        raise FileNotFoundError("d8 / r8.jar not found")

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
        compile_fn = lambda cp, out_dir, src: [
            javac_bin, "-source", "8", "-target", "8", "-encoding", "UTF-8",
            "-Xlint:none", "-classpath", cp, "-d", str(out_dir), str(src),
        ]
    elif ecj_jar.is_file():
        compile_fn = lambda cp, out_dir, src: [
            "java", "-jar", str(ecj_jar), "-1.8", "-encoding", "UTF-8", "-nowarn",
            "-classpath", cp, "-d", str(out_dir), str(src),
        ]
    else:
        raise FileNotFoundError("Neither javac nor ecj found")

    return android_jar, compile_fn, d8_cmd


CLI_TEMPLATE = r'''#!/usr/bin/env bash
# Generic Android Binder keepalive utility for Termux / host Android shells.
# Keeps explicitly specified exported Services responsive via lightweight
# synchronous /dev/binder transactions + periodic startService refreshes while
# the caller's own UID remains unfrozen.
set -euo pipefail

JAR_SHA256="__JAR_SHA256__"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/binder-keepalive"
JAR_PATH="$CACHE_DIR/keepalive-${JAR_SHA256:0:16}.jar"

ensure_host_android() {
    if [[ -n "${PROOT_TMP_DIR:-}" ]]; then
        echo "Error: binder-keepalive must run in native Termux (not inside a proot container) so it shares Termux's Android UID and /dev/binder." >&2
        exit 1
    fi
    if [[ ! -x /system/bin/app_process ]]; then
        echo "Error: /system/bin/app_process not found (must run on Android host / native Termux)." >&2
        exit 1
    fi
}

ensure_dex_jar() {
    mkdir -p "$CACHE_DIR"
    chmod 700 "$CACHE_DIR"
    if [[ -f "$JAR_PATH" && "$(stat -c '%a' "$JAR_PATH" 2>/dev/null || true)" == "400" ]]; then
        return 0
    fi
    local tmp="$CACHE_DIR/.tmp.$$.jar"
    rm -f "$tmp" "$JAR_PATH"
    base64 -d > "$tmp" <<'__DEX_EOF__'
__JAR_BASE64__
__DEX_EOF__
    # Android 14+ Safer Dynamic Code Loading requires read-only DEX files (0400).
    chmod 0400 "$tmp"
    mv -f "$tmp" "$JAR_PATH"
    chmod 0400 "$JAR_PATH"
}

run_app_process() {
    CLASSPATH="$JAR_PATH" \
    env -u LD_LIBRARY_PATH -u LD_PRELOAD \
        /system/bin/app_process -Xnoimage-dex2oat / --nice-name=binder-keepalive \
        org.keepalive.binder.BinderKeepAlive "$@"
}

ipc_send() {
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$@" <<'PY'
import os, socket, sys
cmd = " ".join(sys.argv[1:]) + "\n"
addr = b"\x00binder-keepalive-" + str(os.getuid()).encode("ascii")
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(4.0)
try:
    s.connect(addr)
except OSError:
    sys.exit(3)
s.sendall(cmd.encode("utf-8"))
chunks = []
while True:
    data = s.recv(4096)
    if not data:
        break
    chunks.append(data)
s.close()
text = b"".join(chunks).decode("utf-8", errors="replace")
if not text:
    sys.exit(1)
lines = text.splitlines()
first = lines[0]
if first.startswith("ERR "):
    sys.stderr.write("Error: " + first[4:] + "\n")
    sys.exit(1)
if first.startswith("OK "):
    print(first[3:])
else:
    print(first)
for line in lines[1:]:
    print(line)
PY
    else
        ensure_dex_jar
        run_app_process ctl "$@"
    fi
}

validate_component_syntax() {
    local comp="$1"
    if [[ "$comp" != */* || "$comp" == /* || "$comp" == */ ]]; then
        echo "Error: component must be <package>/<service-class> (e.g. com.example/.MyService)" >&2
        exit 2
    fi
}

cmd_start() {
    local comp="" ping_ms="2000" start_ms="10000" calling_pkg=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --ping-ms)
                ping_ms="${2:-}"; shift 2 ;;
            --start-ms)
                start_ms="${2:-}"; shift 2 ;;
            --calling-pkg)
                calling_pkg="${2:-}"; shift 2 ;;
            -*)
                echo "Error: unknown option '$1'" >&2; usage >&2; exit 2 ;;
            *)
                if [[ -z "$comp" ]]; then
                    comp="$1"; shift
                else
                    echo "Error: unexpected argument '$1'" >&2; usage >&2; exit 2
                fi
                ;;
        esac
    done
    if [[ -z "$comp" ]]; then
        echo "Error: missing <package>/<service> target" >&2
        usage >&2
        exit 2
    fi
    validate_component_syntax "$comp"
    if ! [[ "$ping_ms" =~ ^[0-9]+$ && "$start_ms" =~ ^[0-9]+$ ]]; then
        echo "Error: --ping-ms and --start-ms must be positive integers" >&2
        exit 2
    fi

    ensure_host_android
    ensure_dex_jar

    if ! ipc_send PING >/dev/null 2>&1; then
        local log_file="$CACHE_DIR/daemon.log"
        : > "$log_file"
        chmod 600 "$log_file"
        CLASSPATH="$JAR_PATH" \
        env -u LD_LIBRARY_PATH -u LD_PRELOAD \
            nohup /system/bin/app_process -Xnoimage-dex2oat / --nice-name=binder-keepalive \
            org.keepalive.binder.BinderKeepAlive daemon "$calling_pkg" \
            >"$log_file" 2>&1 </dev/null &
        local daemon_pid=$!

        local ready=0
        for ((i = 1; i <= 35; i++)); do
            if ipc_send PING >/dev/null 2>&1; then
                ready=1
                break
            fi
            if ! kill -0 "$daemon_pid" 2>/dev/null; then
                break
            fi
            sleep 0.1
        done
        if (( ! ready )); then
            echo "Error: binder-keepalive daemon failed to start" >&2
            if [[ -s "$log_file" ]]; then
                cat "$log_file" >&2
            fi
            exit 1
        fi
    fi

    ipc_send START "$comp" "$ping_ms" "$start_ms"
}

cmd_stop() {
    local target="${1:---all}"
    if [[ "$target" != "--all" && "$target" != "all" ]]; then
        validate_component_syntax "$target"
    fi
    ensure_host_android
    local rc=0
    ipc_send STOP "$target" || rc=$?
    if (( rc == 3 )); then
        echo "binder-keepalive is not running."
        return 0
    fi
    return "$rc"
}

cmd_status() {
    local json_mode=0
    [[ "${1:-}" == "--json" ]] && json_mode=1
    ensure_host_android
    local rc=0
    if (( json_mode )); then
        ipc_send STATUS_JSON || rc=$?
        if (( rc == 3 )); then
            echo '{"running":false,"targets":[]}'
            return 0
        fi
    else
        ipc_send STATUS || rc=$?
        if (( rc == 3 )); then
            echo "Daemon   : STOPPED"
            echo "Targets  : 0"
            return 0
        fi
    fi
    return "$rc"
}

usage() {
    cat <<'EOF'
Usage: binder-keepalive <command> [options]

Commands:
  start <pkg>/<service> [--ping-ms N] [--start-ms M]
      Start or update keepalive for an exported Android Service component
      (default: --ping-ms 2000, --start-ms 10000).
  stop [<pkg>/<service> | --all]
      Stop keepalive for one target or all targets (daemon exits when empty).
  status [--json]
      Show active keepalive targets and transaction counters.
EOF
}

case "${1:-}" in
    start)  shift; cmd_start "$@" ;;
    stop)   shift; cmd_stop "$@" ;;
    status) shift; cmd_status "$@" ;;
    -h|--help|help|"") usage ;;
    *) echo "Unknown command: $1" >&2; usage >&2; exit 2 ;;
esac
'''


def main():
    android_jar, compile_fn, d8_cmd = discover_build_tools()
    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        cls_dir = tmp_dir / "classes"
        dex_dir = tmp_dir / "dex"
        cls_dir.mkdir()
        dex_dir.mkdir()

        subprocess.run(compile_fn(str(android_jar), cls_dir, SRC_JAVA), check=True)
        class_files = sorted(cls_dir.rglob("*.class"))
        subprocess.run(
            [*d8_cmd, "--release", "--min-api", "30", "--lib", str(android_jar), "--output", str(dex_dir), *[str(c) for c in class_files]],
            check=True,
        )

        jar_bytes_io = tmp_dir / "keepalive.jar"
        with zipfile.ZipFile(jar_bytes_io, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            for dex in sorted(dex_dir.glob("classes*.dex")):
                zi = zipfile.ZipInfo(dex.name)
                zi.date_time = (2026, 1, 1, 0, 0, 0)
                zi.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(zi, dex.read_bytes())

        raw_jar = jar_bytes_io.read_bytes()
        jar_sha = hashlib.sha256(raw_jar).hexdigest()
        b64_lines = base64.encodebytes(raw_jar).decode("ascii").strip()

        script_text = (
            CLI_TEMPLATE
            .replace("__JAR_SHA256__", jar_sha)
            .replace("__JAR_BASE64__", b64_lines)
        )
        OUT_CLI.write_text(script_text, encoding="utf-8")
        OUT_CLI.chmod(0o755)
        print(f"Built {OUT_CLI} (embedded DEX JAR: {len(raw_jar)} bytes, sha256={jar_sha[:16]}...)")


if __name__ == "__main__":
    main()
