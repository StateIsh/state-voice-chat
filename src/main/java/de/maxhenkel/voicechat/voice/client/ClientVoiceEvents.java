package de.maxhenkel.voicechat.voice.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.maxhenkel.voicechat.Main;
import de.maxhenkel.voicechat.debug.DebugReport;
import de.maxhenkel.voicechat.event.VoiceChatConnectedEvent;
import de.maxhenkel.voicechat.event.VoiceChatDisconnectedEvent;
import de.maxhenkel.voicechat.gui.CreateGroupScreen;
import de.maxhenkel.voicechat.gui.GroupScreen;
import de.maxhenkel.voicechat.gui.VoiceChatScreen;
import de.maxhenkel.voicechat.gui.VoiceChatSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderNameplateEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class ClientVoiceEvents {

    private static final ResourceLocation MICROPHONE_ICON = new ResourceLocation(Main.MODID, "textures/gui/microphone.png");
    private static final ResourceLocation MICROPHONE_OFF_ICON = new ResourceLocation(Main.MODID, "textures/gui/microphone_off.png");
    private static final ResourceLocation SPEAKER_ICON = new ResourceLocation(Main.MODID, "textures/gui/speaker.png");
    private static final ResourceLocation SPEAKER_OFF_ICON = new ResourceLocation(Main.MODID, "textures/gui/speaker_off.png");
    private static final ResourceLocation DISCONNECT_ICON = new ResourceLocation(Main.MODID, "textures/gui/disconnected.png");
    private static final ResourceLocation GROUP_ICON = new ResourceLocation(Main.MODID, "textures/gui/group.png");

    private Client client;
    private ClientPlayerStateManager playerStateManager;
    private PTTKeyHandler pttKeyHandler;
    private Minecraft minecraft;

    public ClientVoiceEvents() {
        playerStateManager = new ClientPlayerStateManager();
        pttKeyHandler = new PTTKeyHandler();
        minecraft = Minecraft.getInstance();

        MinecraftForge.EVENT_BUS.register(pttKeyHandler);
    }

    public void authenticate(UUID playerUUID, UUID secret) {
        Main.LOGGER.info("Received secret");
        if (client != null) {
            onDisconnect();
        }
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            try {
                SocketAddress socketAddress = connection.getConnection().channel().remoteAddress();
                if (socketAddress instanceof InetSocketAddress) {
                    InetSocketAddress address = (InetSocketAddress) socketAddress;
                    String ip = Main.SERVER_CONFIG.voiceHost.get().isEmpty() ? address.getHostString() : Main.SERVER_CONFIG.voiceHost.get();
                    Main.LOGGER.info("Connecting to server: '" + ip + ":" + Main.SERVER_CONFIG.voiceChatPort.get() + "'");
                    client = new Client(ip, Main.SERVER_CONFIG.voiceChatPort.get(), playerUUID, secret);
                    client.start();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @SubscribeEvent
    public void disconnectEvent(WorldEvent.Unload event) {
        // Not just changing the world - Disconnecting
        if (minecraft.gameMode == null) {
            playerStateManager.onDisconnect();
            onDisconnect();
        }
    }

    @SubscribeEvent
    public void connectedEvent(VoiceChatConnectedEvent event) {
        playerStateManager.onVoiceChatConnected(event.getClient());
    }

    @SubscribeEvent
    public void disconnectedEvent(VoiceChatDisconnectedEvent event) {
        playerStateManager.onVoiceChatDisconnected();
    }

    public void onDisconnect() {
        MinecraftForge.EVENT_BUS.post(new VoiceChatDisconnectedEvent());
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Nullable
    public Client getClient() {
        return client;
    }

    public ClientPlayerStateManager getPlayerStateManager() {
        return playerStateManager;
    }

    public PTTKeyHandler getPttKeyHandler() {
        return pttKeyHandler;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Pre event) {
        if (!isMultiplayerServer()) {
            return;
        }
        if (!event.getType().equals(RenderGameOverlayEvent.ElementType.CHAT)) {
            return;
        }
        if (Main.CLIENT_CONFIG.hideIcons.get()) {
            return;
        }

        if (playerStateManager.isDisconnected()) {
            renderIcon(event.getMatrixStack(), DISCONNECT_ICON);
        } else if (playerStateManager.isDisabled()) {
            renderIcon(event.getMatrixStack(), SPEAKER_OFF_ICON);
        } else if (playerStateManager.isMuted() && Main.CLIENT_CONFIG.microphoneActivationType.get().equals(MicrophoneActivationType.VOICE)) {
            renderIcon(event.getMatrixStack(), MICROPHONE_OFF_ICON);
        } else if (client != null && client.getMicThread() != null && client.getMicThread().isTalking()) {
            renderIcon(event.getMatrixStack(), MICROPHONE_ICON);
        }

        if (playerStateManager.isInGroup() && Main.CLIENT_CONFIG.showGroupHUD.get()) {
            GroupChatManager.renderIcons(event.getMatrixStack());
        }
    }

    private void renderIcon(PoseStack matrixStack, ResourceLocation texture) {
        matrixStack.pushPose();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.setShaderTexture(0, texture);
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        HUDIconLocation location = Main.CLIENT_CONFIG.hudIconLocation.get();
        if (location.equals(HUDIconLocation.RIGHT)) {
            Screen.blit(matrixStack, width - 32, height - 32, 0, 0, 16, 16, 16, 16);
        } else if (location.equals(HUDIconLocation.CENTER)) {
            if (minecraft.gameMode != null && minecraft.gameMode.hasExperience()) {
                Screen.blit(matrixStack, width / 2 - 8, height - 16 - 35 - 2, 0, 0, 16, 16, 16, 16);
            } else {
                Screen.blit(matrixStack, width / 2 - 8, height - 35 - 4, 0, 0, 16, 16, 16, 16);
            }
        } else {
            Screen.blit(matrixStack, 16, height - 32, 0, 0, 16, 16, 16, 16);
        }
        matrixStack.popPose();
    }

    @SubscribeEvent
    public void onInput(TickEvent.ClientTickEvent event) {
        if (Main.KEY_VOICE_CHAT.consumeClick()) {
            if (InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_F3)) {
                minecraft.options.renderDebug = true;
                DebugReport.generateReport(minecraft.player);
            } else if (checkConnected()) {
                minecraft.setScreen(new VoiceChatScreen());
            }
        }

        if (Main.KEY_GROUP.consumeClick() && checkConnected()) {
            if (Main.SERVER_CONFIG.groupsEnabled.get()) {
                if (playerStateManager.isInGroup()) {
                    minecraft.setScreen(new GroupScreen());
                } else {
                    minecraft.setScreen(new CreateGroupScreen());
                }
            } else {
                minecraft.player.displayClientMessage(new TranslatableComponent("message.voicechat.groups_disabled"), true);
            }
        }

        if (Main.KEY_VOICE_CHAT_SETTINGS.consumeClick() && checkConnected()) {
            minecraft.setScreen(new VoiceChatSettingsScreen());
        }

        if (Main.KEY_PTT.consumeClick()) {
            checkConnected();
        }

        if (Main.KEY_MUTE.consumeClick() && checkConnected()) {
            playerStateManager.setMuted(!playerStateManager.isMuted());
        }

        if (Main.KEY_DISABLE.consumeClick() && checkConnected()) {
            playerStateManager.setDisabled(!playerStateManager.isDisabled());
        }

        if (Main.KEY_TOGGLE_RECORDING.consumeClick() && checkConnected()) {
            client.toggleRecording();
        }

        if (Main.KEY_HIDE_ICONS.consumeClick()) {
            boolean hidden = !Main.CLIENT_CONFIG.hideIcons.get();
            Main.CLIENT_CONFIG.hideIcons.set(hidden);
            Main.CLIENT_CONFIG.hideIcons.save();

            if (hidden) {
                minecraft.player.displayClientMessage(new TranslatableComponent("message.voicechat.icons_hidden"), true);
            } else {
                minecraft.player.displayClientMessage(new TranslatableComponent("message.voicechat.icons_visible"), true);
            }
        }
    }

    public boolean checkConnected() {
        if (Main.CLIENT_VOICE_EVENTS.getClient() == null || !Main.CLIENT_VOICE_EVENTS.getClient().isAuthenticated()) {
            sendUnavailableMessage();
            return false;
        }
        return true;
    }

    public void sendUnavailableMessage() {
        minecraft.player.displayClientMessage(new TranslatableComponent("message.voicechat.voice_chat_unavailable"), true);
    }

    public boolean isMultiplayerServer() {
        return minecraft.getCurrentServer() != null && !minecraft.getCurrentServer().isLan();
    }

    @SubscribeEvent
    public void onRenderName(RenderNameplateEvent event) {
        if (!isMultiplayerServer()) {
            return;
        }
        if (Main.CLIENT_CONFIG.hideIcons.get()) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        if (event.getEntity() == minecraft.player) {
            return;
        }

        Player player = (Player) event.getEntity();

        if (!minecraft.options.hideGui) {
            String group = playerStateManager.getGroup(player);

            if (client != null && client.getTalkCache().isTalking(player)) {
                renderPlayerIcon(player, SPEAKER_ICON, event.getMatrixStack(), event.getRenderTypeBuffer(), event.getPackedLight());
            } else if (playerStateManager.isPlayerDisconnected(player)) {
                renderPlayerIcon(player, DISCONNECT_ICON, event.getMatrixStack(), event.getRenderTypeBuffer(), event.getPackedLight());
            } else if (group != null && !group.equals(playerStateManager.getGroup())) {
                renderPlayerIcon(player, GROUP_ICON, event.getMatrixStack(), event.getRenderTypeBuffer(), event.getPackedLight());
            } else if (playerStateManager.isPlayerDisabled(player)) {
                renderPlayerIcon(player, SPEAKER_OFF_ICON, event.getMatrixStack(), event.getRenderTypeBuffer(), event.getPackedLight());
            }
        }
    }

    protected void renderPlayerIcon(Player player, ResourceLocation texture, PoseStack matrixStackIn, MultiBufferSource bufferIn, int packedLightIn) {
        matrixStackIn.pushPose();
        matrixStackIn.translate(0D, player.getBbHeight() + 0.5D, 0D);
        matrixStackIn.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        matrixStackIn.scale(-0.025F, -0.025F, 0.025F);
        matrixStackIn.translate(0D, -1D, 0D);

        float offset = (float) (minecraft.font.width(player.getDisplayName()) / 2 + 2);

        VertexConsumer builder = bufferIn.getBuffer(RenderType.text(texture));
        int alpha = 32;

        if (player.isDiscrete()) {
            vertex(builder, matrixStackIn, offset, 10F, 0F, 0F, 1F, alpha, packedLightIn);
            vertex(builder, matrixStackIn, offset + 10F, 10F, 0F, 1F, 1F, alpha, packedLightIn);
            vertex(builder, matrixStackIn, offset + 10F, 0F, 0F, 1F, 0F, alpha, packedLightIn);
            vertex(builder, matrixStackIn, offset, 0F, 0F, 0F, 0F, alpha, packedLightIn);
        } else {
            vertex(builder, matrixStackIn, offset, 10F, 0F, 0F, 1F, packedLightIn);
            vertex(builder, matrixStackIn, offset + 10F, 10F, 0F, 1F, 1F, packedLightIn);
            vertex(builder, matrixStackIn, offset + 10F, 0F, 0F, 1F, 0F, packedLightIn);
            vertex(builder, matrixStackIn, offset, 0F, 0F, 0F, 0F, packedLightIn);

            VertexConsumer builderSeeThrough = bufferIn.getBuffer(RenderType.textSeeThrough(texture));
            vertex(builderSeeThrough, matrixStackIn, offset, 10F, 0F, 0F, 1F, alpha, packedLightIn);
            vertex(builderSeeThrough, matrixStackIn, offset + 10F, 10F, 0F, 1F, 1F, alpha, packedLightIn);
            vertex(builderSeeThrough, matrixStackIn, offset + 10F, 0F, 0F, 1F, 0F, alpha, packedLightIn);
            vertex(builderSeeThrough, matrixStackIn, offset, 0F, 0F, 0F, 0F, alpha, packedLightIn);
        }

        matrixStackIn.popPose();
    }

    private static void vertex(VertexConsumer builder, PoseStack matrixStack, float x, float y, float z, float u, float v, int light) {
        vertex(builder, matrixStack, x, y, z, u, v, 255, light);
    }

    private static void vertex(VertexConsumer builder, PoseStack matrixStack, float x, float y, float z, float u, float v, int alpha, int light) {
        PoseStack.Pose entry = matrixStack.last();
        builder.vertex(entry.pose(), x, y, z)
                .color(255, 255, 255, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(entry.normal(), 0F, 0F, -1F)
                .endVertex();
    }

}