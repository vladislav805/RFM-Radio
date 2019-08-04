/**
 * FTM FM PFAL Header File
 * Function declarations  of the PFAL interfaces for FM.
 *
 * Copyright (c) 2011 by Qualcomm Technologies, Inc.  All Rights Reserved.
 * Qualcomm Technologies Proprietary and Confidential.
 *
 * @authors rakeshk
 */

#include "fmcommon.h"

/**
 * EnableReceiver
 * PFAL specific routine to enable the FM receiver with the Radio Cfg
 * parameters passed.
 * @return FM command status
 */
fm_cmd_status_type EnableReceiver(fm_config_data* radiocfgptr);

/**
 * DisableReceiver
 * PFAL specific routine to disable the FM receiver and free the FM resources
 * @return command status
 */
fm_cmd_status_type DisableReceiver();

/**
 * ConfigureReceiver
 * PFAL specific routine to configure the FM receiver with the Radio Cfg
 * parameters passed.
 * @return FM command status
 */
fm_cmd_status_type ConfigureReceiver(fm_config_data* radiocfgptr);

/**
 * SetFrequencyReceiver
 * PFAL specific routine to configure the FM receiver's Frequency of reception
 * @return FM command status
 */
fm_cmd_status_type SetFrequencyReceiver(uint32 ulfreq);

/**
 * SetMuteModeReceiver
 * PFAL specific routine to configure the FM receiver's mute status
 * @return FM command status
*/
fm_cmd_status_type SetMuteModeReceiver(mute_type mutemode);

/**
 * SetStereoModeReceiver
 * PFAL specific routine to configure the FM receiver's Audio mode on the
 * frequency tuned
 * @return FM command status
 */
fm_cmd_status_type SetStereoModeReceiver(stereo_type stereomode);

/**
 * GetStationParametersReceiver
 * PFAL specific routine to get the station parameters of the Frequency at
 * which the Radio receiver is  tuned
 * @return FM command status
 */
fm_cmd_status_type GetStationParametersReceiver(fm_station_params_available* configparams);

/**
 * SetRdsOptionsReceiver
 * PFAL specific routine to configure the FM receiver's RDS options
 * @return FM command status
 */
fm_cmd_status_type SetRdsOptionsReceiver(fm_rds_options rdsoptions);

/**
 * SetRdsGroupProcReceiver
 * PFAL specific routine to configure the FM receiver's RDS group proc options
 * @return FM command status
 */
fm_cmd_status_type SetRdsGroupProcReceiver(uint32 rdsgroupoptions);

/**
 * SetPowerModeReceiver
 * PFAL specific routine to configure the power mode of FM receiver
 * FM command status
 */
fm_cmd_status_type SetPowerModeReceiver(uint8 powermode);

/**
 * SetSignalThresholdReceiver
 * PFAL specific routine to configure the signal threshold of FM receiver
 * @return FM command status
*/
fm_cmd_status_type SetSignalThresholdReceiver(uint8 signalthreshold);

/**
 * SearchStationsReceiver
 * PFAL specific routine to search for stations from the current frequency of
 * FM receiver and print the information on diag
 * @return FM command status
 */
fm_cmd_status_type SearchStationsReceiver(fm_search_stations searchstationsoptions);


/**
 * SearchRDSStationsReceiver
 * PFAL specific routine to search for stations from the current frequency of
 * FM receiver with a specific program type and print the information on diag
 * @return FM command status
 */
fm_cmd_status_type SearchRdsStationsReceiver(fm_search_rds_stations searchrdsstationsoptions);


/**
 * SearchStationListReceiver
 * PFAL specific routine to search for stations with a specific mode of
 * informaation like WEAK,STRONG,STRONGEST etc
 * @return FM command status
 */
fm_cmd_status_type SearchStationListReceiver(fm_search_list_stations searchliststationsoptions);


/**
 * CancelSearchReceiver
 * PFAL specific routine to cancel the ongoing search operation
 * @return FM command status
 */
fm_cmd_status_type CancelSearchReceiver();
