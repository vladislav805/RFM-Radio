#include "backend/hal_helium/hal_helium_backend.h"
#include "ctl_server.h"

#include <gtest/gtest.h>

#include <chrono>
#include <thread>

Backend *create_hal_helium_backend();

// The real protocol writer is linked, but no command-loop polling is needed.
void poll_rmssi() {}

namespace {

int off_requests;
int last_notch_filter;
bool reject_off;
bool reject_tune;
std::thread enabled_thread;

int fake_set(int id, int value) {
    if (id == kV4l2CtrlSetNotchFilter) {
        last_notch_filter = value;
    }
    if (id == kV4l2CtrlState && value == 1) {
        enabled_thread = std::thread(HalHeliumBackend::enabled_cb);
    }
    if (id == kV4l2CtrlIrisFreq && reject_tune) return -1;
    if (id == kV4l2CtrlState && value == 0) {
        ++off_requests;
        return reject_off ? -1 : 0;
    }
    return 0;
}

int fake_get(int, int *value) {
    *value = 0;
    return 0;
}

fm_interface_t fake_vendor = {nullptr, fake_set, fake_get};

class HalShutdownTest : public ::testing::Test {
protected:
    HalHeliumBackend *backend = nullptr;

    void SetUp() override {
        off_requests = 0;
        last_notch_filter = -1;
        reject_off = false;
        reject_tune = false;
        backend = static_cast<HalHeliumBackend *>(create_hal_helium_backend());
        backend->reset_for_test(&fake_vendor, true, true, true);
    }

    void TearDown() override {
        if (callback_thread.joinable()) callback_thread.join();
        if (enabled_thread.joinable()) enabled_thread.join();
        backend->reset_for_test(nullptr, false, false, false);
    }

    void CompleteLater() {
        callback_thread = std::thread([] {
            std::this_thread::sleep_for(std::chrono::milliseconds(30));
            HalHeliumBackend::disabled_cb();
        });
    }

    std::thread callback_thread;
};

TEST_F(HalShutdownTest, ShutdownRequestsOffAndWaitsForCallback) {
    CompleteLater();
    EXPECT_TRUE(backend->shutdown_with_timeout(500));
    EXPECT_EQ(off_requests, 1);
    EXPECT_FALSE(backend->state().receiver_cleanup_needed);
}

TEST_F(HalShutdownTest, EarlierDisableStillRequiresCallbackWithoutAnotherOff) {
    ASSERT_TRUE(backend->disable());
    EXPECT_TRUE(backend->state().disable_pending);
    EXPECT_FALSE(backend->shutdown_with_timeout(10));
    EXPECT_STREQ(backend->last_error(), "timed out waiting for disabled_cb");
    EXPECT_EQ(off_requests, 1);

    CompleteLater();
    EXPECT_TRUE(backend->shutdown_with_timeout(500));
    EXPECT_EQ(off_requests, 1);
}

TEST_F(HalShutdownTest, CompletedDisableDoesNotSendOffAgain) {
    ASSERT_TRUE(backend->disable());
    HalHeliumBackend::disabled_cb();
    EXPECT_TRUE(backend->shutdown_with_timeout(0));
    EXPECT_EQ(off_requests, 1);
}

TEST_F(HalShutdownTest, MissingCallbackTimesOutEvenWithoutEnabledNotification) {
    backend->state().enabled = false;
    EXPECT_FALSE(backend->shutdown_with_timeout(10));
    EXPECT_TRUE(backend->state().receiver_cleanup_needed);
    EXPECT_STREQ(backend->last_error(), "timed out waiting for disabled_cb");
}

TEST_F(HalShutdownTest, SpuriousNotificationDoesNotConfirmCleanup) {
    HalHeliumBackend *target = backend;
    callback_thread = std::thread([target] {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        pthread_mutex_lock(&target->state().lock);
        pthread_cond_broadcast(&target->state().cond);
        pthread_mutex_unlock(&target->state().lock);
    });
    EXPECT_FALSE(backend->shutdown_with_timeout(30));
    EXPECT_TRUE(backend->state().receiver_cleanup_needed);
}

TEST_F(HalShutdownTest, ShutdownWithoutReceiverStartupDoesNotCallVendor) {
    HeliumRuntimeState &state = backend->state();
    state.initialized = false;
    state.enabled = false;
    state.receiver_cleanup_needed = false;
    state.vendor = nullptr;
    EXPECT_TRUE(backend->shutdown_with_timeout(0));
    EXPECT_EQ(off_requests, 0);
}

TEST_F(HalShutdownTest, FailedOffCanBeRetriedDuringShutdown) {
    reject_off = true;
    EXPECT_FALSE(backend->disable());
    EXPECT_FALSE(backend->state().disable_pending);
    reject_off = false;
    CompleteLater();
    EXPECT_TRUE(backend->shutdown_with_timeout(500));
    EXPECT_EQ(off_requests, 2);
}

TEST_F(HalShutdownTest, AdapterWaitsAfterEarlierDisableWithoutExplicitInit) {
    ASSERT_TRUE(backend->disable());
    CompleteLater();
    backend->shutdown();
    EXPECT_FALSE(backend->state().receiver_cleanup_needed);
    EXPECT_EQ(off_requests, 1);
}

TEST_F(HalShutdownTest, AdapterEnableWithoutExplicitInitReceivesCleanup) {
    backend->state().enabled = false;
    backend->state().receiver_cleanup_needed = false;
    StartupConfig config;
    config.frequency_khz = 87500;
    config.spacing_khz = 100;
    ASSERT_TRUE(backend->enable(config));
    EXPECT_TRUE(backend->state().receiver_cleanup_needed);
    CompleteLater();
    backend->shutdown();
    EXPECT_FALSE(backend->state().receiver_cleanup_needed);
    EXPECT_EQ(off_requests, 1);
}

TEST_F(HalShutdownTest, AdapterWaitsForEarlierEnableRollback) {
    backend->state().enabled = false;
    backend->state().receiver_cleanup_needed = false;
    reject_tune = true;
    StartupConfig config;
    config.frequency_khz = 87500;
    config.spacing_khz = 100;
    ASSERT_FALSE(backend->enable(config));
    EXPECT_TRUE(backend->state().disable_pending);
    EXPECT_EQ(off_requests, 1);
    CompleteLater();
    backend->shutdown();
    EXPECT_FALSE(backend->state().receiver_cleanup_needed);
    EXPECT_EQ(off_requests, 1);
}

TEST_F(HalShutdownTest, NotchFilterValidatesRangeAndWritesControl) {
    EXPECT_TRUE(backend->set_notch_filter(1));
    EXPECT_EQ(last_notch_filter, 1);
    EXPECT_TRUE(backend->set_notch_filter(2));
    EXPECT_EQ(last_notch_filter, 2);
    EXPECT_TRUE(backend->set_notch_filter(0));
    EXPECT_EQ(last_notch_filter, 0);
    EXPECT_FALSE(backend->set_notch_filter(-1));
    EXPECT_FALSE(backend->set_notch_filter(3));
    EXPECT_EQ(last_notch_filter, 0);
}

}  // namespace
