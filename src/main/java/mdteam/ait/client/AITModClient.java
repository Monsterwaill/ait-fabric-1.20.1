package mdteam.ait.client;

import mdteam.ait.AITMod;
import mdteam.ait.client.renderers.consoles.ConsoleGeneratorRenderer;
import mdteam.ait.client.renderers.monitors.MonitorRenderer;
import mdteam.ait.core.*;
import mdteam.ait.network.ClientAITNetworkManager;
import mdteam.ait.tardis.animation.ExteriorAnimation;
import mdteam.ait.client.registry.ClientConsoleVariantRegistry;
import mdteam.ait.client.registry.ClientDoorRegistry;
import mdteam.ait.client.registry.ClientExteriorVariantRegistry;
import mdteam.ait.client.renderers.AITRadioRenderer;
import mdteam.ait.client.renderers.consoles.ConsoleRenderer;
import mdteam.ait.client.renderers.coral.CoralRenderer;
import mdteam.ait.client.renderers.doors.DoorRenderer;
import mdteam.ait.client.renderers.entities.ControlEntityRenderer;
import mdteam.ait.client.renderers.entities.FallingTardisRenderer;
import mdteam.ait.client.renderers.entities.TardisRealRenderer;
import mdteam.ait.client.renderers.exteriors.ExteriorRenderer;
import mdteam.ait.client.screens.MonitorScreen;
import mdteam.ait.client.screens.OwOFindPlayerScreen;
import mdteam.ait.client.screens.interior.OwOInteriorSelectScreen;
import mdteam.ait.client.util.ClientTardisUtil;
import mdteam.ait.core.blockentities.ConsoleBlockEntity;
import mdteam.ait.core.blockentities.DoorBlockEntity;
import mdteam.ait.core.blockentities.ExteriorBlockEntity;
import mdteam.ait.core.item.KeyItem;
import mdteam.ait.core.item.SonicItem;
import mdteam.ait.core.item.WaypointItem;
import mdteam.ait.registry.ConsoleRegistry;
import mdteam.ait.tardis.TardisTravel;
import mdteam.ait.tardis.console.ConsoleSchema;
import mdteam.ait.tardis.wrapper.client.manager.ClientTardisManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.client.rendering.BlockEntityRendererRegistryImpl;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

@Environment(value = EnvType.CLIENT)
public class AITModClient implements ClientModInitializer {

    private static KeyBinding keyBinding;

    private final Identifier PORTAL_EFFECT_SHADER = new Identifier(AITMod.MOD_ID, "shaders/core/portal_effect.json");
    public static final Identifier OPEN_SCREEN = new Identifier(AITMod.MOD_ID, "open_screen");
    public static final Identifier OPEN_SCREEN_TARDIS = new Identifier(AITMod.MOD_ID, "open_screen_tardis");

