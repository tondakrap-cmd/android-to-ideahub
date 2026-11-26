package cz.ideamirror.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Result of the PackageInstaller session started by {@link Updater}: shows the system confirmation or the error. */
public class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION = "cz.ideamirror.app.UPDATE_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(confirm); }
                catch (Exception e) { Toast.makeText(context, "Potvrzení instalace nelze otevřít: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return; // the process is replaced; BootReceiver restarts the board
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, "Aktualizace byla zrušena.", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Toast.makeText(context, "Aktualizaci se nepodařilo nainstalovat" + (msg != null ? ": " + msg : "."), Toast.LENGTH_LONG).show();
    }
}
