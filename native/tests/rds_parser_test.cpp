#include "rds_parser.h"

#include <gtest/gtest.h>
#include <vector>

namespace {

std::vector<unsigned char> make_af_payload(int count) {
    std::vector<unsigned char> payload(7 + count * 4, 0);
    payload[6] = static_cast<unsigned char>(count);

    for (int i = 0; i < count; ++i) {
        const int frequency_khz = 76000 + i * 500;
        const int offset = 7 + i * 4;
        payload[offset] = static_cast<unsigned char>(frequency_khz);
        payload[offset + 1] = static_cast<unsigned char>(frequency_khz >> 8);
        payload[offset + 2] = static_cast<unsigned char>(frequency_khz >> 16);
        payload[offset + 3] = static_cast<unsigned char>(frequency_khz >> 24);
    }

    return payload;
}

}  // namespace

TEST(RdsParserTest, ParsesProgramServicePayload) {
    const unsigned char payload[] = {
            2, 0x9f, 0xab, 0xcd, 0x00,
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            kUnknownRdsPayloadLen,
            RdsTextPayloadKind::kProgramService,
            119,
            &parsed
    ));
    EXPECT_EQ(parsed.block_count, 2);
    EXPECT_EQ(parsed.pty, 31);
    EXPECT_EQ(parsed.pi, 0xabcd);
    EXPECT_EQ(parsed.text_len, 16);
    EXPECT_STREQ(parsed.text, "ABCDEFGHabcdefgh");
}

TEST(RdsParserTest, ParsesProgramServicePayloadWithLowNibbleCount) {
    const unsigned char payload[] = {
            0xf2, 0x23, 0x12, 0x34, 0x00,
            'R', 'A', 'D', 'I', 'O', ' ', ' ', ' ',
            'N', 'A', 'M', 'E', ' ', ' ', ' ', ' ',
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kProgramService,
            95,
            &parsed
    ));
    EXPECT_EQ(parsed.block_count, 2);
    EXPECT_EQ(parsed.pty, 3);
    EXPECT_EQ(parsed.pi, 0x1234);
    EXPECT_EQ(parsed.text_len, 16);
    EXPECT_STREQ(parsed.text, "RADIO   NAME    ");
}

TEST(RdsParserTest, ParsesRadioTextPayload) {
    const unsigned char payload[] = {
            5, 0x9f, 0xab, 0xcd, 0x00,
            'H', 'e', 'l', 'l', 'o',
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kRadioText,
            63,
            &parsed
    ));
    EXPECT_EQ(parsed.block_count, 0);
    EXPECT_EQ(parsed.pty, 31);
    EXPECT_EQ(parsed.pi, 0xabcd);
    EXPECT_EQ(parsed.text_len, 5);
    EXPECT_STREQ(parsed.text, "Hello");
}

TEST(RdsParserTest, AllowsEmptyRadioTextPayload) {
    const unsigned char payload[] = {0, 0x01, 0x00, 0x01, 0x00};
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kRadioText,
            63,
            &parsed
    ));
    EXPECT_EQ(parsed.text_len, 0);
    EXPECT_STREQ(parsed.text, "");
}

TEST(RdsParserTest, LimitsTextToOutputTextLength) {
    const unsigned char payload[] = {
            2, 0x01, 0x00, 0x01, 0x00,
            '1', '2', '3', '4', '5', '6', '7', '8',
            '9', '0', 'a', 'b', 'c', 'd', 'e', 'f',
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kProgramService,
            9,
            &parsed
    ));
    EXPECT_EQ(parsed.text_len, 9);
    EXPECT_STREQ(parsed.text, "123456789");
}

TEST(RdsParserTest, LimitsTextToPayloadLength) {
    const unsigned char payload[] = {
            2, 0x01, 0x00, 0x01, 0x00,
            '1', '2', '3', '4', '5',
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kProgramService,
            119,
            &parsed
    ));
    EXPECT_EQ(parsed.text_len, 5);
    EXPECT_STREQ(parsed.text, "12345");
}

TEST(RdsParserTest, RejectsProgramServiceCountOverMax) {
    unsigned char payload[5 + 9 * 8] = {0};
    payload[0] = 9;
    payload[1] = 0x01;
    payload[2] = 0x00;
    payload[3] = 0x01;
    for (int i = 5; i < static_cast<int>(sizeof(payload)); ++i) {
        payload[i] = 'A';
    }

    RdsTextPayload parsed;

    EXPECT_FALSE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kProgramService,
            119,
            &parsed
    ));
}

