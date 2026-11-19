# 교번 모바일 (crewroster-mobile)

데스크톱 crewroster 의 SQLite `.db` 를 폰에서 열어 근무표 보기·셀 수정·저장하는 Kivy 앱.
이 브랜치는 악보 앱(main)과 무관한 독립 브랜치입니다.

- `main.py` : Kivy UI
- `core.py` : DB 로더 + 회전편성(데스크톱과 동일 로직)
- `assets/NotoSansKR.ttf` : 한글 폰트
- APK 는 GitHub Actions(.github/workflows/build-apk.yml)로 빌드됩니다.
