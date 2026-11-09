#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""legacy-ui 编译隔离守卫（不依赖 Gradle / Kotlin 工具链，纯 stdlib）。

背景：用户已放弃 Compose/Android 原生业务 UI，旧 `com.mina.legadostudio.ui` 屏幕整体移到
`legacy-ui/`，只作源码备份，**不得编译、不得打包**。当前隔离靠两点：

1. `legacy-ui/` 不在 Gradle 源码集内（`app/build.gradle.kts` 只编 `app/src/main`，
   `settings.gradle.kts` 只 include `:app` 与 `:legado-rhino`）；
2. 本守卫在 CI/本地校验：`legacy-ui/**` 不得被任何源码集引用，且 `legacy-ui/` 里不得出现
   会参与打包的构建脚本。

任何一条失败即退出码 1，并打印具体文件与行号。用法（仓库根目录）：

    python3 legacy-ui/check_isolation.py

退出码：0 = 隔离成立；1 = 有泄漏。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEGACY = os.path.join(ROOT, 'legacy-ui')

# 参与编译 / 打包的源码根（相对仓库根）
COMPILED_ROOTS = [
    'app/src/main',
    'app/src/test',
    'app/src/androidTest',
    'legado-rhino/src',
]

# legacy-ui 下不允许出现的构建脚本：一旦存在就可能被 Gradle/打包工具拾取
FORBIDDEN_BUILD_FILES = {
    'build.gradle',
    'build.gradle.kts',
    'settings.gradle',
    'settings.gradle.kts',
    'gradle.properties',
    'AndroidManifest.xml',
}

# 源码里出现这些字样即视为引用 legacy-ui
LEGACY_REF = re.compile(r'legacy-ui|legacy_ui|legacyUi')


def _iter_files(root, exts):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ('build', '.gradle', 'node_modules', 'target')]
        for name in sorted(filenames):
            if any(name.endswith(e) for e in exts):
                yield os.path.join(dirpath, name)


def check_no_build_files():
    problems = []
    for path in _iter_files(LEGACY, ('',)):
        if os.path.basename(path) in FORBIDDEN_BUILD_FILES:
            problems.append('%s: legacy-ui 内不得有构建脚本/清单（会被打包工具拾取）' % os.path.relpath(path, ROOT))
    return problems


def check_no_source_references():
    problems = []
    for rel in COMPILED_ROOTS:
        root = os.path.join(ROOT, rel)
        if not os.path.isdir(root):
            continue
        for path in _iter_files(root, ('.kt', '.java', '.kts', '.gradle', '.xml', '.json', '.pro')):
            try:
                with open(path, encoding='utf-8', errors='replace') as f:
                    for lineno, line in enumerate(f, 1):
                        if LEGACY_REF.search(line):
                            problems.append('%s:%d: 源码集引用了 legacy-ui' % (os.path.relpath(path, ROOT), lineno))
            except OSError as exc:
                problems.append('%s: 读取失败 %s' % (os.path.relpath(path, ROOT), exc))
    return problems


def check_legacy_tests_not_in_source_set():
    """legacy-ui/tests 下的 Kotlin 测试不得出现在 app/src/test 里（否则会被 JUnit 跑）。"""
    problems = []
    legacy_tests = os.path.join(LEGACY, 'tests')
    if not os.path.isdir(legacy_tests):
        return problems
    app_tests = os.path.join(ROOT, 'app/src/test')
    names = {os.path.basename(p) for p in _iter_files(legacy_tests, ('.kt',))}
    for path in _iter_files(app_tests, ('.kt',)):
        if os.path.basename(path) in names:
            problems.append('%s: 与 legacy-ui/tests 同名，会被当作现行测试运行' % os.path.relpath(path, ROOT))
    return problems


def main():
    problems = []
    problems += check_no_build_files()
    problems += check_no_source_references()
    problems += check_legacy_tests_not_in_source_set()
    if problems:
        print('legacy-ui 隔离检查失败：')
        for p in problems:
            print('  - ' + p)
        return 1
    print('legacy-ui 隔离检查通过：不在源码集内、无构建脚本、无源码引用。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
