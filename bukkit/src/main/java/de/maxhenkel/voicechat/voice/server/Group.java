package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.voice.common.ClientGroup;

import javax.annotation.Nullable;
import java.util.UUID;

public class Group {

    private UUID id;
    private String name;
    @Nullable
    private String password;

    public Group(UUID id, String name, @Nullable String password) {
        this.id = id;
        this.name = name;
        this.password = password;
    }

    public Group(UUID id, String name) {
        this(id, name, null);
    }

    public Group() {

    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Nullable
    public String getPassword() {
        return password;
    }

    public ClientGroup toClientGroup() {
        return new ClientGroup(id, name, password != null);
    }

	public Group fromBytes(FriendlyByteBuf buf) {
		this.id = buf.readUUID();
		this.name = buf.readUtf(512);
		if (buf.readBoolean()) {
			this.password = buf.readUtf(512);
		}
		return this;
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeUUID(this.id);
		buf.writeUtf(this.name);
		buf.writeBoolean(this.password != null);
		buf.writeUtf(this.password, 512);
	}

}