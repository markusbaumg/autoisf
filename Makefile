SDK := $(shell sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)
ADB ?= $(or $(shell command -v adb),$(SDK)/platform-tools/adb)
APKSIGNER ?= $(lastword $(sort $(wildcard $(SDK)/build-tools/*/apksigner)))
DIST := dist
# Wireless-debugging port changes on every pairing: `make watch WATCH=<ip:port>` (see `adb devices`)
WATCH ?= 192.168.1.16:44183

.PHONY: build verify phone watch clean connect upstream-status

build:
	rm -rf $(DIST)
	./gradlew --parallel clean :app:assembleFullRelease :wear:assembleFullRelease
	mkdir -p $(DIST)
	cp app/build/outputs/apk/full/release/app-full-release.apk $(DIST)/
	cp wear/build/outputs/apk/full/release/wear-full-release.apk $(DIST)/
	ls -lh $(DIST)/

verify:
	$(APKSIGNER) verify --print-certs $(DIST)/app-full-release.apk | grep -i sha-256
	$(APKSIGNER) verify --print-certs $(DIST)/wear-full-release.apk | grep -i sha-256

phone:
	$(ADB) -d install -r $(DIST)/app-full-release.apk

watch:
	$(ADB) -s $(WATCH) install -r $(DIST)/wear-full-release.apk

connect:
	$(ADB) connect $(WATCH)

clean:
	rm -rf $(DIST)
	./gradlew clean

# AAPS 4 migration gate (_docs/aaps4/MIGRATION_AAPS4.md): is there an AutoISF line on 4.x yet?
upstream-status:
	@echo "AutoISF fork (T-o-b-i-a-s) branches on 4.x:"
	@git ls-remote --heads https://github.com/T-o-b-i-a-s/AndroidAPS.git | awk '{print $$2}' | sed 's|refs/heads/||' | grep -E '^4\.' || echo "  none yet -> keep waiting"
	@echo "nightscout AAPS 4 tags:"
	@git ls-remote --tags https://github.com/nightscout/AndroidAPS.git | awk '{print $$2}' | sed 's|refs/tags/||' | grep -E '^4\.' | grep -v '\^{}' | sort -V | tail -5
