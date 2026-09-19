import Toybox.Application.Storage;
import Toybox.Lang;
import Toybox.Time;

// Shared persistence for the phone battery state. Runs in the background
// service, the glance and the full widget, so everything here must be
// annotated for all three scopes.
// Sound mode constants (mirrored on the Android side):
//   SOUND_NORMAL  = 0  — full ring + vibration
//   SOUND_VIBRATE = 1  — vibrate only, no ring
//   SOUND_SILENT  = 2  — no sound, no vibration (needs DND permission on Android)
(:background :glance)
module BatteryData {

    const KEY = "phoneBattery";
    const SOUND_KEY = "soundMode";

    // Mode requested from the watch but not yet confirmed by the phone.
    // Foreground-only display state; it is never persisted.
    var pendingSoundMode = null;

    // Message payload from the Android companion app:
    //   { "level" => 0..100, "charging" => true/false, "soundMode" => 0/1/2 }
    // The two halves are independent: the reply to a watch-initiated sound
    // change arrives immediately and may carry no battery fields at all.
    function saveFromMessage(data as Lang.Object?) as Void {
        if (!(data instanceof Lang.Dictionary)) {
            return;
        }

        // Sound mode is stored separately so the watch can update it
        // independently of the battery data.
        var soundMode = data["soundMode"];
        if (soundMode instanceof Lang.Number && soundMode >= 0 && soundMode <= 2) {
            Storage.setValue(SOUND_KEY, soundMode);
            // The phone has answered, so any watch request is settled — even
            // if it was refused and the mode came back unchanged.
            pendingSoundMode = null;
        }

        var level = data["level"];
        if (!(level instanceof Lang.Number)) {
            return;
        }
        if (level < 0) {
            level = 0;
        } else if (level > 100) {
            level = 100;
        }
        Storage.setValue(KEY, {
            "level" => level,
            "charging" => data["charging"] == true,
            "ts" => Time.now().value()
        });
    }

    function getSoundMode() as Lang.Number {
        var mode = Storage.getValue(SOUND_KEY);
        if (mode instanceof Lang.Number) {
            return mode;
        }
        return 0;
    }

    function get() {
        var info = Storage.getValue(KEY);
        if (info instanceof Lang.Dictionary && info["level"] != null) {
            return info;
        }
        return null;
    }

    function ageMinutes(info) {
        var ts = info["ts"];
        if (!(ts instanceof Lang.Number)) {
            return null;
        }
        var age = Time.now().value() - ts;
        if (age < 0) {
            age = 0;
        }
        return age / 60;
    }

    // Display the level as a 5% band: 77 -> "75-80%", 100 -> "100%".
    function formatRange(level) {
        if (level >= 100) {
            return "100%";
        }
        var lower = (level / 5) * 5;
        return lower + "-" + (lower + 5) + "%";
    }

    function formatAge(mins) {
        if (mins == null) {
            return "";
        }
        if (mins < 1) {
            return "now";
        }
        if (mins < 60) {
            return mins + "m";
        }
        if (mins < 1440) {
            return (mins / 60) + "h";
        }
        return (mins / 1440) + "d";
    }

    function levelColor(level, charging) {
        if (charging) {
            return 0x00AAFF; // blue while charging
        }
        if (level <= 15) {
            return 0xFF0000; // red
        }
        if (level <= 40) {
            return 0xFFAA00; // orange
        }
        return 0x00FF00;     // green
    }
}
