package de.maxhenkel.voicechat.voice.common;

import de.maxhenkel.voicechat.util.FriendlyByteBuf;

import java.util.UUID;

public class ExternalSoundPacket {
	private Character type;
	private UUID destinationUser;
	private SoundPacket<?> soundPacket;
	private String source;

	public ExternalSoundPacket() {
	}

	public ExternalSoundPacket(UUID destinationUser, SoundPacket<?> soundPacket, String source) {
		if (soundPacket instanceof GroupSoundPacket) {
			this.type = 'G';
		} else if (soundPacket instanceof PlayerSoundPacket) {
			this.type = 'P';
		} else if (soundPacket instanceof LocationSoundPacket) {
			this.type = 'L';
		} else {
			throw new IllegalArgumentException("Unknown sound packet type");
		}
		this.destinationUser = destinationUser;
		this.soundPacket = soundPacket;
		this.source = source;
	}

	public Character getType() {
		return this.type;
	}

	public UUID getDestinationUser() {
		return this.destinationUser;
	}

	public SoundPacket<?> getSoundPacket() {
		return this.soundPacket;
	}

	public String getSource() {
		return this.source;
	}

	public void setType(Character type) {
		this.type = type;
	}

	public void setDestinationUser(UUID destinationUser) {
		this.destinationUser = destinationUser;
	}

	public void setSoundPacket(SoundPacket<?> soundPacket) {
		this.soundPacket = soundPacket;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public static ExternalSoundPacket fromBytes(FriendlyByteBuf buf) {
		ExternalSoundPacket soundPacket = new ExternalSoundPacket();
		soundPacket.type = buf.readChar();
		soundPacket.destinationUser = buf.readUUID();
		byte[] packet = buf.readByteArray();

		FriendlyByteBuf packetBuf = new FriendlyByteBuf();
		packetBuf.writeBytes(packet);

		if (soundPacket.type == 'G') {
			GroupSoundPacket groupSoundPacket = new GroupSoundPacket();
			soundPacket.soundPacket = groupSoundPacket.fromBytes(packetBuf);
		} else if (soundPacket.type == 'P') {
			PlayerSoundPacket playerSoundPacket = new PlayerSoundPacket();
			soundPacket.soundPacket = playerSoundPacket.fromBytes(packetBuf);
		} else if (soundPacket.type == 'L') {
			LocationSoundPacket locationSoundPacket = new LocationSoundPacket();
			soundPacket.soundPacket = locationSoundPacket.fromBytes(packetBuf);
		} else {
			throw new IllegalArgumentException("Unknown sound packet type");
		}

		soundPacket.source = buf.readUtf(32);

		return soundPacket;
	}

	public void toBytes(FriendlyByteBuf buf) {
		buf.writeChar(this.type);
		buf.writeUUID(this.destinationUser);

		FriendlyByteBuf soundPacketBuf = new FriendlyByteBuf();
		this.soundPacket.toBytes(soundPacketBuf);

		buf.writeByteArray(soundPacketBuf.array());
		buf.writeUtf(this.source, 32);
	}
}
