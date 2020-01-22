#pragma clang diagnostic push
#pragma ide diagnostic ignored "hicpp-signed-bitwise"
/**
 * FM Platform specific File
 * Platform specific routines to program the V4L2 driver for FM
 * Copyright (c) 2011, 2013 by Qualcomm Technologies, Inc.  All Rights Reserved.
 * Qualcomm Technologies Proprietary and Confidential.
 * @author rakeshk
 */

#include "fmhalapis.h"
#include <linux/videodev2.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <stdlib.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "fmsrv.h"

//#include <cutils/properties.h>
#ifdef __ANDROID_API__
	#include <sys/system_properties.h>
#include <sys/stat.h>

#else
	#define __system_property_get(x, y)
	#define __system_property_get(x, y)
#endif

#define PROPERTY_VALUE_MAX 256

// Multiplying factor to convert to Radio frequency
#define TUNE_MULT 16000

// Constant to request for Radio Events
#define EVENT_LISTEN 1

// 1000 multiplier
#define MULTIPLE_1000 1000

// Tavaura I2C address
int SLAVE_ADDR = 0x2A;

// Tavaura I2C status register
#define INTSTAT_0 0x0

#define ARRAY_SIZE(a)   (sizeof(a) / sizeof(*a))

/* Debug Macro */
#define FM_DEBUG
#ifdef FM_DEBUG
	#define print(x) printf(x)
	#define print2(x,y) printf(x,y)
	#define print3(x,y,z) printf(x,y,z)
#else
  #define print(x)
	#define print2(x,y)
	#define print3(x,y,z)
#endif

#define TAVARUA_BUF_RT_RDS 2
#define TAVARUA_BUF_PS_RDS 3
#define V4L2_CID_PRIVATE_TAVARUA_STATE         0x08000004
#define V4L2_CID_PRIVATE_TAVARUA_EMPHASIS      0x0800000C
#define V4L2_CID_PRIVATE_TAVARUA_SPACING       0x0800000E
#define V4L2_CID_PRIVATE_TAVARUA_RDS_STD       0x0800000D
#define V4L2_CID_PRIVATE_TAVARUA_REGION        0x08000007
#define V4L2_CID_PRIVATE_TAVARUA_RDSON         0x0800000F
#define V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_PROC 0x08000010
#define V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_MASK 0x08000006
#define V4L2_CID_PRIVATE_TAVARUA_RDSD_BUF      0x08000013
#define V4L2_CID_PRIVATE_TAVARUA_PSALL         0x08000014
#define V4L2_CID_PRIVATE_TAVARUA_ANTENNA       0x08000012
#define V4L2_CID_PRIVATE_TAVARUA_STATE         0x08000004
#define V4L2_CID_PRIVATE_TAVARUA_LP_MODE       0x08000011
#define V4L2_CID_PRIVATE_TAVARUA_SIGNAL_TH     0x08000008
#define V4L2_CID_PRIVATE_TAVARUA_SRCHMODE      0x08000001
#define V4L2_CID_PRIVATE_TAVARUA_SCANDWELL     0x08000002
#define V4L2_CID_PRIVATE_TAVARUA_SRCH_PTY      0x08000009
#define V4L2_CID_PRIVATE_TAVARUA_SRCH_PI       0x0800000A
#define V4L2_CID_PRIVATE_TAVARUA_SRCH_CNT      0x0800000B
#define V4L2_CID_PRIVATE_TAVARUA_SRCHON        0x08000003

// #define V4L2_CID_TUNE_POWER_LEVEL              0x009b0971




char tmp[31];

char* itoa(int val) {
	snprintf(tmp, 31, "%d", val);
	return tmp;
}

enum tavarua_evt_t {
	TAVARUA_EVT_RADIO_READY,
	TAVARUA_EVT_TUNE_SUCC,
	TAVARUA_EVT_SEEK_COMPLETE,
	TAVARUA_EVT_SCAN_NEXT,
	TAVARUA_EVT_NEW_RAW_RDS,
	TAVARUA_EVT_NEW_RT_RDS,
	TAVARUA_EVT_NEW_PS_RDS,
	TAVARUA_EVT_ERROR,
	TAVARUA_EVT_BELOW_TH,
	TAVARUA_EVT_ABOVE_TH,
	TAVARUA_EVT_STEREO,
	TAVARUA_EVT_MONO,
	TAVARUA_EVT_RDS_AVAIL,
	TAVARUA_EVT_RDS_NOT_AVAIL,
	TAVARUA_EVT_NEW_SRCH_LIST,
	TAVARUA_EVT_NEW_AF_LIST,
	TAVARUA_EVT_RADIO_DISABLED
};

static char *qsoc_poweron_path = NULL;
//static char *qsoc_poweroff_path = NULL;

// enum to monitor the Power On status
typedef enum {
	INPROGRESS,
	COMPLETE
} poweron_status;

boolean cmd_queued = FALSE;

// Resource Numbers for Rx/TX
int FM_RX = 1;
int FM_TX = 2;

// Boolean to control the power down sequence
volatile boolean power_down = FALSE;

