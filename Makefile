.PHONY: help test test-native test-java build-native build-debug build-release clean-native-tests

NATIVE_TEST_BUILD_DIR ?= /tmp/rfm-radio-native-tests

test: test-native test-java

test-native:
	cmake -S native -B "$(NATIVE_TEST_BUILD_DIR)" -DRFM_NATIVE_TESTS=ON
	cmake --build "$(NATIVE_TEST_BUILD_DIR)"
	"$(NATIVE_TEST_BUILD_DIR)/rfm_native_tests"

test-java:
	./gradlew --no-daemon :app:testDebugUnitTest

build-native:
	./native/build.sh

build-debug:
	./gradlew --no-daemon :app:assembleDebug

build-release:
	./gradlew --no-daemon :app:assembleRelease

clean-native-tests:
	rm -rf "$(NATIVE_TEST_BUILD_DIR)"
