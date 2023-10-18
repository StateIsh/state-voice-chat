package de.maxhenkel.voicechat.util;

import com.github.puregero.multilib.MultiLib;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.voice.common.ExternalSoundPacket;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.common.SoundPacket;
import de.maxhenkel.voicechat.voice.server.Group;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

public final class ToExternal {
	public static void encodeSoundPacket(String serverName, UUID destPlayer, SoundPacket<?> packet, String source) {
		FriendlyByteBuf buf = new FriendlyByteBuf();

		ExternalSoundPacket externalPacket = new ExternalSoundPacket(destPlayer, packet, source);
		externalPacket.toBytes(buf);

		Player player = Bukkit.getPlayer(destPlayer);

		if (player != null) {
			if (Objects.equals(source, SoundPacketEvent.SOURCE_PROXIMITY)) {
				MultiLib.notifyOwningServer(player, "voicechat:proximity_sound_packet", buf.array());
			} else if (Objects.equals(source, SoundPacketEvent.SOURCE_GROUP)) {
				MultiLib.notifyOwningServer(player, "voicechat:group_sound_packet", buf.array());
			} else {
				MultiLib.notifyOwningServer(player, "voicechat:plugin_sound_packet", buf.array());
			}
		} else {
			if (Objects.equals(source, SoundPacketEvent.SOURCE_PROXIMITY)) {
				MultiLib.notify("voicechat:proximity_sound_packet", buf.array());
			} else if (Objects.equals(source, SoundPacketEvent.SOURCE_GROUP)) {
				MultiLib.notify("voicechat:group_sound_packet", buf.array());
			} else {
				MultiLib.notify("voicechat:plugin_sound_packet", buf.array());
			}
		}
	}

	public static byte[] externalEncodeSoundPacket(String serverName, UUID destPlayer, SoundPacket<?> packet, String source) {
		FriendlyByteBuf buf = new FriendlyByteBuf();
		ExternalSoundPacket externalPacket = new ExternalSoundPacket(destPlayer, packet, source);
		externalPacket.toBytes(buf);
		return buf.array();
	}

	public static SoundPacket<?> externalDecodeSoundPacket(byte[] data) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
		ExternalSoundPacket packet = ExternalSoundPacket.fromBytes(buf);
		return packet.getSoundPacket();
	}

	public static byte[] encodeGroup(Group group) {
		FriendlyByteBuf groupBuf = new FriendlyByteBuf();
		group.toBytes(groupBuf);
		return groupBuf.array();
	}

	public static byte[] encodeCompatibility(UUID playerUUID, int compatVersion) {
		FriendlyByteBuf compatBuf = new FriendlyByteBuf();
		compatBuf.writeUUID(playerUUID);
		compatBuf.writeInt(compatVersion);
		return compatBuf.array();
	}

	public static byte[] encodePlayerState(PlayerState state) {
		FriendlyByteBuf buf = new FriendlyByteBuf();
		state.toBytes(buf);
		return buf.array();
	}

	public static void inviteMessage(Group group, Player sender, Player receiver) {
		String passwordSuffix = group.getPassword() == null ? "" : " \"" + group.getPassword() + "\"";
		NetManager.sendMessage(receiver, Component.translatable("message.voicechat.invite",
				Component.text(sender.getName()),
				Component.text(group.getName()).toBuilder().color(NamedTextColor.GRAY).asComponent(),
				Component.text("[").toBuilder().append(
						Component.translatable("message.voicechat.accept_invite")
								.toBuilder()
								.clickEvent(ClickEvent.clickEvent(ClickEvent.Action.RUN_COMMAND, "/voicechat join " + group.getId().toString() + passwordSuffix))
								.hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("message.voicechat.accept_invite.hover")))
								.color(NamedTextColor.GREEN)
								.build()
				).append(Component.text("]")).color(NamedTextColor.GREEN).asComponent()
		));
		NetManager.sendMessage(sender, Component.translatable("message.voicechat.invite_successful", Component.text(receiver.getName())));
	}
}
