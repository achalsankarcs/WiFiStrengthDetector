WiFi Strength Detector 

This project is a complete Android + ESP32 solution for scanning WiFi networks, analyzing signal strength, optimizing access point positioning, and generating visual heatmaps. Ideal for field testing, performance tuning, and understanding wireless interference in real environments.



##Features##

Android App
BLE Communication: Connects to ESP32 via Bluetooth Low Energy (BLE)
Live WiFi Scan Viewer: Displays SSID, RSSI, signal bars, and security types
WiFi Optimization Screen:
  - Record RSSI values at custom coordinates
  - Calculate average signal strength per point
  - Detect channel interference in real time
Heatmap Generator:
  - Sends location-wise RSSI data to Python backend
  - Displays generated signal heatmap in-app
Scan History + Export:
   View previous scans and export data in JSON or plain text

 ESP32 (Arduino)
-  Scans nearby WiFi networks every 2 seconds
-  Sends scan data over BLE in JSON format
-  Supports manual scan commands via BLE
-  Sends RSSI values for selected SSID over 5 seconds for optimization mode

---


##Android Tech Stack##

- Kotlin + Jetpack Compose
- BluetoothGatt (BLE API)
- Navigation Component
- JSON Parsing (org.json)
- OkHttp3 (for HTTP communication with Python server)
- Custom UI for RSSI bars, channel info, and export tools


##ESP32 Tech Stack##

- Arduino Framework
- WiFi.h + BLEDevice.h
- JSON formatting
- BLE Notify/Write Characteristics
- Manual + Automatic WiFi scan logic



##Python Server (for heatmap)##

- Flask (API server)
- Numpy + Scipy (for interpolation)
- Matplotlib + Seaborn (for generating heatmaps)
- Receives JSON with (x, y, RSSI) points
- Returns heatmap image back to Android

