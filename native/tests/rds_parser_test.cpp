#include "rds_parser.h"

#include <gtest/gtest.h>
#include <algorithm>
#include <string>
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

TEST(RdsParserTest, TrimsRadioTextTerminatorAndVendorPadding) {
    std::vector<unsigned char> payload(5 + 60, 0xff);
    payload[0] = 60;
    payload[1] = 10;
    payload[2] = 0x77;
    payload[3] = 0x34;
    payload[4] = 1;
    const char text[] = "Radio_Kek 90.0 FM";
    std::copy(text, text + sizeof(text) - 1, payload.begin() + 5);

    RdsTextPayload parsed;
    ASSERT_TRUE(parse_rds_text_payload(
            payload.data(),
            payload.size(),
            RdsTextPayloadKind::kRadioText,
            64,
            &parsed
    ));
    EXPECT_EQ(parsed.text_len, 17);
    EXPECT_STREQ(parsed.text, "Radio_Kek 90.0 FM");

    payload[5 + 9] = '\r';
    ASSERT_TRUE(parse_rds_text_payload(
            payload.data(),
            payload.size(),
            RdsTextPayloadKind::kRadioText,
            64,
            &parsed
    ));
    EXPECT_EQ(parsed.text_len, 9);
    EXPECT_STREQ(parsed.text, "Radio_Kek");
}

TEST(RdsParserTest, ConvertsEbuLatinHighBytes) {
    const unsigned char ps_payload[] = {
            1, 0x01, 0x00, 0x01, 0x00,
            0x82, ' ', 0x99, ' ', 0xA9, ' ', 'F', 'M',
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            ps_payload,
            sizeof(ps_payload),
            RdsTextPayloadKind::kProgramService,
            255,
            &parsed
    ));
    // 0x82='é' (C3 A9), 0x99='ü' (C3 BC), 0xA9='€' (E2 82 AC).
    EXPECT_EQ(std::string(parsed.text), std::string("é ü € FM"));
    EXPECT_EQ(parsed.text_len, 12);
    EXPECT_EQ(parsed.raw_text_len, 8);
    EXPECT_EQ(parsed.raw_text[0], 0x82);

    const unsigned char rt_payload[] = {
            4, 0x01, 0x00, 0x01, 0x00,
            'R', 0x82, 'o', 0x9A,
    };

    EXPECT_TRUE(parse_rds_text_payload(
            rt_payload,
            sizeof(rt_payload),
            RdsTextPayloadKind::kRadioText,
            255,
            &parsed
    ));
    // 0x82='é', 0x9A='ñ'.
    EXPECT_EQ(std::string(parsed.text), std::string("Réoñ"));
    EXPECT_EQ(parsed.text_len, 6);
    EXPECT_EQ(parsed.raw_text_len, 4);
}

TEST(RdsParserTest, ConvertsEbuLatinAsciiRemaps) {
    const unsigned char payload[] = {
            1, 0x01, 0x00, 0x01, 0x00,
            0x24, 0x5E, 0x60, 0x7E, 'A', 'B', 'C', 'D',
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kProgramService,
            255,
            &parsed
    ));
    // 0x24='¤', 0x5E='―', 0x60='‖', 0x7E='¯' in the RDS G0 set.
    EXPECT_EQ(std::string(parsed.text), std::string("¤―‖¯ABCD"));
    EXPECT_EQ(parsed.text_len, 14);
}

TEST(RdsParserTest, ConvertsEbuLatinLowByteAliases) {
    const unsigned char payload[] = {
            2, 0x01, 0x00, 0x01, 0x00,
            0x0B, 0x0A,
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kRadioText,
            255,
            &parsed
    ));
    // 0x00-0x1F are low-byte aliases of 0x80-0x9F (high-bit-stripped encoders),
    // except 0x0A (LF '\n') and 0x0D (CR '\r').
    // 0x0B -> alias of 0x8B = 'Ç', 0x0A -> preserved line feed '\n'.
    EXPECT_EQ(std::string(parsed.text), std::string("Ç\n"));
    EXPECT_EQ(parsed.text_len, 3);
}

TEST(RdsParserTest, PreservesLineFeedInRadioText) {
    const unsigned char payload[] = {
            12, 0x01, 0x00, 0x01, 0x00,
            'A', 'r', 't', 'i', 's', 't', 0x0A, 'T', 'i', 't', 'l', 'e',
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kRadioText,
            255,
            &parsed
    ));
    EXPECT_EQ(std::string(parsed.text), std::string("Artist\nTitle"));
}

