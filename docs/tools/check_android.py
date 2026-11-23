#!/usr/bin/env python3
"""فحص ساكن لمشروع Android بدون Gradle أو Android SDK.

يتحقق من:
- صحة XML في كل ملفات الموارد والمانيفست.
- تطابق package في Kotlin مع مسار الملف.
- وجود كل R.string / R.drawable مستخدم.
- وجود أنشطة المانيفست كملفات Kotlin.
- توازن الأقواس في Kotlin.
- خلو المشروع من WebView (المطلوب تطبيق أصلي).
- وجود سجلات كتالوج الإصدارات.

التشغيل من جذر المشروع:  python docs/tools/check_android.py
"""

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def strip_code(source: str) -> str:
    """يزيل التعليقات والنصوص بأمان (بدون أن يعتبر // داخل رابط تعليقاً)."""
    out = []
    i = 0
    length = len(source)

    while i < length:
        char = source[i]

        if source.startswith('"""', i):
            i += 3
            while i + 2 < length and not source.startswith('"""', i):
                i += 1
            i += 3
            out.append('""')
        elif char == "/" and i + 1 < length and source[i + 1] == "/":
            while i < length and source[i] != "\n":
                i += 1
        elif char == "/" and i + 1 < length and source[i + 1] == "*":
            i += 2
            while i + 1 < length and not (source[i] == "*" and source[i + 1] == "/"):
                i += 1
            i += 2
        elif char in ('"', "'"):
            quote = char
            i += 1
            while i < length:
                if source[i] == "\\":
                    i += 2
                    continue
                if source[i] == quote:
                    i += 1
                    break
                i += 1
            out.append(quote * 2)
        elif char == "`":
            i += 1
            while i < length and source[i] != "`":
                i += 1
            i += 1
            out.append("``")
        else:
            out.append(char)
            i += 1

    return "".join(out)


def main() -> int:
    errors = []
    notes = []

    xml_files = glob.glob("android/**/*.xml", recursive=True)
    for path in xml_files:
        try:
            ET.parse(path)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"XML malformed: {path}: {exc}")

    strings_root = ET.parse("android/app/src/main/res/values/strings.xml").getroot()
    string_names = {el.get("name") for el in strings_root if el.tag == "string"}

    kotlin_files = glob.glob("android/**/*.kt", recursive=True)
    if not kotlin_files:
        errors.append("no kotlin files found")

    for path in kotlin_files:
        source = open(path, encoding="utf-8").read()
        package_match = re.search(r"^package\s+([\w.]+)", source, re.M)
        if not package_match:
            errors.append(f"no package declaration: {path}")
        else:
            normalized = os.path.dirname(path).replace("\\", "/")
            for marker in ("src/main/java/", "src/test/java/"):
                if marker in normalized:
                    normalized = normalized.split(marker, 1)[1]
                    break
            expected = normalized.replace("/", ".")
            if package_match.group(1) != expected:
                errors.append(
                    f"package mismatch {path}: {package_match.group(1)} != {expected}"
                )
        if re.search(r"webview", source, re.I):
            errors.append(f"WebView reference: {path}")
        if "TODO(" in source:
            notes.append(f"TODO in {path}")

    used_strings = set()
    for path in kotlin_files:
        used_strings |= set(
            re.findall(r"R\.string\.(\w+)", open(path, encoding="utf-8").read())
        )
    missing_strings = sorted(used_strings - string_names)
    if missing_strings:
        errors.append("missing string resources: " + ", ".join(missing_strings))

    drawables = {
        os.path.splitext(os.path.basename(p))[0]
        for p in glob.glob("android/app/src/main/res/drawable*/*")
    }
    used_drawables = set()
    for path in kotlin_files:
        used_drawables |= set(
            re.findall(r"R\.drawable\.(\w+)", open(path, encoding="utf-8").read())
        )
    missing_drawables = sorted(used_drawables - drawables)
    if missing_drawables:
        errors.append("missing drawables: " + ", ".join(missing_drawables))

    manifest = open(
        "android/app/src/main/AndroidManifest.xml", encoding="utf-8"
    ).read()
    for name in re.findall(r'android:name="(\.?[\w.]+Activity)"', manifest):
        if name.startswith("."):
            path = (
                "android/app/src/main/java/com/brainrot/pigeon/"
                + name[1:].replace(".", "/")
                + ".kt"
            )
        else:
            path = "android/app/src/main/java/" + name.replace(".", "/") + ".kt"
        if not os.path.exists(path):
            errors.append(f"manifest activity missing: {name} -> {path}")

    for path in kotlin_files:
        code = strip_code(open(path, encoding="utf-8").read())
        for opener, closer in [("{", "}"), ("(", ")"), ("[", "]")]:
            if code.count(opener) != code.count(closer):
                errors.append(
                    f"unbalanced {opener}{closer} in {path}: "
                    f"{code.count(opener)}/{code.count(closer)}"
                )

    toml = open("android/gradle/libs.versions.toml", encoding="utf-8").read()
    for alias in [
        "android-application",
        "kotlin-android",
        "kotlin-compose",
        "kotlin-serialization",
    ]:
        if alias not in toml:
            errors.append(f"missing plugin alias: {alias}")

    print(
        f"kotlin files: {len(kotlin_files)}, xml files: {len(xml_files)}, "
        f"strings: {len(string_names)}"
    )
    print(f"R.string used: {len(used_strings)}, drawables used: {sorted(used_drawables)}")

    for note in notes:
        print("NOTE:", note)

    if errors:
        print("ERRORS:")
        for error in errors:
            print(" -", error)
        return 1

    print("STATIC CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
