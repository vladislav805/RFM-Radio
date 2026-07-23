#include "ctl_json.h"

#include <gtest/gtest.h>

namespace {

radio_state_patch_t make_empty_patch() {
    radio_state_patch_t patch;

    patch.ps = patch.rt = patch.pi = patch.country = nullptr;

    patch.frequency_khz = patch.pty = patch.af_count = patch.stereo = RADIO_PATCH_ABSENT_INT;
    patch.af_khz = nullptr;

    return patch;
}

} // namespace

TEST(CtlJsonTest, BuildsNativeEvent) {
    EXPECT_EQ(build_native_event_json("enabled"), "{\"type\":\"enabled\"}");
    EXPECT_EQ(build_native_event_json(""), "");
    EXPECT_EQ(build_native_event_json(nullptr), "");
}

TEST(CtlJsonTest, BuildsSearchDone) {
    const int stations[] = {87500, 101700};
    std::string json;

    ASSERT_TRUE(build_search_done_json(stations, 2, &json));
    EXPECT_EQ(json, "{\"type\":\"search_done\",\"stations\":[87500,101700]}");
}

TEST(CtlJsonTest, BuildsEmptySearchDone) {
    std::string json;

    ASSERT_TRUE(build_search_done_json(nullptr, 0, &json));
    EXPECT_EQ(json, "{\"type\":\"search_done\",\"stations\":[]}");
}

TEST(CtlJsonTest, BuildsMaximumScanResultWithinUdpLimit) {
    int stations[64];
    for (int &station : stations) {
        station = 108000;
    }
    std::string json;

    ASSERT_TRUE(build_search_done_json(stations, 64, &json));
    EXPECT_LT(json.size() + 1, 512u);
}

TEST(CtlJsonTest, RejectsInvalidSearchDone) {
    std::string json;

    EXPECT_FALSE(build_search_done_json(nullptr, 1, &json));
    EXPECT_FALSE(build_search_done_json(nullptr, -1, &json));
    EXPECT_FALSE(build_search_done_json(nullptr, 0, nullptr));
}

TEST(CtlJsonTest, BuildsStatePatchWithChangedFieldsOnly) {
    RadioStateJsonCache cache;
    cache.ps = "OLD";
    cache.pi = "7756";
    cache.pty = 7;

    radio_state_patch_t patch = make_empty_patch();
    patch.frequency_khz = 101700;
    patch.ps = "NEW";
    patch.pi = "7756";
    patch.pty = 7;
    patch.stereo = 1;

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"frequency\":101700,\"stereo\":true,\"rds\":{\"ps\":\"NEW\"}}");
}

TEST(CtlJsonTest, EscapesRdsStrings) {
    RadioStateJsonCache cache;
    radio_state_patch_t patch = make_empty_patch();
    patch.ps = "A\"B\\C\nD";

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rds\":{\"ps\":\"A\\\"B\\\\C\\nD\"}}");
}

TEST(CtlJsonTest, BuildsAndClearsCountryPatch) {
    RadioStateJsonCache cache;
    radio_state_patch_t patch = make_empty_patch();
    patch.country = "UA";

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rds\":{\"country\":\"UA\"}}");

    apply_radio_state_patch(&cache, &patch);
    EXPECT_EQ(cache.country, "UA");
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));

    patch.country = "";
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rds\":{\"country\":\"\"}}");
}

TEST(CtlJsonTest, BuildsAfPatch) {
    RadioStateJsonCache cache;
    const int af[] = {107200, 106000, 104900};
    radio_state_patch_t patch = make_empty_patch();
    patch.af_khz = af;
    patch.af_count = 3;

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rds\":{\"af\":[107200,106000,104900]}}");
}

TEST(CtlJsonTest, BuildsMaximumAfPatchWithinUdpLimit) {
    RadioStateJsonCache cache;
    int af[kRadioJsonMaxAfCount];
    for (int &frequency : af) {
        frequency = 108000;
    }

    radio_state_patch_t patch = make_empty_patch();
    patch.af_khz = af;
    patch.af_count = kRadioJsonMaxAfCount;

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_LT(json.size(), static_cast<size_t>(CS_BUF));

    std::string expected = "{\"type\":\"state\",\"rds\":{\"af\":[";
    for (int i = 0; i < kRadioJsonMaxAfCount; ++i) {
        if (i > 0) {
            expected += ',';
        }
        expected += "108000";
    }
    expected += "]}}";
    EXPECT_EQ(json, expected);

    apply_radio_state_patch(&cache, &patch);
    EXPECT_EQ(cache.af_count, kRadioJsonMaxAfCount);
    EXPECT_EQ(cache.af_khz[kRadioJsonMaxAfCount - 1], 108000);
}

TEST(CtlJsonTest, BuildsAfClearPatch) {
    RadioStateJsonCache cache;
    cache.af_count = 2;
    cache.af_khz[0] = 87500;
    cache.af_khz[1] = 101700;

    radio_state_patch_t patch = make_empty_patch();
    patch.af_count = 0;

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rds\":{\"af\":[]}}");
}

TEST(CtlJsonTest, SkipsUnchangedPatch) {
    RadioStateJsonCache cache;
    cache.frequency_khz = 101700;
    cache.ps = "PS";

    radio_state_patch_t patch = make_empty_patch();
    patch.frequency_khz = 101700;
    patch.ps = "PS";

    std::string json = "keep";
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "");
}

TEST(CtlJsonTest, AppliesPatchAfterSuccessfulSend) {
    RadioStateJsonCache cache;
    const int af[] = {87500, 101700};
    radio_state_patch_t patch = make_empty_patch();
    patch.frequency_khz = 101700;
    patch.ps = "PS";
    patch.rt = "RT";
    patch.pi = "7756";
    patch.country = "RU";
    patch.pty = 7;
    patch.af_khz = af;
    patch.af_count = 2;
    patch.stereo = 1;

    apply_radio_state_patch(&cache, &patch);

    EXPECT_EQ(cache.frequency_khz, 101700);
    EXPECT_EQ(cache.ps, "PS");
    EXPECT_EQ(cache.rt, "RT");
    EXPECT_EQ(cache.pi, "7756");
    EXPECT_EQ(cache.country, "RU");
    EXPECT_EQ(cache.pty, 7);
    EXPECT_EQ(cache.af_count, 2);
    EXPECT_EQ(cache.af_khz[0], 87500);
    EXPECT_EQ(cache.af_khz[1], 101700);
    EXPECT_EQ(cache.stereo, 1);
}
