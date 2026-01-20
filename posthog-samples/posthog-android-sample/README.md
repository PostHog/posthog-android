# PostHog Android Sample App

This sample app demonstrates how to integrate the PostHog Android SDK, including push notification support via Firebase Cloud Messaging (FCM).

## Setup

### Firebase Configuration (Optional - for Push Notifications)

To enable push notification features in this sample app, you need to configure Firebase:

1. **Create a Firebase Project** (if you don't have one):
   - Go to [Firebase Console](https://console.firebase.google.com/)
   - Click "Add project" and follow the setup wizard

2. **Add Android App to Firebase**:
   - In your Firebase project, click "Add app" and select Android
   - Enter the package name: `com.posthog.android.sample`
   - Register the app

3. **Download `google-services.json`**:
   - Download the `google-services.json` file from Firebase Console
   - Place it in the `posthog-samples/posthog-android-sample/` directory (same level as `build.gradle.kts`)

4. **Verify the file location**:
   ```
   posthog-samples/posthog-android-sample/
   ├── build.gradle.kts
   ├── google-services.json  ← Place it here
   └── src/
   ```

**Note:** The `google-services.json` file is gitignored as it contains project-specific configuration. Each developer needs to add their own file.

### Running Without Firebase

If you don't have a `google-services.json` file, the app will still run but push notification features will be disabled:
- FCM token registration will be skipped
- Push notifications will not be received
- All other PostHog SDK features will work normally
