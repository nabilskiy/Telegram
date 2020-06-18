package com.lopei.util;

import android.content.SharedPreferences;

public class EncryptionContacts {
    public static final String ENCRYPTION_CONTACTS_PREFS = "encryptionContacts";
    private static final String PEER_SUFFIX = "peer_";

    private SharedPreferences sharedPreferences;

    public EncryptionContacts(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
    }

    public void setEncryptPeer(long peerId, boolean encrypt) {
        sharedPreferences.edit().putBoolean(PEER_SUFFIX + peerId, encrypt).apply();
    }

    public boolean isEncryptPeer(long peerId) {
        return sharedPreferences.getBoolean(PEER_SUFFIX + peerId, false);
    }
}
