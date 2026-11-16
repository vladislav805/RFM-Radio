#include <cstring>

#include "detector.h"
#include "fmcommon.h"

bool is_smd_transport_layer() {
    char buf[40] = {0};

    __system_property_get("ro.qualcomm.bt.hci_transport", buf);

    return std::strcmp(buf, "smd") == 0;
}

bool is_rome_chip() {
    char buf[40] = {0};

    __system_property_get("vendor.bluetooth.soc", buf);
    if (std::strcmp(buf, "rome") == 0) {
        return true;
    }

    buf[0] = '\0';
    __system_property_get("qcom.bluetooth.soc", buf);

    return std::strcmp(buf, "rome") == 0;
}
