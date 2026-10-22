// Include the implementation to inject the vendor interface and exercise its
// real callbacks without loading a device library or exposing test-only APIs.
#include "hal/fm2_backend.cpp"
#include "backend.h"

#include <gtest/gtest.h>

#include <chrono>
#include <thread>

Backend *create_hal_backend();

// The real protocol writer is linked, but no command-loop polling is needed.
void poll_rmssi() {}

namespace {

int off_requests;
bool reject_off;
bool reject_tune;
std::thread enabled_thread;

int fake_set(int id, int value) {
    if (id == kV4l2CtrlState && value == kFmRxState) {
        enabled_thread = std::thread(enabled_cb);
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
    void SetUp() override {
        off_requests = 0;
        reject_off = false;
        reject_tune = false;
        g_state.vendor = &fake_vendor;
        g_state.initialized = true;
        g_state.enabled = true;
        g_state.receiver_cleanup_needed = true;
        g_state.disable_pending = false;
        clear_error_locked();
    }

    void TearDown() override {
        if (callback_thread.joinable()) callback_thread.join();
        if (enabled_thread.joinable()) enabled_thread.join();
        g_state.vendor = nullptr;
        g_state.initialized = false;
        g_state.enabled = false;
        g_state.receiver_cleanup_needed = false;
        g_state.disable_pending = false;
    }

    void CompleteLater() {
        callback_thread = std::thread([] {
            std::this_thread::sleep_for(std::chrono::milliseconds(30));
            disabled_cb();
        });
    }

    std::thread callback_thread;
};

TEST_F(HalShutdownTest, ShutdownRequestsOffAndWaitsForCallback) {
    CompleteLater();
    EXPECT_TRUE(fm2_backend_shutdown(500));
    EXPECT_EQ(off_requests, 1);
    EXPECT_FALSE(g_state.receiver_cleanup_needed);
}

TEST_F(HalShutdownTest, EarlierDisableStillRequiresCallbackWithoutAnotherOff) {
    ASSERT_TRUE(fm2_backend_disable());
    EXPECT_TRUE(g_state.disable_pending);
    EXPECT_FALSE(fm2_backend_shutdown(10));
    EXPECT_STREQ(fm2_backend_last_error(), "timed out waiting for disabled_cb");
    EXPECT_EQ(off_requests, 1);

    CompleteLater();
    EXPECT_TRUE(fm2_backend_shutdown(500));
    EXPECT_EQ(off_requests, 1);
}

TEST_F(HalShutdownTest, CompletedDisableDoesNotSendOffAgain) {
    ASSERT_TRUE(fm2_backend_disable());
    disabled_cb();
    EXPECT_TRUE(fm2_backend_shutdown(0));
    EXPECT_EQ(off_requests, 1);
}

TEST_F(HalShutdownTest, MissingCallbackTimesOutEvenWithoutEnabledNotification) {
    g_state.enabled = false;
    EXPECT_FALSE(fm2_backend_shutdown(10));
    EXPECT_TRUE(g_state.receiver_cleanup_needed);
    EXPECT_STREQ(fm2_backend_last_error(), "timed out waiting for disabled_cb");
}

TEST_F(HalShutdownTest, SpuriousNotificationDoesNotConfirmCleanup) {
    callback_thread = std::thread([] {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        pthread_mutex_lock(&g_state.lock);
        pthread_cond_broadcast(&g_state.cond);
        pthread_mutex_unlock(&g_state.lock);
    });
    EXPECT_FALSE(fm2_backend_shutdown(30));
    EXPECT_TRUE(g_state.receiver_cleanup_needed);
}

TEST_F(HalShutdownTest, ShutdownWithoutReceiverStartupDoesNotCallVendor) {
    g_state.initialized = false;
    g_state.enabled = false;
    g_state.receiver_cleanup_needed = false;
    g_state.vendor = nullptr;
    EXPECT_TRUE(fm2_backend_shutdown(0));
    EXPECT_EQ(off_requests, 0);
}

TEST_F(HalShutdownTest, FailedOffCanBeRetriedDuringShutdown) {
    reject_off = true;
    EXPECT_FALSE(fm2_backend_disable());
    EXPECT_FALSE(g_state.disable_pending);
    reject_off = false;
    CompleteLater();
    EXPECT_TRUE(fm2_backend_shutdown(500));
    EXPECT_EQ(off_requests, 2);
}

TEST_F(HalShutdownTest, AdapterWaitsAfterEarlierDisableWithoutExplicitInit) {
    Backend *backend = create_hal_backend();
    ASSERT_TRUE(backend->disable());
    CompleteLater();
    backend->shutdown();
    EXPECT_FALSE(g_state.receiver_cleanup_needed);
    EXPECT_EQ(off_requests, 1);
}

TEST_F(HalShutdownTest, AdapterEnableWithoutExplicitInitReceivesCleanup) {
    g_state.enabled = false;
    g_state.receiver_cleanup_needed = false;
    StartupConfig config;
    config.frequency_khz = 87500;
    config.spacing_khz = 100;
    Backend *backend = create_hal_backend();
    ASSERT_TRUE(backend->enable(config));
    EXPECT_TRUE(g_state.receiver_cleanup_needed);
    CompleteLater();
    backend->shutdown();
    EXPECT_FALSE(g_state.receiver_cleanup_needed);
    EXPECT_EQ(off_requests, 1);
}

TEST_F(HalShutdownTest, AdapterWaitsForEarlierEnableRollback) {
    g_state.enabled = false;
    g_state.receiver_cleanup_needed = false;
    reject_tune = true;
    StartupConfig config;
    config.frequency_khz = 87500;
    config.spacing_khz = 100;
    Backend *backend = create_hal_backend();
    ASSERT_FALSE(backend->enable(config));
    EXPECT_TRUE(g_state.disable_pending);
    EXPECT_EQ(off_requests, 1);
    CompleteLater();
    backend->shutdown();
    EXPECT_FALSE(g_state.receiver_cleanup_needed);
    EXPECT_EQ(off_requests, 1);
}

}  // namespace
