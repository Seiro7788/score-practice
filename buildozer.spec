[app]
title = 교번 모바일
package.name = crewroster
package.domain = com.smartrain.crewroster
source.dir = .
source.include_exts = py,ttf,db
source.include_patterns = assets/*
version = 1.0
requirements = python3,kivy==2.3.1
# 최신 p4a 릴리스: 호환되지 않는 비순수파이썬 휠(charset_normalizer 등 Kivy 가 선언한
# 미사용 의존성)을 자동으로 무시(is_wheel_compatible). 구버전 p4a 는 이걸 설치하려다 실패.
p4a.branch = v2026.05.09
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