// V4L2 radio handle
int fd_radio = -1;

// FM asynchronous thread to perform the long running ON
pthread_t fm_interrupt_thread;
pthread_t fm_on_thread;

// Prototype of FM ON thread
fm_cmd_status_type (ftm_on_long_thread)(void *ptr);

// Global state of the FM task
fm_station_params_available fm_global_params;

volatile poweron_status poweron;



/**
 * set_v4l2_ctrl
 * Sets the V4L2 control sent as argument with the requested value and returns the status
 * @return FALSE in failure, TRUE in success
 */
boolean set_v4l2_ctrl(int fd, uint32 id, int32 value) {
	struct v4l2_control control;
	int err;
	control.value = value;
	control.id = id;

	err = ioctl(fd, VIDIOC_S_CTRL, &control);
	if (err < 0) {
		print2("set_v4l2_ctrl failed for control = 0x%x\n", control.id);
		return FALSE;
	}
	return TRUE;
}

/**
 * read_data_from_v4l2
 * Reads the fm_radio handle and updates the FM global configuration based on
 * the interrupt data received
 * @return FALSE in failure, TRUE in success
 */
int read_data_from_v4l2(int fd, const uint8* buf, int index) {
	int err;

	struct v4l2_buffer v4l2_buf;
	memset(&v4l2_buf, 0, sizeof(v4l2_buf));

	//reqbuf.type = V4L2_BUF_TYPE_PRIVATE;
	//reqbuf;
	v4l2_buf.index = index;
	v4l2_buf.type = V4L2_BUF_TYPE_PRIVATE;
	v4l2_buf.memory = V4L2_MEMORY_USERPTR;
	v4l2_buf.m.userptr = (unsigned long) buf;
	v4l2_buf.length = 128;
	err = ioctl(fd, VIDIOC_DQBUF, &v4l2_buf);
	if (err < 0) {
		printf("ioctl failed with error = %d\n", err);
		return -1;
	}
	return v4l2_buf.bytesused;
}

/**
 * Helper routine to read the Program Services data from the V4L2 buffer
 * following a PS event
 * Depends in PS event
 * Updates the Global data strutures PS info entry
 * @return TRUE if success, else FALSE
 */
boolean extract_program_service() {
	uint8 buf[64] = {0};
	int ret;
	ret = read_data_from_v4l2(fd_radio, buf, TAVARUA_BUF_PS_RDS);
	if (ret < 0) {
		return TRUE;
	}
	int num_of_ps = (int) (buf[0] & 0x0F);
	int ps_services_len = ((int) ((num_of_ps * 8) + 5)) - 5;

	fm_global_params.pgm_id = (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF));
	fm_global_params.pgm_type = (int) (buf[1] & 0x1F);
	memset(fm_global_params.pgm_services, 0x0, 96);
	memcpy(fm_global_params.pgm_services, &buf[5], ps_services_len);
	fm_global_params.pgm_services[ps_services_len] = '\0';

	print2("ProgramId: %d\n", fm_global_params.pgm_id);
	print2("ProgramType: %d\n", fm_global_params.pgm_type);
	print2("ProgramServiceName: %s\n", fm_global_params.pgm_services);

	return TRUE;
}

/**
 * extract_radio_text
 * Helper routine to read the Radio text data from the V4L2 buffer
 * following a RT event
 * Depends on RT event
 * Updates the Global data strutures RT info entry
 * @return TRUE if success,else FALSE
 */

boolean extract_radio_text() {
	uint8 buf[128];

	int bytesread = read_data_from_v4l2(fd_radio, buf, TAVARUA_BUF_RT_RDS);
	if (bytesread < 0) {
		return TRUE;
	}
	int radiotext_size = (int) (buf[0] & 0xFF);
	fm_global_params.pgm_id = (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF));
	fm_global_params.pgm_type = (int) (buf[1] & 0x1F);
	memset(fm_global_params.radio_text, 0x0, 64);
	memcpy(fm_global_params.radio_text, &buf[5], radiotext_size);
	//fm_global_params.radio_text[radiotext_size] = '\0';
	printf("RadioText: %s\n", fm_global_params.radio_text);
	return TRUE;
}

/**
 * stationList
 * Helper routine to extract the list of good stations from the V4L2 buffer
 * @return NONE, If list is non empty then it prints the stations available
 */
