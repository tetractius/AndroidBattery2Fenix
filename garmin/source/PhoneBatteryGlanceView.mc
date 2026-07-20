import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Compact entry shown in the glance carousel (press DOWN from the watch
// face on a fenix 8, next to Weather, Heart Rate, etc.).
(:glance)
class PhoneBatteryGlanceView extends WatchUi.GlanceView {

    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, h / 4, Graphics.FONT_GLANCE, "PHONE BATTERY",
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);

        var info = BatteryData.get();
        if (info == null) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(0, (h * 3) / 4, Graphics.FONT_GLANCE_NUMBER, "--%",
                Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }

        var level = info["level"];
        var charging = info["charging"] == true;
        var mins = BatteryData.ageMinutes(info);
        var stale = (mins != null) && (mins > 120);

        // Battery range, e.g. "75-80%"
        dc.setColor(stale ? Graphics.COLOR_LT_GRAY : Graphics.COLOR_WHITE,
            Graphics.COLOR_TRANSPARENT);
        var pctText = BatteryData.formatRange(level) + (charging ? " +" : "");
        dc.drawText(0, (h * 3) / 4, Graphics.FONT_GLANCE_NUMBER, pctText,
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);

        // Battery gauge at the right edge of the bottom line
        var barX = (w * 7) / 10;
        var barW = w / 4;
        var barH = h / 5;
        var barY = (h * 3) / 4 - barH / 2;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawRoundedRectangle(barX, barY, barW, barH, 2);
        dc.fillRectangle(barX + barW, barY + barH / 3, 2, barH / 3); // terminal

        var fillW = ((barW - 4) * level) / 100;
        if (fillW > 0) {
            dc.setColor(BatteryData.levelColor(level, charging),
                Graphics.COLOR_TRANSPARENT);
            dc.fillRectangle(barX + 2, barY + 2, fillW, barH - 4);
        }

        // Age of the data, above the gauge
        if (mins != null && mins >= 1) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w, h / 4, Graphics.FONT_GLANCE, BatteryData.formatAge(mins),
                Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }
}