    @Override
    public void onInitializeClient() {
        ClientAITNetworkManager.init();
        setupBlockRendering();
        blockEntityRendererRegister();
        entityRenderRegister();
        sonicModelPredicate();
        riftScannerPredicate();
        waypointPredicate();
        setKeyBinding();

        ClientExteriorVariantRegistry.init();
        ClientConsoleVariantRegistry.init();
        ClientDoorRegistry.init();



        ClientPlayNetworking.registerGlobalReceiver(OPEN_SCREEN,
                (client, handler, buf, responseSender) -> {
                    int id = buf.readInt();

                    Screen screen = screenFromId(id);
                    if (screen == null) return;
                    MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreenAndRender(screen));
                });
        ClientPlayNetworking.registerGlobalReceiver(OPEN_SCREEN_TARDIS, // fixme this might not be necessary could just be easier to always use the other method.
                (client, handler, buf, responseSender) -> {
                    int id = buf.readInt();
                    UUID uuid = buf.readUuid();

                    Screen screen = screenFromId(id, uuid);
                    if (screen == null) return;
                    MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreenAndRender(screen));
                });


        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((block, world) -> {
            if (block instanceof ExteriorBlockEntity exterior) {
                if (exterior.getTardis() == null) return;
                ClientAITNetworkManager.ask_for_exterior_subscriber(exterior.getTardis().getUuid());
                if (ClientTardisManager.getInstance().exteriorToTardis.containsKey(exterior)) return;
                ClientTardisManager.getInstance().exteriorToTardis.put(exterior, exterior.getTardis());
                exterior.getTardis().getDoor().clearExteriorAnimationState();
            }
            else if (block instanceof DoorBlockEntity door) {
                if (door.getTardis() == null) return;
                ClientAITNetworkManager.ask_for_interior_subscriber(door.getTardis().getUuid());
                if (ClientTardisManager.getInstance().interiorDoorToTardis.containsKey(door)) return;
                ClientTardisManager.getInstance().interiorDoorToTardis.put(door, door.getTardis());
                door.getTardis().getDoor().clearInteriorAnimationState();
            }
            else if (block instanceof ConsoleBlockEntity console) {
                if (console.getTardis() == null) return;
                ClientAITNetworkManager.ask_for_interior_subscriber(console.getTardis().getUuid());
                if (ClientTardisManager.getInstance().consoleToTardis.containsKey(console)) return;
                ClientTardisManager.getInstance().consoleToTardis.put(console, console.getTardis());
            }
        });

        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((block, world) -> {
            if (block instanceof ExteriorBlockEntity exteriorBlock) {
                if (!ClientTardisManager.getInstance().exteriorToTardis.containsKey(exteriorBlock)) return;
                UUID uuid = ClientTardisManager.getInstance().exteriorToTardis.get(exteriorBlock).getUuid();
                ClientTardisManager.getInstance().exteriorToTardis.remove(exteriorBlock);
                ClientAITNetworkManager.send_exterior_unloaded(uuid);
            }
            else if (block instanceof DoorBlockEntity doorBlockEntity) {
                if (!ClientTardisManager.getInstance().interiorDoorToTardis.containsKey(doorBlockEntity)) return;
                UUID uuid = ClientTardisManager.getInstance().interiorDoorToTardis.get(doorBlockEntity).getUuid();
                ClientTardisManager.getInstance().interiorDoorToTardis.remove(doorBlockEntity);
                if (!ClientTardisManager.getInstance().consoleToTardis.isEmpty()) return;
                ClientAITNetworkManager.send_interior_unloaded(uuid);
            }
            else if (block instanceof ConsoleBlockEntity consoleBlockEntity) {
                if (!ClientTardisManager.getInstance().consoleToTardis.containsKey(consoleBlockEntity)) return;
                UUID uuid = ClientTardisManager.getInstance().consoleToTardis.get(consoleBlockEntity).getUuid();
                ClientTardisManager.getInstance().consoleToTardis.remove(consoleBlockEntity);
                if (!ClientTardisManager.getInstance().consoleToTardis.isEmpty()) return;
                ClientAITNetworkManager.send_interior_unloaded(uuid);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(ConsoleBlockEntity.SYNC_TYPE, (client, handler, buf, responseSender) -> {
            if (client.world == null || client.world.getRegistryKey() != AITDimensions.TARDIS_DIM_WORLD) return;

            String id = buf.readString();
            ConsoleSchema type = ConsoleRegistry.REGISTRY.get(Identifier.tryParse(id));
            BlockPos consolePos = buf.readBlockPos();
            if (client.world.getBlockEntity(consolePos) instanceof ConsoleBlockEntity console) console.setType(type);
        });

        ClientPlayNetworking.registerGlobalReceiver(ConsoleBlockEntity.SYNC_VARIANT, (client, handler, buf, responseSender) -> {
            if (client.world == null || client.world.getRegistryKey() != AITDimensions.TARDIS_DIM_WORLD) return;

            Identifier id = Identifier.tryParse(buf.readString());
            BlockPos consolePos = buf.readBlockPos();
            if (client.world.getBlockEntity(consolePos) instanceof ConsoleBlockEntity console) console.setVariant(id);
        });


        // does all this clientplaynetwrokigng shite really have to go in here, theres probably somewhere else it can go right??
        ClientPlayNetworking.registerGlobalReceiver(AITMessages.CANCEL_DEMAT_SOUND, (client, handler, buf, responseSender) -> {
            client.getSoundManager().stopSounds(AITSounds.DEMAT.getId(), SoundCategory.BLOCKS);
        });

        // This entrypoint is suitable for setting up client-specific logic, such as rendering.
    }


    public static void openScreen(ServerPlayerEntity player, int id) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(id);
        ServerPlayNetworking.send(player, OPEN_SCREEN, buf);
    }

    /**
     * This is for screens without a tardis
     */
    public static Screen screenFromId(int id) {
        return screenFromId(id, null);
    }

    public static Screen screenFromId(int id, UUID tardis) {
        return switch (id) {
            default -> null;
            case 0 -> new MonitorScreen(tardis); // todo new OwoMonitorScreen(tardis); god rest ye merry gentlemen
            case 1 -> new OwOFindPlayerScreen(tardis);
            case 2 -> new OwOInteriorSelectScreen(tardis, new MonitorScreen(tardis));
        };
    }


    // @TODO creativious this is the model predicate for the rift scanner, all you have to do is make the value being returned go from 0.0f to 0.75f in a circle to simulate a compass-like item.
    public void riftScannerPredicate() {
        ModelPredicateProviderRegistry.register(AITItems.RIFT_SCANNER, new Identifier("scanner"),new RiftClampBullshit((world, stack, entity) -> GlobalPos.create(entity.getWorld().getRegistryKey(), BlockPos.fromLong(stack.getOrCreateNbt().getLong("targetBlock")))));
    }

    public void sonicModelPredicate() { // fixme lord give me strength - amen brother
        ModelPredicateProviderRegistry.register(AITItems.MECHANICAL_SONIC_SCREWDRIVER, new Identifier("inactive"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 0 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.MECHANICAL_SONIC_SCREWDRIVER, new Identifier("interaction"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 1 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.MECHANICAL_SONIC_SCREWDRIVER, new Identifier("overload"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 2 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.MECHANICAL_SONIC_SCREWDRIVER, new Identifier("scanning"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 3 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.MECHANICAL_SONIC_SCREWDRIVER, new Identifier("tardis"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 4 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.RENAISSANCE_SONIC_SCREWDRIVER, new Identifier("inactive"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 0 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.RENAISSANCE_SONIC_SCREWDRIVER, new Identifier("interaction"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 1 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.RENAISSANCE_SONIC_SCREWDRIVER, new Identifier("overload"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 2 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.RENAISSANCE_SONIC_SCREWDRIVER, new Identifier("scanning"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 3 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.RENAISSANCE_SONIC_SCREWDRIVER, new Identifier("tardis"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 4 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.CORAL_SONIC_SCREWDRIVER, new Identifier("inactive"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 0 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.CORAL_SONIC_SCREWDRIVER, new Identifier("interaction"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 1 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.CORAL_SONIC_SCREWDRIVER, new Identifier("overload"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 2 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.CORAL_SONIC_SCREWDRIVER, new Identifier("scanning"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 3 ? 1.0F : 0.0F;
        });
        ModelPredicateProviderRegistry.register(AITItems.CORAL_SONIC_SCREWDRIVER, new Identifier("tardis"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            return SonicItem.findModeInt(itemStack) == 4 ? 1.0F : 0.0F;
        });
    }

    public void waypointPredicate() {
        ModelPredicateProviderRegistry.register(AITItems.WAYPOINT_CARTRIDGE, new Identifier("type"), (itemStack, clientWorld, livingEntity, integer) -> {
            if (livingEntity == null) return 0.0F;
            if(itemStack.getItem() == AITItems.WAYPOINT_CARTRIDGE)
                if(itemStack.getOrCreateNbt().contains(WaypointItem.POS_KEY))
                    return 1.0f;
                else return 0.5f;
            else return 0.5f;
        });
    }


    //@TODO Shader stuff, decide whether or not to use this or glScissor stuff. - Loqor
	/*public void shaderStuffForBOTI() {
		CoreShaderRegistrationCallback.EVENT.register(manager -> {
			manager.register(PORTAL_EFFECT_SHADER, VertexFormats.POSITION_TEXTURE, ShaderProgram::attachReferencedShaders);
		});
	}

	public ShaderProgram getShader() throws IOException {
		return new FabricShaderProgram(MinecraftClient.getInstance().getResourceManager(), PORTAL_EFFECT_SHADER, VertexFormats.POSITION_TEXTURE);
	}*/

    public void blockEntityRendererRegister() {
        BlockEntityRendererRegistry.register(AITBlockEntityTypes.AIT_RADIO_BLOCK_ENTITY_TYPE, AITRadioRenderer::new);
        BlockEntityRendererRegistry.register(AITBlockEntityTypes.CONSOLE_BLOCK_ENTITY_TYPE, ConsoleRenderer::new);
        BlockEntityRendererRegistry.register(AITBlockEntityTypes.CONSOLE_GENERATOR_ENTITY_TYPE, ConsoleGeneratorRenderer::new);
        BlockEntityRendererRegistry.register(AITBlockEntityTypes.EXTERIOR_BLOCK_ENTITY_TYPE, ExteriorRenderer::new);
        BlockEntityRendererRegistry.register(AITBlockEntityTypes.DOOR_BLOCK_ENTITY_TYPE, DoorRenderer::new);
        BlockEntityRendererRegistry.register(AITBlockEntityTypes.CORAL_BLOCK_ENTITY_TYPE, CoralRenderer::new);
        BlockEntityRendererRegistry.register(AITBlockEntityTypes.MONITOR_BLOCK_ENTITY_TYPE, MonitorRenderer::new);
    }

    public void entityRenderRegister() {
        EntityRendererRegistry.register(AITEntityTypes.CONTROL_ENTITY_TYPE, ControlEntityRenderer::new);
        EntityRendererRegistry.register(AITEntityTypes.FALLING_TARDIS_TYPE, FallingTardisRenderer::new);
        EntityRendererRegistry.register(AITEntityTypes.TARDIS_REAL_ENTITY_TYPE, TardisRealRenderer::new);
    }

    private boolean keyHeldDown = false;

    public void setKeyBinding() {
        keyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + AITMod.MOD_ID + ".open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category." + AITMod.MOD_ID + ".snap"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                if (keyBinding.isPressed()) {
                    if (!keyHeldDown) {
                        keyHeldDown = true;
                        ItemStack stack = KeyItem.getFirstKeyStackInInventory(player);
                        if (stack != null && stack.getItem() instanceof KeyItem key && key.hasProtocol(KeyItem.Protocols.SNAP)) {
                            NbtCompound tag = stack.getOrCreateNbt();
                            if (!tag.contains("tardis")) {
                                return;
                            }
                            ClientAITNetworkManager.send_snap_to_open_doors(UUID.fromString(tag.getString("tardis")));
                        }
                    }
                } else {
                    keyHeldDown = false;
                }
            }
        });
    }

    public void setupBlockRendering() {
        BlockRenderLayerMap map = BlockRenderLayerMap.INSTANCE;
        //map.putBlock(FMCBlocks.RADIO, RenderLayer.getCutout());
    }
}