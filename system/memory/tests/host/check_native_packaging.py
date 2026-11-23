#!/usr/bin/env python3
"""Static contract: the ROM build of TakuMemory must package the ONNX Runtime JNI libraries.

Soong only packages prebuilt JNI from an AAR (android_library_import extract_jni) into an android_app when the app
embeds native libraries (use_embedded_native_libs); it then sets extractNativeLibs=false via manifest_fixer, which
fails on a manifest that sets it to true. A ROM once shipped without libonnxruntime4j_jni.so and crashed the memory
process. Usage: check_native_packaging.py [--self-test] [AAR]
"""
import pathlib
import re
import sys
import zipfile

MEMORY = pathlib.Path(__file__).resolve().parents[2]
APP, ORT = 'TakuMemory', 'taku-memory-onnxruntime'
JNI = ('jni/arm64-v8a/libonnxruntime.so', 'jni/arm64-v8a/libonnxruntime4j_jni.so')


def modules(blueprint):
    """Top-level Blueprint modules as {name: (type, body)}; comments stripped, braces matched."""
    text = re.sub(r'//[^\n]*', '', blueprint)
    found, pos = {}, 0
    while (m := re.compile(r'(\w+)\s*\{').search(text, pos)):
        depth, i = 1, m.end()
        while depth:
            depth += {'{': 1, '}': -1}.get(text[i], 0)
            i += 1
        body = text[m.end():i - 1]
        name = re.search(r'^\s*name:\s*"([^"]+)"', body, re.M)
        if name:
            found[name.group(1)] = (m.group(1), body)
        pos = i
    return found


def problems(blueprint, manifest, build_sh):
    out, mods = [], modules(blueprint)
    app_type, app = mods.get(APP, (None, ''))
    ort_type, ort = mods.get(ORT, (None, ''))
    if app_type != 'android_app':
        out.append(f'{APP} android_app module missing from Android.bp')
    if not re.search(r'^\s*use_embedded_native_libs:\s*true\s*,', app, re.M):
        out.append(f'{APP} must set use_embedded_native_libs: true, or Soong drops the AAR JNI libraries')
    if not re.search(r'static_libs:\s*\[[^\]]*"' + re.escape(ORT) + '"', app):
        out.append(f'{APP} static_libs must include {ORT}')
    if ort_type != 'android_library_import' or not re.search(r'^\s*extract_jni:\s*true\s*,', ort, re.M):
        out.append(f'{ORT} must be an android_library_import with extract_jni: true')
    if re.search(r'android:extractNativeLibs\s*=\s*"true"', manifest):
        out.append('AndroidManifest.xml must not set extractNativeLibs="true" (conflicts with embedded native libs)')
    if "'jni/arm64-v8a/*'" not in build_sh or 'zip -q -r unsigned.apk lib' not in build_sh:
        out.append('build.sh must package jni/arm64-v8a from the ONNX Runtime AAR')
    return out


def aar_problems(aar):
    with zipfile.ZipFile(aar) as z:
        names = set(z.namelist())
    return [f'{aar} lacks {lib}' for lib in JNI if lib not in names]


def main(argv):
    read = lambda name: (MEMORY / name).read_text(encoding='utf-8')
    sources = read('Android.bp'), read('AndroidManifest.xml'), read('build.sh')
    found = problems(*sources)
    if '--self-test' in argv:
        bp, manifest, build = sources
        # Each known regression must be caught.
        mutations = {
            'missing use_embedded_native_libs': (re.sub(r'use_embedded_native_libs:\s*true', 'use_embedded_native_libs: false', bp), manifest, build),
            'extract_jni off': (bp.replace('extract_jni: true', 'extract_jni: false'), manifest, build),
            'manifest extracts': (bp, manifest.replace('<application ', '<application android:extractNativeLibs="true" ', 1), build),
            'onnxruntime not linked': (bp.replace(f', "{ORT}"]', ']'), manifest, build),
        }
        for label, mutated in mutations.items():
            if not problems(*mutated):
                found.append(f'self-test: regression "{label}" was not detected')
    for aar in [a for a in argv if not a.startswith('--')]:
        found += aar_problems(aar)
    for p in found:
        print('FAIL', p)
    if found:
        return 1
    print('TakuMemory native packaging contract ok')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
