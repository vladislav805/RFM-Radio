#include "rds_parser.h"

#include <gtest/gtest.h>

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
