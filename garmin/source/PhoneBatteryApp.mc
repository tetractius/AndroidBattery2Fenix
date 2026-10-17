import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

(:background :glance)
class PhoneBatteryApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
    }

    function onStop(state) {
    }

    (:glance)
    function getGlanceView() {
        return [new PhoneBatteryGlanceView()];
    }

    function getInitialView() {
        // Live updates while the widget is in the foreground.
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        // (Re)register the background listener so updates arrive even when
        // the widget is closed. Registration persists across reboots.
        Background.registerForPhoneAppMessageEvent();
        return [new PhoneBatteryView(), new SoundDelegate()];
    }

    function getServiceDelegate() {
        return [new PhoneBatteryBackgroundService()];
    }

    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        BatteryData.saveFromMessage(msg.data);
        WatchUi.requestUpdate();
    }

    function onBackgroundData(data) {
        WatchUi.requestUpdate();
    }
}
