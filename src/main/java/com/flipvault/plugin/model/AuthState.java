package com.flipvault.plugin.model;

public enum AuthState {
    NO_KEY,          // No API key stored, show login panel
    VALIDATING,      // Startup: validating stored key with backend
    VALID,           // Key valid and bound to current player, normal operation
    KEY_CONFLICT,    // Key bound to a different player
    EXPIRED,         // Key expired or subscription lapsed
    LOGGING_IN,      // Login request in flight, show spinner
    POLLING_BROWSER, // Browser auth in progress, polling for completion
    SELECTING_KEY,   // Login succeeded, user picking from key dropdown
    ERROR            // Network error during auth, show retry
}
