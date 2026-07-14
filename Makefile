.PHONY: help test-native build-native build-app clean-native-tests

NATIVE_TEST_BUILD_DIR ?= /tmp/rfm-radio-native-tests

test-native:
	cmake -S native -B "$(NATIVE_TEST_BUILD_DIR)" -DRFM_NATIVE_TESTS=ON
	cmake --build "$(NATIVE_TEST_BUILD_DIR)"
	"$(NATIVE_TEST_BUILD_DIR)/rfm_native_tests"

build-native:
	./native/build.sh

build-app:
	./gradlew --no-daemon :app:assembleDebug

clean-native-tests:
	rm -rf "$(NATIVE_TEST_BUILD_DIR)"
