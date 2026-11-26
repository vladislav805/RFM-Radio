#include "ctl_json.h"
#include "rt_plus_test_types.h"

#include <gtest/gtest.h>

namespace {

radio_state_patch_t make_empty_patch() {
    return radio_state_patch_empty();
}

} // namespace

TEST(CtlJsonTest, SendsExtendedFieldsAndExplicitClearsOnStationReset) {
    RadioStateJsonCache cache;
    auto patch = radio_state_patch_empty();
    patch.rt_plus[25] = "Sunny";
    patch.rt_plus[63] = "fm.example";
    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, R"({"type":"state","rds":{"rt_plus":{"INFO.WEATHER":"Sunny","DESCRIPTOR.GET_DATA":"fm.example"}}})");
    apply_radio_state_patch(&cache, &patch);
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));

    patch = make_station_metadata_reset_patch();
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_NE(json.find(R"("INFO.WEATHER":"")"), std::string::npos);
    EXPECT_NE(json.find(R"("DESCRIPTOR.GET_DATA":"")"), std::string::npos);
    EXPECT_EQ(json.find(R"("RESERVED":)"), std::string::npos);
    apply_radio_state_patch(&cache, &patch);
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));
}

TEST(CtlJsonTest, SendsTrafficProgrammeOnlyWhenTheIndicatorChanges) {
    RadioStateJsonCache cache;
    auto patch = radio_state_patch_empty();
    std::string json;
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));
    patch.tp = 0;
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));
    patch.tp = 1;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, R"({"type":"state","rds":{"tp":true}})");
    apply_radio_state_patch(&cache, &patch);
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));

    patch = make_station_metadata_reset_patch();
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_NE(json.find(R"("tp":false)"), std::string::npos);
    apply_radio_state_patch(&cache, &patch);
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));
}

TEST(CtlJsonTest, BuildsNativeEvent) {
    EXPECT_EQ(build_native_event_json("enabled"), "{\"type\":\"enabled\"}");
    EXPECT_EQ(build_native_event_json(""), "");
    EXPECT_EQ(build_native_event_json(nullptr), "");
}

TEST(CtlJsonTest, BuildsProcessReadyMessage) {
    EXPECT_EQ(build_ready_json(1234), "{\"type\":\"ready\",\"pid\":1234}");
    EXPECT_EQ(
            build_ready_json(1234, "hal_helium"),
            "{\"type\":\"ready\",\"pid\":1234,\"backend\":\"hal_helium\"}"
    );
}

TEST(CtlJsonTest, BuildsCommandResponse) {
    const response_t ok = {0, "ok"};
    const response_t error = {1, "bad \"value\""};

    EXPECT_EQ(build_response_json(7, ok), "{\"type\":\"response\",\"id\":7,\"ok\":true,\"data\":\"ok\"}");
    EXPECT_EQ(build_response_json(8, error), "{\"type\":\"response\",\"id\":8,\"ok\":false,\"data\":\"bad \\\"value\\\"\"}");
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

TEST(CtlJsonTest, BuildsCompactMaximumScanResult) {
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

TEST(CtlJsonTest, EscapesUtf8RdsText) {
    RadioStateJsonCache cache;
    radio_state_patch_t patch = make_empty_patch();
    // Multi-byte UTF-8 (RDS EBU Latin converted PS, e.g. 0x82 = é) must
    // survive the JSON round-trip byte-for-byte without \uFFFD replacement.
    patch.ps = "Caf\xC3\xA9 90.0";

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rds\":{\"ps\":\"Caf\xC3\xA9 90.0\"}}");
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

TEST(CtlJsonTest, BuildsAndClearsRtPlusPatch) {
    RadioStateJsonCache cache;
    radio_state_patch_t patch = make_empty_patch();
    patch.rt_plus[kRdsRtpContentTypeTitle] = "Stil'nyy oranjevyy galstuk";
    patch.rt_plus[kRdsRtpContentTypeArtist] = "Valeriy Syutkin";
    patch.rt_plus[kRdsRtpContentTypeAlbum] = "Radio Hits";

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, R"({"type":"state","rds":{"rt_plus":{"ITEM.TITLE":"Stil'nyy oranjevyy galstuk","ITEM.ALBUM":"Radio Hits","ITEM.ARTIST":"Valeriy Syutkin"}}})");

    apply_radio_state_patch(&cache, &patch);
    EXPECT_EQ(cache.rt_plus[kRdsRtpContentTypeTitle], "Stil'nyy oranjevyy galstuk");
    EXPECT_EQ(cache.rt_plus[kRdsRtpContentTypeArtist], "Valeriy Syutkin");
    EXPECT_EQ(cache.rt_plus[kRdsRtpContentTypeAlbum], "Radio Hits");
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));

    patch.rt_plus[kRdsRtpContentTypeTitle] = "";
    patch.rt_plus[kRdsRtpContentTypeArtist] = "";
    patch.rt_plus[kRdsRtpContentTypeAlbum] = "";
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, R"({"type":"state","rds":{"rt_plus":{"ITEM.TITLE":"","ITEM.ALBUM":"","ITEM.ARTIST":""}}})");
}