TEST(RdsParserTest, TerminatorTrimRunsBeforeConversion) {
    // '\r' terminates the RT and trailing 0xff vendor padding is trimmed on RAW
    // bytes; the conversion runs afterwards, so 0xff is never seen as a UTF-8
    // lead byte.
    std::vector<unsigned char> payload(5 + 8, 0);
    payload[0] = 8;
    payload[1] = 0x01;
    payload[2] = 0x00;
    payload[3] = 0x01;
    payload[5] = 'A';
    payload[6] = 0x82;
    payload[7] = '\r';
    payload[8] = 0x99;
    payload[9] = 0xff;
    payload[10] = 0xff;
    payload[11] = 0xff;
    payload[12] = 0xff;

    RdsTextPayload parsed;
    ASSERT_TRUE(parse_rds_text_payload(
            payload.data(),
            payload.size(),
            RdsTextPayloadKind::kRadioText,
            255,
            &parsed
    ));
    EXPECT_EQ(parsed.raw_text_len, 2);
    EXPECT_EQ(std::string(parsed.text), std::string("Aé"));
    EXPECT_EQ(parsed.text_len, 3);
}

TEST(RdsParserTest, CapsConvertedOutputBytes) {
    // 64 raw characters, each converting to a 3-byte UTF-8 sequence (€).
    std::vector<unsigned char> payload(5 + 64, 0xA9);
    payload[0] = 64;
    payload[1] = 0x01;
    payload[2] = 0x00;
    payload[3] = 0x01;

    RdsTextPayload parsed;
    ASSERT_TRUE(parse_rds_text_payload(
            payload.data(),
            payload.size(),
            RdsTextPayloadKind::kRadioText,
            255,
            &parsed
    ));
    EXPECT_EQ(parsed.raw_text_len, 64);
    EXPECT_EQ(parsed.text_len, 192);
    EXPECT_EQ(parsed.text[192], '\0');

    // Clamped by max_text_len (an OUTPUT byte limit): 33 x 3 = 99 bytes fit.
    ASSERT_TRUE(parse_rds_text_payload(
            payload.data(),
            payload.size(),
            RdsTextPayloadKind::kRadioText,
            100,
            &parsed
    ));
    EXPECT_EQ(parsed.text_len, 99);
    EXPECT_EQ(parsed.text[99], '\0');
}

TEST(RdsParserTest, ConvertsEbuLatinRareCodePoints) {
    // Positions that are easy to mistranscribe; guard the table against
    // regressions: 0x9D='ǧ', 0xA4='Ǧ', 0xDF='ŀ', and the 0x0E alias of
    // 0x8E = '¡' (the alias row shifts around the 0x0D terminator exception).
    const unsigned char payload[] = {
            4, 0x01, 0x00, 0x01, 0x00,
            0x9D, 0xA4, 0xDF, 0x0E,
    };
    RdsTextPayload parsed;

    EXPECT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kRadioText,
            255,
            &parsed
    ));
    EXPECT_EQ(std::string(parsed.text), std::string("ǧǦŀ¡"));
    EXPECT_EQ(parsed.text_len, 8);
}

TEST(RdsParserTest, TrimsRadioTextNullPadding) {
    // Stations that pad unused RT with NULs instead of 0xff/spaces: trailing
    // 0x00 is padding on raw bytes, so it must not surface as 'a'-with-acute
    // low-byte-alias characters after the conversion.
    std::vector<unsigned char> payload(5 + 12, 0x00);
    payload[0] = 12;
    payload[1] = 0x01;
    payload[2] = 0x00;
    payload[3] = 0x01;
    const char text[] = "Hello";
    std::copy(text, text + sizeof(text) - 1, payload.begin() + 5);

    RdsTextPayload parsed;
    ASSERT_TRUE(parse_rds_text_payload(
            payload.data(),
            payload.size(),
            RdsTextPayloadKind::kRadioText,
            255,
            &parsed
    ));
    EXPECT_EQ(parsed.raw_text_len, 5);
    EXPECT_EQ(std::string(parsed.text), std::string("Hello"));
    EXPECT_EQ(parsed.text_len, 5);
}

