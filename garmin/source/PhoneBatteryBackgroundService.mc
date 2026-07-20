import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;

// Woken by the system whenever the Android companion app sends a message,
// even while the widget/glance is not running. Persists the battery state
// so the glance can render it instantly the next time it is shown.
(:background)
class PhoneBatteryBackgroundService extends System.ServiceDelegate {

    function initialize() {
        ServiceDelegate.initialize();
    }

    function onPhoneAppMessage(msg as Communications.PhoneAppMessage) as Void {
        BatteryData.saveFromMessage(msg.data);
        Background.exit(null);
    }
}
