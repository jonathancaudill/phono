package com.thelightphone.sdk.ui

sealed class LightIconConfiguration(
    val name: String,
    val darkModeResource: Int,
    val lightModeResource: Int,
)

object LightIcons {
    object ACCEPT : LightIconConfiguration(
        name = "confirm",
        darkModeResource = R.drawable.ic_accept_white,
        lightModeResource = R.drawable.ic_accept_black
    )
    object ADD : LightIconConfiguration(
        name = "Add",
        darkModeResource = R.drawable.ic_add_white,
        lightModeResource = R.drawable.ic_add_black
    )
    object AIRPLANE : LightIconConfiguration(
        name = "airplane",
        darkModeResource = R.drawable.ic_airplane_white,
        lightModeResource = R.drawable.ic_airplane_black
    )
    object ALARM : LightIconConfiguration(
        name = "alarm",
        darkModeResource = R.drawable.ic_alarm_white,
        lightModeResource = R.drawable.ic_alarm_black
    )
    object ARROW_DOWN : LightIconConfiguration(
        name = "arrow pointing down",
        darkModeResource = R.drawable.ic_arrow_down_white,
        lightModeResource = R.drawable.ic_arrow_down_black
    )
    object AUDIO_MESSAGE : LightIconConfiguration(
        name = "audio message",
        darkModeResource = R.drawable.ic_audio_message_white,
        lightModeResource = R.drawable.ic_audio_message_black
    )
    object BACK : LightIconConfiguration(
        name = "back",
        darkModeResource = R.drawable.ic_back_white,
        lightModeResource = R.drawable.ic_back_black
    )
    object BATTERY_ERROR : LightIconConfiguration(
        name = "battery empty",
        darkModeResource = R.drawable.ic_battery_error_white,
        lightModeResource = R.drawable.ic_battery_error_black
    )
    object BATTERY_EMPTY : LightIconConfiguration(
        name = "battery empty",
        darkModeResource = R.drawable.ic_battery_empty_white,
        lightModeResource = R.drawable.ic_battery_empty_black
    )
    object BATTERY_ONE_QUARTER : LightIconConfiguration(
        name = "battery one quarter full",
        darkModeResource = R.drawable.ic_battery_one_quarter_white,
        lightModeResource = R.drawable.ic_battery_one_quarter_black
    )
    object BATTERY_HALF : LightIconConfiguration(
        name = "battery half full",
        darkModeResource = R.drawable.ic_battery_half_white,
        lightModeResource = R.drawable.ic_battery_half_black
    )
    object BATTERY_THREE_QUARTERS : LightIconConfiguration(
        name = "battery three quarters full",
        darkModeResource = R.drawable.ic_battery_three_quarters_white,
        lightModeResource = R.drawable.ic_battery_three_quarters_black
    )
    object BATTERY_FULL : LightIconConfiguration(
        name = "full battery",
        darkModeResource = R.drawable.ic_battery_full_white,
        lightModeResource = R.drawable.ic_battery_full_black
    )
    object BATTERY_ALMOST_FULL : LightIconConfiguration(
        name = "almost full battery",
        darkModeResource = R.drawable.ic_battery_almost_full_white,
        lightModeResource = R.drawable.ic_battery_almost_full_black
    )
    object BATTERY_CHARGING : LightIconConfiguration(
        name = "battery charging",
        darkModeResource = R.drawable.ic_battery_charging_white,
        lightModeResource = R.drawable.ic_battery_charging_black
    )
    object BLUETOOTH : LightIconConfiguration(
        name = "bluetooth",
        darkModeResource = R.drawable.ic_bluetooth_white,
        lightModeResource = R.drawable.ic_bluetooth_black
    )
    object CALL : LightIconConfiguration(
        name = "call",
        darkModeResource = R.drawable.ic_call_white,
        lightModeResource = R.drawable.ic_call_black
    )
    object CALL_MISSED : LightIconConfiguration(
        name = "mute",
        darkModeResource = R.drawable.ic_missed_call_white,
        lightModeResource = R.drawable.ic_missed_call_black
    )
    object CAMERA_BRIGHTNESS : LightIconConfiguration(
        name = "camera brightness",
        darkModeResource = R.drawable.ic_camera_brightness,
        lightModeResource = R.drawable.ic_camera_brightness
    )
    object CAMERA : LightIconConfiguration(
        name = "camera",
        darkModeResource = R.drawable.ic_camera,
        lightModeResource = R.drawable.ic_camera
    )
    object CAMERA_FLASH_ON : LightIconConfiguration(
        name = "camera flash on",
        darkModeResource = R.drawable.ic_camera_flash_on,
        lightModeResource = R.drawable.ic_camera_flash_on
    )
    object CAMERA_FLASH_OFF : LightIconConfiguration(
        name = "camera flash off",
        darkModeResource = R.drawable.ic_camera_flash_off,
        lightModeResource = R.drawable.ic_camera_flash_off
    )
    object CAMERA_FLASH_AUTO : LightIconConfiguration(
        name = "camera flash auto",
        darkModeResource = R.drawable.ic_camera_flash_auto,
        lightModeResource = R.drawable.ic_camera_flash_auto
    )
    object CAMERA_LANDSCAPE : LightIconConfiguration(
        name = "camera landscape",
        darkModeResource = R.drawable.ic_camera_landscape,
        lightModeResource = R.drawable.ic_camera_landscape
    )
    object CAMERA_SETTINGS : LightIconConfiguration(
        name = "camera settings",
        darkModeResource = R.drawable.ic_camera_settings,
        lightModeResource = R.drawable.ic_camera_settings
    )
    object CAMERA_RECORDING : LightIconConfiguration(
        name = "camera recording",
        darkModeResource = R.drawable.ic_camera_recording,
        lightModeResource = R.drawable.ic_camera_recording
    )
    object CAMERA_FOCUS_LOCKING : LightIconConfiguration(
        name = "camera focus locking",
        darkModeResource = R.drawable.ic_camera_focus_locking,
        lightModeResource = R.drawable.ic_camera_focus_locking
    )
    object CAMERA_FOCUS_LOCKED : LightIconConfiguration(
        name = "camera focus locked",
        darkModeResource = R.drawable.ic_camera_focus_locked,
        lightModeResource = R.drawable.ic_camera_focus_locked
    )
    object CE_MARK : LightIconConfiguration(
        name = "ce mark",
        darkModeResource = R.drawable.ic_ce_mark_white,
        lightModeResource = R.drawable.ic_ce_mark_black
    )
    object CIRCLE : LightIconConfiguration(
        name = "circle",
        darkModeResource = R.drawable.ic_circle_white,
        lightModeResource = R.drawable.ic_circle_black
    )
    object CLOSE : LightIconConfiguration(
        name = "close",
        darkModeResource = R.drawable.ic_close_white,
        lightModeResource = R.drawable.ic_close_black
    )
    object COMPOSE_MESSAGE : LightIconConfiguration(
        name = "compose message",
        darkModeResource = R.drawable.ic_compose_white,
        lightModeResource = R.drawable.ic_compose_black
    )
    object PENCIL : LightIconConfiguration(
        name = "pencil",
        darkModeResource = R.drawable.ic_pencil_white,
        lightModeResource = R.drawable.ic_pencil_black
    )
    object DELETE : LightIconConfiguration(
        name = "delete",
        darkModeResource = R.drawable.ic_delete_white,
        lightModeResource = R.drawable.ic_delete_black
    )
    object DENY : LightIconConfiguration(
        name = "deny",
        darkModeResource = R.drawable.ic_deny_white,
        lightModeResource = R.drawable.ic_deny_black
    )
    object DIALPAD : LightIconConfiguration(
        name = "dialpad",
        darkModeResource = R.drawable.ic_dialpad_white,
        lightModeResource = R.drawable.ic_dialpad_black
    )
    object DIRECTIONS_ARRIVAL : LightIconConfiguration(
        name = "arrival",
        darkModeResource = R.drawable.ic_directions_arrival_white,
        lightModeResource = R.drawable.ic_directions_arrival_black
    )
    object DIRECTIONS_LEFT : LightIconConfiguration(
        name = "left turn",
        darkModeResource = R.drawable.ic_directions_left_white,
        lightModeResource = R.drawable.ic_directions_left_black
    )
    object DIRECTIONS_RIGHT : LightIconConfiguration(
        name = "right turn",
        darkModeResource = R.drawable.ic_directions_right_white,
        lightModeResource = R.drawable.ic_directions_right_black
    )
    object DIRECTIONS_SLIGHT_LEFT : LightIconConfiguration(
        name = "slight left turn",
        darkModeResource = R.drawable.ic_directions_slight_left_white,
        lightModeResource = R.drawable.ic_directions_slight_left_black
    )
    object DIRECTIONS_SLIGHT_RIGHT : LightIconConfiguration(
        name = "slight right turn",
        darkModeResource = R.drawable.ic_directions_slight_right_white,
        lightModeResource = R.drawable.ic_directions_slight_right_black
    )
    object DIRECTIONS_MIDDLE_FORK : LightIconConfiguration(
        name = "middle fork",
        darkModeResource = R.drawable.ic_directions_middle_fork_white,
        lightModeResource = R.drawable.ic_directions_middle_fork_black
    )
    object DIRECTIONS_STRAIGHT : LightIconConfiguration(
        name = "continue straight",
        darkModeResource = R.drawable.ic_directions_straight_white,
        lightModeResource = R.drawable.ic_directions_straight_black
    )
    object DIRECTIONS_BUS : LightIconConfiguration(
        name = "bus",
        darkModeResource = R.drawable.ic_directions_bus_white,
        lightModeResource = R.drawable.ic_directions_bus_black
    )
    object DIRECTIONS_SUBWAY : LightIconConfiguration(
        name = "subway",
        darkModeResource = R.drawable.ic_directions_subway_white,
        lightModeResource = R.drawable.ic_directions_subway_black
    )
    object DIRECTIONS_TRAIN : LightIconConfiguration(
        name = "train",
        darkModeResource = R.drawable.ic_directions_train_white,
        lightModeResource = R.drawable.ic_directions_train_black
    )
    object DIRECTIONS_PEDESTRIAN : LightIconConfiguration(
        name = "pedestrian",
        darkModeResource = R.drawable.ic_directions_pedestrian_white,
        lightModeResource = R.drawable.ic_directions_pedestrian_black
    )
    object DIRECTIONS_ROUNDABOUT : LightIconConfiguration(
        name = "roundabout",
        darkModeResource = R.drawable.ic_directions_round_about_white,
        lightModeResource = R.drawable.ic_directions_round_about_black
    )
    object DIRECTIONS_U_TURN_RIGHT : LightIconConfiguration(
        name = "u turn right",
        darkModeResource = R.drawable.ic_directions_uturn_right_white,
        lightModeResource = R.drawable.ic_directions_uturn_right_black
    )
    object DIRECTIONS_U_TURN_LEFT : LightIconConfiguration(
        name = "u turn left",
        darkModeResource = R.drawable.ic_directions_uturn_left_white,
        lightModeResource = R.drawable.ic_directions_uturn_left_black
    )
    object DIRECTIONS_FERRY : LightIconConfiguration(
        name = "ferry",
        darkModeResource = R.drawable.ic_directions_ferry_white,
        lightModeResource = R.drawable.ic_directions_ferry_black
    )
    object DOWN : LightIconConfiguration(
        name = "down",
        darkModeResource = R.drawable.ic_down_white,
        lightModeResource = R.drawable.ic_down_black
    )
    object EMERGENCY : LightIconConfiguration(
        name = "emergency",
        darkModeResource = R.drawable.ic_emergency_white,
        lightModeResource = R.drawable.ic_emergency_black
    )
    object FCC_MARK : LightIconConfiguration(
        name = "fcc mark",
        darkModeResource = R.drawable.ic_fcc_mark_white,
        lightModeResource = R.drawable.ic_fcc_mark_black
    )
    object LIGHT_LOGO : LightIconConfiguration(
        name = "light logo",
        darkModeResource = R.drawable.ic_light_logo_white,
        lightModeResource = R.drawable.ic_light_logo_black
    )
    object FAST_FORWARD : LightIconConfiguration(
        name = "fast-forward",
        darkModeResource = R.drawable.ic_fast_forward_white,
        lightModeResource = R.drawable.ic_fast_forward_black
    )
    object LIST : LightIconConfiguration(
        name = "list",
        darkModeResource = R.drawable.ic_list_white,
        lightModeResource = R.drawable.ic_list_black
    )
    object LOOP : LightIconConfiguration(
        name = "loop",
        darkModeResource = R.drawable.ic_loop_white,
        lightModeResource = R.drawable.ic_loop_black
    )
    object MEDIA : LightIconConfiguration(
        name = "media",
        darkModeResource = R.drawable.ic_media_white,
        lightModeResource = R.drawable.ic_media_black
    )
    object MICROPHONE : LightIconConfiguration(
        name = "microphone",
        darkModeResource = R.drawable.ic_microphone_white,
        lightModeResource = R.drawable.ic_microphone_black
    )
    object MUTE : LightIconConfiguration(
        name = "mute",
        darkModeResource = R.drawable.ic_mute_white,
        lightModeResource = R.drawable.ic_mute_black
    )
    object PAUSE : LightIconConfiguration(
        name = "pause",
        darkModeResource = R.drawable.ic_pause_white,
        lightModeResource = R.drawable.ic_pause_black
    )
    object PLAY : LightIconConfiguration(
        name = "play",
        darkModeResource = R.drawable.ic_play_white,
        lightModeResource = R.drawable.ic_play_black
    )
    object REWIND : LightIconConfiguration(
        name = "rewind",
        darkModeResource = R.drawable.ic_rewind_white,
        lightModeResource = R.drawable.ic_rewind_black
    )
    object SAVE_TO_ALBUM : LightIconConfiguration(
        name = "save to album",
        darkModeResource = R.drawable.ic_save_to_album,
        lightModeResource = R.drawable.ic_save_to_album
    )
    object SEARCH : LightIconConfiguration(
        name = "search",
        darkModeResource = R.drawable.ic_search_white,
        lightModeResource = R.drawable.ic_search_black
    )
    object SELECT_OFF : LightIconConfiguration(
        name = "select off",
        darkModeResource = R.drawable.ic_select_off_white,
        lightModeResource = R.drawable.ic_select_off_black
    )
    object SELECT_ON : LightIconConfiguration(
        name = "select on",
        darkModeResource = R.drawable.ic_select_on_white,
        lightModeResource = R.drawable.ic_select_on_black
    )
    object SEND : LightIconConfiguration(
        name = "send",
        darkModeResource = R.drawable.ic_send_white,
        lightModeResource = R.drawable.ic_send_black
    )
    object SETTINGS : LightIconConfiguration(
        name = "settings",
        darkModeResource = R.drawable.ic_settings_white,
        lightModeResource = R.drawable.ic_settings_black
    )
    object SHUFFLE : LightIconConfiguration(
        name = "shuffle",
        darkModeResource = R.drawable.ic_shuffle_white,
        lightModeResource = R.drawable.ic_shuffle_black
    )
    object SIGNAL_1 : LightIconConfiguration(
        name = "signal 1 bar",
        darkModeResource = R.drawable.ic_signal1_bar_white,
        lightModeResource = R.drawable.ic_signal1_bar_black
    )
    object SIGNAL_2 : LightIconConfiguration(
        name = "signal 2 bars",
        darkModeResource = R.drawable.ic_signal2_bars_white,
        lightModeResource = R.drawable.ic_signal2_bars_black
    )
    object SIGNAL_3 : LightIconConfiguration(
        name = "signal 3 bars",
        darkModeResource = R.drawable.ic_signal3_bars_white,
        lightModeResource = R.drawable.ic_signal3_bars_black
    )
    object SIGNAL_4 : LightIconConfiguration(
        name = "signal 4 bars",
        darkModeResource = R.drawable.ic_signal4_bars_white,
        lightModeResource = R.drawable.ic_signal4_bars_black
    )
    object SIGNAL_NONE : LightIconConfiguration(
        name = "no signal",
        darkModeResource = R.drawable.ic_signal_none_white,
        lightModeResource = R.drawable.ic_signal_none_black
    )
    object SPEAKER : LightIconConfiguration(
        name = "speaker",
        darkModeResource = R.drawable.ic_speaker_white,
        lightModeResource = R.drawable.ic_speaker_black
    )
    object STAR : LightIconConfiguration(
        name = "star",
        darkModeResource = R.drawable.ic_star_white,
        lightModeResource = R.drawable.ic_star_black
    )
    object STAR_OUTLINE : LightIconConfiguration(
        name = "star outline",
        darkModeResource = R.drawable.ic_star_outline_white,
        lightModeResource = R.drawable.ic_star_outline_black
    )
    object TETHERING : LightIconConfiguration(
        name = "tethering enabled",
        darkModeResource = R.drawable.ic_tethering_white,
        lightModeResource = R.drawable.ic_tethering_black
    )
    object TOGGLE_OFF : LightIconConfiguration(
        name = "toggle off",
        darkModeResource = R.drawable.ic_toggle_off_white,
        lightModeResource = R.drawable.ic_toggle_off_black
    )
    object TOGGLE_ON : LightIconConfiguration(
        name = "toggle on",
        darkModeResource = R.drawable.ic_toggle_on_white,
        lightModeResource = R.drawable.ic_toggle_on_black
    )
    object UP : LightIconConfiguration(
        name = "up",
        darkModeResource = R.drawable.ic_up_white,
        lightModeResource = R.drawable.ic_up_black
    )
    object VOICE_MAIL : LightIconConfiguration(
        name = "voicemail",
        darkModeResource = R.drawable.ic_voice_mail_white,
        lightModeResource = R.drawable.ic_voice_mail_black
    )
    object VOICE_MEMO : LightIconConfiguration(
        name = "voicememo",
        darkModeResource = R.drawable.ic_voice_memo_white,
        lightModeResource = R.drawable.ic_voice_memo_black
    )
    object WEEE_MARK : LightIconConfiguration(
        name = "weee mark",
        darkModeResource = R.drawable.ic_weee_mark_white,
        lightModeResource = R.drawable.ic_weee_mark_black
    )
    object WIFI : LightIconConfiguration(
        name = "wifi",
        darkModeResource = R.drawable.ic_wifi_white,
        lightModeResource = R.drawable.ic_wifi_black
    )
    object WIFI_NO_INTERNET : LightIconConfiguration(
        name = "wifi no internet",
        darkModeResource = R.drawable.ic_wifi_no_internet_white,
        lightModeResource = R.drawable.ic_wifi_no_internet_black
    )
    object LARGE_LIST : LightIconConfiguration(
        name = "large list",
        darkModeResource = R.drawable.ic_large_list_white,
        lightModeResource = R.drawable.ic_large_list_black
    )
    object DOWNLOADED_ARROW : LightIconConfiguration(
        name = "downloaded arrow",
        darkModeResource = R.drawable.ic_downloaded_arrow_white,
        lightModeResource = R.drawable.ic_downloaded_arrow_black
    )
    object DOWNLOAD_ARROW : LightIconConfiguration(
        name = "download arrow",
        darkModeResource = R.drawable.ic_download_arrow_white,
        lightModeResource = R.drawable.ic_download_arrow_black
    )
    object SKIP_BACKWARD_FIFTEEN : LightIconConfiguration(
        name = "skip backward fifteen",
        darkModeResource = R.drawable.ic_skip_backward_fifteen_white,
        lightModeResource = R.drawable.ic_skip_backward_fifteen_black
    )
    object SKIP_FORWARD_FIFTEEN : LightIconConfiguration(
        name = "skip forward fifteen",
        darkModeResource = R.drawable.ic_skip_forward_fifteen_white,
        lightModeResource = R.drawable.ic_skip_forward_fifteen_black
    )
    object REFRESH : LightIconConfiguration(
        name = "refresh",
        darkModeResource = R.drawable.ic_refresh_white,
        lightModeResource = R.drawable.ic_refresh_black
    )
    object MAP : LightIconConfiguration(
        name = "map",
        darkModeResource = R.drawable.ic_map_white,
        lightModeResource = R.drawable.ic_map_black
    )
    object CROSSHAIR : LightIconConfiguration(
        name = "crosshair",
        darkModeResource = R.drawable.ic_crosshair_white,
        lightModeResource = R.drawable.ic_crosshair_black
    )
    object ARROW_RIGHT : LightIconConfiguration(
        name = "arrow right",
        darkModeResource = R.drawable.ic_arrow_right_white,
        lightModeResource = R.drawable.ic_arrow_right_black
    )
    object STOP : LightIconConfiguration(
        name = "stop",
        darkModeResource = R.drawable.ic_stop_white,
        lightModeResource = R.drawable.ic_stop_black
    )
    object CONTACTS : LightIconConfiguration(
        name = "contacts",
        darkModeResource = R.drawable.ic_contacts_white,
        lightModeResource = R.drawable.ic_contacts_black
    )
    object REVERSE_ORDER : LightIconConfiguration(
        name = "reverse order",
        darkModeResource = R.drawable.ic_reverse_order_white,
        lightModeResource = R.drawable.ic_reverse_order_black
    )
    object ELLIPSES : LightIconConfiguration(
        name = "ellipses",
        darkModeResource = R.drawable.ic_ellipse_white,
        lightModeResource = R.drawable.ic_ellipse_black
    )
    object SPACER : LightIconConfiguration(
        name = "spacer",
        darkModeResource = R.drawable.ic_spacer,
        lightModeResource = R.drawable.ic_spacer
    )
    object TRASH : LightIconConfiguration(
        name = "trash",
        darkModeResource = R.drawable.ic_trash,
        lightModeResource = R.drawable.ic_trash
    )
    object SPEAKER_ON : LightIconConfiguration(
        name = "speaker on",
        darkModeResource = R.drawable.ic_speaker_on,
        lightModeResource = R.drawable.ic_speaker_on
    )
    object SPEAKER_MUTED : LightIconConfiguration(
        name = "speaker muted",
        darkModeResource = R.drawable.ic_speaker_muted,
        lightModeResource = R.drawable.ic_speaker_muted
    )
    object ROTATE : LightIconConfiguration(
        name = "rotate",
        darkModeResource = R.drawable.ic_rotate_white,
        lightModeResource = R.drawable.ic_rotate_black
    )

    val allEntries: List<Pair<String, LightIconConfiguration>> by lazy {
        LightIcons::class.java.declaredClasses
            .asSequence()
            .filter { LightIconConfiguration::class.java.isAssignableFrom(it) }
            .mapNotNull { iconClass ->
                val instance = runCatching {
                    val instanceField = iconClass.getDeclaredField("INSTANCE")
                    instanceField.isAccessible = true
                    instanceField.get(null) as LightIconConfiguration
                }.getOrNull() ?: return@mapNotNull null
                iconClass.simpleName to instance
            }
            .sortedBy { it.first }
            .toList()
    }
}
