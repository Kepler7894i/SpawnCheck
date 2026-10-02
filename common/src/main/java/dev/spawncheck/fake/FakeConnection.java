package dev.spawncheck.fake;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.SocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** A connection with no socket behind it: everything sent to it is dropped. */
public final class FakeConnection extends Connection {
	public FakeConnection() {
		super(PacketFlow.SERVERBOUND);
		// Loaders such as NeoForge read attributes off the connection's channel when a player joins, so give it a real
		// (in-memory, unconnected) one; adding this connection to its pipeline makes it the connection's channel.
		new EmbeddedChannel(this);
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(final ProtocolInfo<T> protocol, final T listener) {
	}

	@Override
	public void setupOutboundProtocol(final ProtocolInfo<?> protocol) {
	}

	@Override
	public void setListenerForServerboundHandshake(final PacketListener listener) {
	}

	@Override
	public void send(final Packet<?> packet) {
	}

	@Override
	public void send(final Packet<?> packet, final ChannelFutureListener listener) {
	}

	@Override
	public void send(final Packet<?> packet, final ChannelFutureListener listener, final boolean flush) {
	}

	@Override
	public void flushChannel() {
	}

	@Override
	public void tick() {
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public boolean isMemoryConnection() {
		return true;
	}

	@Override
	public SocketAddress getRemoteAddress() {
		return new java.net.InetSocketAddress("127.0.0.1", 0);
	}

	@Override
	public String getLoggableAddress(final boolean logIps) {
		return "spawncheck-fake";
	}

	@Override
	public void disconnect(final Component reason) {
	}

	@Override
	public void disconnect(final DisconnectionDetails details) {
	}

	@Override
	public void setReadOnly() {
	}

	@Override
	public void handleDisconnection() {
	}
}
