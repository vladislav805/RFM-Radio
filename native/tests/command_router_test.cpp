#include "command_router.h"
#include "backend/v4l2_exynos/v4l2_exynos_backend.h"

#include <gtest/gtest.h>

namespace {

Backend *unexpected_backend() {
    ADD_FAILURE() << "This command should return before requesting a backend";
    return nullptr;
}

void unexpected_shutdown() {
    ADD_FAILURE() << "Unexpected shutdown";
}

class CommandTestBackend final : public V4l2ExynosBackend {
public:
    bool jump(int direction, uint32_t *new_frequency) override {
        EXPECT_EQ(-1, direction);
        *new_frequency = 98700;
        return true;
    }

    bool set_volume(int volume) override {
        return set_status(volume == 7, "ERR_VOLUME");
    }
};

Backend *test_backend() {
    static CommandTestBackend backend;
    return &backend;
}

TEST(CommandRouter, RejectsInvalidRequestsBeforeBackendLookup) {
    const struct {
        const char *command;
        const char *error;
    } cases[] = {
            {"   ", "ERR_UNK_CMD"},
            {"unknown", "ERR_UNK_CMD"},
            {"jump", "ERR_IVD_FRQ"},
            {"set_force_use 1", "ERR_IVD_FRQ"},
            {"enable freq=98500", "ERR_IVD_CFG"},
            {"set_region xx", "ERR_IVD_CFG"},
            {"set_spacing 75", "ERR_IVD_CFG"},
            {"set_soft_mute 1 extra", "ERR_IVD_CFG"},
            {"set_notch_filter 3", "ERR_IVD_CFG"},
    };
    for (const auto &item : cases) {
        SCOPED_TRACE(item.command);
        const auto response = route_command(item.command, unexpected_backend, unexpected_shutdown);
        EXPECT_EQ(1, response.code);
        EXPECT_STREQ(item.error, response.data);
    }
}

TEST(CommandRouter, ReportsMissingBackend) {
    const auto response = route_command("init", []() -> Backend * { return nullptr; }, unexpected_shutdown);
    EXPECT_EQ(1, response.code);
    EXPECT_STREQ("ERR_UNSUPPORTED", response.data);
}

TEST(CommandRouter, JumpReturnsBackendFrequencyInKHz) {
    const auto response = route_command("  jump   -1 ", test_backend, unexpected_shutdown);
    EXPECT_EQ(0, response.code);
    EXPECT_STREQ("98700", response.data);
}

TEST(CommandRouter, VolumePassesValueAndPropagatesBackendError) {
    const auto ok = route_command("set_volume 7", test_backend, unexpected_shutdown);
    EXPECT_EQ(0, ok.code);
    EXPECT_STREQ("ok", ok.data);

    const auto error = route_command("set_volume 8", test_backend, unexpected_shutdown);
    EXPECT_EQ(1, error.code);
    EXPECT_STREQ("ERR_VOLUME", error.data);
}

TEST(CommandRouter, ForceUseRunsWithoutRadioBackend) {
    const auto response = route_command("set_force_use 1 0", unexpected_backend, unexpected_shutdown);
    EXPECT_EQ(0, response.code);
    EXPECT_STREQ("ok", response.data);
}

TEST(CommandRouter, BothExitCommandsInvokeShutdownWithoutBackendLookup) {
    static int shutdown_count;
    for (const char *command : {"shutdown", "exit"}) {
        SCOPED_TRACE(command);
        shutdown_count = 0;
        const auto response = route_command(command, unexpected_backend, [] { ++shutdown_count; });
        EXPECT_EQ(1, shutdown_count);
        EXPECT_EQ(0, response.code);
        EXPECT_STREQ("ok", response.data);
    }
}

}  // namespace
