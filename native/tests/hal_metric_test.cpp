#include "backend/hal_helium/hal_helium_backend.h"
#include "backend/qcom_fm_controls.h"
#include "ctl_server.h"

#include <gtest/gtest.h>

#include <string>
#include <unistd.h>
#include <vector>

Backend *create_hal_helium_backend();

namespace {

// Captures JSON messages written to STDOUT_FILENO during a test scope.
class StdoutCapture {
public:
    StdoutCapture() {
        if (pipe(pipe_fds_) == 0) {
            saved_stdout_ = dup(STDOUT_FILENO);
            if (saved_stdout_ >= 0) {
                dup2(pipe_fds_[1], STDOUT_FILENO);
            }
        }
    }

    ~StdoutCapture() {
        restore();
        if (pipe_fds_[0] >= 0) {
            close(pipe_fds_[0]);
            pipe_fds_[0] = -1;
        }
    }

    std::string read_all() {
        restore();
        std::string output;
        if (pipe_fds_[0] >= 0) {
            char buffer[512];
            ssize_t n = 0;
            while ((n = read(pipe_fds_[0], buffer, sizeof(buffer))) > 0) {
                output.append(buffer, static_cast<size_t>(n));
            }
        }
        return output;
    }

private:
    void restore() {
        if (saved_stdout_ >= 0) {
            dup2(saved_stdout_, STDOUT_FILENO);
            close(saved_stdout_);
            saved_stdout_ = -1;
        }
        if (pipe_fds_[1] >= 0) {
            close(pipe_fds_[1]);
            pipe_fds_[1] = -1;
        }
    }

    int pipe_fds_[2] = {-1, -1};
    int saved_stdout_ = -1;
};

std::vector<std::string> split_lines(const std::string &text) {
    std::vector<std::string> lines;
    size_t start = 0;
    while (start < text.size()) {
        size_t end = text.find('\n', start);
        if (end == std::string::npos) {
            lines.push_back(text.substr(start));
            break;
        }
        lines.push_back(text.substr(start, end - start));
        start = end + 1;
    }
    return lines;
}

bool reject_sinr_ctrl = false;
int sinr_get_calls = 0;
int rmssi_get_calls = 0;

int fake_metric_set(int, int) {
    return 0;
}

int fake_metric_get(int id, int *value) {
    if (id == kV4l2CtrlIrisSinr) {
        ++sinr_get_calls;
        if (reject_sinr_ctrl) {
            return -1;
        }
        return 0;
    }
    if (id == kV4l2CtrlIrisRmssi) {
        ++rmssi_get_calls;
        return 0;
    }
    if (value != nullptr) {
        *value = 0;
    }
    return 0;
}

fm_interface_t fake_metric_vendor = {nullptr, fake_metric_set, fake_metric_get};

class HalMetricTest : public ::testing::Test {
protected:
    HalHeliumBackend *backend = nullptr;

    void SetUp() override {
        reject_sinr_ctrl = false;
        sinr_get_calls = 0;
        rmssi_get_calls = 0;
        reset_ctl_server_state_for_test();
        backend = static_cast<HalHeliumBackend *>(create_hal_helium_backend());
        backend->reset_for_test(&fake_metric_vendor, true, true, true);
    }

