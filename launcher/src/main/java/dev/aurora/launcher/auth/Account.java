package dev.aurora.launcher.auth;

import java.time.Instant;
import java.util.Objects;

/** Minecraft credentials stay in memory. Only the separate refresh-token store persists a secret. */
public final class Account {
    private final String accessToken;
    private final String refreshToken;
    private final String name;
    private final String uuid;
    private final Instant expiresAt;
    private final String xuid;

    public Account(String accessToken, String refreshToken, String name, String uuid, Instant expiresAt) {
        this(accessToken, refreshToken, name, uuid, "", expiresAt);
    }
    public Account(String accessToken, String refreshToken, String name, String uuid, String xuid, Instant expiresAt) {
        this.accessToken = Objects.requireNonNull(accessToken);
        this.refreshToken = Objects.requireNonNull(refreshToken);
        this.name = Objects.requireNonNull(name);
        this.uuid = Objects.requireNonNull(uuid);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.xuid = Objects.requireNonNull(xuid);
    }
    public String accessToken() { return accessToken; }
    public String refreshToken() { return refreshToken; }
    public String name() { return name; }
    public String uuid() { return uuid; }
    public String xuid() { return xuid; }
    public Instant expiresAt() { return expiresAt; }
    public boolean validAt(Instant time) { return expiresAt.isAfter(time.plusSeconds(30)); }
    @Override public String toString() { return "Account[name=" + name + ", credentials=REDACTED]"; }
}