void stationList (int fd) {
	int freq = 0;
	int i = 0;
	int station_num;
	float real_freq = 0;
	int* stationList;
	uint8 sList[100] = {0};
	int tmpFreqByte1 = 0;
	int tmpFreqByte2 = 0;
	float lowBand;
	struct v4l2_tuner tuner;

	tuner.index = 0;
	ioctl(fd, VIDIOC_G_TUNER, &tuner);
	lowBand = (float) (((tuner.rangelow * 1000) / TUNE_MULT) / 1000.00);

	printf("lowBand %f\n", lowBand);

	if (read_data_from_v4l2(fd, sList, 0) < 0) {
		printf("Data read from v4l2 failed\n");
		return;
	}

	station_num = (int) sList[0];
	stationList = (int*) malloc(sizeof(int) * (station_num + 1));
	printf("station_num: %d\n", station_num);

	if (stationList == NULL) {
		printf("stationList: failed to allocate memory\n");
		return;
	}

	char* res = malloc(sizeof(char) * 4 * station_num);

	for (i = 0; i < station_num; i++) {
		tmpFreqByte1 = sList[i * 2 + 1] & 0xFF;
		tmpFreqByte2 = sList[i * 2 + 2] & 0xFF;
		freq = (tmpFreqByte1 & 0x03) << 8;
		freq |= tmpFreqByte2;
		//printf(" freq: %d\n", freq);
		real_freq = (float) (freq * 0.05) + lowBand;
		//printf(" real_freq: %f\n", real_freq);
		stationList[i] = (int) (real_freq * 1000);
		printf(" stationList: %d\n", stationList[i]);

		sprintf(res, "%s%04d", res, stationList[i] / 100);
	}

	send_interruption_info(EVT_SEARCH_DONE, res);

	free(stationList);
	free(res);
}

/**
 * process_radio_event
 * Helper routine to process the radio event read from the V4L2 and performs
 * the corresponding action.
 * Depends on Radio event
 * Updates the Global data structures info entry like frequency, station
 * available, RDS sync status etc.
 * @return TRUE if success, else FALSE
 */
boolean process_radio_event(uint8 event_buf) {
	struct v4l2_frequency freq;
	boolean ret = TRUE;

	switch (event_buf) {
		case TAVARUA_EVT_RADIO_READY:
			printf("FM enabled\n");
			send_interruption_info(EVT_ENABLED, "enabled");
			break;

		case TAVARUA_EVT_RADIO_DISABLED:
			printf("FM disabled\n");
			close(fd_radio);
			fd_radio = -1;
			pthread_exit(NULL);
			ret = FALSE;
			break;

		case TAVARUA_EVT_TUNE_SUCC:
			freq.type = V4L2_TUNER_RADIO;
			if (ioctl(fd_radio, VIDIOC_G_FREQUENCY, &freq) < 0) {
				return FALSE;
			}
			fm_global_params.current_station_freq = ((freq.frequency * MULTIPLE_1000) / TUNE_MULT);
			strcpy(fm_global_params.pgm_services, "");
			strcpy(fm_global_params.radio_text, "");
			fm_global_params.rssi = 0;
			printf("Event tuned success, frequency: %d\n", fm_global_params.current_station_freq);
			send_interruption_info(EVT_FREQUENCY_SET, itoa(fm_global_params.current_station_freq));
			send_interruption_info(EVT_UPDATE_PS, "");
			send_interruption_info(EVT_UPDATE_RT, "");
			break;

		case TAVARUA_EVT_SEEK_COMPLETE:
			print("Seek Complete\n");
			freq.type = V4L2_TUNER_RADIO;
			if (ioctl(fd_radio, VIDIOC_G_FREQUENCY, &freq) < 0) {
				return FALSE;
			}
			fm_global_params.current_station_freq = ((freq.frequency * MULTIPLE_1000) / TUNE_MULT);
			printf("Seeked to frequency: %d\n", fm_global_params.current_station_freq);
			send_interruption_info(EVT_SEEK_COMPLETE, itoa(fm_global_params.current_station_freq));
			break;

		case TAVARUA_EVT_SCAN_NEXT:
			print("Event Scan next\n");
			freq.type = V4L2_TUNER_RADIO;
			if (ioctl(fd_radio, VIDIOC_G_FREQUENCY, &freq) < 0) {
				return FALSE;
			}
			printf("Scanned frequency: %d\n", ((freq.frequency * MULTIPLE_1000) / TUNE_MULT));
			break;

		case TAVARUA_EVT_NEW_RAW_RDS:
			print("Received Raw RDS info\n");
			break;

		case TAVARUA_EVT_NEW_RT_RDS:
			print("Received RT\n");
			ret = extract_radio_text();
			send_interruption_info(EVT_UPDATE_RT, fm_global_params.radio_text);
			break;

		case TAVARUA_EVT_NEW_PS_RDS:
			print("Received PS\n");
			ret = extract_program_service();
			send_interruption_info(EVT_UPDATE_PS, fm_global_params.pgm_services);
			break;

		case TAVARUA_EVT_ERROR:
			print("Received Error\n");
			break;

		case TAVARUA_EVT_BELOW_TH:
			fm_global_params.service_available = FM_SERVICE_NOT_AVAILABLE;
			break;

		case TAVARUA_EVT_ABOVE_TH:
			fm_global_params.service_available = FM_SERVICE_AVAILABLE;
			break;

		case TAVARUA_EVT_STEREO:
			print("Received Stereo Mode\n");
			fm_global_params.stype = FM_RX_STEREO;
			send_interruption_info(EVT_STEREO, "1");
			break;

		case TAVARUA_EVT_MONO:
			print("Received Mono Mode\n");
			fm_global_params.stype = FM_RX_MONO;
			send_interruption_info(EVT_STEREO, "0");
			break;

		case TAVARUA_EVT_RDS_AVAIL:
			print("Received RDS Available\n");
			fm_global_params.rds_sync_status = FM_RDS_SYNCED;
			break;

		case TAVARUA_EVT_RDS_NOT_AVAIL:
			fm_global_params.rds_sync_status = FM_RDS_NOT_SYNCED;
			break;

		case TAVARUA_EVT_NEW_SRCH_LIST:
			print("Received new search list\n");
			stationList(fd_radio);
			break;

		case TAVARUA_EVT_NEW_AF_LIST:
			print("Received new AF List\n");
			break;
	}
	/**
	 * This logic is applied to ensure the exit of the Event read thread
	 * before the FM Radio control is turned off. This is a temporary fix
	 */
	return ret;
}