TEST(RdsParserTest, RejectsRadioTextLengthOverMax) {
    const unsigned char payload[] = {65, 0x01, 0x00, 0x01, 0x00, 'A'};
    RdsTextPayload parsed;

    EXPECT_FALSE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kRadioText,
            63,
            &parsed
    ));
}

TEST(RdsParserTest, RejectsInvalidPayloads) {
    RdsTextPayload parsed;
    const unsigned char zero_ps_count[] = {0, 0, 0, 0, 0};
    const unsigned char too_short[] = {1, 0, 0, 0};

    EXPECT_FALSE(parse_rds_text_payload(
            zero_ps_count,
            sizeof(zero_ps_count),
            RdsTextPayloadKind::kProgramService,
            119,
            &parsed
    ));
    EXPECT_STREQ(parsed.text, "");
    EXPECT_FALSE(parse_rds_text_payload(
            too_short,
            sizeof(too_short),
            RdsTextPayloadKind::kProgramService,
            119,
            &parsed
    ));
    EXPECT_FALSE(parse_rds_text_payload(
            nullptr,
            kUnknownRdsPayloadLen,
            RdsTextPayloadKind::kProgramService,
            119,
            &parsed
    ));
    EXPECT_FALSE(parse_rds_text_payload(
            zero_ps_count,
            sizeof(zero_ps_count),
            RdsTextPayloadKind::kProgramService,
            119,
            nullptr
    ));
}

TEST(RdsParserTest, ParsesAfPayload) {
    const unsigned char payload[] = {
            0x00, 0x00, 0x00, 0x00,
            0x12, 0x34,
            2,
            0xcc, 0x55, 0x01, 0x00,
            0x10, 0x8b, 0x01, 0x00,
    };
    RdsAfList parsed;

    EXPECT_TRUE(parse_rds_af_payload(
            payload,
            sizeof(payload),
            &parsed
    ));
    EXPECT_EQ(parsed.count, 2);
    EXPECT_EQ(parsed.current_frequency_khz, 0);
    EXPECT_EQ(parsed.frequencies_khz[0], 87500);
    EXPECT_EQ(parsed.frequencies_khz[1], 101136);
}

TEST(RdsParserTest, ParsesAfPayloadWithUnknownLength) {
    const unsigned char payload[] = {
            0xcc, 0x55, 0x01, 0x00,
            0x12, 0x34,
            1,
            0x88, 0xa9, 0x01, 0x00,
    };
    RdsAfList parsed;

    EXPECT_TRUE(parse_rds_af_payload(
            payload,
            kUnknownRdsPayloadLen,
            &parsed
    ));
    EXPECT_EQ(parsed.count, 1);
    EXPECT_EQ(parsed.current_frequency_khz, 87500);
    EXPECT_EQ(parsed.frequencies_khz[0], 108936);
}

TEST(RdsParserTest, ParsesAfPayloadUpToHeliumCapacity) {
    for (const int count : {25, 26, 50}) {
        SCOPED_TRACE(count);
        const std::vector<unsigned char> payload = make_af_payload(count);
        RdsAfList parsed;

        ASSERT_TRUE(parse_rds_af_payload(
                payload.data(),
                static_cast<int>(payload.size()),
                &parsed
        ));
        EXPECT_EQ(parsed.count, count);
        EXPECT_EQ(parsed.frequencies_khz[0], 76000);
        EXPECT_EQ(parsed.frequencies_khz[count - 1], 76000 + (count - 1) * 500);
    }
}

TEST(RdsParserTest, RejectsInvalidAfPayloads) {
    RdsAfList parsed;
    const unsigned char zero_count[] = {0, 0, 0, 0, 0, 0, 0};
    const unsigned char too_many[] = {0, 0, 0, 0, 0, 0, 51};
    const unsigned char too_short[] = {0, 0, 0, 0, 0, 0};
    const unsigned char truncated[] = {
            0, 0, 0, 0, 0, 0, 2,
            0xcc, 0x55, 0x01, 0x00,
    };

    EXPECT_FALSE(parse_rds_af_payload(
            zero_count,
            sizeof(zero_count),
            &parsed
    ));
    EXPECT_EQ(parsed.count, 0);
    EXPECT_FALSE(parse_rds_af_payload(
            too_many,
            sizeof(too_many),
            &parsed
    ));
    EXPECT_FALSE(parse_rds_af_payload(
            too_short,
            sizeof(too_short),
            &parsed
    ));
    EXPECT_FALSE(parse_rds_af_payload(
            truncated,
            sizeof(truncated),
            &parsed
    ));
    EXPECT_FALSE(parse_rds_af_payload(
            nullptr,
            kUnknownRdsPayloadLen,
            &parsed
    ));
    EXPECT_FALSE(parse_rds_af_payload(
            zero_count,
            sizeof(zero_count),
            nullptr
    ));
}

