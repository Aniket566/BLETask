package com.newproject.bletask2;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BLEActivity";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int SCAN_TIMEOUT = 30000;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;

    private ArrayList<BluetoothDevice> deviceList = new ArrayList<>();
    private ArrayAdapter<String> deviceListAdapter;

    private static final UUID SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_WRITE_UUID = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_NOTIFY_UUID = UUID.fromString("00009abc-0000-1000-8000-00805f9b34fb");

    private BluetoothDevice selectedDevice;

    private ListView deviceListView;
    private Button scanButton, connectButton, sendCommandButton;
    private EditText commandInput;
    private TextView receivedDataText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUI();
        initBluetoothAdapter();
    }

    private void initUI() {
        deviceListView = findViewById(R.id.deviceList);
        scanButton = findViewById(R.id.btnScan);
        connectButton = findViewById(R.id.btnConnect);
        sendCommandButton = findViewById(R.id.btnSendCommand);
        commandInput = findViewById(R.id.etCommand);
        receivedDataText = findViewById(R.id.tvReceivedData);

        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        deviceListView.setAdapter(deviceListAdapter);

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            selectedDevice = deviceList.get(position);
            Toast.makeText(this, "Selected: " + selectedDevice.getName(), Toast.LENGTH_SHORT).show();
        });

        scanButton.setOnClickListener(v -> startBleScan());
        connectButton.setOnClickListener(v -> {
            if (selectedDevice != null) {
                connectToDevice(selectedDevice);
            } else {
                Toast.makeText(this, "Select a device first", Toast.LENGTH_SHORT).show();
            }
        });
        sendCommandButton.setOnClickListener(v -> sendCommand());
    }

    private void initBluetoothAdapter() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }

    private void startBleScan() {
        if (!hasRequiredPermissions()) {
            requestPermissions();
            return;
        }

        deviceList.clear();
        deviceListAdapter.clear();

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        bluetoothLeScanner.startScan(scanCallback);

        new Handler().postDelayed(() -> {
            bluetoothLeScanner.stopScan(scanCallback);
            Toast.makeText(this, "Scan stopped", Toast.LENGTH_SHORT).show();
        }, SCAN_TIMEOUT);

        Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (!deviceList.contains(device)) {
                String deviceInfo = device.getName() != null ?
                        device.getName() + " (" + device.getAddress() + ")" :
                        "Unknown Device (" + device.getAddress() + ")";
                deviceList.add(device);
                deviceListAdapter.add(deviceInfo);
                deviceListAdapter.notifyDataSetChanged();
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
        Toast.makeText(this, "Attempting to connect...", Toast.LENGTH_SHORT).show();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connected to device, discovering services...", Toast.LENGTH_SHORT).show());
                bluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Disconnected", Toast.LENGTH_SHORT).show());
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNotifications(gatt);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connected and services discovered", Toast.LENGTH_SHORT).show());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (CHARACTERISTIC_NOTIFY_UUID.equals(characteristic.getUuid())) {
                processReceivedData(characteristic);
            }
        }
    };

    private void enableNotifications(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(SERVICE_UUID);
        if (service == null) {
            Log.e(TAG, "Service not found");
            return;
        }

        BluetoothGattCharacteristic notifyCharacteristic = service.getCharacteristic(CHARACTERISTIC_NOTIFY_UUID);
        if (notifyCharacteristic == null) {
            Log.e(TAG, "Notify characteristic not found");
            return;
        }

        gatt.setCharacteristicNotification(notifyCharacteristic, true);

        BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        }
    }

    private void sendCommand() {
        if (bluetoothGatt == null) {
            Toast.makeText(this, "Not connected to a device", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) {
            Toast.makeText(this, "Service not found", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothGattCharacteristic writeCharacteristic = service.getCharacteristic(CHARACTERISTIC_WRITE_UUID);
        if (writeCharacteristic == null) {
            Toast.makeText(this, "Write characteristic not found", Toast.LENGTH_SHORT).show();
            return;
        }

        String command = commandInput.getText().toString();
        writeCharacteristic.setValue(command.getBytes());
        bluetoothGatt.writeCharacteristic(writeCharacteristic);
    }

    private void processReceivedData(BluetoothGattCharacteristic characteristic) {
        final byte[] data = characteristic.getValue();
        if (data != null && data.length > 0) {
            String receivedText = new String(data);
            runOnUiThread(() -> receivedDataText.append("\nReceived: " + receivedText));
        }
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
        }, REQUEST_ENABLE_BT);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }
}


