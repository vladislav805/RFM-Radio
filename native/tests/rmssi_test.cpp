#include "rmssi.h"

#include <gtest/gtest.h>

TEST(RmssiTest, DecodesSignedLowByte) {
    EXPECT_EQ(decode_rmssi(0x00), 0);
    EXPECT_EQ(decode_rmssi(0x7f), 127);
    EXPECT_EQ(decode_rmssi(0x80), -128);
    EXPECT_EQ(decode_rmssi(0xc6), -58);
    EXPECT_EQ(decode_rmssi(0x90), -112);
    EXPECT_EQ(decode_rmssi(0xff), -1);
    EXPECT_EQ(decode_rmssi(0xffc6), -58);
    EXPECT_EQ(decode_rmssi(-58), -58);
}
