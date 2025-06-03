# Syncy P2P - Android Wi-Fi Direct File Sync & Messaging

A comprehensive Android application that enables peer-to-peer connectivity for offline file synchronization and messaging using Wi-Fi Direct technology.

## ✅ Implementation Status

### **COMPLETED FEATURES**

- ✅ **Wi-Fi Direct Infrastructure**: Complete Wi-Fi Direct manager with discovery, connection, and group management
- ✅ **Real-time Messaging**: TCP socket-based messaging system with reliable delivery
- ✅ **File Transfer System**: Robust file sharing with progress tracking and error handling
- ✅ **Folder Synchronization**: Complete sync functionality with three distinct modes:
  - **Two-way Sync**: Bidirectional synchronization where changes on either device are merged to both
  - **One-way Backup**: Source device backs up files to destination (destination files are not sent back)
  - **One-way Mirror**: Destination becomes exact copy of source (including deletions to match source)
  - **Real-time Conflict Detection**: Automatic handling of file conflicts with user-configurable resolution
  - **Progress Tracking**: Real-time sync progress with file-by-file status updates
- ✅ **Storage Access Framework (SAF)**: Files saved directly to user-selected folders with proper permissions
- ✅ **Sync Management**: Dedicated interface for managing synced folders, viewing logs, and handling conflicts
- ✅ **Modern UI**: Material Design interface with reactive updates using StateFlow
- ✅ **Background Services**: Foreground services for reliable message and file transfers
- ✅ **Permission Management**: Comprehensive runtime permission handling
- ✅ **Error Handling**: Extensive error recovery and connection state management
- ✅ **Build Configuration**: Optimized Gradle build with all required dependencies

### **TECHNICAL ARCHITECTURE**

- **Core Framework**: Wi-Fi Direct API with Kotlin Coroutines for async operations
- **Communication**: TCP sockets for reliable data transfer (messages and files)
- **UI Pattern**: MVVM with ViewBinding and StateFlow for reactive updates
- **Background Processing**: Foreground services with notification support
- **File Management**: Scoped storage compliance with proper file handling

### **PROJECT STRUCTURE**

```
app/src/main/java/com/example/syncy_p2p/
├── MainActivity.kt                     # Main UI and coordination
├── p2p/
│   ├── WiFiDirectManager.kt           # Core Wi-Fi Direct operations
│   ├── core/
│   │   ├── Config.kt                  # Configuration constants
│   │   ├── Event.kt                   # Event types
│   │   ├── Utils.kt                   # Utility functions
│   │   ├── Models.kt                  # Data models
│   │   └── EventReceiver.kt           # System event handling
│   ├── sender/
│   │   ├── MessageSender.kt           # Message transmission service
│   │   └── FileSender.kt             # File transfer service
│   └── receiver/
│       ├── MessageReceiver.kt         # Message reception handler
│       └── FileReceiver.kt           # File reception handler
├── sync/
│   ├── SyncManager.kt                 # Core synchronization logic
│   ├── SyncModels.kt                  # Sync data models and types
│   └── SyncConfiguration.kt           # Sync configuration management
├── files/
│   └── FileManager.kt                 # Storage Access Framework handling
└── ui/
    ├── PeerAdapter.kt                 # RecyclerView adapter for peer list
    ├── SyncManagementActivity.kt      # Sync folder management UI
    └── SyncedFoldersAdapter.kt        # Adapter for synced folder list
```

## 🚀 **READY FOR TESTING**

The application is now **fully functional** and ready for testing on physical Android devices.

### **Quick Start for Testing**

1. **Build the APK**: `./gradlew assembleDebug`
2. **Install on 2+ devices**: `adb install app/build/outputs/apk/debug/app-debug.apk`
3. **Grant permissions**: Location, Nearby devices, Storage, Notifications
4. **Test discovery**: Tap "Discover Peers" on both devices
5. **Connect**: Select a peer to establish connection
6. **Test messaging**: Send text messages between devices
7. **Test file transfer**: Use "Send File" to share files
8. **Test folder sync**: Select a folder and use "Sync Folder" for complete folder synchronization
   - **Two-way Sync**: Both devices exchange and merge files bidirectionally
   - **One-way Backup**: Source device sends files to destination only
   - **One-way Mirror**: Destination becomes exact replica of source folder

### **Key Features Working**

- ✅ **Device Discovery**: Automatic peer detection within Wi-Fi Direct range
- ✅ **One-tap Connection**: Simple peer selection for connection establishment
- ✅ **Manual Disconnection**: Easy disconnect with confirmation dialog
- ✅ **Instant Messaging**: Real-time text communication
- ✅ **File Sharing**: Transfer any file type with progress indication
- ✅ **Folder Synchronization**: Complete folder sync with multiple modes and conflict resolution
  - **Two-way Sync**: Merges changes from both devices bidirectionally
  - **One-way Backup**: Copies files from source to destination only
  - **One-way Mirror**: Makes destination an exact copy of source (with deletions)
