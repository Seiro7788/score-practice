[app]
title = 교번 모바일
package.name = crewroster
package.domain = com.smartrain.crewroster
source.dir = .
source.include_exts = py,ttf,db
source.include_patterns = assets/*
version = 1.0
requirements = python3,kivy==2.3.1
# Kivy 2.3.1 메타데이터가 선언하는 미사용 의존성(requests 등)을 p4a가 android 휠로
# 빌드하려다 실패하므로 제외. 이 앱은 kivy core + sqlite3 만 사용.
p4a.extra_args = --blacklist-requirements=requests,charset-normalizer,urllib3,certifi,idna,chardet,filetype,six,docutils,pygments,kivy-garden
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
