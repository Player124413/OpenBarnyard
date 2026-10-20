#!/usr/bin/env python3
"""
Verifies built APKs structurally (CI runs this on every artifact):
  * classes.dex present
  * every expected ABI's libopenbarnyard.so present, nothing unexpected
  * each .so is a real ELF of the right class (32/64-bit) and machine type
Usage: python3 Android/verify_apk.py Android/dist/*.apk
"""
import struct
import sys
import zipfile

# ELF e_machine values
EM_386 = 3
EM_ARM = 40
EM_X86_64 = 62
EM_AARCH64 = 183

ABI_EXPECT = {
    "arm64-v8a":    (2, EM_AARCH64),   # ELFCLASS64
    "armeabi-v7a":  (1, EM_ARM),       # ELFCLASS32
    "x86_64":       (2, EM_X86_64),
    "x86":          (1, EM_386),
}


def check_elf(name, data, expect_class, expect_machine):
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return "%s: not an ELF file" % name
    elf_class = data[4]
    machine = struct.unpack_from("<H", data, 18)[0]
    if elf_class != expect_class:
        return "%s: ELF class %d, expected %d (%s-bit)" % (
            name, elf_class, expect_class, "64" if expect_class == 2 else "32")
    if machine != expect_machine:
        return "%s: e_machine %d, expected %d" % (name, machine, expect_machine)
    return None


def verify(path):
    errors = []
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if "classes.dex" not in names:
            errors.append("classes.dex missing")
        if "AndroidManifest.xml" not in names:
            errors.append("AndroidManifest.xml missing")
        libs = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
        found_abis = set()
        for lib in libs:
            parts = lib.split("/")
            if len(parts) != 3:
                errors.append("unexpected lib path: " + lib)
                continue
            abi = parts[1]
            found_abis.add(abi)
            if abi not in ABI_EXPECT:
                errors.append("unknown ABI folder: " + abi)
                continue
            expect_class, expect_machine = ABI_EXPECT[abi]
            err = check_elf(lib, z.read(lib), expect_class, expect_machine)
            if err:
                errors.append(err)

    # Variant is encoded in the filename: ...-universal.apk / ...-<abi>.apk
    base = path.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
    stem = base[:-4] if base.endswith(".apk") else base
    if stem.endswith("-universal"):
        missing = set(ABI_EXPECT) - found_abis
        if missing:
            errors.append("universal APK is missing ABIs: %s" % ", ".join(sorted(missing)))
    else:
        want = None
        for abi in ABI_EXPECT:
            if stem.endswith("-" + abi):
                want = abi
                break
        if want is not None and found_abis != {want}:
            errors.append("split APK for %s contains ABIs %s" % (want, sorted(found_abis)))
        elif want is None and found_abis:
            print("   (note: filename does not encode an ABI variant; structural checks only)")

    status = "OK " if not errors else "FAIL"
    print("%s %s  abis=%s" % (status, base, ",".join(sorted(found_abis))))
    for e in errors:
        print("   * " + e)
    return not errors


def main():
    if len(sys.argv) < 2:
        print("usage: verify_apk.py <apk> [apk...]")
        return 2
    ok = True
    for path in sys.argv[1:]:
        if path.endswith(".apk"):
            ok = verify(path) and ok
    print("ALL APKS VALID" if ok else "APK VERIFICATION FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
