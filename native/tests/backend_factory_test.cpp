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

TEST(BackendFactoryTest, AllowsSamsungS610DriverName) {
    EXPECT_TRUE(is_samsung_s610_driver_name("s610-radio"));
    EXPECT_TRUE(is_samsung_s610_driver_name("S610-RADIO\n"));
    EXPECT_FALSE(is_samsung_s610_driver_name("radio-iris"));
    EXPECT_FALSE(is_samsung_s610_driver_name(""));

    EXPECT_EQ(classify_backend_kind("s610-radio", true, false), BackendKind::kV4l2Exynos);
    // The V4L2 node wins over a present HAL library.
    EXPECT_EQ(classify_backend_kind("s610-radio", true, true), BackendKind::kV4l2Exynos);
    // Without /dev/radio0 the s610 name is not usable.
    EXPECT_EQ(classify_backend_kind("s610-radio", false, false), BackendKind::kNone);
    EXPECT_EQ(classify_backend_kind("s610-radio", false, true), BackendKind::kHelium);
}

TEST(BackendFactoryTest, RejectsUnknownOrEmptyV4l2Names) {
    EXPECT_FALSE(is_qualcomm_v4l2_driver_name("s610"));
    EXPECT_FALSE(is_qualcomm_v4l2_driver_name("si4709"));
    EXPECT_FALSE(is_qualcomm_v4l2_driver_name(""));

    EXPECT_EQ(classify_backend_kind("si4709", true, false), BackendKind::kNone);
    EXPECT_EQ(classify_backend_kind("", true, false), BackendKind::kNone);

    // Rejected V4L2 driver names fall through to HAL Helium when present.
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
    EXPECT_STREQ(backend_kind_name(BackendKind::kV4l2Exynos), "v4l2_exynos");
    EXPECT_STREQ(backend_kind_name(BackendKind::kHelium), "hal_helium");
    EXPECT_STREQ(backend_kind_name(BackendKind::kNone), "none");
}