/**
 * interrupt_thread
 * Thread to perform a continous read on the radio handle for events
 * @return NIL
 */
void* interrupt_thread(void *ptr) {
	printf("Starting FM event listener\n");
	uint8 buf[128] = {0};
	boolean status = TRUE;

	struct v4l2_tuner tuner;
	tuner.index = 0;
	tuner.signal = 0;

	int i = 0;
	int bytesread = 0;
	int k = 0;
	while (1) {
		bytesread = read_data_from_v4l2(fd_radio, buf, EVENT_LISTEN);
		if (bytesread < 0)
			break;

		if (++k > 3) {
			if (ioctl(fd_radio, VIDIOC_G_TUNER, &tuner) == 0) {
				send_interruption_info(EVT_UPDATE_RSSI, itoa(tuner.signal));
			}
			k = 0;
		}

		for (i = 0; i < bytesread; i++) {
			status = process_radio_event(buf[i]);
			if (status != TRUE) {
				return NULL;
			}
		}
	}
	print("FM listener thread exited\n");
	return NULL;
}

int file_exists(const char* file) { // Return 1 if file, or directory, or device node etc. exists
	struct stat sb;
	return stat(file, &sb) == 0;
}

void wait(int ms) {
  usleep(ms * 1000);
}

/**
 * EnableReceiver
 * PFAL specific routine to enable the FM receiver with the Radio Cfg
 * parameters passed.
 * PLATFORM SPECIFIC DESCRIPTION
 *   Opens the handle to /dev/radio0 V4L2 device and initates a Soc Patch
 *   download, configurs the Init parameters like Band Limit, RDS type,
 *   Frequency Band, and Radio State.
 * DEPENDENCIES
 *   NIL
 * @return FM command status
 */
fm_cmd_status_type EnableReceiver(fm_config_data* radiocfgptr) {
	int err;
	int retval;
	char versionStr[40] = {'\0'};
	struct v4l2_capability cap;

	system("setprop hw.fm.mode normal >/dev/null 2>/dev/null; setprop hw.fm.version 0 >/dev/null 2>/dev/null; setprop ctl.start fm_dl >/dev/null 2>/dev/null");

  if (file_exists("/system/lib/modules/radio-iris-transport.ko")) {
    system("insmod /system/lib/modules/radio-iris-transport.ko >/dev/null 2>/dev/null");
  }

  char value[4] = {0x41, 0x42, 0x43, 0x44};

  int i = 0;
  int init_success = 0;

  for (i = 0; i < 600; i ++) {
    __system_property_get("hw.fm.init", value);
    if (value[0] == '1') {
      init_success = 1;
      break;
    } else {
      wait(10);
    }
  }

  if (init_success) {
    print2("[ OK ] Init success after %d attempts\n", i);
  } else {
    print2("[FAIL] Init failed after %d attempts\n", i);
    return FM_CMD_FAILURE;
  }

  wait(500);



	print("\nEnable Receiver entry\n");

	fd_radio = open("/dev/radio0", O_RDWR | O_NONBLOCK);

	if (fd_radio < 0) {
		print2("EnableReceiver Failed to open = %d\n", fd_radio);
		return FM_CMD_FAILURE;
	}

	wait(700);

	// Read the driver versions
	err = ioctl(fd_radio, VIDIOC_QUERYCAP, &cap);

	printf("VIDIOC_QUERYCAP returns :%d: version: %d\n", err, cap.version);

	if (err >= 0) {
		printf("Driver Version (Same as chip id): %x\n", cap.version);
		// Convert the integer to string
		retval = snprintf(versionStr, sizeof(versionStr), "%d", cap.version);
		if ((retval >= sizeof(versionStr))) {
			close(fd_radio);
			fd_radio = -1;
			return FM_CMD_FAILURE;
		}
		__system_property_set("hw.fm.version", versionStr);
		asprintf(&qsoc_poweron_path, "fm_qsoc_patches %d 0", cap.version);
		if (qsoc_poweron_path != NULL) {
			printf("qsoc_onpath = %s\n", qsoc_poweron_path);
		}
	} else {
		return FM_CMD_FAILURE;
	}



	print("\nOpened Receiver\n");
	return ftm_on_long_thread(radiocfgptr);
}

/**
 * ftm_on_long_thread
 * Helper routine to perform the rest of the FM calibration and SoC Patch
 * download and configuration settings following the opening of radio handle
 * @return NIL
 */
