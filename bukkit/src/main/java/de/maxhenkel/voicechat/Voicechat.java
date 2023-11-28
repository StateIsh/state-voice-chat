package de.maxhenkel.voicechat;

import com.github.puregero.multilib.MultiLib;
import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.command.VoiceChatCommands;
import de.maxhenkel.voicechat.compatibility.BukkitCompatibilityManager;
import de.maxhenkel.voicechat.compatibility.Compatibility;
import de.maxhenkel.voicechat.compatibility.Compatibility1_20;
import de.maxhenkel.voicechat.compatibility.IncompatibleBukkitVersionException;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.config.Translations;
import de.maxhenkel.voicechat.integration.commodore.CommodoreCommands;
import de.maxhenkel.voicechat.integration.placeholderapi.VoicechatExpansion;
import de.maxhenkel.voicechat.integration.viaversion.ViaVersionCompatibility;
import de.maxhenkel.voicechat.logging.JavaLoggingLogger;
import de.maxhenkel.voicechat.logging.VoicechatLogger;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.plugins.impl.BukkitVoicechatServiceImpl;
import de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl;
import de.maxhenkel.voicechat.plugins.impl.audiochannel.AudioChannelImpl;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.util.ToExternal;
import de.maxhenkel.voicechat.voice.common.ExternalSoundPacket;
import de.maxhenkel.voicechat.voice.common.NetworkMessage;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.common.SoundPacket;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import io.netty.buffer.Unpooled;
import me.lucko.commodore.Commodore;
import me.lucko.commodore.CommodoreProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.regex.Pattern;

public final class Voicechat extends JavaPlugin {

    public static Voicechat INSTANCE;

    public static final String MODID = "voicechat";
    public static VoicechatLogger LOGGER;

    public static int COMPATIBILITY_VERSION = 17;

    public static ServerConfig SERVER_CONFIG;
    public static Translations TRANSLATIONS;
    public static ServerVoiceEvents SERVER;

    public static BukkitVoicechatServiceImpl apiService;
    public static NetManager netManager;

    public static Compatibility compatibility;

    public static final Pattern GROUP_REGEX = Pattern.compile("^[^\\n\\r\\t\\s][^\\n\\r\\t]{0,23}$");

    @Override
    public void onEnable() {
        INSTANCE = this;
        LOGGER = new JavaLoggingLogger(getLogger());

        if (debugMode()) {
            LOGGER.warn("Running in debug mode - Don't leave this enabled in production!");
        }

        compatibility = new Compatibility1_20();

        LOGGER.info("Compatibility version {}", COMPATIBILITY_VERSION);

        SERVER_CONFIG = ConfigBuilder.builder(ServerConfig::new).path(getDataFolder().toPath().resolve("voicechat-server.properties")).build();
        TRANSLATIONS = ConfigBuilder.builder(Translations::new).path(getDataFolder().toPath().resolve("translations.properties")).build();

        netManager = new NetManager();
        netManager.onEnable();

        apiService = new BukkitVoicechatServiceImpl();
        getServer().getServicesManager().register(BukkitVoicechatService.class, apiService, this, ServicePriority.Normal);

        PluginCommand voicechatCommand = getCommand(VoiceChatCommands.VOICECHAT_COMMAND);
        if (voicechatCommand != null) {
            VoiceChatCommands voiceChatCommands = new VoiceChatCommands();
            voicechatCommand.setExecutor(voiceChatCommands);
            voicechatCommand.setTabCompleter(voiceChatCommands);
            try {
                if (CommodoreProvider.isSupported()) {
                    Commodore commodore = CommodoreProvider.getCommodore(this);
                    CommodoreCommands.registerCompletions(commodore);
                    LOGGER.info("Successfully initialized commodore command completion");
                } else {
                    LOGGER.warn("Commodore command completion is not supported");
                }
            } catch (Throwable t) {
                LOGGER.warn("Failed to initialize commodore command completion", t);
            }
        } else {
            LOGGER.error("Failed to register commands");
        }

        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                new VoicechatExpansion().register();
                LOGGER.info("Successfully registered PlaceholderAPI expansion");
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to register PlaceholderAPI expansion", t);
        }

        try {
            if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
                ViaVersionCompatibility.register();
                LOGGER.info("Successfully added ViaVersion mappings");
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to add ViaVersion mappings", t);
        }

        if (System.getProperty("VOICECHAT_RELOADED") != null) {
            LOGGER.error("Simple Voice Chat does not support reloads! Expect that things will break!");
        }
        System.setProperty("VOICECHAT_RELOADED", "true");

        compatibility.runTask(() -> {
            SERVER = new ServerVoiceEvents();
            PluginManager.instance().init();
            SERVER.init();

            Bukkit.getPluginManager().registerEvents(SERVER, this);
            Bukkit.getPluginManager().registerEvents(SERVER.getServer().getPlayerStateManager(), this);
        });

		MultiLib.on(this, "voicechat:external_invite", (data) -> {
			FriendlyByteBuf inviteBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			Group group = SERVER.getServer().getGroupManager().getGroup(inviteBuf.readUUID());
			Player sender = Bukkit.getPlayer(inviteBuf.readUUID());
			Player receiver = Bukkit.getPlayer(inviteBuf.readUUID());

			if (receiver != null) {
				ToExternal.inviteMessage(group, sender, receiver);
			}
		});

