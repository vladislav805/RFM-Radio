#include "frequency_format.h"

#include <gtest/gtest.h>

TEST(FrequencyFormatTest, FormatsFrequenciesWithSpaces) {
    const int frequencies[] = {87500, 87950, 90600};
    const std::string output = format_frequency_list_khz(
            frequencies,
            3
    );

    EXPECT_EQ(output, "87500 87950 90600");
    EXPECT_EQ(output.size(), 17u);
}

TEST(FrequencyFormatTest, HandlesEmptyInput) {
    const int frequencies[] = {87500};

    const std::string output = format_frequency_list_khz(
            frequencies,
            0
    );

    EXPECT_EQ(output, "");
    EXPECT_EQ(output.size(), 0u);
}

TEST(FrequencyFormatTest, FormatsFullStringWithoutExternalBuffer) {
    const int frequencies[] = {87500, 87950};

    const std::string output = format_frequency_list_khz(
            frequencies,
            2
    );

    EXPECT_EQ(output, "87500 87950");
    EXPECT_EQ(output.size(), 11u);
}
