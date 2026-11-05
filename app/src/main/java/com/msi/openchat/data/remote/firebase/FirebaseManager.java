package com.msi.openchat.data.remote.firebase;

import android.util.Log;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

/**
 * FirebaseManager
 * Java class handling initialization, authentication, and Realtime Database references
 * for users, messages, conversations, signaling, and calls.
 * Provides both public static fields and getters for 100% interoperability with Java and Kotlin.
 */
public class FirebaseManager {
    private static final String TAG = "FirebaseManager";
    public static final String DATABASE_URL = "https://msi-dev-2705a-default-rtdb.asia-southeast1.firebasedatabase.app/";
    public static final String STORAGE_BUCKET = "msi-dev-2705a.firebasestorage.app";

    private static volatile FirebaseManager sInstance;

    // Public static fields for direct Kotlin/Java property access (e.g. FirebaseManager.auth, FirebaseManager.usersRef)
    public static FirebaseAuth auth;
    public static FirebaseDatabase database;
    public static FirebaseStorage storage;

    public static DatabaseReference usersRef;
    public static DatabaseReference usernamesRef;
    public static DatabaseReference phoneNumbersRef;
    public static DatabaseReference emailsRef;
    public static DatabaseReference friendsRef;
    public static DatabaseReference friendRequestsRef;
    public static DatabaseReference conversationsRef;
    public static DatabaseReference messagesRef;
    public static DatabaseReference typingRef;
    public static DatabaseReference callsRef;
    public static DatabaseReference callSignalingRef;
    public static DatabaseReference connectedRef;
    public static DatabaseReference mediaMetadataRef;
    public static DatabaseReference conversationMediaRef;

    public static StorageReference avatarsStorageRef;
    public static StorageReference chatImagesStorageRef;
    public static StorageReference chatAudioStorageRef;
    public static StorageReference chatVideosStorageRef;
    public static StorageReference chatAttachmentsStorageRef;

    static {
        init();
    }

    private FirebaseManager() {}

    public static FirebaseManager getInstance() {
        if (sInstance == null) {
            synchronized (FirebaseManager.class) {
                if (sInstance == null) {
                    sInstance = new FirebaseManager();
                    init();
                }
            }
        }
        return sInstance;
    }

