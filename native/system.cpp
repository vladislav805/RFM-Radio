#include "system.h"

#include <cstdlib>
#include <sys/stat.h>
#include <unistd.h>

#ifdef __ANDROID_API__
#    include <sys/system_properties.h>
#endif

int read_system_property(const char *name, char *value) {
    if (value == nullptr) {
        return 0;
    }
#ifdef __ANDROID_API__
    const int length = __system_property_get(name, value);
    if (length <= 0) {
        value[0] = '\0';
    }
    return length;
#else
    (void)name;
    value[0] = '\0';
    return 0;
#endif
}

bool write_system_property(const char *name, const char *value) {
    if (name == nullptr || value == nullptr) {
        return false;
    }
#ifdef __ANDROID_API__
    return __system_property_set(name, value) == 0;
#else
    (void)name;
    (void)value;
    return true;
#endif
}

bool file_exists(const char *path) {
    if (path == nullptr) {
        return false;
    }
    struct stat sb;
    return stat(path, &sb) == 0;
}

bool is_executable_file(const char *path) {
    if (path == nullptr) {
        return false;
    }
    return access(path, X_OK) == 0;
}

int run_shell_command(const char *command) {
    if (command == nullptr) {
        return -1;
    }
#ifdef __ANDROID_API__
    return system(command);
#else
    (void)command;
    return 0;
#endif
}
