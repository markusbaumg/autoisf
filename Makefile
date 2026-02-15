ADB := $(HOME)/dev/androidkey/adb/adb
DIST := dist
WATCH := 192.168.1.16:45659

.PHONY: build phone watch clean connect

build:
	rm -rf $(DIST)
	./gradlew --parallel clean :app:assembleFullRelease :wear:assembleFullRelease
	mkdir -p $(DIST)
	cp app/build/outputs/apk/full/release/app-full-release.apk $(DIST)/
	cp wear/build/outputs/apk/full/release/wear-full-release.apk $(DIST)/
	ls -lh $(DIST)/

phone:
	$(ADB) -d install -r $(DIST)/app-full-release.apk

watch:
	$(ADB) -s $(WATCH) install -r $(DIST)/wear-full-release.apk

connect:
	$(ADB) connect $(WATCH)

clean:
	rm -rf $(DIST)
	./gradlew clean