TEST(RdsParserTest, TrimsProgramServiceNullPaddingButKeepsSpaces) {
    // A broken stack may deliver unused PS block bytes as NULs instead of
    // spaces: trailing NULs are padding and must not surface as 'a'-with-acute
    // alias characters. Trailing SPACES are part of the station name and stay.
    const unsigned char payload[] = {
            1, 0x01, 0x00, 0x01, 0x00,
            'R', 'O', 'C', 'K', 0x00, 0x00, 0x00, 0x00,
    };
    RdsTextPayload parsed;

    ASSERT_TRUE(parse_rds_text_payload(
            payload,
            sizeof(payload),
            RdsTextPayloadKind::kProgramService,
            255,
            &parsed
    ));
    EXPECT_EQ(parsed.raw_text_len, 4);
    EXPECT_EQ(std::string(parsed.text), std::string("ROCK"));

    const unsigned char padded_payload[] = {
            1, 0x01, 0x00, 0x01, 0x00,
            'R', 'A', 'D', 'I', 'O', ' ', ' ', ' ',
    };

    ASSERT_TRUE(parse_rds_text_payload(
            padded_payload,
            sizeof(padded_payload),
            RdsTextPayloadKind::kProgramService,
            255,
            &parsed
    ));
    EXPECT_EQ(parsed.raw_text_len, 8);
    EXPECT_EQ(std::string(parsed.text), std::string("RADIO   "));
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
    EXPECT_EQ(parsed.frequencies_khz[1], 87950);
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

TEST(RdsParserTest, DecodesRegisteredRdsCountries) {
    char country[3];

    ASSERT_TRUE(decode_rds_country_iso(0xe4, 6, country));
    EXPECT_STREQ(country, "UA");
    ASSERT_TRUE(decode_rds_country_iso(0xe0, 7, country));
    EXPECT_STREQ(country, "RU");
    ASSERT_TRUE(decode_rds_country_iso(0xe0, 1, country));
    EXPECT_STREQ(country, "DE");
    ASSERT_TRUE(decode_rds_country_iso(0xf2, 9, country));
    EXPECT_STREQ(country, "JP");
    ASSERT_TRUE(decode_rds_country_iso(0xa2, 10, country));
    EXPECT_STREQ(country, "AR");
}

TEST(RdsParserTest, RejectsUnknownRdsCountries) {
    char country[3] = "XX";

    EXPECT_FALSE(decode_rds_country_iso(0xe0, 14, country));
    EXPECT_STREQ(country, "");
    EXPECT_FALSE(decode_rds_country_iso(0xe0, 0, country));
    EXPECT_FALSE(decode_rds_country_iso(0x00, 7, country));
    EXPECT_FALSE(decode_rds_country_iso(0xe0, 7, nullptr));
}

namespace {

// Helium group 1A callback: [4]=TP, [5]=radio paging codes, [7]=variant, [9]=ECC.
void make_ecc_payload(unsigned char payload[12], unsigned char ta_tp, unsigned char variant, unsigned char ecc) {
    payload[0] = 12;
    payload[1] = 0x02;  // PTY
    payload[2] = 0x61;  // PI 0x6123 -> country nibble 6 (UA row at ecc 0xe4)
    payload[3] = 0x23;
    payload[4] = ta_tp;
    payload[5] = 0x00;
    payload[6] = 0x00;
    payload[7] = variant;
    payload[8] = 0x00;
    payload[9] = ecc;
    payload[10] = 0x12;
    payload[11] = 0x34;
}

}  // namespace

TEST(RdsParserTest, EccParsesWithTpHeaderByte) {
    // Issue #102: the old 16-bit check included TP and rejected this packet as 0x0100.
    unsigned char payload[12];
    make_ecc_payload(payload, 0x01, 0x00, 0xe4);

    char country[3] = "XX";
    ASSERT_TRUE(parse_rds_ecc_payload(payload, 12, country));
    EXPECT_STREQ(country, "UA");
}

TEST(RdsParserTest, EccParsesWithoutTpHeaderByte) {
    unsigned char payload[12];
    make_ecc_payload(payload, 0x00, 0x00, 0xe4);

    char country[3] = "XX";
    ASSERT_TRUE(parse_rds_ecc_payload(payload, 12, country));
    EXPECT_STREQ(country, "UA");
}

TEST(RdsParserTest, EccIgnoresNonZeroVariant) {
    unsigned char payload[12];
    make_ecc_payload(payload, 0x01, 0x03, 0xe4);

    char country[3] = "XX";
    EXPECT_FALSE(parse_rds_ecc_payload(payload, 12, country));
}

TEST(RdsParserTest, EccRejectsShortPayload) {
    unsigned char payload[12];
    make_ecc_payload(payload, 0x01, 0x00, 0xe4);

    char country[3] = "XX";
    EXPECT_FALSE(parse_rds_ecc_payload(payload, 11, country));
    EXPECT_FALSE(parse_rds_ecc_payload(nullptr, 12, country));
    EXPECT_FALSE(parse_rds_ecc_payload(payload, 12, nullptr));
}

TEST(RdsParserTest, EccDecodesVariantZeroWithNonzeroPagingCodes) {
    // 90.6 MHz capture. With paging codes 5 and variant 0, PI 0x7850
    // and the transmitted ECC 0xe2 map to TN.
    const unsigned char payload[12] =
            {0x0c, 0x00, 0x78, 0x50, 0x00, 0x05, 0x00, 0x00, 0x00, 0xe2, 0xde, 0x52};

    char country[3] = "XX";
    ASSERT_TRUE(parse_rds_ecc_payload(payload, 12, country));
    EXPECT_STREQ(country, "TN");
}

TEST(RdsParserTest, EccRejectsUnknownCountry) {
    unsigned char payload[12];
    make_ecc_payload(payload, 0x01, 0x00, 0x00);

    char country[3] = "XX";
    EXPECT_FALSE(parse_rds_ecc_payload(payload, 12, country));
    EXPECT_STREQ(country, "");
}

TEST(RdsParserTest, ParsesHalRtPlusPayload) {
    // HAL example capture from helium_backend.cpp:
    // [0]=15, [1]=PTY 7, [2..3]=PI 0x7756, [4]=TP 1, [5]=ODA 0x0c, [6]=0, [7]=1
    // [8..10]=tag1 TITLE (ct=1, start=24, raw_len=25 -> decoded 26)
    // [11..13]=tag2 ARTIST (ct=4, start=0, raw_len=20 -> decoded 21)
    // [14]=padding 0
    const unsigned char payload[] = {
            15, 0x07, 0x77, 0x56, 0x01, 0x0c, 0x00, 0x01,
            0x01, 24, 25,
            0x04, 0, 20,
            0x00,
    };

    RdsRtPlusPayload out;
    ASSERT_TRUE(parse_rds_rt_plus_payload(
            payload,
            sizeof(payload),
            RdsRtPlusKind::kHelium,
            &out
    ));

    EXPECT_FALSE(out.toggle);
    EXPECT_TRUE(out.running);
    EXPECT_FALSE(out.ert);
    EXPECT_EQ(out.tag_count, 2);

    EXPECT_EQ(out.tags[0].content_type, 1);
    EXPECT_EQ(out.tags[0].start, 24);
    EXPECT_EQ(out.tags[0].length, 26);

    EXPECT_EQ(out.tags[1].content_type, 4);
    EXPECT_EQ(out.tags[1].start, 0);
    EXPECT_EQ(out.tags[1].length, 21);
}

TEST(RdsParserTest, HalRtPlusSkipsZeroClassTags) {
    const unsigned char payload[] = {
            15, 0x07, 0x77, 0x56, 0x01, 0x0c, 0x01, 0x00,
            0x00, 0, 0,
            0x04, 0, 20,
            0x00,
    };

    RdsRtPlusPayload out;
    ASSERT_TRUE(parse_rds_rt_plus_payload(
            payload,
            sizeof(payload),
            RdsRtPlusKind::kHelium,
            &out
    ));

    EXPECT_TRUE(out.toggle);
    EXPECT_FALSE(out.running);
    EXPECT_EQ(out.tag_count, 1);
    EXPECT_EQ(out.tags[0].content_type, 4);
    EXPECT_EQ(out.tags[0].start, 0);
    EXPECT_EQ(out.tags[0].length, 21);
}

TEST(RdsParserTest, HalRtPlusRejectsShortPayload) {
    const unsigned char short_payload[14] = {14};
    RdsRtPlusPayload out;

    EXPECT_FALSE(parse_rds_rt_plus_payload(
            short_payload,
            sizeof(short_payload),
            RdsRtPlusKind::kHelium,
            &out
    ));
}

TEST(RdsParserTest, ParsesLegacyRtPlusPayload) {
    // Legacy V4L2 format: [0]=len, [1]=ert, [2..4]=triplet 1, [5..7]=triplet 2
    const unsigned char payload[] = {
            8, 1,
            1, 24, 26,
            4, 0, 21,
    };

    RdsRtPlusPayload out;
    ASSERT_TRUE(parse_rds_rt_plus_payload(
            payload,
            sizeof(payload),
            RdsRtPlusKind::kV4l2Qcom,
            &out
    ));

    EXPECT_FALSE(out.toggle);
    EXPECT_FALSE(out.running);
    EXPECT_TRUE(out.ert);
    EXPECT_EQ(out.tag_count, 2);

    EXPECT_EQ(out.tags[0].content_type, 1);
    EXPECT_EQ(out.tags[0].start, 24);
    EXPECT_EQ(out.tags[0].length, 26);

    EXPECT_EQ(out.tags[1].content_type, 4);
    EXPECT_EQ(out.tags[1].start, 0);
    EXPECT_EQ(out.tags[1].length, 21);
}

TEST(RdsParserTest, LegacyRtPlusSkipsZeroClassTags) {
    const unsigned char payload[] = {
            8, 0,
            0, 0, 0,
            4, 0, 21,
    };

    RdsRtPlusPayload out;
    ASSERT_TRUE(parse_rds_rt_plus_payload(
            payload,
            sizeof(payload),
            RdsRtPlusKind::kV4l2Qcom,
            &out
    ));

    EXPECT_FALSE(out.ert);
    EXPECT_EQ(out.tag_count, 1);
    EXPECT_EQ(out.tags[0].content_type, 4);
    EXPECT_EQ(out.tags[0].start, 0);
    EXPECT_EQ(out.tags[0].length, 21);
}

TEST(RdsParserTest, LegacyRtPlusRejectsShortPayload) {
    const unsigned char short_payload[1] = {1};
    RdsRtPlusPayload out;

    EXPECT_FALSE(parse_rds_rt_plus_payload(
            short_payload,
            sizeof(short_payload),
            RdsRtPlusKind::kV4l2Qcom,
            &out
    ));
}

TEST(RdsParserTest, ReturnsRdsRtPlusContentTypeNames) {
    EXPECT_STREQ(get_rds_rt_plus_content_type_name(1), "ITEM.TITLE");
    EXPECT_STREQ(get_rds_rt_plus_content_type_name(2), "ITEM.ALBUM");
    EXPECT_STREQ(get_rds_rt_plus_content_type_name(4), "ITEM.ARTIST");
    EXPECT_STREQ(get_rds_rt_plus_content_type_name(11), "ITEM.GENRE");
    EXPECT_STREQ(get_rds_rt_plus_content_type_name(29), "INFO.URL");
    EXPECT_STREQ(get_rds_rt_plus_content_type_name(41), "PHONE.HOTLINE");
    EXPECT_STREQ(get_rds_rt_plus_content_type_name(0), "UNKNOWN");
    EXPECT_STREQ(get_rds_rt_plus_content_type_name(64), "UNKNOWN");
    EXPECT_STREQ(get_rds_rt_plus_content_type_name(-1), "UNKNOWN");
}

TEST(RdsParserTest, ParsesV4l2RawRdsEccGroup1a) {
    // Group 1A: PI=0x7202 (cc=7), Block B=0x1000 (type 1, version A),
    // Block C=0x00e0 (variant 0, ECC=0xe0 -> RU), Block D=0x0000.
    const unsigned char raw_blocks[] = {
            0x02, 0x72, 0x00,  // Block A
            0x00, 0x10, 0x01,  // Block B (1A)
            0xe0, 0x00, 0x02,  // Block C (variant=0, ecc=0xe0)
            0x00, 0x00, 0x03,  // Block D
    };

    char country[3] = "";
    EXPECT_TRUE(parse_v4l2_raw_rds_ecc(raw_blocks, sizeof(raw_blocks), country));
    EXPECT_STREQ(country, "RU");
}

TEST(RdsParserTest, RejectsV4l2RawRdsEccWithNonZeroVariantOrBlockError) {
    // Variant 1 in Block C (0x10e0) must be ignored.
    const unsigned char non_zero_variant[] = {
            0x02, 0x72, 0x00,
            0x00, 0x10, 0x01,
            0xe0, 0x10, 0x02,
            0x00, 0x00, 0x03,
    };
    char country[3] = "";
    EXPECT_FALSE(parse_v4l2_raw_rds_ecc(non_zero_variant, sizeof(non_zero_variant), country));

    // Block B marked with V4L2_RDS_BLOCK_ERROR (0x80) must reset the group.
    const unsigned char block_error[] = {
            0x02, 0x72, 0x00,
            0x00, 0x10, 0x81,
            0xe0, 0x00, 0x02,
            0x00, 0x00, 0x03,
    };
    EXPECT_FALSE(parse_v4l2_raw_rds_ecc(block_error, sizeof(block_error), country));
}