- ✅ **Sync Management**: Dedicated UI for managing synced folders and viewing sync logs
- ✅ **Connection Management**: Automatic state updates and error recovery
- ✅ **Background Operation**: Continues working when app is backgrounded

## 🔄 **SYNC MODES EXPLAINED**

The Syncy P2P application supports three distinct synchronization modes, each designed for different use cases:

### **1. Two-Way Sync (Bidirectional)**
- **Use Case**: Collaborative sharing between equal devices
- **Behavior**: 
  - Both devices send and receive files
  - Files unique to each device are copied to the other
  - File conflicts are detected and handled based on user preferences
  - Both folders end up containing all files from both sources
- **Example**: Two team members sharing a project folder where both contribute files

### **2. One-Way Backup**
- **Use Case**: Backing up files from source to destination
- **Behavior**:
  - Only source device sends files to destination
  - Destination device does not send files back
  - Files unique to destination are preserved (not deleted)
  - Source folder remains unchanged, destination gains new files
- **Example**: Backing up photos from phone to tablet for safekeeping

### **3. One-Way Mirror**
- **Use Case**: Creating an exact replica of source folder
- **Behavior**:
  - Only source device sends files to destination
  - Destination becomes identical to source
  - Files that exist only on destination are deleted
  - Result is destination folder exactly matching source folder
- **Example**: Updating a presentation folder where destination must match master copy exactly

### **Conflict Resolution Options**
When files with the same name but different content are found:
- **Keep Newer**: File with more recent modification date is kept
- **Keep Larger**: File with larger size is kept
- **Overwrite Local/Remote**: Always prefer one side
- **Keep Both**: Rename one file to preserve both versions
- **Ask User**: Prompt user to decide for each conflict

## 📱 **HARDWARE REQUIREMENTS**

- **Minimum**: Android 9.0+ (API 28)
- **Recommended**: Android 10.0+ for optimal performance
- **Wi-Fi Direct support** (available on most modern Android devices)
- **Location services** enabled for peer discovery

## 🔧 **DEVELOPMENT NOTES**

### **Build System**

- **Gradle**: Kotlin DSL with optimized dependencies
- **Lint**: Configured to handle expected Wi-Fi Direct permission warnings
- **ViewBinding**: Enabled for type-safe UI access
- **Coroutines**: Used throughout for async operations

### **Security & Permissions**

```xml
<!-- Required for Wi-Fi Direct operations -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />

<!-- Required for file operations -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<!-- Required for background services -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

## 📋 **NEXT STEPS**

### **Phase 1: Testing & Validation** ⏭️

1. **Performance Optimization**: Monitor and optimize sync performance for large folders
2. **Edge Case Validation**: Test various network conditions and device scenarios
3. **User Experience Refinement**: Polish UI flows and error handling

### **Phase 2: Enhanced Features** (Future Development)

- **Group Messaging**: Support for multiple device connections
- **Message History**: Persistent message storage and history
- **Contact Management**: Device nickname and trusted device lists
- **Transfer Resume**: Resume interrupted file transfers
- **Progress Notifications**: Detailed transfer progress in notification area

### **Phase 3: Advanced Features** (Future Development)

- **Encryption**: End-to-end encryption for messages and files
- **Mesh Network**: Multi-hop communication through intermediate devices
- **Cloud Backup**: Optional cloud sync for message history
- **Cross-platform**: Consider iOS compatibility

## 🐛 **CURRENT STATUS & LIMITATIONS**

### **✅ WORKING FEATURES**
- **Complete folder synchronization** with three modes:
  - **Two-way Sync**: Both devices contribute files and receive updates from each other
  - **One-way Backup**: Source device uploads files to destination (backup scenario)
  - **One-way Mirror**: Destination becomes identical to source including file deletions
- Files correctly saved to user-accessible folders
- Real-time conflict detection and resolution
- Progress tracking and sync logging
- Storage Access Framework integration

### **⚠️ KNOWN LIMITATIONS**

- **Range dependency**: Limited by Wi-Fi Direct range (typically 50-100 meters)
- **Android version variations**: Some features may behave differently across Android versions
- **Background limitations**: Android battery optimization may affect background transfers
- **Large file sync**: Performance optimization needed for very large folder synchronization


**The Syncy P2P application is now complete with full folder synchronization capabilities and ready for comprehensive real-world testing on Android devices!**

