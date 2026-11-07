package dev.aurora.launcher.auth;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** The short-lived code is displayed to the user, but never included in routine logging. */
public record DeviceCode(String userCode, URI verificationUri, Instant expiresAt) {
    public DeviceCode {
        Objects.requireNonNull(userCode);
        Objects.requireNonNull(verificationUri);
        Objects.requireNonNull(expiresAt);
    }
    @Override public String toString() { return "DeviceCode[code=REDACTED, expiresAt=" + expiresAt + "]"; }
}
