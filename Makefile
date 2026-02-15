ADB := $(HOME)/dev/androidkey/adb/adb

build:
	./gradlew --parallel :app:assembleFullRelease :wear:assembleFullRelease

phone:
	$(ADB) -d install -r app/build/outputs/apk/full/release/app-full-release.apk

watch:
	$(ADB) -e install -r wear/build/outputs/apk/full/release/wear-full-release.apk
