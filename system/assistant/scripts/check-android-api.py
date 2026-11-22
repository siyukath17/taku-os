#!/usr/bin/env python3
"""Fail when compiled broker classes reference platform members android.jar does not declare.

The broker is compiled with JDK 17's own java.* classes (javac --release 17), so a Java 9+ API that
ART's core-oj lacks compiles cleanly and only fails on the phone. ProcessBuilder.Redirect.DISCARD
did exactly that: NoSuchFieldError on a background thread killed the broker's app_process on the
Pixel 9 (2026-11-22). This check resolves every java/javax/android field and method reference in the
given class jar against android.jar (walking superclasses and interfaces) and lists what is missing.

usage: check-android-api.py ANDROID_JAR CLASSES_JAR [--baseline FILE]
"""
import struct
import sys
import zipfile

PLATFORM = ("java/", "javax/", "android/")
# invokedynamic bootstraps that d8 desugars away (string concat, lambdas, records, switch patterns).
DESUGARED = {"java/lang/invoke/StringConcatFactory", "java/lang/invoke/LambdaMetafactory",
             "java/lang/runtime/ObjectMethods", "java/lang/runtime/SwitchBootstraps"}


def parse(data):
    """(this, super, interfaces, members {(name, desc)}, refs [(kind, class, name, desc)])."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack(">H", data[8:10])[0]
    pool = [None] * count
    i, off = 1, 10
    while i < count:
        tag = data[off]
        if tag == 1:
            n = struct.unpack(">H", data[off + 1:off + 3])[0]
            pool[i] = ("utf8", data[off + 3:off + 3 + n].decode("utf-8", "replace"))
            off += 3 + n
        elif tag in (3, 4):
            off += 5
        elif tag in (5, 6):
            off += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, struct.unpack(">H", data[off + 1:off + 3])[0])
            off += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            a, b = struct.unpack(">HH", data[off + 1:off + 5])
            pool[i] = (tag, a, b)
            off += 5
        elif tag == 15:
            off += 4
        else:
            raise ValueError("bad constant tag %d" % tag)
        i += 1

    def utf(k):
        return pool[k][1]

    def cls(k):
        return utf(pool[k][1]) if k else None

    _, this, sup, nif = struct.unpack(">HHHH", data[off:off + 8])
    off += 8
    interfaces = [cls(struct.unpack(">H", data[off + 2 * j:off + 2 * j + 2])[0]) for j in range(nif)]
    off += 2 * nif
    members = set()
    for _ in range(2):  # fields, then methods
        n = struct.unpack(">H", data[off:off + 2])[0]
        off += 2
        for _ in range(n):
            _, name, desc, attrs = struct.unpack(">HHHH", data[off:off + 8])
            off += 8
            members.add((utf(name), utf(desc)))
            for _ in range(attrs):
                length = struct.unpack(">I", data[off + 2:off + 6])[0]
                off += 6 + length
    refs = []
    for entry in pool:
        if entry and entry[0] in (9, 10, 11):
            owner = cls(entry[1])
            nat = pool[entry[2]]
            refs.append(({9: "field", 10: "method", 11: "method"}[entry[0]], owner, utf(nat[1]), utf(nat[2])))
    return cls(this), cls(sup), interfaces, members, refs


def load_platform(path):
    classes = {}
    with zipfile.ZipFile(path) as jar:
        for name in jar.namelist():
            if name.endswith(".class") and name.startswith(PLATFORM):
                this, sup, interfaces, members, _ = parse(jar.read(name))
                classes[this] = (sup, interfaces, members)
    return classes


def resolves(classes, owner, name, desc):
    seen, stack = set(), [owner]
    while stack:
        c = stack.pop()
        if c is None or c in seen:
            continue
        seen.add(c)
        info = classes.get(c)
        if info is None:
            continue
        sup, interfaces, members = info
        if (name, desc) in members:
            return True
        # Signature-polymorphic MethodHandle/VarHandle methods match any descriptor.
        if c in ("java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle") and any(m[0] == name for m in members):
            return True
        stack.append(sup)
        stack.extend(interfaces)
    return False


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    baseline = set()
    if "--baseline" in argv:
        with open(argv[argv.index("--baseline") + 1]) as f:
            baseline = {line.strip() for line in f if line.strip() and not line.startswith("#")}
    classes = load_platform(argv[1])
    missing = set()
    with zipfile.ZipFile(argv[2]) as jar:
        for name in jar.namelist():
            if not name.endswith(".class"):
                continue
            this, _, _, _, refs = parse(jar.read(name))
            for kind, owner, member, desc in refs:
                if not owner or not owner.startswith(PLATFORM) or owner.startswith("[") or owner in DESUGARED:
                    continue
                if owner not in classes:
                    missing.add("%s class %s (from %s)" % (kind, owner, this.split("$")[0]))
                elif not resolves(classes, owner, member, desc):
                    missing.add("%s %s.%s%s (from %s)" % (kind, owner, member, desc, this.split("$")[0]))
    new = sorted(m for m in missing if m not in baseline)
    for line in new:
        print("MISSING ON ANDROID: " + line)
    print("android API check: %d references missing from android.jar (%d new, %d baselined)"
          % (len(missing), len(new), len(missing) - len(new)))
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
