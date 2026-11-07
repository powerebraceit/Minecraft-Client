package dev.aurora.launcher.auth;

/** A stable error code and a user-facing message, never a token response body. */
public final class AuthException extends Exception {
    private final String code;
    public AuthException(String code, String message) { super(message); this.code = code; }
    public AuthException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}
