#include "backend/v4l2_exynos/s610_rds_decoder.h"
#include "backend/v4l2_exynos/s610_fm_controls.h"
#include "rt_plus_test_types.h"

#include <gtest/gtest.h>

namespace {

constexpr Logger kTestLog{"s610-test"};
constexpr uint16_t kPi = 0x7714;
constexpr int kPty = 10;

std::optional<radio_state_patch_t> feed_group(S610RdsDecoder &decoder, uint16_t b, uint16_t c, uint16_t d) {
    decoder.process_block(kPi, kS610RdsBlkA, 0);
    decoder.process_block(b | (kPty << 5), kS610RdsBlkB, 0);
    decoder.process_block(c, kS610RdsBlkC, 0);
    return decoder.process_block(d, kS610RdsBlkD, 0);
}

uint16_t word(const char *pair) {
    return (static_cast<uint8_t>(pair[0]) << 8) | static_cast<uint8_t>(pair[1]);
}

TEST(S610RdsDecoderTest, ConfirmsPiPtyPsAndCountryBeforePublishing) {
    S610RdsDecoder decoder(kTestLog);
    EXPECT_FALSE(feed_group(decoder, 0x0000, 0, 0));
    auto patch = feed_group(decoder, 0x0000, 0, 0);
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->pi, "7714");
    EXPECT_EQ(patch->pty, kPty);

    const char ps[] = "106.3   ";
    for (int cycle = 0; cycle < 2; ++cycle) {
        for (int seg = 0; seg < 4; ++seg) {
            patch = feed_group(decoder, seg, 0, word(ps + seg * 2));
            if (cycle == 0 || seg < 3) {
                EXPECT_FALSE(patch);
            }
        }
    }
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->ps, "106.3");
    EXPECT_EQ(patch->pi, nullptr);

    // Group 1A variant 0 carries ECC in the low byte of block C.
    EXPECT_FALSE(feed_group(decoder, 0x1000, 0x00E0, 0));
    patch = feed_group(decoder, 0x1000, 0x00E0, 0);
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->country, "RU");
    EXPECT_FALSE(feed_group(decoder, 0x1000, 0x00E0, 0));
}

TEST(S610RdsDecoderTest, PublishesItemFieldsUntilTheItemStops) {
    S610RdsDecoder decoder(kTestLog);
    // Announce RT+ on group 11A twice so the PI is confirmed.
    feed_group(decoder, 0x3016, 0, 0x4BD7);
    feed_group(decoder, 0x3016, 0, 0x4BD7);

    const char rt[] = "Artist - Title\r ";
    std::optional<radio_state_patch_t> patch;
    for (int seg = 0; seg < 4; ++seg) {
        patch = feed_group(decoder, 0x2000 | seg, word(rt + seg * 4), word(rt + seg * 4 + 2));
        if (seg < 3) {
            EXPECT_FALSE(patch);
        }
    }
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt, "Artist - Title");

    // ARTIST starts at byte 0, length 6. TITLE starts at byte 9, length 5.
    const uint16_t c = (4 << 13) | ((6 - 1) << 1);
    const uint16_t d = (1 << 11) | (9 << 5) | (5 - 1);
    patch = feed_group(decoder, 0xB008, c, d);
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt_plus[kRdsRtpContentTypeArtist], "Artist");
    EXPECT_STREQ(patch->rt_plus[kRdsRtpContentTypeTitle], "Title");
    EXPECT_FALSE(feed_group(decoder, 0xB008, c, d));

    patch = feed_group(decoder, 0x2000, word("FM"), word("\r "));
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt, "FM");
    EXPECT_EQ(patch->rt_plus[kRdsRtpContentTypeTitle], nullptr);

    // Item running goes low while the station continues transmitting the same tag offsets.
    patch = feed_group(decoder, 0xB000, c, d);
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt_plus[kRdsRtpContentTypeArtist], "");
    EXPECT_STREQ(patch->rt_plus[kRdsRtpContentTypeTitle], "");
    EXPECT_EQ(patch->rt_plus[kRdsRtpContentTypeAlbum], nullptr);
}

