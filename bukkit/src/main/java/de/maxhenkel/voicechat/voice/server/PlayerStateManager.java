package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.PlayerStatePacket;
import de.maxhenkel.voicechat.net.PlayerStatesPacket;
import de.maxhenkel.voicechat.net.UpdateStatePacket;
import de.maxhenkel.voicechat.util.ToExternal;
import de.maxhenkel.voicechat.voice.common.ClientGroup;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager implements Listener {

    private final ConcurrentHashMap<UUID, PlayerState> states;

    public PlayerStateManager() {
        this.states = new ConcurrentHashMap<>();
    }

    public void onUpdateStatePacket(Player player, UpdateStatePacket packet) {
        PlayerState state = states.get(player.getUniqueId());

        if (state == null) {
            state = defaultDisconnectedState(player);
        }

        state.setDisabled(packet.isDisabled());
		Bukkit.getMultiPaperNotificationManager().notify("voicechat:add_playerstate", ToExternal.encodePlayerState(state));
        states.put(player.getUniqueId(), state);

        broadcastState(state);
        Voicechat.logDebug("Got state of {}: {}", player.getName(), state);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
		Bukkit.getMultiPaperNotificationManager().notify("voicechat:remove_playerstate", event.getPlayer().getUniqueId().toString());
        states.remove(event.getPlayer().getUniqueId());
        broadcastState(new PlayerState(event.getPlayer().getUniqueId(), event.getPlayer().getName(), false, true));
        Voicechat.logDebug("Removing state of {}", event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PlayerState state = defaultDisconnectedState(event.getPlayer());
		Bukkit.getMultiPaperNotificationManager().notify("voicechat:add_playerstate", ToExternal.encodePlayerState(state));
        states.put(event.getPlayer().getUniqueId(), state);
        broadcastState(state);
        Voicechat.logDebug("Setting default state of {}: {}", event.getPlayer().getName(), state);
    }

	public void removeState(UUID uuid) {
		states.remove(uuid);
		broadcastState(new PlayerState(uuid, Bukkit.getOfflinePlayer(uuid).getName(), false, true));
		Voicechat.logDebug("Removing state of {}", Bukkit.getOfflinePlayer(uuid).getName());
	}

	public void addState(PlayerState state) {
		states.put(state.getUuid(), state);
		broadcastState(state);
		Voicechat.logDebug("Adding state of {}: {}", state.getName(), state);
	}

    private void broadcastState(PlayerState state) {
        PlayerStatePacket packet = new PlayerStatePacket(state);
        Voicechat.INSTANCE.getServer().getOnlinePlayers().forEach(p -> NetManager.sendToClient(p, packet));
    }

    public void onPlayerCompatibilityCheckSucceeded(Player player) {
        PlayerStatesPacket packet = new PlayerStatesPacket(states);
        NetManager.sendToClient(player, packet);
        Voicechat.logDebug("Sending initial states to {}", player.getName());
    }

    public void onPlayerVoicechatDisconnect(UUID uuid) {
        PlayerState state = states.get(uuid);
        if (state == null) {
            return;
        }

        state.setDisconnected(true);
		Bukkit.getMultiPaperNotificationManager().notify("voicechat:add_playerstate", ToExternal.encodePlayerState(state));

        broadcastState(state);
        Voicechat.logDebug("Set state of {} to disconnected: {}", uuid, state);
    }

    public void onPlayerVoicechatConnect(Player player) {
        PlayerState state = states.get(player.getUniqueId());

        if (state == null) {
            state = defaultDisconnectedState(player);
        }

        state.setDisconnected(false);
		Bukkit.getMultiPaperNotificationManager().notify("voicechat:add_playerstate", ToExternal.encodePlayerState(state));
        states.put(player.getUniqueId(), state);

        broadcastState(state);
        Voicechat.logDebug("Set state of {} to connected: {}", player.getName(), state);
    }

    @Nullable
    public PlayerState getState(UUID playerUUID) {
        return states.get(playerUUID);
    }

    public static PlayerState defaultDisconnectedState(Player player) {
        return new PlayerState(player.getUniqueId(), player.getName(), false, true);
    }

    public void setGroup(Player player, @Nullable UUID group) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) {
            state = PlayerStateManager.defaultDisconnectedState(player);
            Voicechat.logDebug("Defaulting to default state for {}: {}", player.getName(), state);
        }
        state.setGroup(group);
		Bukkit.getMultiPaperNotificationManager().notify("voicechat:add_playerstate", ToExternal.encodePlayerState(state));
        states.put(player.getUniqueId(), state);
        broadcastState(state);
        Voicechat.logDebug("Setting group of {}: {}", player.getName(), state);
    }

    public Collection<PlayerState> getStates() {
        return states.values();
    }

}