        MultiLib.onString(this, "voicechat:playerstate_request", (data) -> {
            UUID uuid = UUID.fromString(data);
            Player player = Bukkit.getPlayer(uuid);
            PlayerState state = SERVER.getServer().getPlayerStateManager().getState(uuid);
            if (player != null && MultiLib.isLocalPlayer(player) && state != null) {
                MultiLib.notify("voicechat:add_playerstate", ToExternal.encodePlayerState(state));
            }
        });

		MultiLib.on(this, "voicechat:add_playerstate", (data) -> {
			FriendlyByteBuf playerStateBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			PlayerState playerState = PlayerState.fromBytes(playerStateBuf);
			SERVER.getServer().getPlayerStateManager().addState(playerState);
		});

		MultiLib.onString(this, "voicechat:remove_playerstate", (data) -> {
			SERVER.getServer().getPlayerStateManager().removeState(UUID.fromString(data));
		});

		MultiLib.on(this, "voicechat:update_compatibility", (data) -> {
			FriendlyByteBuf compatBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			UUID playerUUID = compatBuf.readUUID();
			int compatibility = compatBuf.readInt();

			if (compatibility == -1) {
				SERVER.removeCompatibility(playerUUID);
				return;
			}

			SERVER.addCompatibility(playerUUID, compatibility);
		});

		MultiLib.on(this, "voicechat:create_group", (data) -> {
			FriendlyByteBuf groupBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			Group group = new Group();
			group.fromBytes(groupBuf);

			SERVER.getServer().getGroupManager().addGroup(group);
		});

		MultiLib.on(this, "voicechat:proximity_sound_packet", (data) -> {
			FriendlyByteBuf soundBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			ExternalSoundPacket packet = ExternalSoundPacket.fromBytes(soundBuf);

            SERVER.getServer().externalLastSequenceNumbers.put(packet.getSoundPacket().getSender(), packet.getSoundPacket().getSequenceNumber());

			ClientConnection connection = SERVER.getServer().getConnection(packet.getDestinationUser());
			if (connection != null) {
				Player sender = Bukkit.getPlayer(packet.getSoundPacket().getSender());
				if (sender != null) {
					sendToPlayer(packet, connection, sender);
				}
			} else {
                Voicechat.LOGGER.debug("Could not send vc packet to {}", packet.getDestinationUser());
			}
		});

		MultiLib.on(this, "voicechat:group_sound_packet", (data) -> {
			FriendlyByteBuf soundBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			ExternalSoundPacket packet = ExternalSoundPacket.fromBytes(soundBuf);

			ClientConnection connection = SERVER.getServer().getConnection(packet.getDestinationUser());
			if (connection != null) {
                Player sender = Bukkit.getPlayer(packet.getSoundPacket().getSender());
				if (sender != null) {
					sendToPlayer(packet, connection, sender);
				}
			} else {
                Voicechat.LOGGER.debug("Could not send vc packet to {}", packet.getDestinationUser());
			}
		});

        MultiLib.on(this, "voicechat:plugin_sound_packet", (data) -> {
            FriendlyByteBuf soundBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            ExternalSoundPacket packet = ExternalSoundPacket.fromBytes(soundBuf);

            ClientConnection connection = SERVER.getServer().getConnection(packet.getDestinationUser());

            try {
                if (connection != null) {
                    connection.send(SERVER.getServer(), new NetworkMessage(packet.getSoundPacket()));
                }
            } catch (Exception e) {
                Voicechat.LOGGER.debug("Could not send vc packet to {}", packet.getDestinationUser());
            }
        });

        MultiLib.on(this, "voicechat:broadcast_proximity_sound_packet", (data) -> {
            SERVER.getServer().runTask(() -> {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
                Player sender = Bukkit.getPlayer(buf.readUUID());
                SoundPacket<?> soundPacket = ToExternal.externalDecodeSoundPacket(buf.readByteArray());
                PlayerState playerState = PlayerState.fromBytes(buf);
                UUID groupId = buf.readBoolean() ? buf.readUUID() : null;
                float distance = buf.readFloat();
                String source = buf.readUtf();
                try {
                    SERVER.getServer().broadcastProximityPacket(sender, playerState, soundPacket, groupId, source, distance);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
	}

	private void sendToPlayer(ExternalSoundPacket packet, ClientConnection connection, Player sender) {
		PlayerState senderState = SERVER.getServer().getPlayerStateManager().getState(packet.getSoundPacket().getSender());
		Player player = Bukkit.getPlayer(packet.getDestinationUser());
		try {
			if (!PluginManager.instance().onSoundPacket(sender, senderState, player, SERVER.getServer().getPlayerStateManager().getState(packet.getDestinationUser()), packet.getSoundPacket(), packet.getSource())) {
				SERVER.getServer().sendSoundPacket(player, connection, packet.getSoundPacket());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
    }

    @Override
    public void onDisable() {
        if (netManager != null) {
            netManager.onDisable();
        }
        getServer().getServicesManager().unregister(apiService);
        if (SERVER != null) {
            SERVER.getServer().close();
        }
    }

    public static boolean debugMode() {
        return System.getProperty("voicechat.debug") != null;
    }

}
