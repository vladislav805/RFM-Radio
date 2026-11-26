#include "rt_plus_context.h"
#include "rt_plus_test_types.h"

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
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeTitle], "");
    context.set_text(text);
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeTitle], "é");
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeArtist], "B");

    context.reset();
    context.set_text(text);
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeArtist], "");
    context.set_tags(tags);
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeTitle], "é");
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeArtist], "B");
}

TEST(RtPlusContextTest, SuccessiveTagPairsMergeFieldsOfTheSameRadioText) {
    RtPlusContext context(kTestLog);
    const RdsTextPayload text{.raw_text = {'A', 'B'}, .raw_text_len = 2};
    RdsRtPlusPayload tags{
            .tags = {{kRdsRtpContentTypeTitle, 0, 1}, {kRdsRtpContentTypeArtist, 1, 1}},
            .tag_count = 2
    };
    context.set_text(text);
    context.set_tags(tags);
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeArtist], "B");

    tags.tag_count = 1;
    context.set_tags(tags);
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeArtist], "B");
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeTitle], "A");

    context.set_tags({});
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeTitle], "");
    context.set_tags(tags);
    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeTitle], "A");
}

TEST(RtPlusContextTest, KeepsBackendLogPrefixAcrossStationReset) {
    RtPlusContext context(kTestLog);
    const RdsRtPlusPayload tags{.tags = {{kRdsRtpContentTypeTitle, 0, 1}}, .tag_count = 1};
    context.set_text({.raw_text = {'A'}, .raw_text_len = 1});
    context.reset();
    context.set_tags(tags);

    EXPECT_EQ(context.fields().values[kRdsRtpContentTypeTitle], "");
    testing::internal::CaptureStderr();
    context.set_text({.raw_text = {'B'}, .raw_text_len = 1});
    const std::string log = testing::internal::GetCapturedStderr();
    EXPECT_EQ(log.find("test-backend/rds"), 0u);
}

TEST(RtPlusContextTest, EvaluatesRtPlusSlices) {
    const unsigned char raw_rt[] = "Valeriy Syutkin - Stil'nyy oranjevyy galstuk";
    const int raw_rt_len = static_cast<int>(sizeof(raw_rt) - 1);
    RtPlusContext context(kTestLog);
    context.set_text(std::span(raw_rt, raw_rt_len));
    context.set_tags({
            .tags = {
                    {4, 0, 15},   // ARTIST: "Valeriy Syutkin" (15 chars)
                    {1, 18, 26},  // TITLE: "Stil'nyy oranjevyy galstuk" (26 chars)
            },
            .tag_count = 2
    });

    const auto slices = context.fields().values;

    EXPECT_EQ(slices[kRdsRtpContentTypeArtist], "Valeriy Syutkin");
    EXPECT_EQ(slices[kRdsRtpContentTypeTitle], "Stil'nyy oranjevyy galstuk");
}

TEST(RtPlusContextTest, EvaluatesRtPlusAlbumSlices) {
    const unsigned char raw_rt[] = "Greatest Hits";
    const int raw_rt_len = static_cast<int>(sizeof(raw_rt) - 1);
    RtPlusContext context(kTestLog);
    context.set_text(std::span(raw_rt, raw_rt_len));
    context.set_tags({
            .tags = {
                    {2, 0, 13},  // ALBUM: "Greatest Hits" (13 chars)
            },
            .tag_count = 1
    });

    const auto slices = context.fields().values;

    EXPECT_EQ(slices[kRdsRtpContentTypeAlbum], "Greatest Hits");
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
    RtPlusContext context(kTestLog);
    context.set_text(std::span(raw_rt, raw_rt_len));
    context.set_tags({
            .tags = {
                    {1, 14, 4},  // TITLE: "Song" starting at raw byte index 14
            },
            .tag_count = 1
    });

    const auto slices = context.fields().values;

    EXPECT_EQ(slices[kRdsRtpContentTypeTitle], "Song");
}

TEST(RtPlusContextTest, EvaluatesRtPlusSlicesClearsWhenInvalidOrOutOfRange) {
    const unsigned char raw_rt[] = "Short RT";
    const int raw_rt_len = static_cast<int>(sizeof(raw_rt) - 1);
    RtPlusContext context(kTestLog);
    context.set_text(std::span(raw_rt, raw_rt_len));
    context.set_tags({
            .tags = {
                    {1, 0, 50},  // TITLE: length 50 exceeds raw_rt_len 8
                    {4, 0, 0},   // ARTIST: invalid length 0
            },
            .tag_count = 2
    });

    const auto slices = context.fields().values;

    EXPECT_EQ(slices[kRdsRtpContentTypeTitle], "");
    EXPECT_EQ(slices[kRdsRtpContentTypeArtist], "");
}

TEST(RtPlusContextTest, VendorItemTagsCanPrecedeUpdatedTextAtTheSameOffsets) {
    RtPlusContext context(kTestLog);
    context.set_text({.raw_text = {'A', 'B'}, .raw_text_len = 2});
    context.set_tags({.tags = {{1, 0, 1}, {4, 1, 1}}, .tag_count = 2});
    context.set_text({.raw_text = {'C', 'D'}, .raw_text_len = 2});
    EXPECT_EQ(context.fields().values[1], "C");
    EXPECT_EQ(context.fields().values[4], "D");
}

TEST(RtPlusContextTest, SwitchingTagSourceClearsFieldsAndKeepsOrdinaryRadioText) {
    RtPlusContext context(kTestLog);
    context.set_text({.raw_text = {'A', 'B'}, .raw_text_len = 2});
    context.set_tags({.tags = {{39, 0, 1}}, .tag_count = 1});
    context.clear_tags();
    EXPECT_TRUE(context.fields().values[39].empty());
    context.set_tags({.tags = {{25, 1, 1}}, .tag_count = 1});
    EXPECT_EQ(context.fields().values[25], "B");
}

TEST(RtPlusContextTest, DecodesAllAssignedAndPrivateFieldsAcrossTagGroups) {
    RtPlusContext context(kTestLog);
    // Several groups can arrive before the first complete RT.
    for (int type = 0; type < 64; type += 2) {
        context.set_tags({
                .tags = {{type, 0, 1}, {type + 1, 1, 2}},
                .tag_count = 2
        });
    }
    context.set_text({.raw_text = {'A', 0x82, 'B'}, .raw_text_len = 3});
    const auto &values = context.fields().values;
    for (int type = 0; type < 64; ++type) {
        if (type == 0 || type == 54 || type == 55) {
            EXPECT_TRUE(values[type].empty()) << type;
        } else {
            EXPECT_EQ(values[type], type % 2 == 0 ? "A" : "éB") << type;
        }
    }
    // The station homepage survives a new RT and can be deleted with a tagged blank.
    context.set_text({.raw_text = {' ', 'C'}, .raw_text_len = 2});
    EXPECT_TRUE(values[kRdsRtpContentTypeTitle].empty());
    EXPECT_EQ(values[39], "éB");
    context.set_tags({.tags = {{39, 0, 1}, {63, 50, 2}}, .tag_count = 2});
    EXPECT_TRUE(values[39].empty());
    EXPECT_EQ(values[63], "éB");
    context.reset();
    EXPECT_TRUE(values[63].empty());
}

}  // namespace
