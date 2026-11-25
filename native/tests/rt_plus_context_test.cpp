#include "rt_plus_context.h"

#include <gtest/gtest.h>
#include <string>

namespace {

constexpr Logger kTestLog{"test-backend"};

TEST(RtPlusContextTest, AcceptsTextAndTagsInEitherOrderAfterStationReset) {
    RtPlusContext context(kTestLog);
    const RdsTextPayload text{.raw_text = {'A', 0x82, 'B'}, .raw_text_len = 3};
    const RdsRtPlusPayload tags{
            .tags = {{kRdsRtpContentTypeTitle, 1, 1}, {kRdsRtpContentTypeArtist, 2, 1}},
            .tag_count = 2
    };

    context.set_tags(tags);
    EXPECT_STREQ(context.slices().title, "");
    context.set_text(text);
    EXPECT_STREQ(context.slices().title, "é");
    EXPECT_STREQ(context.slices().artist, "B");

    context.reset();
    context.set_text(text);
    EXPECT_STREQ(context.slices().artist, "");
    context.set_tags(tags);
    EXPECT_STREQ(context.slices().title, "é");
    EXPECT_STREQ(context.slices().artist, "B");
}

TEST(RtPlusContextTest, ReplacingTagsDropsPreviousSlotsAndRetainsText) {
    RtPlusContext context(kTestLog);
    const RdsTextPayload text{.raw_text = {'A', 'B'}, .raw_text_len = 2};
    RdsRtPlusPayload tags{
            .tags = {{kRdsRtpContentTypeTitle, 0, 1}, {kRdsRtpContentTypeArtist, 1, 1}},
            .tag_count = 2
    };
    context.set_text(text);
    context.set_tags(tags);
    EXPECT_STREQ(context.slices().artist, "B");

    tags.tag_count = 1;
    context.set_tags(tags);
    EXPECT_STREQ(context.slices().artist, "");
    EXPECT_STREQ(context.slices().title, "A");

    context.set_tags({});
    EXPECT_STREQ(context.slices().title, "");
    context.set_tags(tags);
    EXPECT_STREQ(context.slices().title, "A");
}

TEST(RtPlusContextTest, KeepsBackendLogPrefixAcrossStationReset) {
    RtPlusContext context(kTestLog);
    const RdsRtPlusPayload tags{.tags = {{kRdsRtpContentTypeTitle, 0, 1}}, .tag_count = 1};
    context.set_text({.raw_text = {'A'}, .raw_text_len = 1});
    context.reset();
    context.set_tags(tags);

    EXPECT_STREQ(context.slices().title, "");
    testing::internal::CaptureStderr();
    context.log_slices();
    const std::string log = testing::internal::GetCapturedStderr();
    EXPECT_EQ(log.find("test-backend/rds"), 0u);
}

TEST(RtPlusContextTest, EvaluatesRtPlusSlices) {
    const unsigned char raw_rt[] = "Valeriy Syutkin - Stil'nyy oranjevyy galstuk";
    const int raw_rt_len = static_cast<int>(sizeof(raw_rt) - 1);
    const RdsRtPlusTag tags[2] = {
            {4, 0, 15},   // ARTIST: "Valeriy Syutkin" (15 chars)
            {1, 18, 26},  // TITLE: "Stil'nyy oranjevyy galstuk" (26 chars)
    };

    const auto slices = RtPlusContext::extract_slices(std::span(raw_rt, raw_rt_len), tags);

    EXPECT_STREQ(slices.artist, "Valeriy Syutkin");
    EXPECT_STREQ(slices.title, "Stil'nyy oranjevyy galstuk");
}

TEST(RtPlusContextTest, EvaluatesRtPlusAlbumSlices) {
    const unsigned char raw_rt[] = "Greatest Hits";
    const int raw_rt_len = static_cast<int>(sizeof(raw_rt) - 1);
    const RdsRtPlusTag tags[2] = {
            {2, 0, 13},  // ALBUM: "Greatest Hits" (13 chars)
            {0, 0, 0},
    };

    const auto slices = RtPlusContext::extract_slices(std::span(raw_rt, raw_rt_len), tags);

    EXPECT_STREQ(slices.album, "Greatest Hits");
}

TEST(RtPlusContextTest, EvaluatesRtPlusSlicesWithMultiByteEbuCharacters) {
    // raw EBU Latin G0 bytes:
    // 0x82 = 'é' (2 UTF-8 bytes: 0xC3 0xA9)
    // 0x8E = '¡' (2 UTF-8 bytes: 0xC2 0xA1)
    // Raw text: "Café ¡Hola! - Song"
    // Byte indices in raw EBU Latin G0:
    //   'C'=0 'a'=1 'f'=2 'é'=3(0x82) ' '=4 '¡'=5(0x8E) 'H'=6 'o'=7 'l'=8 'a'=9 '!'=10
    //   ' '=11 '-'=12 ' '=13
    //   'S'=14 'o'=15 'n'=16 'g'=17
    //
    // Tag for TITLE starts at raw byte 14 ("Song", 4 bytes).
    // Note that after UTF-8 conversion, "Café ¡Hola! - " takes 16 UTF-8 bytes!
    // A naïve UTF-8 substring at offset 14 would cut into "¡Hola!".
    // Slicing the raw EBU buffer at byte offset 14 correctly yields "Song".
    const unsigned char raw_rt[] = {
            'C', 'a', 'f', 0x82, ' ', 0x8E, 'H', 'o', 'l', 'a', '!',
            ' ', '-', ' ',
            'S', 'o', 'n', 'g'
    };
    const int raw_rt_len = static_cast<int>(sizeof(raw_rt));
    const RdsRtPlusTag tags[2] = {
            {1, 14, 4},  // TITLE: "Song" starting at raw byte index 14
            {0, 0, 0},
    };

    const auto slices = RtPlusContext::extract_slices(std::span(raw_rt, raw_rt_len), tags);

    EXPECT_STREQ(slices.title, "Song");
}

TEST(RtPlusContextTest, EvaluatesRtPlusSlicesClearsWhenInvalidOrOutOfRange) {
    const unsigned char raw_rt[] = "Short RT";
    const int raw_rt_len = static_cast<int>(sizeof(raw_rt) - 1);
    const RdsRtPlusTag out_of_bounds_tags[2] = {
            {1, 0, 50},  // TITLE: length 50 exceeds raw_rt_len 8
            {4, 0, 0},   // ARTIST: invalid length 0
    };

    const auto slices = RtPlusContext::extract_slices(std::span(raw_rt, raw_rt_len), out_of_bounds_tags);

    EXPECT_STREQ(slices.title, "");
    EXPECT_STREQ(slices.artist, "");
}

}  // namespace
