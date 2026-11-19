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
# p4a v2024.01.21: 의존성 자동해석(charset_normalizer native wheel 실패의 원인)과
# libthorvg 레시피(NDK 25b 와 경로 불일치로 IndexError) 둘 다 없는 안정 릴리스.
# Kivy 레시피는 체크섬 없는 zip 이라 kivy==2.3.1 로 안전하게 오버라이드되고,
# 2.3.0 전용 패치는 2.3.1 에 적용되지 않음. NDK 권장도 25b 로 일치.
p4a.branch = v2024.01.21
orientation = portrait
fullscreen = 0
android.permissions = READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE
android.api = 34
android.minapi = 24
android.ndk = 25b
android.archs = arm64-v8a
android.accept_sdk_license = True
android.allow_backup = 1

[buildozer]
log_level = 2
warn_on_root = 0
