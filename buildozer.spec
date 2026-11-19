[app]
title = 교번 모바일
package.name = crewroster
package.domain = com.smartrain.crewroster
source.dir = .
source.include_exts = py,ttf,db
source.include_patterns = assets/*
version = 1.0
requirements = python3,kivy==2.3.1
# p4a develop: PR#3366 이 python 모듈 설치 단계의 (python3.14) venv 생성을 제거하고
# 비호환 휠(charset_normalizer 등 Kivy 가 선언한 미사용 의존성)을 올바르게 무시한다.
# 태그 릴리스에는 이 수정이 아직 없어 charset_normalizer 설치에서 실패함.
p4a.branch = develop
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
