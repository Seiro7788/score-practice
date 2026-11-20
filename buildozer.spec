[app]
title = 교번 모바일
package.name = crewroster
package.domain = com.smartrain.crewroster
source.dir = .
source.include_exts = py,ttf,db
source.include_patterns = assets/*
# 버전은 main.py 의 __version__ 에서 읽음(이후 버전업은 main.py 만 고치면 됨 → 캐시 유지)
version.regex = __version__\s*=\s*['"]([^'"]*)['"]
version.filename = %(source.dir)s/main.py
requirements = python3,kivy==2.3.1
# p4a develop + NDK 28c: 최신 안드로이드(갤S25 등)는 16KB 메모리 페이지를 쓰며,
# NDK r25b 가 만든 4KB 정렬 .so 는 로드가 거부되어 실행 직후 튕긴다. NDK r27+ 는
# 16KB 정렬을 기본으로 하므로 로드된다. develop 는 libthorvg 레시피의 clang 경로를
# NDK 28 에서 올바르게 찾는다(구 NDK25 의 lib64/clang 불일치 문제 없음).
p4a.branch = develop
orientation = portrait
fullscreen = 0
android.permissions = READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE
android.api = 34
android.minapi = 24
android.ndk = 28c
android.archs = arm64-v8a
android.accept_sdk_license = True
android.allow_backup = 1

[buildozer]
log_level = 2
warn_on_root = 0
