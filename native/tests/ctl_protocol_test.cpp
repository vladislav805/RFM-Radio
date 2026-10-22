#include "ctl_protocol.h"

#include <gtest/gtest.h>

TEST(CtlProtocolTest, ParsesRequestIdAndCommand) {
    ControlRequest request;

    ASSERT_TRUE(parse_control_request_line("42\tsetfreq 101700", &request));
    EXPECT_EQ(request.id, 42u);
    EXPECT_EQ(request.command, "setfreq 101700");
}

TEST(CtlProtocolTest, RejectsMalformedRequests) {
    ControlRequest request;

    EXPECT_FALSE(parse_control_request_line("", &request));
    EXPECT_FALSE(parse_control_request_line("init", &request));
    EXPECT_FALSE(parse_control_request_line("0\tinit", &request));
    EXPECT_FALSE(parse_control_request_line("x\tinit", &request));
    EXPECT_FALSE(parse_control_request_line("1\t", &request));
    EXPECT_FALSE(parse_control_request_line("1\tinit\nnext", &request));
    EXPECT_FALSE(parse_control_request_line("1\tinit", nullptr));
}
