package de.maxhenkel.voicechat;

import com.github.puregero.multilib.MultiLib;
import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.command.VoiceChatCommands;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.config.Translations;
import de.maxhenkel.voicechat.integration.commodore.CommodoreCommands;
import de.maxhenkel.voicechat.integration.placeholderapi.VoicechatExpansion;
import de.maxhenkel.voicechat.integration.viaversion.ViaVersionCompatibility;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.plugins.impl.BukkitVoicechatServiceImpl;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.util.ToExternal;
import de.maxhenkel.voicechat.voice.common.ExternalSoundPacket;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import io.netty.buffer.Unpooled;
import me.lucko.commodore.Commodore;
import me.lucko.commodore.CommodoreProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public static int COMPATIBILITY_VERSION = BuildConstants.COMPATIBILITY_VERSION;

    public static ServerConfig SERVER_CONFIG;
    public static Translations TRANSLATIONS;
    public static ServerVoiceEvents SERVER;

    public static BukkitVoicechatServiceImpl apiService;
    public static NetManager netManager;

    public static final Pattern GROUP_REGEX = Pattern.compile("^[^\\n\\r\\t\\s][^\\n\\r\\t]{0,23}$");

    @Override
    public void onEnable() {
        INSTANCE = this;

        if (!BukkitVersionCheck.matchesTargetVersion()) {
            LOGGER.fatal("Disabling Simple Voice Chat due to incompatible Bukkit version!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        LOGGER.info("Compatibility version {}", COMPATIBILITY_VERSION);

        SERVER_CONFIG = ConfigBuilder.build(getDataFolder().toPath().resolve("voicechat-server.properties"), true, ServerConfig::new);
        TRANSLATIONS = ConfigBuilder.build(getDataFolder().toPath().resolve("translations.properties"), true, Translations::new);

        netManager = new NetManager();
        netManager.onEnable();

        apiService = new BukkitVoicechatServiceImpl();
        getServer().getServicesManager().register(BukkitVoicechatService.class, apiService, this, ServicePriority.Normal);

        PluginCommand voicechatCommand = getCommand(VoiceChatCommands.VOICECHAT_COMMAND);
        if (voicechatCommand != null) {
            voicechatCommand.setExecutor(new VoiceChatCommands());

            if (CommodoreProvider.isSupported()) {
                Commodore commodore = CommodoreProvider.getCommodore(this);
                CommodoreCommands.registerCompletions(commodore);
                LOGGER.info("Successfully initialized commodore command completion");
            } else {
                LOGGER.warn("Could not initialize commodore command completion");
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

        getServer().getScheduler().scheduleSyncDelayedTask(this, () -> {
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

		MultiLib.on(this, "voicechat:proximity_sound_packet_" + MultiLib.getLocalServerName(), (data) -> {
			FriendlyByteBuf soundBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			ExternalSoundPacket packet = ExternalSoundPacket.fromBytes(soundBuf);

			ClientConnection connection = SERVER.getServer().getConnection(packet.getDestinationUser());
			if (connection != null) {
				Player sender = MultiLib.getAllOnlinePlayers().stream().filter(p -> p.getUniqueId().equals(packet.getSoundPacket().getSender())).findFirst().orElse(null);
				if (sender != null) {
					sendToPlayer(packet, connection, sender);
				}
			} else {
				Voicechat.logDebug("Could not send vc packet to {}", packet.getDestinationUser());
			}
		});

		MultiLib.on(this, "voicechat:group_sound_packet_" + MultiLib.getLocalServerName(), (data) -> {
			FriendlyByteBuf soundBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			ExternalSoundPacket packet = ExternalSoundPacket.fromBytes(soundBuf);

			ClientConnection connection = SERVER.getServer().getConnection(packet.getDestinationUser());
			if (connection != null) {
				Player sender = MultiLib.getAllOnlinePlayers().stream().filter(p -> p.getUniqueId().equals(packet.getSoundPacket().getSender())).findFirst().orElse(null);
				if (sender != null) {
					sendToPlayer(packet, connection, sender);
				}
			} else {
				Voicechat.logDebug("Could not send vc packet to {}", packet.getDestinationUser());
			}
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

    public static void logDebug(String message, Object... objects) {
        if (System.getProperty("voicechat.debug") != null) {
            LOGGER.info(message, objects);
        }
    }

}
