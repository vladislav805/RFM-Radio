.PHONY: help test test-native test-java test-integration build-native build-debug build-release deploy-debug deploy-release clean-native-tests

NATIVE_TEST_BUILD_DIR ?= /tmp/rfm-radio-native-tests

test: test-native test-java

test-native:
	cmake -S native -B "$(NATIVE_TEST_BUILD_DIR)" -DRFM_NATIVE_TESTS=ON
	cmake --build "$(NATIVE_TEST_BUILD_DIR)"
	"$(NATIVE_TEST_BUILD_DIR)/rfm_native_tests"

test-java:
	./gradlew --no-daemon :app:testDebugUnitTest

test-integration:
	npm --prefix integration-tests test

build-native:
	./native/build.sh

build-debug:
	./gradlew --no-daemon :app:assembleDebug

build-release:
	./gradlew --no-daemon :app:assembleRelease

deploy-debug:
	./gradlew --no-daemon :app:installDebug

deploy-release: build-release
	@set -eu; \
	ABIS=$$(adb shell getprop ro.product.cpu.abilist | tr -d '\r'); \
	case ",$$ABIS," in \
		*,arm64-v8a,*) FLAVOR=arm64 ;; \
		*,armeabi-v7a,*) FLAVOR=armv7 ;; \
		*) echo "No supported ARM ABI on selected device ($$ABIS)" >&2; exit 1 ;; \
	esac; \
	DIR="app/build/outputs/apk/$$FLAVOR/release"; \
	APK="$$DIR/$$(jq -er '.elements[0].outputFile' "$$DIR/output-metadata.json")"; \
	SDK_ROOT="$${ANDROID_SDK_ROOT:-$${ANDROID_HOME:-$$HOME/Android/Sdk}}"; \
	APKSIGNER=$$(ls -1d "$$SDK_ROOT"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1); \
	if [ -n "$$APKSIGNER" ] && ! "$$APKSIGNER" verify "$$APK" >/dev/null 2>&1; then \
		"$$APKSIGNER" sign --ks "$$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android "$$APK"; \
	fi; \
	adb install -r "$$APK"

clean-native-tests:
	rm -rf "$(NATIVE_TEST_BUILD_DIR)"
