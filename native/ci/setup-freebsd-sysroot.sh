#!/usr/bin/env bash
# Downloads and unpacks a minimal FreeBSD 14 base sysroot for cross-compiling.
# Performs atomic installation on the destination filesystem, SHA256 integrity verification,
# symlink validation, and writes a manifest.
set -euo pipefail

SYSROOT_DIR="${1:-${PWD}/native/freebsd-sysroot}"
FREEBSD_VER="${2:-14.4-RELEASE}"
ARCH="${3:-amd64}"

if [ "${FREEBSD_VER}" != "14.4-RELEASE" ] || [ "${ARCH}" != "amd64" ]; then
    echo "ERROR: Unsupported FreeBSD version/arch: ${FREEBSD_VER} / ${ARCH}. Supported: 14.4-RELEASE / amd64." >&2
    exit 1
fi

PARENT_DIR="$(cd "$(dirname "${SYSROOT_DIR}")" 2>/dev/null && pwd || { mkdir -p "$(dirname "${SYSROOT_DIR}")" && cd "$(dirname "${SYSROOT_DIR}")" && pwd; })"
TARGET_BASE="$(basename "${SYSROOT_DIR}")"
SYSROOT_DIR="${PARENT_DIR}/${TARGET_BASE}"

MANIFEST_FILE="${SYSROOT_DIR}/.sysroot-manifest"
SCRIPT_REVISION="2026-10-14-r3"

if [ -f "${MANIFEST_FILE}" ] && grep -q "status=complete" "${MANIFEST_FILE}" && grep -q "revision=${SCRIPT_REVISION}" "${MANIFEST_FILE}"; then
    echo "FreeBSD sysroot already present and verified at ${SYSROOT_DIR}."
    exit 0
fi

echo "Setting up FreeBSD ${FREEBSD_VER} (${ARCH}) sysroot at ${SYSROOT_DIR}..."

# Serialize concurrent installations targeting the same parent directory / sysroot
LOCK_DIR="${PARENT_DIR}/.sysroot-install.lock"
while ! mkdir "${LOCK_DIR}" 2>/dev/null; do
    echo "  Another installer is running. Waiting for lock ${LOCK_DIR}..."
    sleep 3
    if [ -f "${MANIFEST_FILE}" ] && grep -q "status=complete" "${MANIFEST_FILE}" && grep -q "revision=${SCRIPT_REVISION}" "${MANIFEST_FILE}"; then
        echo "FreeBSD sysroot setup completed by concurrent installer at ${SYSROOT_DIR}."
        exit 0
    fi
done

# Ensure lock and temporary staging directory are cleaned up on exit
STAGE_DIR="${PARENT_DIR}/.${TARGET_BASE}.staging.$$"
BACKUP_DIR="${PARENT_DIR}/.${TARGET_BASE}.old.$$"
cleanup() {
    rm -rf "${STAGE_DIR}" "${LOCK_DIR}"
    if [ -d "${BACKUP_DIR}" ]; then
        rm -rf "${BACKUP_DIR}"
    fi
}
trap cleanup EXIT INT TERM

mkdir -p "${STAGE_DIR}"

BASE_TXZ="${STAGE_DIR}/base.txz"
BASE_URL="https://download.freebsd.org/releases/${ARCH}/${FREEBSD_VER}/base.txz"
EXPECTED_BASE_SHA="769f60a6eea2938ad6b7943cfbcc17dfaf7d1f7ba59a32b869a00349302df853"

echo "Downloading FreeBSD base.txz..."
curl -fsSL --retry 3 --retry-delay 5 -o "${BASE_TXZ}" "${BASE_URL}"

ACTUAL_BASE_SHA="$(sha256sum "${BASE_TXZ}" 2>/dev/null | awk '{print $1}' || shasum -a 256 "${BASE_TXZ}" | awk '{print $1}')"
if [ "${ACTUAL_BASE_SHA}" != "${EXPECTED_BASE_SHA}" ]; then
    echo "ERROR: base.txz SHA256 mismatch! Expected ${EXPECTED_BASE_SHA}, got ${ACTUAL_BASE_SHA}" >&2
    exit 1
fi

echo "Extracting essential FreeBSD headers and libraries..."
tar -xf "${BASE_TXZ}" -C "${STAGE_DIR}" \
    ./usr/include \
    ./usr/lib \
    ./lib

rm -f "${BASE_TXZ}"

# 2. Extract essential FreeBSD packages needed by PDFium (fontconfig, expat) with digest verification
echo "Installing FreeBSD fontconfig and expat into sysroot with integrity checks..."
python3 - "${STAGE_DIR}" <<'PY_EOF'
import os, sys, urllib.request, subprocess, tarfile, io, hashlib

stage_dir = os.path.abspath(sys.argv[1])
usr_inc = os.path.join(stage_dir, "usr", "include")
usr_lib = os.path.join(stage_dir, "usr", "lib")

