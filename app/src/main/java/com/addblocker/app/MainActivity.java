package com.addblocker.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 1001;
    private SharedPreferences prefs;
    private Button protectionButton;
    private TextView statusText;
    private TextView blockedCountText;
    private TextView seenCountText;
    private Switch bootSwitch;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable statsUpdater = new Runnable() {
        @Override public void run() {
            refreshUi();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
        logo.setImageResource(com.addblocker.app.R.drawable.app_icon);
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
        TextView statsLabel = text("Consultas bloqueadas", 14, Color.rgb(176,176,176), false);
        blockedCountText = text("0", 34, Color.rgb(255,122,0), true);
        TextView seenLabel = text("Dominios detectados en el registro", 14, Color.rgb(176,176,176), false);
        seenLabel.setPadding(0, dp(12), 0, 0);
        seenCountText = text("0", 24, Color.WHITE, true);
        statsCard.addView(statsLabel);
        statsCard.addView(blockedCountText);
        statsCard.addView(seenLabel);
        statsCard.addView(seenCountText);
        root.addView(statsCard, matchWrap(dp(14)));

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
        excludedApps.setOnClickListener(v -> chooseExcludedApps());
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

        TextView note = text("El filtrado se hace en el teléfono. El registro muestra consultas DNS recientes y marca como posibles anuncios los dominios que coinciden con patrones de redes publicitarias. La marca es orientativa: puedes revisar cada dominio antes de bloquearlo.", 13, Color.rgb(176,176,176), false);
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
            Intent stop = new Intent(this, AdBlockVpnService.class).setAction(AdBlockVpnService.ACTION_STOP);
            startService(stop);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
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
    }

    private void startNewCapture() {
        new AlertDialog.Builder(this)
                .setTitle("Nueva captura")
                .setMessage("Se borrará el registro de dominios detectados. Después abre la app donde aparece el anuncio, intenta reproducirlo y vuelve a AddBlocker para revisar los dominios nuevos.")
                .setPositiveButton("Empezar", (d, w) -> {
                    long generation = prefs.getLong("log_generation", 0) + 1;
                    prefs.edit()
                            .putString("dns_log", "")
                            .putLong("seen_count", 0)
                            .putLong("log_generation", generation)
                            .apply();
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

        java.util.List<String> domains = new java.util.ArrayList<>();
        java.util.List<String> flags = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.Set<String> unique = new java.util.HashSet<>();

        for (String line : raw.split("\\r?\\n")) {
            String[] parts = line.split("\\t", 3);
            if (parts.length != 3) continue;
            String flag = parts[1];
            String domain = parts[2].trim();
            if (domain.isEmpty() || !unique.add(domain)) continue;
            domains.add(domain);
            flags.add(flag);
            String prefix;
            if ("B".equals(flag)) prefix = "[BLOQUEADO] ";
            else if ("S".equals(flag)) prefix = "[POSIBLE ANUNCIO] ";
            else prefix = "[TRÁFICO] ";
            labels.add(prefix + domain);
        }

        if (domains.isEmpty()) {
            Toast.makeText(this, "No hay entradas válidas en el registro", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence[] items = labels.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle("Dominios detectados")
                .setMessage("Toca cualquier dominio para añadirlo a la lista de bloqueo. 'Posible anuncio' es una detección heurística, no una garantía.")
                .setItems(items, (dialog, which) -> addCustomBlock(domains.get(which)))
                .setPositiveButton("Bloquear sospechosos", (dialog, which) -> blockSuspicious(domains, flags))
                .setNeutralButton("Limpiar registro", (dialog, which) -> {
                    long generation = prefs.getLong("log_generation", 0) + 1;
                    prefs.edit().putString("dns_log", "").putLong("seen_count", 0).putLong("log_generation", generation).apply();
                    Toast.makeText(this, "Registro limpiado", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void blockSuspicious(java.util.List<String> domains, java.util.List<String> flags) {
        java.util.LinkedHashSet<String> rules = getCustomBlockRules();
        int added = 0;
        for (int i = 0; i < domains.size(); i++) {
            if (!"S".equals(flags.get(i))) continue;
            String domain = normalizeDomain(domains.get(i));
            if (!domain.isEmpty() && rules.add(domain)) added++;
        }
        saveCustomBlockRules(rules);
        Toast.makeText(this, added > 0 ? (added + " dominios añadidos al bloqueo") : "No había nuevos sospechosos", Toast.LENGTH_LONG).show();
    }

    private void addCustomBlock(String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized.isEmpty()) return;
        java.util.LinkedHashSet<String> rules = getCustomBlockRules();
        boolean added = rules.add(normalized);
        saveCustomBlockRules(rules);
        Toast.makeText(this, added ? (normalized + " bloqueado") : (normalized + " ya estaba bloqueado"), Toast.LENGTH_LONG).show();
    }

    private java.util.LinkedHashSet<String> getCustomBlockRules() {
        java.util.LinkedHashSet<String> rules = new java.util.LinkedHashSet<>();
        String current = prefs.getString("custom_block", "");
        if (current != null) {
            for (String line : current.split("\\r?\\n")) {
                String d = normalizeDomain(line);
                if (!d.isEmpty()) rules.add(d);
            }
        }
        return rules;
    }

    private void saveCustomBlockRules(java.util.LinkedHashSet<String> rules) {
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
        box.setPadding(p,p,p,p);
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
            .setPositiveButton("Guardar", (d, w) -> {
                prefs.edit().putString(key, normalizeDomainList(input.getText().toString())).apply();
                Toast.makeText(this, "Lista guardada", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void chooseExcludedApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<ResolveInfo> infos = getPackageManager().queryIntentActivities(launcher, 0);
        java.util.Map<String, String> labels = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ResolveInfo info : infos) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            String label = String.valueOf(info.loadLabel(getPackageManager()));
            labels.put(pkg, label);
        }

        String saved = prefs.getString("excluded_apps", "");
        java.util.Set<String> selected = new java.util.HashSet<>();
        for (String line : saved.split("\\r?\\n")) if (!line.trim().isEmpty()) selected.add(line.trim());

        java.util.List<String> packages = new java.util.ArrayList<>(labels.keySet());
        CharSequence[] names = new CharSequence[packages.size()];
        boolean[] checked = new boolean[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            names[i] = labels.get(packages.get(i));
            checked[i] = selected.contains(packages.get(i));
        }

        new AlertDialog.Builder(this)
            .setTitle("Apps excluidas")
            .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton("Guardar", (dialog, which) -> {
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < packages.size(); i++) if (checked[i]) out.append(packages.get(i)).append('\n');
                prefs.edit().putString("excluded_apps", out.toString().trim()).apply();
                if (prefs.getBoolean("running", false)) {
                    Toast.makeText(this, "Desactiva y vuelve a activar la protección para aplicar el cambio", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Apps excluidas guardadas", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private String normalizeDomain(String value) {
        String d = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
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
