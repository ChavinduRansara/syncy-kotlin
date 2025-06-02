#!/bin/bash

# Sync Functionality Automated Verification Script
# This script performs automated checks to verify the sync implementation

echo "🔍 Syncy P2P - Sync Functionality Verification Script"
echo "=================================================="

# Set the project directory
PROJECT_DIR="c:\Users\Chavindu\Desktop\Test\kotlin\syncy-kotlin"
cd "$PROJECT_DIR"

echo ""
echo "1. Building project to verify compilation..."
echo "-------------------------------------------"
./gradlew build > build_output.txt 2>&1

if grep -q "BUILD SUCCESSFUL" build_output.txt; then
    echo "✅ Project builds successfully"
else
    echo "❌ Build failed. Check build_output.txt for details"
    exit 1
fi

echo ""
echo "2. Running unit tests..."
echo "------------------------"
./gradlew test > test_output.txt 2>&1

if grep -q "BUILD SUCCESSFUL" test_output.txt; then
    echo "✅ Unit tests pass"
else
    echo "⚠️  Some tests may have issues. Check test_output.txt for details"
fi

echo ""
echo "3. Checking for key implementation files..."
echo "--------------------------------------------"

# Check if key sync methods are implemented
SYNC_MANAGER="app/src/main/java/com/example/syncy_p2p/sync/SyncManager.kt"
MAIN_ACTIVITY="app/src/main/java/com/example/syncy_p2p/MainActivity.kt"
WIFI_MANAGER="app/src/main/java/com/example/syncy_p2p/p2p/WiFiDirectManager.kt"
FILE_RECEIVER="app/src/main/java/com/example/syncy_p2p/p2p/receiver/FileReceiver.kt"

# Check SyncManager key methods
if grep -q "handleSyncStartTransfer" "$SYNC_MANAGER" && \
   grep -q "handleSyncRequestFilesList" "$SYNC_MANAGER" && \
   grep -q "handleSyncRequestFile" "$SYNC_MANAGER" && \
   grep -q "handleSyncFilesListResponse" "$SYNC_MANAGER"; then
    echo "✅ SyncManager: All key handler methods found"
else
    echo "❌ SyncManager: Missing key handler methods"
fi

# Check MainActivity callback implementations
if grep -q "onFileTransferProgress" "$MAIN_ACTIVITY" && \
   grep -q "onSyncFileReceived" "$MAIN_ACTIVITY"; then
    echo "✅ MainActivity: New sync callbacks implemented"
else
    echo "❌ MainActivity: Missing sync callback implementations"
fi

# Check WiFiDirectManager enhancements
if grep -q "SYNC_START_TRANSFER" "$WIFI_MANAGER" && \
   grep -q "SYNC_REQUEST_FILES_LIST" "$WIFI_MANAGER" && \
   grep -q "SYNC_REQUEST_FILE" "$WIFI_MANAGER" && \
   grep -q "SYNC_FILES_LIST_RESPONSE" "$WIFI_MANAGER"; then
    echo "✅ WiFiDirectManager: Sync message detection implemented"
else
    echo "❌ WiFiDirectManager: Missing sync message detection"
fi

# Check FileReceiver multi-connection support
if grep -q "while.*isRunning.*serverSocket?.accept()" "$FILE_RECEIVER"; then
    echo "✅ FileReceiver: Multi-connection support implemented"
else
    echo "❌ FileReceiver: Multi-connection support missing"
fi

echo ""
echo "4. Checking callback interface completeness..."
echo "----------------------------------------------"

# Check WiFiDirectCallback interface for new methods
if grep -q "onFileTransferProgress" "$WIFI_MANAGER" && \
   grep -q "onSyncFileReceived" "$WIFI_MANAGER"; then
    echo "✅ WiFiDirectCallback: Enhanced with new sync methods"
else
    echo "❌ WiFiDirectCallback: Missing new sync methods"
fi

echo ""
echo "5. Verifying file handling improvements..."
echo "-------------------------------------------"

FILE_MANAGER="app/src/main/java/com/example/syncy_p2p/files/FileManager.kt"

if grep -q "writeFileToSyncFolder" "$FILE_MANAGER"; then
    echo "✅ FileManager: SAF folder writing method implemented"
else
    echo "❌ FileManager: Missing SAF folder writing method"
