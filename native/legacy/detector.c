#include <string.h>
#include "detector.h"
#include "fmcommon.h"

bool is_smd_transport_layer() {
    char buf[40];

    __system_property_get("ro.qualcomm.bt.hci_transport", buf);

    return strcmp(buf, "smd") == 0;
}

bool is_rome_chip() {
    char buf[40];

    __system_property_get("vendor.bluetooth.soc", buf);

    return strcmp(buf, "rome") == 0;
}