    public static synchronized void init() {
        try {
            android.content.Context ctx = com.msi.openchat.core.di.ServiceLocator.INSTANCE.getContext();
            if (ctx != null && com.google.firebase.FirebaseApp.getApps(ctx).isEmpty()) {
                com.google.firebase.FirebaseOptions options = new com.google.firebase.FirebaseOptions.Builder()
                        .setApplicationId("1:610942504921:android:79c60acb8ad7a63f81d3c8")
                        .setApiKey("AIzaSyAs5oHilVqw6a8K2bRk3l7tJZHEPZ-sZr4")
                        .setProjectId("msi-dev-2705a")
                        .setDatabaseUrl(DATABASE_URL)
                        .setStorageBucket(STORAGE_BUCKET)
                        .build();
                com.google.firebase.FirebaseApp.initializeApp(ctx, options);
            }
        } catch (Exception ignored) {}

        try {
            auth = FirebaseAuth.getInstance();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing FirebaseAuth: " + e.getMessage());
        }

        try {
            database = FirebaseDatabase.getInstance(DATABASE_URL);
            try {
                database.setPersistenceEnabled(true);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            try {
                database = FirebaseDatabase.getInstance();
            } catch (Exception ex) {
                Log.e(TAG, "Error initializing FirebaseDatabase: " + ex.getMessage());
            }
        }

        try {
            storage = FirebaseStorage.getInstance("gs://" + STORAGE_BUCKET);
        } catch (Exception e) {
            try {
                storage = FirebaseStorage.getInstance();
            } catch (Exception ex) {
                Log.e(TAG, "Error initializing FirebaseStorage: " + ex.getMessage());
            }
        }

        if (database != null) {
            usersRef = database.getReference("users");
            usernamesRef = database.getReference("usernames");
            phoneNumbersRef = database.getReference("phone_numbers");
            emailsRef = database.getReference("emails");
            friendsRef = database.getReference("friends");
            friendRequestsRef = database.getReference("friend_requests");
            conversationsRef = database.getReference("conversations");
            messagesRef = database.getReference("messages");
            typingRef = database.getReference("typing");
            callsRef = database.getReference("calls");
            callSignalingRef = database.getReference("call_signaling");
            connectedRef = database.getReference(".info/connected");
            mediaMetadataRef = database.getReference("media_metadata");
            conversationMediaRef = database.getReference("conversation_media");
        }

        if (storage != null) {
            avatarsStorageRef = storage.getReference().child("avatars");
            chatImagesStorageRef = storage.getReference().child("chat_images");
            chatAudioStorageRef = storage.getReference().child("chat_audio");
            chatVideosStorageRef = storage.getReference().child("chat_videos");
            chatAttachmentsStorageRef = storage.getReference().child("chat_attachments");
        }
    }

    // Static getters for standard Java bean access
    public static FirebaseAuth getAuth() {
        if (auth == null) init();
        return auth;
    }

    public static FirebaseDatabase getDatabase() {
        if (database == null) init();
        return database;
    }

    public static FirebaseStorage getStorage() {
        if (storage == null) init();
        return storage;
    }

    public static DatabaseReference getUsersRef() {
        if (usersRef == null) init();
        return usersRef;
    }

    public static DatabaseReference getUsernamesRef() {
        if (usernamesRef == null) init();
        return usernamesRef;
    }

    public static DatabaseReference getPhoneNumbersRef() {
        if (phoneNumbersRef == null) init();
        return phoneNumbersRef;
    }

    public static DatabaseReference getEmailsRef() {
        if (emailsRef == null) init();
        return emailsRef;
    }

    public static DatabaseReference getFriendsRef() {
        if (friendsRef == null) init();
        return friendsRef;
    }

    public static DatabaseReference getFriendRequestsRef() {
        if (friendRequestsRef == null) init();
        return friendRequestsRef;
    }

    public static DatabaseReference getConversationsRef() {
        if (conversationsRef == null) init();
        return conversationsRef;
    }

    public static DatabaseReference getMessagesRef() {
        if (messagesRef == null) init();
        return messagesRef;
    }

    public static DatabaseReference getTypingRef() {
        if (typingRef == null) init();
        return typingRef;
    }

    public static DatabaseReference getCallsRef() {
        if (callsRef == null) init();
        return callsRef;
    }

    public static DatabaseReference getCallSignalingRef() {
        if (callSignalingRef == null) init();
        return callSignalingRef;
    }

    public static DatabaseReference getConnectedRef() {
        if (connectedRef == null) init();
        return connectedRef;
    }

    public static DatabaseReference getMediaMetadataRef() {
        if (mediaMetadataRef == null) init();
        return mediaMetadataRef;
    }

    public static DatabaseReference getConversationMediaRef() {
        if (conversationMediaRef == null) init();
        return conversationMediaRef;
    }

    public static StorageReference getAvatarsStorageRef() {
        if (avatarsStorageRef == null) init();
        return avatarsStorageRef;
    }

    public static StorageReference getChatImagesStorageRef() {
        if (chatImagesStorageRef == null) init();
        return chatImagesStorageRef;
    }

    public static StorageReference getChatAudioStorageRef() {
        if (chatAudioStorageRef == null) init();
        return chatAudioStorageRef;
    }

    public static StorageReference getChatVideosStorageRef() {
        if (chatVideosStorageRef == null) init();
        return chatVideosStorageRef;
    }

    public static StorageReference getChatAttachmentsStorageRef() {
        if (chatAttachmentsStorageRef == null) init();
        return chatAttachmentsStorageRef;
    }
}