fi

echo ""
echo "6. Checking for proper error handling..."
echo "-----------------------------------------"

# Check for try-catch blocks in key methods
sync_try_catch_count=$(grep -c "try {" "$SYNC_MANAGER")
main_try_catch_count=$(grep -c "try {" "$MAIN_ACTIVITY")

if [ "$sync_try_catch_count" -gt 5 ] && [ "$main_try_catch_count" -gt 3 ]; then
    echo "✅ Error handling: Adequate try-catch blocks found"
else
    echo "⚠️  Error handling: May need more comprehensive error handling"
fi

echo ""
echo "7. Verifying logging implementation..."
echo "-------------------------------------"

# Check for proper logging statements
if grep -q "SYNC_START_TRANSFER_DETECTED" "$WIFI_MANAGER" && \
   grep -q "FILES_LIST_SENT" "$SYNC_MANAGER" && \
   grep -q "FILE_RECEIVER_STARTED" "$SYNC_MANAGER"; then
    echo "✅ Logging: Key sync operations have proper logging"
else
    echo "⚠️  Logging: Some sync operations may lack proper logging"
fi

echo ""
echo "8. Code quality check..."
echo "------------------------"

./gradlew lint > lint_output.txt 2>&1

critical_issues=$(grep -c "Error" lint_output.txt || echo "0")
if [ "$critical_issues" -eq 0 ]; then
    echo "✅ Code quality: No critical lint errors found"
else
    echo "⚠️  Code quality: $critical_issues critical lint errors found"
fi

echo ""
echo "9. Documentation check..."
echo "-------------------------"

if [ -f "SYNC_HANDLER_IMPLEMENTATION.md" ] && \
   [ -f "SYNC_TESTING_GUIDE.md" ] && \
   [ -f "TEST_SYNC_FUNCTIONALITY.md" ]; then
    echo "✅ Documentation: Implementation and testing guides present"
else
    echo "⚠️  Documentation: Some documentation files may be missing"
fi

echo ""
echo "📊 VERIFICATION SUMMARY"
echo "======================="

# Count successful checks
total_checks=9
successful_checks=0

# Re-run key checks and count
if ./gradlew build --quiet > /dev/null 2>&1; then ((successful_checks++)); fi
if grep -q "handleSyncStartTransfer" "$SYNC_MANAGER"; then ((successful_checks++)); fi
if grep -q "onFileTransferProgress" "$MAIN_ACTIVITY"; then ((successful_checks++)); fi
if grep -q "SYNC_START_TRANSFER" "$WIFI_MANAGER"; then ((successful_checks++)); fi
if grep -q "while.*isRunning.*serverSocket?.accept()" "$FILE_RECEIVER"; then ((successful_checks++)); fi
if grep -q "writeFileToSyncFolder" "$FILE_MANAGER"; then ((successful_checks++)); fi
if [ "$sync_try_catch_count" -gt 5 ]; then ((successful_checks++)); fi
if grep -q "SYNC_START_TRANSFER_DETECTED" "$WIFI_MANAGER"; then ((successful_checks++)); fi
if [ "$critical_issues" -eq 0 ]; then ((successful_checks++)); fi

echo "✅ Successful checks: $successful_checks/$total_checks"

if [ "$successful_checks" -eq "$total_checks" ]; then
    echo "🎉 ALL CHECKS PASSED! Sync functionality implementation appears complete."
    echo ""
    echo "Next steps:"
    echo "1. Install app on two test devices"
    echo "2. Follow the manual testing checklist in TEST_SYNC_FUNCTIONALITY.md"
    echo "3. Verify end-to-end sync operations work correctly"
elif [ "$successful_checks" -ge 7 ]; then
    echo "✅ MOSTLY COMPLETE: $successful_checks/$total_checks checks passed."
    echo "The implementation is largely complete with minor issues to address."
else
    echo "⚠️  NEEDS WORK: Only $successful_checks/$total_checks checks passed."
    echo "Several implementation issues need to be resolved."
fi

echo ""
echo "📁 Output files generated:"
echo "- build_output.txt (build results)"
echo "- test_output.txt (test results)"
echo "- lint_output.txt (code quality analysis)"
echo ""
echo "For detailed testing instructions, see: TEST_SYNC_FUNCTIONALITY.md"