packages = [
    ("fontconfig",
     "https://pkg.freebsd.org/FreeBSD:14:amd64/quarterly/All/Hashed/fontconfig-2.17.1,1~2$uydndcrs.pkg",
     "1c71560ea0f2cbc994a826ce7f02c093b6dd37e7a6e7aaf13c1631539576d47e"),
    ("expat",
     "https://pkg.freebsd.org/FreeBSD:14:amd64/quarterly/All/Hashed/expat-2.8.4~2$akytky6u.pkg",
     "52860f701df0565b47ef9ba143db79a7f76cdcb2e8ffc5f63001dc9c1a5c2e62")
]

for name, url, expected_sha in packages:
    try:
        print(f"  Fetching FreeBSD {name} package...")
        req = urllib.request.Request(url, headers={"User-Agent": "curl/7.68.0"})
        raw_data = urllib.request.urlopen(req, timeout=30).read()
        actual_sha = hashlib.sha256(raw_data).hexdigest()
        if actual_sha != expected_sha:
            raise RuntimeError(f"Digest mismatch for {name}: expected {expected_sha}, got {actual_sha}")

        proc = subprocess.Popen(["zstd", "-dc"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        data, err = proc.communicate(input=raw_data)
        if proc.returncode != 0:
            raise RuntimeError(f"zstd decompression failed: {err.decode('utf-8', errors='ignore')}")
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as tar:
            for m in tar.getmembers():
                if m.name.startswith("/usr/local/include/"):
                    rel = m.name[len("/usr/local/include/"):]
                    dest = os.path.join(usr_inc, rel)
                    if m.isdir():
                        os.makedirs(dest, exist_ok=True)
                    elif m.isfile():
                        os.makedirs(os.path.dirname(dest), exist_ok=True)
                        with open(dest, "wb") as f_out:
                            f_out.write(tar.extractfile(m).read())
                elif m.name.startswith("/usr/local/lib/"):
                    rel = m.name[len("/usr/local/lib/"):]
                    if "/" not in rel:
                        dest = os.path.join(usr_lib, rel)
                        if m.isfile():
                            with open(dest, "wb") as f_out:
                                f_out.write(tar.extractfile(m).read())
                        elif m.islnk() or m.issym():
                            if os.path.exists(dest) or os.path.islink(dest):
                                os.unlink(dest)
                            os.symlink(m.linkname, dest)
        print(f"  Installed FreeBSD {name} (verified SHA256).")
    except Exception as e:
        print(f"  ERROR: failed to install FreeBSD {name}: {e}")
        sys.exit(1)
PY_EOF

# 3. Sanitize symlinks: convert absolute symlinks to relative so they resolve inside sysroot
echo "Sanitizing symlinks in sysroot..."
python3 - "${STAGE_DIR}" <<'PY_EOF'
import os, sys

sysroot = os.path.abspath(sys.argv[1])
usr_lib = os.path.join(sysroot, "usr", "lib")

if os.path.exists(usr_lib):
    for root, dirs, files in os.walk(usr_lib):
        for f in files:
            path = os.path.join(root, f)
            if os.path.islink(path):
                target = os.readlink(path)
                if target.startswith("/lib/"):
                    rel_target = os.path.relpath(os.path.join(sysroot, target.lstrip("/")), root)
                    os.unlink(path)
                    os.symlink(rel_target, path)
                elif target.startswith("/usr/lib/"):
                    rel_target = os.path.relpath(os.path.join(sysroot, target.lstrip("/")), root)
                    os.unlink(path)
                    os.symlink(rel_target, path)
print("  Symlinks sanitized.")
PY_EOF

# 4. Comprehensive validation of required headers and libraries before declaration of completion
echo "Validating sysroot completeness..."
REQUIRED_FILES=(
    "usr/include/stdlib.h"
    "usr/include/fontconfig/fontconfig.h"
    "usr/include/expat.h"
    "usr/lib/libc.so"
    "usr/lib/libm.so"
    "usr/lib/libthr.so"
    "usr/lib/libfontconfig.so"
    "usr/lib/libexpat.so"
)
for rf in "${REQUIRED_FILES[@]}"; do
    if [ ! -e "${STAGE_DIR}/${rf}" ]; then
        echo "ERROR: Sysroot validation failed - missing required file: ${rf}" >&2
        exit 1
    fi
done

# 5. Write verified completion manifest inside staging directory
cat > "${STAGE_DIR}/.sysroot-manifest" <<EOF
version=${FREEBSD_VER}
arch=${ARCH}
revision=${SCRIPT_REVISION}
base_sha256=${EXPECTED_BASE_SHA}
status=complete
EOF

# 6. Atomic publication on the SAME filesystem:
# Move existing SYSROOT_DIR to BACKUP_DIR first, then rename STAGE_DIR to SYSROOT_DIR.
# If anything fails, restore from BACKUP_DIR.
if [ -d "${SYSROOT_DIR}" ]; then
    mv "${SYSROOT_DIR}" "${BACKUP_DIR}"
fi

if mv "${STAGE_DIR}" "${SYSROOT_DIR}"; then
    rm -rf "${BACKUP_DIR}"
    echo "FreeBSD sysroot successfully verified and atomically published at ${SYSROOT_DIR}."
else
    echo "ERROR: Failed to move staged sysroot to ${SYSROOT_DIR}! Restoring backup..." >&2
    if [ -d "${BACKUP_DIR}" ]; then
        mv "${BACKUP_DIR}" "${SYSROOT_DIR}"
    fi
    exit 1
fi
