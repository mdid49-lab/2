package ru.itag.controller;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.util.UUID;

public class MainActivity extends Activity {

    private static final String TAG = "iTAG";
    private static final String MAC = "5B:98:5C:0C:8B:0A";

    private static final UUID SERVICE_FFE0 = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_ALERT = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bt;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic alertChar;
    private BluetoothGattCharacteristic customWriteChar;
    private boolean scanning = false;
    private boolean connected = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Button connectButton, voltageButton, starterButton, saveButton, voltageStart, starterStart;
    private TextView voltageTimer, starterTimer, statusText, ledInfo;
    private EditText voltageMinutes, starterSeconds;

    private int voltageRemaining = 0;
    private int starterRemaining = 0;
    private boolean voltageOn = false;
    private boolean starterOn = false;

    private static final int REQ_PERM = 1001;
    private static final int REQ_BT = 1002;

    private final Runnable voltageTick = new Runnable() {
        public void run() {
            if (voltageRemaining > 0) {
                voltageRemaining--;
                showVoltageTime();
                handler.postDelayed(this, 1000);
            } else if (voltageOn) {
                voltageOn = false;
                sendLed(false);
                updateButtons();
            }
        }
    };

    private final Runnable starterTick = new Runnable() {
        public void run() {
            if (starterRemaining > 0) {
                starterRemaining--;
                showStarterTime();
                handler.postDelayed(this, 1000);
            } else if (starterOn) {
                starterOn = false;
                sendStarter(false);
                updateButtons();
            }
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        loadPrefs();
        initBluetooth();
    }

    private void buildUi() {
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(18), dp(12), dp(18), dp(18));
        main.setBackgroundColor(Color.rgb(245,245,245));

        TextView title = new TextView(this);
        title.setText("iTAG Controller");
        title.setTextSize(25);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16),0,0,0);
        title.setBackgroundColor(Color.rgb(0,158,184));
        main.addView(title, new LinearLayout.LayoutParams(-1, dp(62)));

        statusText = new TextView(this);
        statusText.setText("Проверка Bluetooth…");
        statusText.setTextSize(16);
        statusText.setPadding(dp(8),dp(12),dp(8),dp(8));
        main.addView(statusText);

        connectButton = button("ПОДКЛЮЧИТЬСЯ");
        connectButton.setOnClickListener(v -> connectOrScan());
        main.addView(connectButton);

        addSpace(main, 12);
        main.addView(section("УПРАВЛЕНИЕ НАПРЯЖЕНИЕМ"));

        voltageButton = button("вкл. напряжение");
        voltageButton.setOnClickListener(v -> {
            if (!connected) { toast("Нет связи с iTAG"); return; }
            voltageOn = !voltageOn;
            if (voltageOn) {
                voltageRemaining = getVoltageSeconds();
                sendLed(true);
            } else {
                voltageRemaining = 0;
                sendLed(false);
            }
            updateButtons();
        });
        main.addView(voltageButton);

        LinearLayout vr = new LinearLayout(this);
        vr.setGravity(Gravity.CENTER_VERTICAL);
        voltageMinutes = numberEdit("1");
        vr.addView(label("Минуты:"), new LinearLayout.LayoutParams(0,-2,1));
        vr.addView(voltageMinutes, new LinearLayout.LayoutParams(dp(90),-2));
        voltageStart = button("ПУСК");
        voltageStart.setOnClickListener(v -> startVoltageTimer());
        vr.addView(voltageStart, new LinearLayout.LayoutParams(dp(105),-2));
        main.addView(vr);

        voltageTimer = timerText();
        main.addView(voltageTimer);

        ledInfo = new TextView(this);
        ledInfo.setText("LED: канал FFE1 будет определён после подключения");
        ledInfo.setTextSize(13);
        ledInfo.setTextColor(Color.DKGRAY);
        main.addView(ledInfo);

        addSpace(main, 12);
        main.addView(section("УПРАВЛЕНИЕ СТАРТЕРОМ / ПИЩАЛКОЙ"));

        starterButton = button("вкл. стартер");
        starterButton.setOnClickListener(v -> {
            if (!connected) { toast("Нет связи с iTAG"); return; }
            starterOn = !starterOn;
            if (starterOn) {
                starterRemaining = getStarterSeconds();
                sendStarter(true);
            } else {
                starterRemaining = 0;
                sendStarter(false);
            }
            updateButtons();
        });
        main.addView(starterButton);

        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        starterSeconds = numberEdit("10");
        sr.addView(label("Секунды:"), new LinearLayout.LayoutParams(0,-2,1));
        sr.addView(starterSeconds, new LinearLayout.LayoutParams(dp(90),-2));
        starterStart = button("ПУСК");
        starterStart.setOnClickListener(v -> startStarterTimer());
        sr.addView(starterStart, new LinearLayout.LayoutParams(dp(105),-2));
        main.addView(sr);

        starterTimer = timerText();
        main.addView(starterTimer);

        addSpace(main, 14);
        saveButton = button("СОХРАНИТЬ ПАРАМЕТРЫ");
        saveButton.setOnClickListener(v -> savePrefs());
        main.addView(saveButton);

        setContentView(main);
        updateButtons();
        showVoltageTime();
        showStarterTime();
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTextColor(Color.rgb(0,125,145));
        t.setPadding(dp(4),dp(4),dp(4),dp(5));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(16); return t;
    }

    private TextView timerText() {
        TextView t = new TextView(this);
        t.setTextSize(25);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0,dp(6),0,dp(6));
        return t;
    }

    private EditText numberEdit(String s) {
        EditText e = new EditText(this);
        e.setText(s); e.setTextSize(18); e.setInputType(2);
        e.setGravity(Gravity.CENTER);
        return e;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(15);
        return b;
    }

    private void addSpace(LinearLayout l, int h) {
        Space s = new Space(this);
        l.addView(s, new LinearLayout.LayoutParams(1,dp(h)));
    }

    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + .5f); }

    private void updateButtons() {
        connectButton.setText(connected ? "СВЯЗЬ УСТАНОВЛЕНА" : "НЕТ СВЯЗИ — ПОДКЛЮЧИТЬ");
        connectButton.setBackgroundColor(connected ? Color.rgb(55,170,75) : Color.rgb(220,55,55));
        connectButton.setTextColor(Color.WHITE);

        voltageButton.setBackgroundColor(voltageOn ? Color.rgb(55,170,75) : Color.rgb(220,55,55));
        voltageButton.setTextColor(Color.WHITE);

        starterButton.setBackgroundColor(starterOn ? Color.rgb(55,170,75) : Color.rgb(220,55,55));
        starterButton.setTextColor(Color.WHITE);
    }

    private int getVoltageSeconds() {
        try { return Math.max(0, Integer.parseInt(voltageMinutes.getText().toString())) * 60; }
        catch(Exception e) { return 60; }
    }

    private int getStarterSeconds() {
        try { return Math.max(0, Integer.parseInt(starterSeconds.getText().toString())); }
        catch(Exception e) { return 10; }
    }

    private void startVoltageTimer() {
        if (!connected) { toast("Нет связи с iTAG"); return; }
        voltageRemaining = getVoltageSeconds();
        voltageOn = voltageRemaining > 0;
        if (voltageOn) sendLed(true);
        handler.removeCallbacks(voltageTick);
        handler.post(voltageTick);
        updateButtons();
    }

    private void startStarterTimer() {
        if (!connected) { toast("Нет связи с iTAG"); return; }
        starterRemaining = getStarterSeconds();
        starterOn = starterRemaining > 0;
        if (starterOn) sendStarter(true);
        handler.removeCallbacks(starterTick);
        handler.post(starterTick);
        updateButtons();
    }

    private void showVoltageTime() {
        voltageTimer.setText(String.format("Таймер: %02d:%02d", voltageRemaining/60, voltageRemaining%60));
    }

    private void showStarterTime() {
        starterTimer.setText(String.format("Таймер: %02d сек", starterRemaining));
    }

    private void savePrefs() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("vm", voltageMinutes.getText().toString())
                .putString("ss", starterSeconds.getText().toString())
                .apply();
        toast("Параметры сохранены");
    }

    private void loadPrefs() {
        android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
        if (voltageMinutes != null) voltageMinutes.setText(p.getString("vm","1"));
        if (starterSeconds != null) starterSeconds.setText(p.getString("ss","10"));
    }

    private void initBluetooth() {
        android.bluetooth.BluetoothManager m =
                (android.bluetooth.BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        bt = m.getAdapter();
        if (bt == null) {
            statusText.setText("Bluetooth не поддерживается");
            return;
        }
        if (!hasPermissions()) { requestBlePermissions(); return; }
        if (!bt.isEnabled()) {
            statusText.setText("Bluetooth выключен — запрашиваю включение");
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT); }
            catch(SecurityException e) { statusText.setText("Разрешите Bluetooth в настройках"); }
        } else {
            autoScan();
        }
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 23)
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
        }
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == REQ_PERM) {
            if (hasPermissions()) initBluetooth();
            else statusText.setText("Нужны разрешения Bluetooth для поиска iTAG");
        }
    }

    @Override protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r,c,d);
        if (r == REQ_BT && bt != null && bt.isEnabled()) autoScan();
        else if (r == REQ_BT) statusText.setText("Bluetooth выключен");
    }

    private void autoScan() {
        if (!hasPermissions() || bt == null || !bt.isEnabled()) return;
        scanner = bt.getBluetoothLeScanner();
        if (scanner == null) { statusText.setText("BLE-сканер недоступен"); return; }
        startScan();
    }

    private void startScan() {
        if (scanning) return;
        scanning = true;
        connected = false;
        updateButtons();
        statusText.setText("Поиск iTAG " + MAC + "…");

        try {
            ScanFilter f = new ScanFilter.Builder().setDeviceAddress(MAC).build();
            ScanSettings s = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(java.util.Collections.singletonList(f), s, scanCallback);
            handler.postDelayed(() -> {
                if (scanning) {
                    stopScan();
                    statusText.setText("iTAG не найден — нет связи");
                    updateButtons();
                }
            }, 12000);
        } catch (Exception e) {
            scanning = false;
            statusText.setText("Ошибка BLE: " + e.getMessage());
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (MAC.equalsIgnoreCase(d.getAddress())) {
                stopScan();
                connect(d);
            }
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            statusText.setText("Ошибка сканирования BLE: " + errorCode);
        }
    };

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch(Exception ignored) {}
    }

    private void connectOrScan() {
        if (connected && gatt != null) {
            disconnect();
        } else {
            if (!hasPermissions()) { requestBlePermissions(); return; }
            if (bt == null || !bt.isEnabled()) { initBluetooth(); return; }
            autoScan();
        }
    }

    private void connect(BluetoothDevice d) {
        statusText.setText("Подключение к iTAG…");
        try {
            gatt = d.connectGatt(this, false, gattCallback);
        } catch(Exception e) {
            statusText.setText("Ошибка подключения");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            runOnUiThread(() -> {
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    connected = true;
                    statusText.setText("iTAG подключён: " + MAC);
                    updateButtons();
                    try { g.discoverServices(); } catch(Exception ignored) {}
                } else {
                    connected = false;
                    alertChar = null;
                    customWriteChar = null;
                    statusText.setText("нет связи");
                    updateButtons();
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            alertChar = null;
            customWriteChar = null;

            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    UUID u = c.getUuid();
                    int props = c.getProperties();
                    if (CHAR_ALERT.equals(u) &&
                            (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                        alertChar = c;

                    if (CHAR_FFE1.equals(u) &&
                            ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                             (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0))
                        customWriteChar = c;
                }
            }

            runOnUiThread(() -> {
                if (customWriteChar != null)
                    ledInfo.setText("LED: найден канал FFE1 с записью");
                else
                    ledInfo.setText("LED: FFE1 доступен только для READ/NOTIFY — команда управления не обнаружена");
            });
        }
    };

    private void sendStarter(boolean on) {
        if (gatt == null || alertChar == null) {
            toast("Канал 2A06 для стартера не найден");
            return;
        }
        // Bluetooth Alert Level: 0 = No Alert, 1 = Mild, 2 = High.
        write(alertChar, new byte[]{(byte)(on ? 2 : 0)});
    }

    private void sendLed(boolean on) {
        if (gatt == null || customWriteChar == null) {
            toast("Управление LED не найдено в GATT iTAG");
            return;
        }
        // Команда LED у данного устройства не подтверждена по предоставленному GATT.
        // Приложение намеренно НЕ подставляет неизвестную команду.
        toast("LED-канал найден, но команда устройства не определена");
    }

    private void write(BluetoothGattCharacteristic c, byte[] data) {
        try {
            c.setValue(data);
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                gatt.writeCharacteristic(c);
            }
        } catch(Exception e) {
            toast("Ошибка отправки BLE-команды");
        }
    }

    private void disconnect() {
        try { if (gatt != null) gatt.disconnect(); } catch(Exception ignored) {}
        connected = false;
        updateButtons();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch(Exception ignored) {}
        try { if (gatt != null) gatt.close(); } catch(Exception ignored) {}
        super.onDestroy();
    }
}
