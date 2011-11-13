/*
 * This is a slightly modified copy of org.bukkit.craftbukkit.ChunkCompressionThread
 * It sends packets to packet listeners before outputting them to the network manager
 */
package org.getspout.spout;

import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.Deflater;

import net.minecraft.server.EntityPlayer;
import net.minecraft.server.Packet;
import net.minecraft.server.Packet51MapChunk;

public final class ChunkCompressionThread implements Runnable {

	private static final ChunkCompressionThread instance = new ChunkCompressionThread();
	private static Thread activeThread = null;
	private static boolean isRunning = false;

	private final int QUEUE_CAPACITY = 1024 * 10;
	private final HashMap<EntityPlayer, Integer> queueSizePerPlayer = new HashMap<EntityPlayer, Integer>();
	private final BlockingQueue<QueuedPacket> packetQueue = new LinkedBlockingQueue<QueuedPacket>(QUEUE_CAPACITY);

	private final int CHUNK_SIZE = 16 * 128 * 16 * 5 / 2;
	private final int REDUCED_DEFLATE_THRESHOLD = CHUNK_SIZE / 4;
	private final int DEFLATE_LEVEL_CHUNKS = 6;
	private final int DEFLATE_LEVEL_PARTS = 1;

	private final Deflater deflater = new Deflater();
	private byte[] deflateBuffer = new byte[CHUNK_SIZE + 100];

	public static void startThread() {
		if (!isRunning) {
			isRunning = true;
			activeThread = new Thread(instance, "Spout Chunk Packet Compression Thread");
			activeThread.start();
		}
	}
	
	public static void endThread() {
		isRunning = false;
		activeThread.interrupt();
		instance.sendFinalChunks();
	}

	public void run() {
		while (!activeThread.isInterrupted()) {
			try {
				handleQueuedPacket(packetQueue.take());
			} catch (InterruptedException ie) {
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	private void sendFinalChunks() {
		//safely manage the chunks after interruption
		Iterator<QueuedPacket> i = packetQueue.iterator();
		while(i.hasNext()) {
			QueuedPacket next = i.next();
			if (next.compress){
				handleMapChunk(next);
			}
			next.player.netServerHandler.networkManager.queue(next.packet);
		}
	}

	private void handleQueuedPacket(QueuedPacket queuedPacket) {
		addToPlayerQueueSize(queuedPacket.player, -1);
		// Compress the packet if necessary.
		if (queuedPacket.compress) {
			handleMapChunk(queuedPacket);
		}
		sendToNetworkQueue(queuedPacket);
	}

	private void handleMapChunk(QueuedPacket queuedPacket) {
		Packet51MapChunk packet = (Packet51MapChunk) queuedPacket.packet;

		// If 'packet.g' is set then this packet has already been compressed.
		if (packet.g != null) {
			return;
		}

		int dataSize = packet.rawData.length;
		if (deflateBuffer.length < dataSize + 100) {
			deflateBuffer = new byte[dataSize + 100];
		}

		deflater.reset();
		deflater.setLevel(dataSize < REDUCED_DEFLATE_THRESHOLD ? DEFLATE_LEVEL_PARTS : DEFLATE_LEVEL_CHUNKS);
		deflater.setInput(packet.rawData);
		deflater.finish();
		int size = deflater.deflate(deflateBuffer);
		if (size == 0) {
			size = deflater.deflate(deflateBuffer);
		}

		// copy compressed data to packet
		packet.g = new byte[size];
		packet.h = size;
		System.arraycopy(deflateBuffer, 0, packet.g, 0, size);
	}

	private void sendToNetworkQueue(QueuedPacket queuedPacket) {
		((SpoutNetServerHandler) queuedPacket.player.netServerHandler).queueOutputPacket(queuedPacket.packet);
	}

	public static void sendPacket(EntityPlayer player, Packet packet) {
		if (packet instanceof Packet51MapChunk) {
			// MapChunk Packets need compressing.
			instance.addQueuedPacket(new QueuedPacket(player, packet, true));
		} else {
			// Other Packets don't.
			instance.addQueuedPacket(new QueuedPacket(player, packet, false));
		}
	}

	private void addToPlayerQueueSize(EntityPlayer player, int amount) {
		synchronized (queueSizePerPlayer) {
			Integer count = queueSizePerPlayer.get(player);
			amount += (count == null) ? 0 : count;
			if (amount == 0) {
				queueSizePerPlayer.remove(player);
			} else {
				queueSizePerPlayer.put(player, amount);
			}
		}
	}

	public static int getPlayerQueueSize(EntityPlayer player) {
		synchronized (instance.queueSizePerPlayer) {
			Integer count = instance.queueSizePerPlayer.get(player);
			return count == null ? 0 : count;
		}
	}

	private void addQueuedPacket(QueuedPacket task) {
		addToPlayerQueueSize(task.player, +1);

		while (true) {
			try {
				packetQueue.put(task);
				return;
			} catch (InterruptedException e) {
			}
		}
	}

	private static class QueuedPacket {
		final EntityPlayer player;
		final Packet packet;
		final boolean compress;

		QueuedPacket(EntityPlayer player, Packet packet, boolean compress) {
			this.player = player;
			this.packet = packet;
			this.compress = compress;
		}
	}
}