    void TearDown() override {
        backend->reset_for_test(nullptr, false, false, false);
        reset_ctl_server_state_for_test();
    }
};

TEST_F(HalMetricTest, SyncFallbackClearsCachedSinrAndDeduplicatesRepeats) {
    std::string output;
    {
        StdoutCapture capture;
        HalHeliumBackend::tune_cb(101700);
        HalHeliumBackend::fm_get_station_param_cb(12, 0);

        reject_sinr_ctrl = true;
        ASSERT_TRUE(backend->request_signal_metric());
        HalHeliumBackend::fm_get_station_param_cb(static_cast<uint8_t>(-70), 0);

        ASSERT_TRUE(backend->request_signal_metric());
        HalHeliumBackend::fm_get_station_param_cb(static_cast<uint8_t>(-68), 0);
        output = capture.read_all();
    }

    const std::vector<std::string> lines = split_lines(output);
    ASSERT_EQ(lines.size(), 5u) << output;
    EXPECT_EQ(lines[0], "{\"type\":\"state\",\"frequency\":101700,\"rds\":{\"pty\":0,\"af\":[]}}");
    EXPECT_EQ(lines[1], "{\"type\":\"state\",\"sinr\":12}");
    EXPECT_EQ(lines[2], "{\"type\":\"state\",\"sinr\":null}");
    EXPECT_EQ(lines[3], "{\"type\":\"state\",\"rmssi\":-70}");
    EXPECT_EQ(lines[4], "{\"type\":\"state\",\"rmssi\":-68}");
    EXPECT_EQ(sinr_get_calls, 2);
    EXPECT_EQ(rmssi_get_calls, 2);
}

TEST_F(HalMetricTest, AsyncSinrErrorFallsBackToRmssiAndRecoveryClearsStaleRmssi) {
    std::string output;
    {
        StdoutCapture capture;
        HalHeliumBackend::tune_cb(101700);
        HalHeliumBackend::fm_get_station_param_cb(12, 0);

        // Next periodic poll tries SINR, then firmware reports async error -> RMSSI fallback.
        ASSERT_TRUE(backend->request_signal_metric());
        HalHeliumBackend::fm_get_station_param_cb(0, -1);
        HalHeliumBackend::fm_get_station_param_cb(static_cast<uint8_t>(-80), 0);

        // Subsequent poll still tries SINR and recovers on the same station -> clears RMSSI.
        ASSERT_TRUE(backend->request_signal_metric());
        HalHeliumBackend::fm_get_station_param_cb(15, 0);
        output = capture.read_all();
    }

    const std::vector<std::string> lines = split_lines(output);
    ASSERT_EQ(lines.size(), 5u) << output;
    EXPECT_EQ(lines[1], "{\"type\":\"state\",\"sinr\":12}");
    EXPECT_EQ(lines[2], "{\"type\":\"state\",\"sinr\":null}");
    EXPECT_EQ(lines[3], "{\"type\":\"state\",\"rmssi\":-80}");
    EXPECT_EQ(lines[4], "{\"type\":\"state\",\"rmssi\":null,\"sinr\":15}");
}

TEST_F(HalMetricTest, SkippedPollPreservesCapabilityAndDoesNotClearActiveMetric) {
    std::string output;
    {
        StdoutCapture capture;
        HalHeliumBackend::tune_cb(101700);
        HalHeliumBackend::fm_get_station_param_cb(12, 0);

        // Pending request makes subsequent request_signal_metric() return false (kSkipped).
        ASSERT_TRUE(backend->request_signal_metric());
        reject_sinr_ctrl = true;
        EXPECT_FALSE(backend->request_signal_metric());

        // Scan-active also skips without clearing SINR or flipping capability.
        HalHeliumBackend::fm_get_station_param_cb(13, 0);
        backend->state().scan.active = true;
        EXPECT_FALSE(backend->request_signal_metric());
        backend->state().scan.active = false;

        // Re-enable SINR success before unskipped poll: capability stayed intact.
        reject_sinr_ctrl = false;
        ASSERT_TRUE(backend->request_signal_metric());
        HalHeliumBackend::fm_get_station_param_cb(14, 0);
        output = capture.read_all();
    }

    const std::vector<std::string> lines = split_lines(output);
    ASSERT_EQ(lines.size(), 4u) << output;
    EXPECT_EQ(lines[1], "{\"type\":\"state\",\"sinr\":12}");
    EXPECT_EQ(lines[2], "{\"type\":\"state\",\"sinr\":13}");
    EXPECT_EQ(lines[3], "{\"type\":\"state\",\"sinr\":14}");
    EXPECT_EQ(rmssi_get_calls, 0);
}

} // namespace
