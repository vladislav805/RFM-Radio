#pragma once

#include <cstddef>

constexpr size_t kSystemPropertyBufferSize = 128;

// Reads an Android system property into a buffer of at least kSystemPropertyBufferSize bytes.
int read_system_property(const char *name, char *value);

// Writes an Android system property and returns true on success.
bool write_system_property(const char *name, const char *value);

// Returns true if the file or device node exists on disk.
bool file_exists(const char *path);

// Returns true if the file exists and is executable (X_OK).
bool is_executable_file(const char *path);

// Executes a shell command via system() and returns its status code.
int run_shell_command(const char *command);
