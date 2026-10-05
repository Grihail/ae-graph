package com.grihail.aegraph.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.grihail.aegraph.AEGraphMod;
import com.grihail.aegraph.remote.PartRemoteBus;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public final class AEGraphNetwork {

    private static SimpleNetworkWrapper channel;

    private AEGraphNetwork() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(AEGraphMod.MODID);
        channel.registerMessage(GraphHandler.class, GraphMessage.class, 0, Side.CLIENT);
        channel.registerMessage(RemoteBusHandler.class, RemoteBusMessage.class, 1, Side.CLIENT);
        channel.registerMessage(RemoteBusSetHandler.class, RemoteBusSetMessage.class, 2, Side.SERVER);
        FMLCommonHandler.instance().bus().register(new ServerTasks());
    }

    public static void sendGraph(EntityPlayerMP player, NBTTagCompound snapshot) {
        try {
            channel.sendTo(new GraphMessage(compress(snapshot)), player);
        } catch (IOException e) {
            AEGraphMod.LOG.error("Could not send the plan graph", e);
        }
    }

    /** Server -> client: open (or refresh) the Remote Crafting Bus screen. */
    public static void sendRemoteBus(EntityPlayerMP player, NBTTagCompound state) {
        try {
            channel.sendTo(new RemoteBusMessage(compress(state)), player);
        } catch (IOException e) {
            AEGraphMod.LOG.error("Could not send the remote bus state", e);
        }
    }

    /** Client -> server: set the exclusive ("priority") mode of the bus at that place, or just ask for a refresh. */
    public static void sendRemoteBusSetting(int x, int y, int z, int side, int exclusive) {
        channel.sendToServer(new RemoteBusSetMessage(x, y, z, side, exclusive));
    }

    private static byte[] compress(NBTTagCompound tag) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(tag, out);
        return out.toByteArray();
    }

    private static NBTTagCompound decompress(byte[] data) throws IOException {
        return CompressedStreamTools.func_152457_a(data, new NBTSizeTracker(64L * 1024 * 1024));
    }

    public static class GraphMessage implements IMessage {

        byte[] data;

        public GraphMessage() {}

        GraphMessage(byte[] data) {
            this.data = data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            data = new byte[buf.readInt()];
            buf.readBytes(data);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(data.length);
            buf.writeBytes(data);
        }
    }

    public static final class GraphHandler implements IMessageHandler<GraphMessage, IMessage> {

        @Override
        public IMessage onMessage(GraphMessage message, MessageContext ctx) {
            try {
                AEGraphMod.proxy.openGraph(decompress(message.data));
            } catch (IOException e) {
                AEGraphMod.LOG.error("Could not read the plan graph", e);
            }
            return null;
        }
    }

    public static final class RemoteBusMessage extends GraphMessage {

        public RemoteBusMessage() {}

        RemoteBusMessage(byte[] data) {
            super(data);
        }
    }

    public static final class RemoteBusHandler implements IMessageHandler<RemoteBusMessage, IMessage> {

        @Override
        public IMessage onMessage(RemoteBusMessage message, MessageContext ctx) {
            try {
                AEGraphMod.proxy.openRemoteBus(decompress(message.data));
            } catch (IOException e) {
                AEGraphMod.LOG.error("Could not read the remote bus state", e);
            }
            return null;
        }
    }

    public static final class RemoteBusSetMessage implements IMessage {

        int x, y, z, side;
        /** 1 on, 0 off, -1 unchanged (refresh only). */
        int exclusive;

        public RemoteBusSetMessage() {}

        RemoteBusSetMessage(int x, int y, int z, int side, int exclusive) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
            this.exclusive = exclusive;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
            side = buf.readByte();
            exclusive = buf.readByte();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x);
            buf.writeInt(y);
            buf.writeInt(z);
            buf.writeByte(side);
            buf.writeByte(exclusive);
        }
    }

    public static final class RemoteBusSetHandler implements IMessageHandler<RemoteBusSetMessage, IMessage> {

        @Override
        public IMessage onMessage(RemoteBusSetMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            // Packets arrive on the network thread; the world is touched on the server thread.
            SERVER_TASKS.add(() -> apply(message, player));
            return null;
        }

        private static void apply(RemoteBusSetMessage message, EntityPlayerMP player) {
            if (player == null || player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > 64) return;
            if (message.side < 0 || message.side >= ForgeDirection.VALID_DIRECTIONS.length) return;
            final TileEntity te = player.worldObj.getTileEntity(message.x, message.y, message.z);
            if (!(te instanceof IPartHost)) return;
            final IPart part = ((IPartHost) te).getPart(ForgeDirection.getOrientation(message.side));
            if (!(part instanceof PartRemoteBus)) return;
            final PartRemoteBus bus = (PartRemoteBus) part;
            if (message.exclusive >= 0) bus.setExclusive(message.exclusive == 1);
            sendRemoteBus(player, bus.describe());
        }
    }

    private static final Queue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<>();

    public static final class ServerTasks {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Runnable task;
            while ((task = SERVER_TASKS.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    AEGraphMod.LOG.error("AEGraph packet task failed", t);
                }
            }
        }
    }
}
