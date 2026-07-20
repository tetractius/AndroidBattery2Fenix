import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Full-screen view shown when the glance is selected (START/tap).
class PhoneBatteryView extends WatchUi.View {

    function initialize() {
        View.initialize();
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h / 6, Graphics.FONT_TINY,
            WatchUi.loadResource(Rez.Strings.AppName),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        var info = BatteryData.get();
        if (info == null) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h / 2, Graphics.FONT_MEDIUM,
                WatchUi.loadResource(Rez.Strings.NoData),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, (h * 2) / 3, Graphics.FONT_XTINY,
                "Open the Android app",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }

        var level = info["level"];
        var charging = info["charging"] == true;
        var mins = BatteryData.ageMinutes(info);
        var color = BatteryData.levelColor(level, charging);

        // Big battery range, e.g. "75-80%"
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, (h * 2) / 5, Graphics.FONT_NUMBER_MEDIUM,
            BatteryData.formatRange(level),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Battery gauge
        var barW = w / 2;
        var barH = h / 12;
        var barX = cx - barW / 2;
        var barY = (h * 3) / 5;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawRoundedRectangle(barX, barY, barW, barH, 4);
        dc.fillRectangle(barX + barW, barY + barH / 3, 3, barH / 3); // terminal

        var fillW = ((barW - 6) * level) / 100;
        if (fillW > 0) {
            dc.setColor(color, Graphics.COLOR_TRANSPARENT);
            dc.fillRectangle(barX + 3, barY + 3, fillW, barH - 6);
        }

        // Status line: charging or data age
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        var status;
        if (charging) {
            status = WatchUi.loadResource(Rez.Strings.Charging);
        } else if (mins != null && mins >= 1) {
            status = WatchUi.loadResource(Rez.Strings.LastUpdate) + " " +
                BatteryData.formatAge(mins) + " ago";
        } else {
            status = WatchUi.loadResource(Rez.Strings.LastUpdate) + " now";
        }
        dc.drawText(cx, (h * 3) / 4, Graphics.FONT_XTINY, status,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Sound mode row: label on the left, SELECT hint on the right
        var soundMode = BatteryData.getSoundMode();
        var soundLabel = soundModeLabel(soundMode);
        var soundColor = soundModeColor(soundMode);
        dc.setColor(soundColor, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, (h * 7) / 8, Graphics.FONT_XTINY, soundLabel,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    private function soundModeLabel(mode as Lang.Number) as Lang.String {
        if (mode == 1) { return WatchUi.loadResource(Rez.Strings.SoundVibrate) as Lang.String; }
        if (mode == 2) { return WatchUi.loadResource(Rez.Strings.SoundSilent) as Lang.String; }
        return WatchUi.loadResource(Rez.Strings.SoundNormal) as Lang.String;
    }

    private function soundModeColor(mode as Lang.Number) as Lang.Number {
        if (mode == 1) { return 0xFFAA00; } // orange — vibrate
        if (mode == 2) { return 0xFF4444; } // red — silent
        return 0x00AA00;                     // green — normal
    }
}
