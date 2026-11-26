package cz.ideamirror.app;

import android.content.*;
import android.os.Build;

/**
 * Starts persistent Tabule Link receiver automatically when the IdeaHub board boots (if enabled), and again right
 * after an in-app update replaced the app (Android kills the running receiver while installing).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction());
        if (updated && Ui.ROLE_BOARD.equals(Ui.role(context))) {
            if (ReceiverService.isAlwaysOnEnabled(context)) {
                try { context.startForegroundService(new Intent(context, ReceiverService.class).setAction(ReceiverService.ACTION_START_ALWAYS_ON)); }
                catch (Exception ignored) {}
            }
            try {
                Intent ui = new Intent(context, ReceiveActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(ui);
            } catch (Exception ignored) {}
            return;
        }
        if (updated) return;
        if (!ReceiverService.isAlwaysOnEnabled(context)) return;
        try {
            Intent svc = new Intent(context, ReceiverService.class).setAction(ReceiverService.ACTION_START_ALWAYS_ON);
            context.startForegroundService(svc);
        } catch (Exception ignored) {}
        if (ReceiverService.isAutoOpenOnBootEnabled(context)) {
            try {
                Intent ui = new Intent(context, ReceiveActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(ui);
            } catch (Exception ignored) {}
        }
    }
}