fm_cmd_status_type (ftm_on_long_thread)(void *ptr) {
	int ret = 0;
	struct v4l2_control control;
	struct v4l2_tuner tuner;
	int i, init_success = 0;
	char value[PROPERTY_VALUE_MAX] = {0};
	char transport[PROPERTY_VALUE_MAX] = {0};
	char soc_type[PROPERTY_VALUE_MAX] = {0};

	fm_config_data* radiocfgptr = (fm_config_data*) ptr;

	__system_property_get("ro.qualcomm.bt.hci_transport", transport);
	__system_property_get("qcom.bluetooth.soc", soc_type);

	if (strlen(transport) == 3 && !strncmp("smd", transport, 3)) {
		__system_property_set("hw.fm.mode", "normal");
		__system_property_set("ctl.start", "fm_dl");
		sleep(1);
		for (i = 0; i < 9; i++) {
			__system_property_get("hw.fm.init", value);
			if (strcmp(value, "1") == 0) {
				init_success = 1;
				break;
			} else {
				sleep(1);
			}
		}
		print3("init_success: %d after %d seconds \n", init_success, i);
		if (!init_success) {
			__system_property_set("ctl.stop", "fm_dl");
			// close the fd(power down)
			close(fd_radio);
			fd_radio = -1;
			return FM_CMD_FAILURE;
		}
	} else if (strcmp(soc_type, "rome") != 0) {
		ret = system(qsoc_poweron_path);
		if (ret != 0) {
			print2("EnableReceiver Failed to download patches = %d\n", ret);
			return FM_CMD_FAILURE;
		}
	}

	/**
	 * V4L2_CID_PRIVATE_TAVARUA_STATE
	 * V4L2_CID_PRIVATE_TAVARUA_EMPHASIS
	 * V4L2_CID_PRIVATE_TAVARUA_SPACING
	 * V4L2_CID_PRIVATE_TAVARUA_RDS_STD
	 * V4L2_CID_PRIVATE_TAVARUA_REGION
	 */

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_STATE, FM_RX);
	if (ret == FALSE) {
		print2("EnableReceiver Failed to set Radio State = %d\n", ret);
		close(fd_radio);
		fd_radio = -1;
		return FM_CMD_FAILURE;
	}

	set_v4l2_ctrl(fd_radio, V4L2_CID_TUNE_POWER_LEVEL, 7);

#ifdef FM_DEBUG
	print2("Emphasis: %d\n", radiocfgptr->emphasis);
#endif
	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_EMPHASIS, radiocfgptr->emphasis);
	if (ret == FALSE) {
		print2("EnableReceiver Failed to set Emphasis = %d\n", ret);
		return FM_CMD_FAILURE;
	}

#ifdef FM_DEBUG
	print2("Spacing: %d\n", radiocfgptr->spacing);
#endif
	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SPACING, radiocfgptr->spacing);
	if (ret == FALSE) {
		print2("EnableReceiver Failed to set channel spacing = %d\n", ret);
		return FM_CMD_FAILURE;
	}

#ifdef FM_DEBUG
	print2("RDS system: %d\n", radiocfgptr->rds_system);
