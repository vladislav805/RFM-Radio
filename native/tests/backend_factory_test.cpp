#include <gtest/gtest.h>

#include <optional>
#include <string_view>

#include "backend_factory.h"

TEST(BackendFactoryTest, AllowsQualcommV4l2FamilyNames) {
    EXPECT_TRUE(is_qualcomm_v4l2_driver_name("radio-iris"));
    EXPECT_TRUE(is_qualcomm_v4l2_driver_name("radio-tavarua"));
    EXPECT_TRUE(is_qualcomm_v4l2_driver_name("RADIO-IRIS\n"));
    EXPECT_TRUE(is_qualcomm_v4l2_driver_name("qcom-tavarua-fm"));

    EXPECT_EQ(classify_backend_kind("radio-iris", true, false), BackendKind::kV4l2Qcom);
    EXPECT_EQ(classify_backend_kind("radio-tavarua", true, true), BackendKind::kV4l2Qcom);
}

TEST(BackendFactoryTest, RejectsNonQualcommOrEmptyV4l2Names) {
    EXPECT_FALSE(is_qualcomm_v4l2_driver_name("s610"));
    EXPECT_FALSE(is_qualcomm_v4l2_driver_name("si4709"));
    EXPECT_FALSE(is_qualcomm_v4l2_driver_name(""));

    EXPECT_EQ(classify_backend_kind("s610", true, false), BackendKind::kNone);
    EXPECT_EQ(classify_backend_kind("si4709", true, false), BackendKind::kNone);
    EXPECT_EQ(classify_backend_kind("", true, false), BackendKind::kNone);

    // Rejected V4L2 driver names fall through to HAL Helium when present.
    EXPECT_EQ(classify_backend_kind("s610", true, true), BackendKind::kHelium);
    EXPECT_EQ(classify_backend_kind("si4709", true, true), BackendKind::kHelium);
    EXPECT_EQ(classify_backend_kind("", true, true), BackendKind::kHelium);
}

TEST(BackendFactoryTest, KeepsLegacyFallbackOnlyWhenSysfsNameIsAbsent) {
    EXPECT_EQ(classify_backend_kind(std::nullopt, true, false), BackendKind::kV4l2Qcom);
    EXPECT_EQ(classify_backend_kind(std::nullopt, false, true), BackendKind::kHelium);
    EXPECT_EQ(classify_backend_kind(std::nullopt, false, false), BackendKind::kNone);
    EXPECT_EQ(classify_backend_kind("radio-iris", false, false), BackendKind::kNone);
}

TEST(BackendFactoryTest, FormatsProtocolBackendNames) {
    EXPECT_STREQ(backend_kind_name(BackendKind::kV4l2Qcom), "v4l2_qcom");
    EXPECT_STREQ(backend_kind_name(BackendKind::kHelium), "hal_helium");
    EXPECT_STREQ(backend_kind_name(BackendKind::kNone), "none");
}
