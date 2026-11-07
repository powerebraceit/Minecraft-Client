package dev.aurora.launcher.minecraft;

/** Authenticated Minecraft identity. Tokens are deliberately omitted from diagnostic text. */
public record GameIdentity(String name,String uuid,String accessToken,String xuid,String clientId) {
    public GameIdentity(String name,String uuid,String accessToken) {this(name,uuid,accessToken,"","");}
    public GameIdentity(String name,String uuid,String accessToken,String xuid) {this(name,uuid,accessToken,xuid,"");}
    public GameIdentity {
        if(name==null || name.isBlank() || uuid==null || !uuid.replace("-","").matches("[0-9a-fA-F]{32}") || accessToken==null || accessToken.isBlank())
            throw new IllegalArgumentException("Logga in på ett giltigt Minecraft-konto först");
        uuid=uuid.replace("-","");xuid=xuid==null?"":xuid;clientId=clientId==null?"":clientId;
    }
    @Override public String toString() {return "GameIdentity[name="+name+", uuid="+uuid+", credentials=<redacted>]";}
}
