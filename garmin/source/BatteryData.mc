import Toybox.Application.Storage;
import Toybox.Lang;
import Toybox.Time;

// Shared persistence for the phone battery state. Runs in the background
// service, the glance and the full widget, so everything here must be
// annotated for all three scopes.
(:background :glance)
module BatteryData {

    const KEY = "phoneBattery";

    // Message payload from the Android companion app:
    //   { "level" => 0..100, "charging" => true/false }
    function saveFromMessage(data) {
        if (!(data instanceof Lang.Dictionary)) {
            return;
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
