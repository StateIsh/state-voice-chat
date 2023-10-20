package de.maxhenkel.voicechat.voice.server;

import com.github.puregero.multilib.MultiLib;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.RequestSecretPacket;
import de.maxhenkel.voicechat.net.SecretPacket;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.util.ToExternal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerVoiceEvents implements Listener {

    private final Map<UUID, Integer> clientCompatibilities;
    private Server server;

    public ServerVoiceEvents() {
        clientCompatibilities = new ConcurrentHashMap<>();
    }

    public void init() {
        if (server != null) {
            return;
        }
        if (!Bukkit.getOnlineMode()) {
            Voicechat.LOGGER.warn("Running in offline mode - Voice chat encryption is not secure!");
        }

        server = new Server();
        server.start();

        PluginManager.instance().onServerStarted();
    }

    public void onRequestSecretPacket(Player player, RequestSecretPacket packet) {
        Voicechat.LOGGER.info("Received secret request of {} ({})", player.getName(), packet.getCompatibilityVersion());

        UUID playerUUID;

        try {
            playerUUID = player.getUniqueId();
        } catch (UnsupportedOperationException e) {
            player.kickPlayer("Tried to authenticate voice chat while still connecting");
            Voicechat.LOGGER.warn("{} tried to authenticate voice chat while still connecting", player.getName());
            return;
        }

        clientCompatibilities.put(playerUUID, packet.getCompatibilityVersion());
        MultiLib.notify("voicechat:update_compatibility", ToExternal.encodeCompatibility(playerUUID, packet.getCompatibilityVersion()));
        if (packet.getCompatibilityVersion() != Voicechat.COMPATIBILITY_VERSION) {
            Voicechat.LOGGER.warn("Connected client {} has incompatible voice chat version (server={}, client={})", player.getName(), Voicechat.COMPATIBILITY_VERSION, packet.getCompatibilityVersion());
            NetManager.sendMessage(player, getIncompatibleMessage(packet.getCompatibilityVersion()));
        } else {
			initializePlayerConnection(player);
        }
    }

	public void addCompatibility(UUID playerUUID, int compatibilityVersion) {
		clientCompatibilities.put(playerUUID, compatibilityVersion);
	}

	public void removeCompatibility(UUID playerUUID) {
		clientCompatibilities.remove(playerUUID);
	}

    public boolean isCompatible(Player player) {
        return isCompatible(player.getUniqueId());
    }

    public boolean isCompatible(UUID playerUuid) {
        return clientCompatibilities.getOrDefault(playerUuid, -1) == Voicechat.COMPATIBILITY_VERSION;
    }

    public static Component getIncompatibleMessage(int clientCompatibilityVersion) {
        if (clientCompatibilityVersion <= 6) {
            // Send a literal string, as we don't know if the translations exist on these versions
            return Component.text(String.format(Voicechat.TRANSLATIONS.voicechatNotCompatibleMessage.get(), Voicechat.INSTANCE.getDescription().getVersion(), "Simple Voice Chat"));
        } else {
            // This translation key is only available for compatibility version 7+
            return Component.translatable("message.voicechat.incompatible_version",
                    Component.text(Voicechat.INSTANCE.getDescription().getVersion()).toBuilder().decorate(TextDecoration.BOLD).build(),
                    Component.text("Simple Voice Chat").toBuilder().decorate(TextDecoration.BOLD).build());
        }
    }

    public void initializePlayerConnection(Player player) {
        if (server == null) {
            return;
        }
        server.getPlayerStateManager().onPlayerCompatibilityCheckSucceeded(player);
        server.getCategoryManager().onPlayerCompatibilityCheckSucceeded(player);
        server.getGroupManager().onPlayerCompatibilityCheckSucceeded(player);

        UUID secret = server.generateNewSecret(player.getUniqueId());
        if (secret == null) {
            Voicechat.LOGGER.warn("Player already requested secret - ignoring");
            return;
        }
        NetManager.sendToClient(player, new SecretPacket(player, secret, server.getPort(), Voicechat.SERVER_CONFIG));
        Voicechat.LOGGER.info("Sent secret to {}", player.getName());
    }

    @EventHandler
    public void playerLoggedIn(PlayerLoginEvent event) {
        if (!Voicechat.SERVER_CONFIG.forceVoiceChat.get()) {
            return;
        }
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskLater(Voicechat.INSTANCE, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!isCompatible(player)) {
                player.kickPlayer(String.format(
                        Voicechat.TRANSLATIONS.forceVoicechatKickMessage.get(),
                        "Simple Voice Chat",
                        Voicechat.INSTANCE.getDescription().getVersion()
                ));
            }
        }, Voicechat.SERVER_CONFIG.loginTimeout.get() / 50L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (isCompatible(event.getPlayer())) {
            // Yolo, let's just connect them straight away
            initializePlayerConnection(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        server.getGroupManager().onPlayerLoggedOut(event.getPlayer());

        // Remember their compatibility version for when they rejoin
        // MultiLib.notify("voicechat:update_compatibility", ToExternal.encodeCompatibility(event.getPlayer().getUniqueId(), -1));
        // clientCompatibilities.remove(event.getPlayer().getUniqueId());
        if (server == null) {
            return;
        }

        server.disconnectClient(event.getPlayer().getUniqueId());
        Voicechat.LOGGER.info("Disconnecting client {}", event.getPlayer().getName());
    }

    public Server getServer() {
        return server;
    }
}
