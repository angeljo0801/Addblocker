package com.addblocker.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 1001;
    private SharedPreferences prefs;
    private Button protectionButton;
    private TextView statusText;
    private TextView blockedCountText;
    private TextView seenCountText;
    private TextView strictCountText;
    private TextView strictBlockedCountText;
    private TextView strictStatusText;
    private Switch bootSwitch;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable statsUpdater = new Runnable() {
        @Override public void run() {
            refreshUi();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("addblocker", MODE_PRIVATE);
        requestNotificationPermissionIfNeeded();
        setContentView(buildUi());
        refreshUi();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(statsUpdater);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statsUpdater);
    }

    private View buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_icon);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(150), dp(150));
        logoLp.bottomMargin = dp(8);
        root.addView(logo, logoLp);

        TextView title = text("ADD BLOCKER", 28, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(4)));

        TextView subtitle = text("Bloqueo local de anuncios y rastreadores", 14, Color.rgb(176,176,176), false);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, matchWrap(dp(24)));

        statusText = text("Protección desactivada", 18, Color.WHITE, true);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, matchWrap(dp(12)));

        protectionButton = new Button(this);
        protectionButton.setTextColor(Color.BLACK);
        protectionButton.setTextSize(18);
        protectionButton.setAllCaps(false);
        protectionButton.setBackgroundColor(Color.rgb(255,122,0));
        protectionButton.setOnClickListener(v -> toggleProtection());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        btnLp.bottomMargin = dp(22);
        root.addView(protectionButton, btnLp);

        LinearLayout statsCard = card();
        statsCard.addView(text("Consultas bloqueadas", 14, Color.rgb(176,176,176), false));
        blockedCountText = text("0", 34, Color.rgb(255,122,0), true);
        statsCard.addView(blockedCountText);

        TextView seenLabel = text("Dominios detectados", 14, Color.rgb(176,176,176), false);
        seenLabel.setPadding(0, dp(12), 0, 0);
        statsCard.addView(seenLabel);
        seenCountText = text("0", 24, Color.WHITE, true);
        statsCard.addView(seenCountText);

        TextView strictLabel = text("Apps en modo estricto", 14, Color.rgb(176,176,176), false);
        strictLabel.setPadding(0, dp(12), 0, 0);
        statsCard.addView(strictLabel);
        strictCountText = text("0", 24, Color.rgb(255,122,0), true);
        statsCard.addView(strictCountText);

        TextView strictBlockedLabel = text("Bloqueos extra del modo estricto", 14, Color.rgb(176,176,176), false);
        strictBlockedLabel.setPadding(0, dp(12), 0, 0);
        statsCard.addView(strictBlockedLabel);
        strictBlockedCountText = text("0", 24, Color.rgb(255,122,0), true);
        statsCard.addView(strictBlockedCountText);

        strictStatusText = text("Modo estricto: comprobando…", 13, Color.LTGRAY, true);
        strictStatusText.setPadding(0, dp(12), 0, 0);
        statsCard.addView(strictStatusText);
        root.addView(statsCard, matchWrap(dp(14)));

        Button strictApps = secondaryButton("🔥 Modo estricto por app");
        strictApps.setOnClickListener(v -> chooseStrictApps());
        root.addView(strictApps, matchWrap(dp(10)));

        Button usageAccess = secondaryButton("Permiso para detectar la app activa");
        usageAccess.setOnClickListener(v -> openUsageAccess());
        root.addView(usageAccess, matchWrap(dp(10)));

        Button detected = secondaryButton("Registro de anuncios detectados");
        detected.setOnClickListener(v -> showDetectedDomains());
        root.addView(detected, matchWrap(dp(10)));

        Button newCapture = secondaryButton("Nueva captura de anuncios");
        newCapture.setOnClickListener(v -> startNewCapture());
        root.addView(newCapture, matchWrap(dp(10)));

        Button blockList = secondaryButton("Editar lista de bloqueo");
        blockList.setOnClickListener(v -> editList("custom_block", "Dominios bloqueados", "Un dominio por línea. Ejemplo:\nads.example.com"));
        root.addView(blockList, matchWrap(dp(10)));

        Button allowList = secondaryButton("Editar lista blanca");
        allowList.setOnClickListener(v -> editList("allow_list", "Lista blanca", "Estos dominios nunca se bloquearán."));
        root.addView(allowList, matchWrap(dp(10)));

        Button excludedApps = secondaryButton("Apps excluidas del bloqueo");
        excludedApps.setOnClickListener(v -> chooseAppList("excluded_apps", "Apps excluidas", "Estas apps no pasarán por AddBlocker."));
        root.addView(excludedApps, matchWrap(dp(10)));

        bootSwitch = new Switch(this);
        bootSwitch.setText("Reactivar al reiniciar el teléfono");
        bootSwitch.setTextColor(Color.WHITE);
        bootSwitch.setTextSize(16);
        bootSwitch.setChecked(prefs.getBoolean("start_on_boot", false));
        bootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.edit().putBoolean("start_on_boot", isChecked).apply());
        root.addView(bootSwitch, matchWrap(dp(8)));

        Button vpnSettings = secondaryButton("Abrir ajustes de VPN");
        vpnSettings.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
            catch (Exception e) { Toast.makeText(this, "No se pudieron abrir los ajustes de VPN", Toast.LENGTH_SHORT).show(); }
        });
        root.addView(vpnSettings, matchWrap(dp(16)));

        TextView note = text("El bloqueo normal permanece activo para todo el teléfono. En las apps del modo estricto, AddBlocker usa una lista más agresiva de redes publicitarias y dominios de video de anuncios. Para saber qué app está abierta, Android requiere Acceso de uso.", 13, Color.rgb(176,176,176), false);
        note.setLineSpacing(0, 1.2f);
        root.addView(note, matchWrap(dp(30)));
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackgroundColor(Color.rgb(20,20,20));
        return card;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setBackgroundColor(Color.rgb(28,28,28));
        return b;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMargin;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleProtection() {
        boolean running = prefs.getBoolean("running", false);
        if (running) {
            startService(new Intent(this, AdBlockVpnService.class).setAction(AdBlockVpnService.ACTION_STOP));
        } else {
            Intent permission = VpnService.prepare(this);
            if (permission != null) startActivityForResult(permission, VPN_REQUEST);
            else startProtection();
        }
    }

    private void startProtection() {
        Intent intent = new Intent(this, AdBlockVpnService.class).setAction(AdBlockVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) startProtection();
    }

    private void refreshUi() {
        if (statusText == null) return;
        boolean running = prefs.getBoolean("running", false);
        statusText.setText(running ? "Protección activa" : "Protección desactivada");
        statusText.setTextColor(running ? Color.rgb(255,122,0) : Color.WHITE);
        protectionButton.setText(running ? "Desactivar" : "Activar protección");
        blockedCountText.setText(String.valueOf(prefs.getLong("blocked_count", 0)));
        seenCountText.setText(String.valueOf(prefs.getLong("seen_count", 0)));
        strictCountText.setText(String.valueOf(countLines(prefs.getString("strict_apps", ""))));
        strictBlockedCountText.setText(String.valueOf(prefs.getLong("strict_blocked_count", 0)));

        boolean usage = hasUsageAccess();
        boolean strictActive = prefs.getBoolean("strict_mode_active", false);
        String lastPackage = prefs.getString("last_foreground_package", "");
        if (!usage) {
            strictStatusText.setText("Modo estricto: FALTA Acceso de uso");
            strictStatusText.setTextColor(Color.rgb(255, 100, 80));
        } else if (strictActive) {
            strictStatusText.setText("Modo estricto ACTIVO ahora" + (lastPackage.isEmpty() ? "" : " · " + lastPackage));
            strictStatusText.setTextColor(Color.rgb(255,122,0));
        } else {
            strictStatusText.setText("Modo estricto listo" + (lastPackage.isEmpty() ? "" : " · última app: " + lastPackage));
            strictStatusText.setTextColor(Color.LTGRAY);
        }
    }

    private int countLines(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0;
        int count = 0;
        for (String line : raw.split("\\r?\\n")) if (!line.trim().isEmpty()) count++;
        return count;
    }

    private void chooseStrictApps() {
        chooseAppList("strict_apps", "Modo estricto por app", "Marca las apps donde quieres que todo dominio sospechoso se bloquee automáticamente.");
    }

    private void chooseAppList(String key, String title, String explanation) {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = getPackageManager().queryIntentActivities(launcher, PackageManager.MATCH_ALL);
        infos.sort((a, b) -> String.valueOf(a.loadLabel(getPackageManager())).compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager()))));

        List<String> packages = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo info : infos) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seenPackages.add(pkg)) continue;
            packages.add(pkg);
            labels.add(String.valueOf(info.loadLabel(getPackageManager())));
        }

        if (packages.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("Android no devolvió ninguna aplicación para mostrar. Cierra y vuelve a abrir AddBlocker después de actualizar.")
                    .setPositiveButton("Cerrar", null)
                    .show();
            return;
        }

        Set<String> selected = new HashSet<>();
        String saved = prefs.getString(key, "");
        if (saved != null) {
            for (String line : saved.split("\\r?\\n")) if (!line.trim().isEmpty()) selected.add(line.trim());
        }

        CharSequence[] names = labels.toArray(new CharSequence[0]);
        boolean[] checked = new boolean[packages.size()];
        for (int i = 0; i < packages.size(); i++) checked[i] = selected.contains(packages.get(i));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(18), dp(24), dp(10));
        TextView hTitle = text(title, 22, Color.WHITE, false);
        TextView hInfo = text(explanation, 14, Color.LTGRAY, false);
        hInfo.setPadding(0, dp(8), 0, 0);
        header.addView(hTitle);
        header.addView(hInfo);

        new AlertDialog.Builder(this)
                .setCustomTitle(header)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < packages.size(); i++) {
                        if (checked[i]) out.append(packages.get(i)).append('\n');
                    }
                    prefs.edit().putString(key, out.toString().trim()).apply();
                    refreshUi();

                    if ("excluded_apps".equals(key) && prefs.getBoolean("running", false)) {
                        Toast.makeText(this, "Desactiva y vuelve a activar la protección para aplicar las exclusiones", Toast.LENGTH_LONG).show();
                    } else if ("strict_apps".equals(key) && !hasUsageAccess()) {
                        new AlertDialog.Builder(this)
                                .setTitle("Falta Acceso de uso")
                                .setMessage("La lista quedó guardada. Para activar el modo estricto solo dentro de esas apps, habilita Add Blocker en Acceso de uso.")
                                .setPositiveButton("Abrir ajustes", (d, w) -> openUsageAccess())
                                .setNegativeButton("Después", null)
                                .show();
                    } else {
                        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), 0);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, ai.uid, ai.packageName);
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void openUsageAccess() {
        try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
        catch (Exception e) { Toast.makeText(this, "No se pudieron abrir los ajustes de Acceso de uso", Toast.LENGTH_LONG).show(); }
    }

    private void startNewCapture() {
        new AlertDialog.Builder(this)
                .setTitle("Nueva captura")
                .setMessage("Se borrará el registro actual. Después abre la app donde aparece el anuncio, intenta reproducirlo y vuelve a AddBlocker.")
                .setPositiveButton("Empezar", (d, w) -> {
                    long generation = prefs.getLong("log_generation", 0) + 1;
                    prefs.edit().putString("dns_log", "").putLong("seen_count", 0).putLong("log_generation", generation).apply();
                    Toast.makeText(this, "Captura iniciada. Ahora reproduce el anuncio.", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showDetectedDomains() {
        String raw = prefs.getString("dns_log", "");
        if (raw == null || raw.trim().isEmpty()) {
            Toast.makeText(this, "Todavía no hay dominios registrados", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> domains = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Set<String> unique = new HashSet<>();

        for (String line : raw.split("\\r?\\n")) {
            String[] parts = line.split("\\t", 3);
            if (parts.length != 3) continue;
            String flag = parts[1];
            String domain = parts[2].trim();
            if (domain.isEmpty() || !unique.add(domain)) continue;
            domains.add(domain);
            flags.add(flag);
            String prefix = "B".equals(flag) ? "[BLOQUEADO] " : ("S".equals(flag) ? "[POSIBLE ANUNCIO] " : "[TRÁFICO] ");
            labels.add(prefix + domain);
        }

        if (domains.isEmpty()) {
            Toast.makeText(this, "No hay entradas válidas", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Dominios detectados")
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> addCustomBlock(domains.get(which)))
                .setPositiveButton("Bloquear sospechosos", (dialog, which) -> blockSuspicious(domains, flags))
                .setNeutralButton("Limpiar registro", (dialog, which) -> {
                    long generation = prefs.getLong("log_generation", 0) + 1;
                    prefs.edit().putString("dns_log", "").putLong("seen_count", 0).putLong("log_generation", generation).apply();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void blockSuspicious(List<String> domains, List<String> flags) {
        LinkedHashSet<String> rules = getCustomBlockRules();
        int added = 0;
        for (int i = 0; i < domains.size(); i++) {
            if (!"S".equals(flags.get(i))) continue;
            String domain = normalizeDomain(domains.get(i));
            if (!domain.isEmpty() && rules.add(domain)) added++;
        }
        saveCustomBlockRules(rules);
        Toast.makeText(this, added > 0 ? (added + " dominios añadidos") : "No había nuevos sospechosos", Toast.LENGTH_LONG).show();
    }

    private void addCustomBlock(String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized.isEmpty()) return;
        LinkedHashSet<String> rules = getCustomBlockRules();
        boolean added = rules.add(normalized);
        saveCustomBlockRules(rules);
        Toast.makeText(this, added ? (normalized + " bloqueado") : (normalized + " ya estaba bloqueado"), Toast.LENGTH_LONG).show();
    }

    private LinkedHashSet<String> getCustomBlockRules() {
        LinkedHashSet<String> rules = new LinkedHashSet<>();
        String current = prefs.getString("custom_block", "");
        if (current != null) {
            for (String line : current.split("\\r?\\n")) {
                String d = normalizeDomain(line);
                if (!d.isEmpty()) rules.add(d);
            }
        }
        return rules;
    }

    private void saveCustomBlockRules(LinkedHashSet<String> rules) {
        StringBuilder out = new StringBuilder();
        for (String rule : rules) {
            if (out.length() > 0) out.append('\n');
            out.append(rule);
        }
        prefs.edit().putString("custom_block", out.toString()).apply();
    }

    private void editList(String key, String title, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);
        TextView helper = text(hint, 13, Color.DKGRAY, false);
        EditText input = new EditText(this);
        input.setMinLines(8);
        input.setGravity(Gravity.TOP);
        input.setText(prefs.getString(key, ""));
        box.addView(helper);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> prefs.edit().putString(key, normalizeDomainList(input.getText().toString())).apply())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private String normalizeDomain(String value) {
        String d = value == null ? "" : value.trim().toLowerCase(Locale.US);
        d = d.replaceFirst("^https?://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        if (d.startsWith("*.")) d = d.substring(2);
        return d;
    }

    private String normalizeDomainList(String raw) {
        StringBuilder out = new StringBuilder();
        for (String line : raw.split("\\r?\\n")) {
            String d = normalizeDomain(line);
            if (!d.isEmpty()) out.append(d).append('\n');
        }
        return out.toString().trim();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }
}
