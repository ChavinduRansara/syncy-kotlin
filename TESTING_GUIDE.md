# Testing Guide for Syncy P2P

## Prerequisites for Testing

### Hardware Requirements

- **Minimum 2 Android devices** with API level 28+ (Android 9.0+)
- Both devices must support Wi-Fi Direct (most modern Android devices do)
- Devices should be in close proximity (Wi-Fi Direct range is typically 50-100 meters)

### Software Requirements

- Android Studio with USB debugging enabled
- Both devices should have Developer Options enabled
- Location services enabled on both devices (required for Wi-Fi Direct discovery)

## Testing Setup

### 1. Build and Install

```bash
# Build the APK
./gradlew assembleDebug

# Install on both devices
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Grant Permissions

On both devices, when the app launches for the first time:

1. Grant **Location** permission (required for Wi-Fi Direct)
2. Grant **Nearby devices** permission
3. Grant **Storage** permission (for file transfers)
4. Allow **notification** access for background services

## Testing Scenarios

### Scenario 1: Basic Discovery and Connection

**Steps:**

1. Launch the app on both devices
2. Tap **"Discover Peers"** on both devices
3. Wait for devices to appear in the peer list (5-10 seconds)
4. On one device, tap on the discovered peer to initiate connection
5. Accept the connection on the other device when prompted

**Expected Results:**

- Both devices should discover each other
- Connection should establish successfully
- Connection status should show "Connected" on both devices
- Group owner should be determined automatically
- **Disconnect button should appear** on connected devices

### Scenario 1b: Manual Disconnection

**Prerequisites:** Devices must be connected (Scenario 1)

**Steps:**

1. On either connected device, tap the **"Disconnect"** button next to a connected peer
2. Confirm the disconnection in the dialog that appears
3. Observe the connection status changes on both devices

**Expected Results:**

- Confirmation dialog should appear asking to confirm disconnection
- After confirming, both devices should show "Available" status
- Disconnect button should disappear and Connect button should reappear
- Both devices should be able to reconnect immediately

### Scenario 2: Text Messaging

**Prerequisites:** Devices must be connected (Scenario 1)

**Steps:**

1. On either device, type a message in the text input field
2. Tap **"Send Message"**
3. Check the other device for the received message

**Expected Results:**

- Message should appear in the message list on the receiving device
- Sender should see confirmation that message was sent
- Messages should be timestamped correctly

### Scenario 3: File Transfer

**Prerequisites:** Devices must be connected (Scenario 1)

**Steps:**

1. On the sending device, tap **"Send File"**
2. Select a file from the device (try different file types: images, documents, etc.)
3. Monitor the transfer progress
4. Check the receiving device for the transferred file

**Expected Results:**

- File picker should open correctly
- Transfer should begin and show progress
- File should be saved in the app's directory on the receiving device
- Both devices should show transfer completion status

### Scenario 4: Connection Recovery

**Steps:**

1. Establish connection between devices
2. Move one device out of Wi-Fi Direct range
3. Bring devices back into range
4. Test reconnection functionality

**Expected Results:**

- App should detect connection loss
- Automatic reconnection should occur when devices are back in range
- Previous state should be restored after reconnection

### Scenario 5: Multiple File Transfers

**Steps:**

1. Send multiple files consecutively
2. Send files while a message is being sent
3. Test with different file sizes (small text files, large images)

**Expected Results:**

- Queue should handle multiple transfers correctly
- No conflicts between message and file transfers
- All transfers should complete successfully

## Debugging Common Issues

### Discovery Problems

- **Devices not finding each other:**
  - Ensure location services are enabled
  - Check that Wi-Fi is enabled (not necessarily connected to a network)
  - Try restarting Wi-Fi Direct: Settings > Wi-Fi > Advanced > Wi-Fi Direct
  - Clear app data and retry

### Connection Issues

- **Connection fails:**
  - Ensure both devices accept the connection prompt
  - Check that firewall/security apps aren't blocking connections
  - Restart the app on both devices

### File Transfer Issues

- **Files not transferring:**
  - Check storage permissions
  - Verify sufficient storage space on receiving device
  - Try with smaller files first

### Performance Issues

- **Slow transfers:**
  - Ensure devices are in close proximity
  - Check for interference from other Wi-Fi networks
  - Test with different file sizes

## Logging and Debugging

### Enable Debug Logging

The app includes comprehensive logging. To view logs:

```bash
# Filter logs for the app
adb logcat | grep "SyncyP2P"

# View Wi-Fi Direct specific logs
adb logcat | grep -E "(WifiP2p|P2P)"
```

### Key Log Tags

- `SyncyP2P_Manager`: Wi-Fi Direct manager operations
- `SyncyP2P_MessageSender`: Message sending operations
- `SyncyP2P_FileSender`: File transfer operations
- `SyncyP2P_Receiver`: Incoming data operations

## Performance Benchmarks

### Expected Performance

- **Discovery time:** 5-15 seconds
- **Connection establishment:** 3-8 seconds
- **Message delivery:** < 1 second
- **File transfer speed:** 5-20 MB/s (depending on devices and proximity)

### File Size Limits

- **Maximum recommended file size:** 100 MB
- **Tested file types:** Images (JPG, PNG), Documents (PDF, TXT), Audio (MP3), Video (MP4)

## Known Limitations

1. **Range:** Wi-Fi Direct typically works within 50-100 meters
2. **Device limit:** One-to-one connections only (not group messaging)
3. **Background limitations:** File transfers may pause if app goes to background
4. **Android version compatibility:** Some features may vary between Android versions

## Troubleshooting Checklist

- [ ] Location services enabled
- [ ] Wi-Fi enabled
- [ ] App permissions granted
- [ ] Devices in range
- [ ] No interference from other devices
- [ ] Sufficient battery (low battery can affect Wi-Fi Direct)
- [ ] Latest app version installed

## Reporting Issues

When reporting issues, please include:

1. Device models and Android versions
2. Steps to reproduce the issue
3. Relevant log output
4. Network environment details
5. File types and sizes (for transfer issues)