TEST(CtlJsonTest, PreservesExistingRtPlusFieldsOnPartialPatch) {
    RadioStateJsonCache cache;
    radio_state_patch_t patch1 = make_empty_patch();
    patch1.rt_plus[kRdsRtpContentTypeTitle] = "Song A";
    patch1.rt_plus[kRdsRtpContentTypeArtist] = "Singer";
    patch1.rt_plus[kRdsRtpContentTypeAlbum] = "Album";

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch1, &json));
    apply_radio_state_patch(&cache, &patch1);

    radio_state_patch_t patch2 = make_empty_patch();
    patch2.rt_plus[kRdsRtpContentTypeTitle] = "Song B";
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch2, &json));
    EXPECT_EQ(json, R"({"type":"state","rds":{"rt_plus":{"ITEM.TITLE":"Song B"}}})");
    apply_radio_state_patch(&cache, &patch2);
    EXPECT_EQ(cache.rt_plus[kRdsRtpContentTypeArtist], "Singer");
    EXPECT_EQ(cache.rt_plus[kRdsRtpContentTypeAlbum], "Album");
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

TEST(CtlJsonTest, BuildsCompactMaximumAfPatch) {
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
    EXPECT_LT(json.size(), 512u);

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

TEST(CtlJsonTest, BuildsAndDeduplicatesRmssiPatch) {
    RadioStateJsonCache cache;
    radio_state_patch_t patch = make_empty_patch();
    patch.rmssi = -66;

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rmssi\":-66}");

    apply_radio_state_patch(&cache, &patch);
    EXPECT_EQ(cache.rmssi, -66);
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));
}

TEST(CtlJsonTest, BuildsAndDeduplicatesSinrPatch) {
    RadioStateJsonCache cache;
    radio_state_patch_t patch = make_empty_patch();
    patch.rmssi = -66;
    patch.sinr = 12;

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rmssi\":-66,\"sinr\":12}");

    apply_radio_state_patch(&cache, &patch);
    EXPECT_EQ(cache.rmssi, -66);
    EXPECT_EQ(cache.sinr, 12);
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));
}

TEST(CtlJsonTest, TreatsMinusOneAsValidRmssi) {
    RadioStateJsonCache cache;
    radio_state_patch_t patch = make_empty_patch();
    patch.rmssi = -1;

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rmssi\":-1}");
}

TEST(CtlJsonTest, FrequencyChangeInvalidatesCachedRmssi) {
    RadioStateJsonCache cache;
    cache.frequency_khz = 101700;
    cache.rmssi = -66;
    radio_state_patch_t patch = make_empty_patch();
    patch.frequency_khz = 99500;

    apply_radio_state_patch(&cache, &patch);

    EXPECT_EQ(cache.frequency_khz, 99500);
    EXPECT_EQ(cache.rmssi, RADIO_PATCH_ABSENT_METRIC);
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

TEST(CtlJsonTest, ClearsActiveMetricOnceAndDeduplicatesRepeats) {
    RadioStateJsonCache cache;
    cache.frequency_khz = 101700;
    cache.sinr = 12;

    radio_state_patch_t patch = make_empty_patch();
    patch.rmssi = -70;
    patch.sinr = RADIO_PATCH_CLEAR_METRIC;

    std::string json;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rmssi\":-70,\"sinr\":null}");

    apply_radio_state_patch(&cache, &patch);
    EXPECT_EQ(cache.rmssi, -70);
    EXPECT_EQ(cache.sinr, RADIO_PATCH_ABSENT_METRIC);

    // Subsequent RMSSI poll with clear SINR must not emit duplicate "sinr":null.
    patch = make_empty_patch();
    patch.rmssi = -68;
    patch.sinr = RADIO_PATCH_CLEAR_METRIC;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rmssi\":-68}");
    apply_radio_state_patch(&cache, &patch);

    // Repeating only a clear for an already cleared/absent metric is suppressed.
    patch = make_empty_patch();
    patch.sinr = RADIO_PATCH_CLEAR_METRIC;
    EXPECT_FALSE(build_radio_state_patch_json(cache, &patch, &json));

    // Switching back to SINR clears the active RMSSI in the same patch.
    patch = make_empty_patch();
    patch.sinr = 15;
    patch.rmssi = RADIO_PATCH_CLEAR_METRIC;
    ASSERT_TRUE(build_radio_state_patch_json(cache, &patch, &json));
    EXPECT_EQ(json, "{\"type\":\"state\",\"rmssi\":null,\"sinr\":15}");
    apply_radio_state_patch(&cache, &patch);
    EXPECT_EQ(cache.rmssi, RADIO_PATCH_ABSENT_METRIC);
    EXPECT_EQ(cache.sinr, 15);
}
