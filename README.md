# OfflineSOS

OfflineSOS is an Android application designed for emergency situations where internet or cellular connectivity is unavailable. It leverages Bluetooth Low Energy (BLE) to create a peer-to-peer distress network, allowing victims to broadcast SOS signals to nearby devices serving as rescuers.

## Features

*   **Offline Communication:** Works completely without Internet or SIM card using BLE.
*   **Dual Roles:**
    *   **Victim Mode:** Scans for nearby rescuers and sends encrypted-like SOS packets containing GPS location and a custom message.
    *   **Rescuer Mode:** Acts as a beacon, detecting incoming SOS signals and alerting the user.
*   **Location Tracking:** Automatically fetches and shares precise GPS coordinates.
*   **Distance Estimation:** Calculates and displays the distance between the victim and the rescuer.
*   **Delivery Confirmation:** Victim receives an acknowledgement (ACK) when help is on the way.

## How It Works

1.  **Rescuer Mode:** A user acts as a responder by enabling "Rescuer Mode". The device starts advertising its presence via BLE.
2.  **Victim Mode:** A user in distress sends an SOS. The app scans for advertising rescuers.
3.  **Transmission:** Once a rescuer is found, a connection is established, and the SOS payload (Location + Message) is transmitted via GATT operations.
4.  **Acknowledgement:** The rescuer automatically replies with an acknowledgement and their own location, reassuring the victim.

## Requirements

*   **Minimum SDK:** Android 8.0 (API 26) or higher.
*   **Hardware:** Android device with Bluetooth 4.0+ (BLE support) and GPS.

## Permissions

The app requires the following permissions to function:
*   `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`: To find and communicate with nearby devices.
*   `ACCESS_FINE_LOCATION`: To capture accurate GPS coordinates for the SOS signal.
*   `ACCESS_COARSE_LOCATION`: Fallback for location services.

## Installation

1.  Clone the repository:
    ```bash
    git clone https://github.com/Yaswanth-rajana/OfflineSOS.git
    ```
2.  Open the project in **Android Studio**.
3.  Sync Gradle and build the project.
4.  Deploy to two different Android devices to test the communication.

## Usage

1.  **Device A (Rescuer):** Toggle the switch to "Rescuer Active". The status will change to "Listen mode".
2.  **Device B (Victim):** Enter a message (optional) and press the **SOS** button.
3.  **Result:** Device A will receive an alert with Device B's location and distance. Device B will receive a confirmation spanning "HELP IS ON THE WAY".