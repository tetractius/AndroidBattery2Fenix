import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

// Watch-initiated control of the phone's sound mode.
//
// SELECT (or a tap) on the full widget view opens a three-way picker. The
// chosen mode is pushed to the Android companion app over the BLE link right
// away — it does not piggyback on the next battery update. The phone answers
// with the resulting mode, which is what actually lands in storage; until
// then the view shows the request as pending.
module SoundControl {

    // Sends the request and marks it pending. The phone is the source of
    // truth: if it refuses (missing Do Not Disturb access, for example) it
    // echoes the unchanged mode back and the pending state simply clears.
    function request(mode as Lang.Number) as Void {
        BatteryData.pendingSoundMode = mode;
        WatchUi.requestUpdate();
        Communications.transmit(
            { "cmd" => "setSoundMode", "soundMode" => mode },
            null,
            new SoundListener()
        );
    }
}

// The watch could not hand the message to the phone at all (out of range,
// Garmin Connect not running). Drop the pending state so the view falls back
// to the last mode we actually know about.
class SoundListener extends Communications.ConnectionListener {

    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() as Void {
        // Delivered to the phone. Still pending until the phone confirms the
        // mode it ended up in, so leave pendingSoundMode alone.
    }

    function onError() as Void {
        BatteryData.pendingSoundMode = null;
        WatchUi.requestUpdate();
    }
}

// Input handler for the full widget view.
class SoundDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onSelect() as Lang.Boolean {
        openMenu();
        return true;
    }

    function onTap(evt) as Lang.Boolean {
        openMenu();
        return true;
    }

    private function openMenu() as Void {
        var menu = new WatchUi.Menu2({
            :title => WatchUi.loadResource(Rez.Strings.SoundTitle)
        });
        var current = BatteryData.getSoundMode();
        menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.SoundNormal),
            currentMark(current, 0), :normal, null));
        menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.SoundVibrate),
            currentMark(current, 1), :vibrate, null));
        menu.addItem(new WatchUi.MenuItem(
            WatchUi.loadResource(Rez.Strings.SoundSilent),
            currentMark(current, 2), :silent, null));
        WatchUi.pushView(menu, new SoundMenuDelegate(), WatchUi.SLIDE_UP);
    }

    private function currentMark(current as Lang.Number, mode as Lang.Number) {
        if (current == mode) {
            return WatchUi.loadResource(Rez.Strings.SoundCurrent);
        }
        return null;
    }
}

class SoundMenuDelegate extends WatchUi.Menu2InputDelegate {

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        var mode = 0;
        if (id == :vibrate) {
            mode = 1;
        } else if (id == :silent) {
            mode = 2;
        }
        SoundControl.request(mode);
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }
}
