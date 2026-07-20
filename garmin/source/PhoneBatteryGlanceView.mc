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

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();

        // Row 1 (top): title + age
        var rowTop = h / 5;
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, rowTop, Graphics.FONT_GLANCE, "PHONE BATTERY",
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);

        var info = BatteryData.get();
        if (info == null) {
            // Row 2: no-data placeholder
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(0, h / 2, Graphics.FONT_GLANCE_NUMBER, "--%",
                Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }

        var level = info["level"];
        var charging = info["charging"] == true;
        var mins = BatteryData.ageMinutes(info);
        var stale = (mins != null) && (mins > 120);

        if (mins != null && mins >= 1) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w, rowTop, Graphics.FONT_GLANCE, BatteryData.formatAge(mins),
                Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // Row 2 (middle): battery percentage + charging bolt + gauge
        var rowMid = h / 2;
        dc.setColor(stale ? Graphics.COLOR_LT_GRAY : Graphics.COLOR_WHITE,
            Graphics.COLOR_TRANSPARENT);
        var pctText = BatteryData.formatRange(level);
        dc.drawText(0, rowMid, Graphics.FONT_GLANCE_NUMBER, pctText,
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);

        if (charging) {
            var dims = dc.getTextDimensions(pctText, Graphics.FONT_GLANCE_NUMBER);
            drawChargingBolt(dc, dims[0] + 4, rowMid - 8);
        }

        var barW = w / 4;
        var barH = h / 5;
        var barX = (w * 7) / 10;
        var barY = rowMid - barH / 2;
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawRoundedRectangle(barX, barY, barW, barH, 2);
        dc.fillRectangle(barX + barW, barY + barH / 3, 2, barH / 3);

        var fillW = ((barW - 4) * level) / 100;
        if (fillW > 0) {
            dc.setColor(BatteryData.levelColor(level, charging),
                Graphics.COLOR_TRANSPARENT);
            dc.fillRectangle(barX + 2, barY + 2, fillW, barH - 4);
        }

        // Row 3 (bottom): sound mode
        var soundMode = BatteryData.getSoundMode();
        dc.setColor(soundModeColor(soundMode), Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, (h * 4) / 5, Graphics.FONT_GLANCE, soundModeLabel(soundMode),
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    private function soundModeLabel(mode as Lang.Number) as Lang.String {
        if (mode == 1) { return "VIB"; }
        if (mode == 2) { return "MUT"; }
        return "NRM";
    }

    private function soundModeColor(mode as Lang.Number) as Lang.Number {
        if (mode == 1) { return 0xFFAA00; } // orange — vibrate
        if (mode == 2) { return 0xFF4444; } // red — silent
        return 0x00AA00;                     // green — normal
    }

    // Draws a small yellow lightning bolt (⚡) at (bx, by).
    // The bolt is ~8px wide and 16px tall, drawn as three thick line segments.
    private function drawChargingBolt(dc as Graphics.Dc, bx as Number, by as Number) as Void {
        var mid = 8;
        dc.setPenWidth(2);
        dc.setColor(0xFFAA00, Graphics.COLOR_TRANSPARENT);
        dc.drawLine(bx + 6, by,       bx + 1, by + mid);   // top-right → mid-left
        dc.drawLine(bx + 1, by + mid, bx + 5, by + mid);   // step right
        dc.drawLine(bx + 5, by + mid, bx,     by + 16);    // mid-right → bottom-left
        dc.setPenWidth(1);
    }
}