TEST(RdsParserTest, ParsesRelativeSearchListPayload) {
    const unsigned char payload[] = {
            2,
            0x00, 0x00,
            0x00, 0x09,
    };
    RdsSearchList parsed;

    EXPECT_TRUE(parse_rds_search_list_payload(
            payload,
            sizeof(payload),
            RdsSearchListKind::kRelativeOffsets,
            87500,
            &parsed
    ));
    EXPECT_EQ(parsed.count, 2);
    EXPECT_EQ(parsed.reported_count, 2);
    EXPECT_EQ(parsed.frequencies_khz[0], 87500);
    EXPECT_EQ(parsed.frequencies_khz[1], 87950);
}

TEST(RdsParserTest, ParsesAbsoluteSearchListPayload) {
    const unsigned char payload[] = {
            2,
            0x06, 0xd6,
            0x06, 0xdf,
    };
    RdsSearchList parsed;

    EXPECT_TRUE(parse_rds_search_list_payload(
            payload,
            sizeof(payload),
            RdsSearchListKind::kAbsoluteChannels,
            0,
            &parsed
    ));
    EXPECT_EQ(parsed.count, 2);
    EXPECT_EQ(parsed.reported_count, 2);
    EXPECT_EQ(parsed.frequencies_khz[0], 87500);
    EXPECT_EQ(parsed.frequencies_khz[1], 88000);
}

TEST(RdsParserTest, ParsesEmptySearchListPayload) {
    const unsigned char payload[] = {0};
    RdsSearchList parsed;

    EXPECT_TRUE(parse_rds_search_list_payload(
            payload,
            sizeof(payload),
            RdsSearchListKind::kRelativeOffsets,
            87500,
            &parsed
    ));
    EXPECT_EQ(parsed.count, 0);
    EXPECT_EQ(parsed.reported_count, 0);
}

TEST(RdsParserTest, LimitsSearchListToAvailablePayloadBytes) {
    const unsigned char payload[] = {
            2,
            0x06, 0xd6,
    };
    RdsSearchList parsed;

    EXPECT_TRUE(parse_rds_search_list_payload(
            payload,
            sizeof(payload),
            RdsSearchListKind::kAbsoluteChannels,
            0,
            &parsed
    ));
    EXPECT_EQ(parsed.count, 1);
    EXPECT_EQ(parsed.reported_count, 2);
    EXPECT_EQ(parsed.frequencies_khz[0], 87500);
}

TEST(RdsParserTest, LimitsSearchListToCallbackCapacity) {
    unsigned char payload[1 + 21 * 2] = {0};
    payload[0] = 21;
    for (int i = 0; i < 21; ++i) {
        const int raw = 0x06d6 + i;
        payload[1 + i * 2] = static_cast<unsigned char>((raw >> 8) & 0xff);
        payload[2 + i * 2] = static_cast<unsigned char>(raw & 0xff);
    }

    RdsSearchList parsed;

    EXPECT_TRUE(parse_rds_search_list_payload(
            payload,
            sizeof(payload),
            RdsSearchListKind::kAbsoluteChannels,
            0,
            &parsed
    ));
    EXPECT_EQ(parsed.count, kMaxRdsSearchCount);
    EXPECT_EQ(parsed.reported_count, 21);
}

TEST(RdsParserTest, RejectsInvalidSearchListPayloads) {
    RdsSearchList parsed;
    const unsigned char too_short[] = {0};

    EXPECT_FALSE(parse_rds_search_list_payload(
            too_short,
            0,
            RdsSearchListKind::kRelativeOffsets,
            87500,
            &parsed
    ));
    EXPECT_FALSE(parse_rds_search_list_payload(
            nullptr,
            kUnknownRdsPayloadLen,
            RdsSearchListKind::kRelativeOffsets,
            87500,
            &parsed
    ));
    EXPECT_FALSE(parse_rds_search_list_payload(
            too_short,
            0,
            RdsSearchListKind::kRelativeOffsets,
            87500,
            nullptr
    ));
}