TEST(S610RdsDecoderTest, DropsDamagedGroupsAndDiscardsPartialGroupAndCandidatesOnReset) {
    S610RdsDecoder decoder(kTestLog);
    // This damaged group cannot count towards the two matching PI readings.
    decoder.process_block(kPi, kS610RdsBlkA, 0);
    decoder.process_block(0, kS610RdsBlkB, 0);
    decoder.process_block(0, kS610RdsBlkC, 1);
    EXPECT_FALSE(decoder.process_block(0, kS610RdsBlkD, 0));
    EXPECT_FALSE(feed_group(decoder, 0, 0, 0));

    decoder.process_block(kPi, kS610RdsBlkA, 0);
    decoder.process_block(0, kS610RdsBlkB, 0);
    decoder.reset();
    EXPECT_FALSE(decoder.process_block(0, kS610RdsBlkD, 0));
    EXPECT_FALSE(feed_group(decoder, 0, 0, 0));
    const auto patch = feed_group(decoder, 0, 0, 0);
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->pi, "7714");
}

TEST(S610RdsDecoderTest, WaitsForRadioTextFromTheNewStationAfterReset) {
    S610RdsDecoder decoder(kTestLog);
    feed_group(decoder, 0x3016, 0, 0x4BD7);
    feed_group(decoder, 0x3016, 0, 0x4BD7);
    feed_group(decoder, 0x2000, word("FM"), word("\r "));

    // ITEM.TITLE covers the first two bytes of RadioText.
    const uint16_t title = (1 << 13) | (1 << 1);
    auto patch = feed_group(decoder, 0xB008, title, 0);
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt_plus[kRdsRtpContentTypeTitle], "FM");

    decoder.reset();
    feed_group(decoder, 0x3016, 0, 0x4BD7);
    feed_group(decoder, 0x3016, 0, 0x4BD7);
    EXPECT_FALSE(feed_group(decoder, 0xB008, title, 0));
    patch = feed_group(decoder, 0x2000, word("AM"), word("\r "));
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt, "AM");
    // The tag received before the first complete RadioText resolves now.
    EXPECT_STREQ(patch->rt_plus[kRdsRtpContentTypeTitle], "AM");

    // A stopped item clears the published title.
    patch = feed_group(decoder, 0xB000, 0, 0);
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt_plus[kRdsRtpContentTypeTitle], "");
}

TEST(S610RdsDecoderTest, ItemToggleWaitsForNewTextAndStationFieldsSurviveItemStop) {
    S610RdsDecoder decoder(kTestLog);
    feed_group(decoder, 0x3016, 0, 0x4BD7);
    feed_group(decoder, 0x3016, 0, 0x4BD7);
    feed_group(decoder, 0x2000, word("AB"), word("\r "));
    // Tag 1 is ITEM.TITLE (1), tag 2 is DESCRIPTOR.GET_DATA (63).
    auto patch = feed_group(decoder, 0xB008, (1 << 13) | 1, (31 << 11) | (1 << 5));
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt_plus[1], "A");
    EXPECT_STREQ(patch->rt_plus[63], "B");

    patch = feed_group(decoder, 0xB018, 1 << 13, 0);
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt_plus[1], "");
    EXPECT_EQ(patch->rt_plus[63], nullptr);
    patch = feed_group(decoder, 0x2010, word("CD"), word("\r "));
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt_plus[1], "C");

    // Item stops. A weather tag in the same group is still valid.
    patch = feed_group(decoder, 0xB013, (1 << 13) | (1 << 7), 0);
    ASSERT_TRUE(patch);
    EXPECT_STREQ(patch->rt_plus[1], "");
    EXPECT_STREQ(patch->rt_plus[25], "D");
    EXPECT_EQ(patch->rt_plus[63], nullptr);
}

}  // namespace
