import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.WatchUi;

// Full-screen view shown when the glance is selected (START/tap).
//
// Layout notes: the targets run from 260x260 (Solar 47mm) to 454x454
// (AMOLED 47mm), so every position is a fraction of the screen rather than a
// pixel. On the small MIP screens the round bezel is the real constraint --
// text near the very bottom gets its first and last characters clipped, so
// the bottom rows sit well inside the circle and fitsWidth() checks the
// chord before drawing.
class PhoneBatteryView extends WatchUi.View {

    // Vertical anchors, as percent of screen height. Everything is pulled up
    // to close the gap that used to sit between the big number and the gauge,
    // which buys the room the two-line hint needs at the bottom.
    private const Y_TITLE  = 13;
    private const Y_NUMBER = 30;
    private const Y_BAR    = 42;
    private const Y_STATUS = 58;
    private const Y_SOUND  = 70;
    private const Y_HINT1  = 80;
    private const Y_HINT2  = 89;

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
        drawCentered(dc, cx, pct(h, Y_TITLE), Graphics.FONT_TINY,
            WatchUi.loadResource(Rez.Strings.AppName));

        var info = BatteryData.get();
        if (info == null) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            drawCentered(dc, cx, h / 2, Graphics.FONT_MEDIUM,
                WatchUi.loadResource(Rez.Strings.NoData));
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            drawCentered(dc, cx, (h * 2) / 3, Graphics.FONT_XTINY,
                "Open the Android app");
            return;
        }

        var level = info["level"];
        var charging = info["charging"] == true;
        var mins = BatteryData.ageMinutes(info);
        var color = BatteryData.levelColor(level, charging);

        // Big battery range, e.g. "75-80%"
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        drawCentered(dc, cx, pct(h, Y_NUMBER), Graphics.FONT_NUMBER_MEDIUM,
            BatteryData.formatRange(level));

        // Battery gauge
        var barW = w / 2;
        var barH = h / 12;
        var barX = cx - barW / 2;
        var barY = pct(h, Y_BAR);

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
        drawCentered(dc, cx, pct(h, Y_STATUS), Graphics.FONT_XTINY, status);

        // Sound mode row. While a change is in flight we show the requested
        // mode greyed out instead of the stored one, so the press feels
        // immediate without pretending the phone has already applied it.
        var pending = BatteryData.pendingSoundMode;
        var soundMode = (pending != null) ? pending : BatteryData.getSoundMode();
        var soundColor = (pending != null)
            ? Graphics.COLOR_DK_GRAY
            : soundModeColor(soundMode);

        dc.setColor(soundColor, Graphics.COLOR_TRANSPARENT);
        drawCentered(dc, cx, pct(h, Y_SOUND), Graphics.FONT_XTINY,
            soundModeLabel(soundMode));

        // Hint. Two lines while idle so the text stays well inside the bezel;
        // one line reading "Sending..." while a request is outstanding.
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        if (pending != null) {
            drawCentered(dc, cx, pct(h, Y_HINT1), Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.SoundSending) as Lang.String);
        } else {
            drawCentered(dc, cx, pct(h, Y_HINT1), Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.SoundHint) as Lang.String);
            drawCentered(dc, cx, pct(h, Y_HINT2), Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.SoundHintLine2) as Lang.String);
        }
    }

    private function pct(h as Lang.Number, percent as Lang.Number) as Lang.Number {
        return (h * percent) / 100;
    }

    // Draws centred text, but only if it actually fits the round screen at
    // this height. Silently skipping is better than drawing a string with its
    // ends sliced off by the bezel.
    private function drawCentered(dc, cx as Lang.Number, y as Lang.Number,
            font, text as Lang.String) as Void {
        if (!fitsWidth(dc, y, dc.getTextWidthInPixels(text, font))) {
            return;
        }
        dc.drawText(cx, y, font, text,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Chord width of the display at vertical position y. Assumes a round
    // screen, which all four supported products are.
    private function fitsWidth(dc, y as Lang.Number, textWidth as Lang.Number) as Lang.Boolean {
        var r = dc.getHeight() / 2;
        var dy = y - r;
        var inner = (r * r) - (dy * dy);
        if (inner <= 0) {
            return false;
        }
        var half = Math.sqrt(inner);
        return textWidth <= (2 * half);
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
