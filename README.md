# FFit BT (Bluetooth Thermal Printer Driver)

FFit BT is a robust Android Print Service that integrates Bluetooth thermal printers (ESC/POS) directly into the Android system print framework. Once configured, you can print receipts, bills, and documents directly from any Android app (such as Google Chrome, Gmail, or custom web apps) just like a standard desktop printer.

Developed by **raj** for **FFIT.IO**.

---

## 🚀 Key Features

*   **System-Level Print Service**: Registers directly as an Android system Print Service, making it selectable in any native Android print dialog.
*   **Persistent Auto-Connect**: Automatically reconnects to your last-used printer using the saved MAC address—works seamlessly in the background even if the app UI is closed.
*   **Intelligent Whitespace Cropping**: Automatically crops blank margins at the top and bottom of print documents to save thermal paper.
*   **Dual Roll Size Support**: Supports both **58mm Receipt** (default, 203 DPI) and **80mm Receipt** formats.
*   **Smart Device Discovery**: Filters paired Bluetooth devices and alerts you if a selected device is not a thermal printer.
*   **Beautiful Branded Footer**: Includes a custom dotted separator and `Powered by- FFIT.IO ♥` company branding at the end of each print.

---

## 🛠️ Step-by-Step Configuration

Follow these steps to configure FFit BT on your Android device:

```
┌─────────────────────────────────────────────────────────┐
│  1. Pair Printer via Android Bluetooth Settings         │
└────────────────────────────┬────────────────────────────┘
                             ▼
┌─────────────────────────────────────────────────────────┐
│  2. Open FFit BT → Scan & Connect to your paired device │
└────────────────────────────┬────────────────────────────┘
                             ▼
┌─────────────────────────────────────────────────────────┐
│  3. Go to Android Print Settings → Enable FFit BT       │
└────────────────────────────┬────────────────────────────┘
                             ▼
┌─────────────────────────────────────────────────────────┐
│  4. Print from Chrome/any app → Select FFit BT printer  │
└─────────────────────────────────────────────────────────┘
```

1.  **Pair the Printer**: Go to Android **Settings** ➔ **Bluetooth** and pair your thermal printer.
2.  **Select within App**: Open the **FFit BT** app, tap **Scan**, and select your paired thermal printer from the list to connect and save it.
3.  **Enable Print Service**: Go to Android **Settings** ➔ **Connection Preferences** ➔ **Printing** ➔ Select **FFit BT** and toggle the service to **ON**.
4.  **Ready to Print**: Open any document or webpage (e.g., in Chrome), click **Share** ➔ **Print**, select **FFit BT** as your printer, and press the Print button.

---

## 💻 Tech Stack & Architecture

*   **Language**: Kotlin
*   **UI Framework**: Android Jetpack / ViewBinding / BottomSheetDialog
*   **Printing Integration**: Android `android.printservice.PrintService` & `android.print.PrinterDiscoverySession`
*   **Document Processing**: Android `PdfRenderer` for converting spooler PDFs into high-quality monochrome raster images (384px width for 58mm).
*   **Communication**: Bluetooth Classic (RFCOMM socket communication using standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB`).
*   **Printer Control**: Direct ESC/POS bytecode formatting and page cutting commands.

---

## 📦 How to Download & Run

A live landing page and APK download is available in the `public` directory. You can host this directory on GitHub Pages to let users download the latest build easily.

To build the APK from source, open the directory in Android Studio and run:
```bash
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`