#endif
	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDS_STD, radiocfgptr->rds_system);
	if (ret == FALSE) {
		print2("EnableReceiver Failed to set RDS std = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	tuner.index = 0;
	tuner.signal = 0;
	tuner.rangelow = radiocfgptr->bandlimits.lower_limit * (TUNE_MULT / 1000);
	tuner.rangehigh = radiocfgptr->bandlimits.upper_limit * (TUNE_MULT / 1000);
	ret = ioctl(fd_radio, VIDIOC_S_TUNER, &tuner);
	if (ret < 0) {
		print2("EnableReceiver Failed to set Band Limits  = %d\n", ret);
		return FM_CMD_FAILURE;
	}
#ifdef FM_DEBUG
	print2("Band: %d\n", radiocfgptr->band);
#endif
	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_REGION, radiocfgptr->band);
	if (ret == FALSE) {
		print2("EnableReceiver Failed to set Band = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSON, 1);
	if (ret == FALSE) {
		print2("EnableReceiver Failed to set RDS on = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	control.id = V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_PROC;
	ret = ioctl(fd_radio, VIDIOC_G_CTRL, &control);
	if (ret < 0) {
		print2("EnableReceiver Failed to set RDS group  = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	int rdsMask = 23;
	uint8 rds_group_mask = (uint8) control.value;
	int psAllVal = rdsMask & (1 << 4);

	print2("RdsOptions: %x\n", rdsMask);
	rds_group_mask &= 0xC7; // 199

	rds_group_mask |= ((rdsMask & 0x07) << 3); // 255

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_PROC, rds_group_mask); // 255
	if (ret == FALSE) {
		print2("EnableReceiver Failed to set RDS on = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	if (strcmp(soc_type, "rome") == 0) {
		ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_MASK, 1);
		if (ret == FALSE) {
			print("Failed to set RDS GRP MASK\n");
			return FM_CMD_FAILURE;
		}
		ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSD_BUF, 1);
		if (ret == FALSE) {
			print("Failed to set RDS BUF\n");
			return FM_CMD_FAILURE;
		}
	} else {
		ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_PSALL, psAllVal >> 4);
		if (ret == FALSE) {
			print2("EnableReceiver Failed to set RDS on = %d\n", ret);
			return FM_CMD_FAILURE;
		}
	}
	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_ANTENNA, 0);
	if (ret == FALSE) {
		print2("EnableReceiver Failed to set RDS on = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	pthread_create(&fm_interrupt_thread, NULL, interrupt_thread, NULL);

#ifdef FM_DEBUG
	print("\nEnable Receiver exit\n");
#endif

	poweron = COMPLETE;
	return FM_CMD_SUCCESS;

}

/**
 * DisableReceiver
 * PFAL specific routine to disable the FM receiver and free the FM resources
 * PLATFORM SPECIFIC DESCRIPTION
 *   Closes the handle to /dev/radio0 V4L2 device
 * @return FM command status
 */
fm_cmd_status_type DisableReceiver() {
	int ret;

	// Wait till the previous ON sequence has completed
	if (poweron != COMPLETE) {
		printf("FM is already disabled\n");
		return FM_CMD_FAILURE;
	}

#ifdef FM_DEBUG
	print("Disable start\n");
#endif

	printf("Shutting down FM\n");
	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_STATE, 0);
	if (ret == FALSE) {
		print2("DisableReceiver Failed to set FM OFF = %d\n", ret);
		return FM_CMD_FAILURE;
	}

#ifdef FM_DEBUG
	print("Disabled Receiver\n");
#endif

	return FM_CMD_SUCCESS;
}

/**
 * ConfigureReceiver
 * PFAL specific routine to configure the FM receiver with the Radio Cfg
 * parameters passed.
 * PLATFORM SPECIFIC DESCRIPTION
 *   configurs the Init parameters like Band Limit, RDS type,
 *   Frequency Band, and Radio State.
 * @return FM command status
 */
fm_cmd_status_type ConfigureReceiver(fm_config_data* radiocfgptr) {
	int ret = 0;
	struct v4l2_control control;
	struct v4l2_tuner tuner;

#ifdef FM_DEBUG
	print("\nConfigure Receiver entry\n");
#endif

	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_EMPHASIS, radiocfgptr->emphasis);

	if (ret == FALSE) {
		print2("Configure  Failed to set Emphasis = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SPACING, radiocfgptr->spacing);
	if (ret == FALSE) {
		print2("Configure  Failed to set channel spacing  = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDS_STD, radiocfgptr->rds_system);
	if (ret == FALSE) {
		print2("Configure  Failed to set RDS std = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	tuner.index = 0;
	tuner.signal = 0;
	tuner.rangelow = radiocfgptr->bandlimits.lower_limit * (TUNE_MULT / 1000);
	tuner.rangehigh = radiocfgptr->bandlimits.upper_limit * (TUNE_MULT / 1000);
	ret = ioctl(fd_radio, VIDIOC_S_TUNER, &tuner);

	if (ret < 0) {
		print2("Configure  Failed to set Band Limits  = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_REGION, radiocfgptr->band);
	if (ret == FALSE) {
		print2("Configure  Failed to set Band  = %d\n", ret);
		return FM_CMD_FAILURE;
	}

out :
#ifdef FM_DEBUG
	print("\nConfigure Receiver exit\n");
#endif

	return FM_CMD_SUCCESS;
}

/**
 * SetFrequencyReceiver
 * PFAL specific routine to configure the FM receiver's Frequency of reception
 * @return FM command status
 */
fm_cmd_status_type SetFrequencyReceiver(uint32 ulfreq) {
	int err;
	struct v4l2_frequency freq_struct;

#ifdef FM_DEBUG
	print2("\nSetFrequency Receiver entry freq = %d\n", (int) ulfreq);
#endif

	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}

	freq_struct.type = V4L2_TUNER_RADIO;
	freq_struct.frequency = (ulfreq * TUNE_MULT / 1000);

	err = ioctl(fd_radio, VIDIOC_S_FREQUENCY, &freq_struct);
	if (err < 0) {
		return FM_CMD_FAILURE;
	}

#ifdef FM_DEBUG
	print("\nSetFrequency Receiver exit\n");
#endif

	return FM_CMD_SUCCESS;
}

fm_cmd_status_type JumpToFrequencyReceiver(uint32 delta) {
	uint32 frequency = fm_global_params.current_station_freq + delta;

	return SetFrequencyReceiver(frequency);
}

/**
 * SetMuteModeReceiver
 * PFAL specific routine to configure the FM receiver's mute status
 * @return FM command status
 */
fm_cmd_status_type SetMuteModeReceiver(mute_type mutemode) {
	int err, i;
	struct v4l2_control control;
	print2("SetMuteModeReceiver mode = %d\n", mutemode);
	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}

	control.value = mutemode;
	control.id = V4L2_CID_AUDIO_MUTE;

	for (i = 0; i < 3; i++) {
		err = ioctl(fd_radio, VIDIOC_S_CTRL, &control);
		if (err >= 0) {
			print("SetMuteMode Success\n");
			return FM_CMD_SUCCESS;
		}
	}
	print2("Set mute mode ret = %d\n", err);
	return FM_CMD_FAILURE;
}

/**
 * SetStereoModeReceiver
 * PFAL specific routine to configure the FM receiver's Audio mode on the
 * frequency tuned
 * @return FM command status
 */
fm_cmd_status_type SetStereoModeReceiver(stereo_type stereomode) {
	struct v4l2_tuner tuner;
	int err;
	print2("SetStereoModeReceiver stereomode = %d \n", stereomode);

	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}

	tuner.index = 0;
	tuner.type = V4L2_TUNER_RADIO;
	err = ioctl(fd_radio, VIDIOC_G_TUNER, &tuner);
	print3("Get stereo mode ret = %d tuner.audmode = %d\n", err, tuner.audmode);

	if (err < 0) {
		return FM_CMD_FAILURE;
	}

	tuner.audmode = stereomode;
	err = ioctl(fd_radio, VIDIOC_S_TUNER, &tuner);
	print2("Set stereo mode ret = %d\n", err);

	if (err < 0) {
		return FM_CMD_FAILURE;
	}

	print("SetStereoMode Success\n");
	return FM_CMD_SUCCESS;
}

/**
 * GetStationParametersReceiver
 * PFAL specific routine to get the station parameters of the Frequency at
 * which the Radio receiver is tuned
 * @return FM command status
 */
fm_cmd_status_type GetStationParametersReceiver(fm_station_params_available* configparams) {
	int i;
	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}
	configparams->current_station_freq = fm_global_params.current_station_freq;
	configparams->service_available = fm_global_params.service_available;

	struct v4l2_tuner tuner;
	tuner.index = 0;
	tuner.signal = 0;
	if (ioctl(fd_radio, VIDIOC_G_TUNER, &tuner) < 0) {
		return FM_CMD_FAILURE;
	}

	configparams->rssi = tuner.signal;
	configparams->stype = fm_global_params.stype;
	configparams->rds_sync_status = fm_global_params.rds_sync_status;
	configparams->pgm_type = fm_global_params.pgm_type;
	configparams->audmode = tuner.audmode;

	strcpy(configparams->pgm_services, fm_global_params.pgm_services);
	strcpy(configparams->radio_text, fm_global_params.radio_text);

	struct v4l2_control control;
	control.id = V4L2_CID_AUDIO_MUTE;

	for (i = 0; i < 3; ++i) {
		int err = ioctl(fd_radio, VIDIOC_G_CTRL, &control);
		if (err >= 0) {
			configparams->mute_status = control.value;
			return FM_CMD_SUCCESS;
		}
	}

	return FM_CMD_FAILURE;
}

/**
 * SetRdsOptionsReceiver
 * PFAL specific routine to configure the FM receiver's RDS options
 * @return FM command status
 */
fm_cmd_status_type SetRdsOptionsReceiver(fm_rds_options rdsoptions) {
	int ret;
	print("SetRdsOptionsReceiver\n");

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_MASK, rdsoptions.rds_group_mask);

	if (ret == FALSE) {
		print2("SetRdsOptionsReceiver Failed to set RDS group options = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSD_BUF, rdsoptions.rds_group_buffer_size);

	if (ret == FALSE) {
		print2("SetRdsOptionsReceiver Failed to set RDS group options = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	// Change Filter not supported
	print("SetRdsOptionsReceiver<\n");

	return FM_CMD_SUCCESS;
}

/**
 * SetRdsGroupProcReceiver
 * PFAL specific routine to configure the FM receiver's RDS group proc options
 * @return FM command status
 */
fm_cmd_status_type SetRdsGroupProcReceiver(uint32 rdsgroupoptions) {
	int ret;
	print("SetRdsGroupProcReceiver\n");
	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_PROC, rdsgroupoptions);
	if (ret == FALSE) {
		print2("SetRdsGroupProcReceiver Failed to set RDS proc = %d\n", ret);
		return FM_CMD_FAILURE;
	}

	print("SetRdsGroupProcReceiver<\n");
	return FM_CMD_SUCCESS;
}


/**
 * SetPowerModeReceiver
 * PFAL specific routine to configure the power mode of FM receiver
 * @return FM command status
 */
fm_cmd_status_type SetPowerModeReceiver(uint8 powermode) {
	struct v4l2_control control;
	int i, err;
	print2("SetPowerModeReceiver mode = %d\n", powermode);

	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}

	control.value = powermode;
	control.id = V4L2_CID_PRIVATE_TAVARUA_LP_MODE;

	for (i = 0; i < 3; i++) {
		err = ioctl(fd_radio, VIDIOC_S_CTRL, &control);
		if (err >= 0) {
			print("SetPowerMode Success\n");
			return FM_CMD_SUCCESS;
		}
	}
	return FM_CMD_SUCCESS;
}

/**
 * SetSignalThresholdReceiver
 * PFAL specific routine to configure the signal threshold of FM receiver
 * @return FM command status
 */
fm_cmd_status_type SetSignalThresholdReceiver(uint8 signalthreshold) {
	struct v4l2_control control;
	int i, err;
	print2("SetSignalThresholdReceiver threshold = %d\n", signalthreshold);
	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}

	control.value = signalthreshold;
	control.id = V4L2_CID_PRIVATE_TAVARUA_SIGNAL_TH;

	for (i = 0; i < 3; i++) {
		err = ioctl(fd_radio, VIDIOC_S_CTRL, &control);
		if (err >= 0) {
			print("SetSignalThresholdReceiver Success\n");
			return FM_CMD_SUCCESS;
		}
	}
	return FM_CMD_SUCCESS;
}

/**
 * SearchStationsReceiver
 * PFAL specific routine to search for stations from the current frequency of
 * FM receiver and print the information on diag
 * @return FM command status
 */
fm_cmd_status_type SearchStationsReceiver(fm_search_stations searchstationsoptions) {
	int err, i;
	struct v4l2_control control;
	struct v4l2_hw_freq_seek hwseek;
	boolean ret;

	hwseek.type = V4L2_TUNER_RADIO;
	print("SearchStationsReceiver\n");

	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCHMODE, searchstationsoptions.search_mode);
	if (ret == FALSE) {
		print("SearchStationsReceiver failed \n");
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SCANDWELL, searchstationsoptions.dwell_period);
	if (ret == FALSE) {
		print("SearchStationsReceiver failed \n");
		return FM_CMD_FAILURE;
	}

	hwseek.seek_upward = searchstationsoptions.search_dir;
	err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hwseek);

	if (err < 0) {
		print("SearchStationsReceiver failed \n");
		return FM_CMD_FAILURE;
	}
	print("SearchStationsReceiver<\n");
	return FM_CMD_SUCCESS;
}


/**
 * PFAL specific routine to search for stations from the current frequency of
 * FM receiver with a specific program type and print the information on diag
 * @return FM command status
 */
fm_cmd_status_type SearchRdsStationsReceiver(fm_search_rds_stations searchrdsstationsoptions) {
	int i, err;
	boolean ret;
	struct v4l2_control control;
	struct v4l2_hw_freq_seek hwseek;

	hwseek.type = V4L2_TUNER_RADIO;
	print("SearchRdsStationsReceiver>\n");

	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCHMODE, searchrdsstationsoptions.search_mode);
	if (ret == FALSE) {
		print("SearchRdsStationsReceiver failed\n");
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SCANDWELL, searchrdsstationsoptions.dwell_period);
	if (ret == FALSE) {
		print("SearchRdsStationsReceiver failed\n");
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCH_PTY, searchrdsstationsoptions.program_type);
	if (ret == FALSE) {
		print("SearchRdsStationsReceiver failed\n");
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCH_PI, searchrdsstationsoptions.program_id);
	if (ret == FALSE) {
		print("SearchRdsStationsReceiver failed\n");
		return FM_CMD_FAILURE;
	}

	hwseek.seek_upward = searchrdsstationsoptions.search_dir;
	err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hwseek);

	if (err < 0) {
		print("SearchRdsStationsReceiver failed\n");
		return FM_CMD_FAILURE;
	}

	print("SearchRdsStationsReceiver<\n");
	return FM_CMD_SUCCESS;
}

/**
 * PFAL specific routine to search for stations with a specific mode of
 * information like WEAK, STRONG, STRONGEST, etc
 * @return FM command status
 */
fm_cmd_status_type SearchStationListReceiver(fm_search_list_stations searchliststationsoptions) {
	int i, err;
	boolean ret;
	struct v4l2_control control;
	struct v4l2_hw_freq_seek hwseek;

	hwseek.type = V4L2_TUNER_RADIO;
	print("SearchStationListReceiver>\n");
	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCHMODE, searchliststationsoptions.search_mode);
	if (ret == FALSE) {
		print("SearchStationListReceiver 1 failed\n");
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCH_CNT, searchliststationsoptions.srch_list_max);
	if (ret == FALSE) {
		print("SearchStationListReceiver 2 failed\n");
		return FM_CMD_FAILURE;
	}

	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCH_PTY, searchliststationsoptions.program_type);
	if (ret == FALSE) {
		print("SearchStationListReceiver 3 failed\n");
		return FM_CMD_FAILURE;
	}

	hwseek.seek_upward = searchliststationsoptions.search_dir;
	err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hwseek);

	if (err < 0) {
		print("SearchStationListReceiver 4 failed\n");
		return FM_CMD_FAILURE;
	}

	print("SearchStationListReceiver<\n");
	return FM_CMD_SUCCESS;
}

/**
 * PFAL specific routine to cancel the ongoing search operation
 * @return FM command status
 */
fm_cmd_status_type CancelSearchReceiver() {
	struct v4l2_control control;
	boolean ret;

	if (fd_radio < 0) {
		return FM_CMD_NO_RESOURCES;
	}
	ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCHON, 0);
	if (ret == FALSE) {
		return FM_CMD_FAILURE;
	}
	return FM_CMD_SUCCESS;
}


#pragma clang diagnostic pop