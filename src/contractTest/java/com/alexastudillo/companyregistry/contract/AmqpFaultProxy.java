package com.alexastudillo.companyregistry.contract;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Test-only AMQP 0-9-1 confirm fault injection; all handshake/data frames otherwise pass unchanged. */
public final class AmqpFaultProxy implements AutoCloseable {
    public enum Mode { PASS, HOLD, NACK }
    private record Held(Socket client, byte[] frame) {}
    private final ServerSocket listener;
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final List<Held> held = new CopyOnWriteArrayList<>();
    private volatile Mode mode;
    public final CountDownLatch confirmation = new CountDownLatch(1);
    public final java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();

    public AmqpFaultProxy(String host, int port, Mode mode) throws IOException {
        this.mode = mode; listener = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        workers.submit(() -> accept(host, port));
    }
    public int port() { return listener.getLocalPort(); }

    private void accept(String host, int port) {
        try {
            while (!listener.isClosed()) {
                var client = listener.accept(); var broker = new Socket(host, port);
                connections.incrementAndGet();
                sockets.add(client); sockets.add(broker);
                workers.submit(() -> forward(client, broker));
                workers.submit(() -> frames(broker, client));
            }
        } catch (IOException _) { /* Closing the listener ends this test fixture. */ }
    }
    private static void forward(Socket source, Socket target) {
        try { source.getInputStream().transferTo(target.getOutputStream()); }
        catch (IOException _) { /* Closing either peer terminates the pump. */ }
    }
    private void frames(Socket broker, Socket client) {
        try {
            var input = broker.getInputStream();
            while (!broker.isClosed()) {
                byte[] header = input.readNBytes(7);
                if (header.length != 7) return;
                int size = java.nio.ByteBuffer.wrap(header, 3, 4).getInt();
                if (size < 0 || size > 16 * 1024 * 1024) throw new IOException("Invalid test AMQP frame");
                byte[] body = input.readNBytes(size + 1);
                if (body.length != size + 1) return;
                byte[] frame = Arrays.copyOf(header, header.length + body.length);
                System.arraycopy(body, 0, frame, header.length, body.length);
                boolean ack = header[0] == 1 && size >= 4 && body[0] == 0 && body[1] == 60 && body[2] == 0 && body[3] == 80;
                if (ack) {
                    confirmation.countDown();
                    if (mode == Mode.HOLD) { held.add(new Held(client, frame)); continue; }
                    if (mode == Mode.NACK) frame[10] = 120;
                }
                write(client, frame);
            }
        } catch (IOException _) { /* Fault tests deliberately close transport sockets. */ }
    }
    public void release() throws IOException {
        mode = Mode.PASS;
        for (var value : held) { write(value.client(), value.frame()); held.remove(value); }
    }
    private static void write(Socket target, byte[] frame) throws IOException {
        synchronized (target) { target.getOutputStream().write(frame); target.getOutputStream().flush(); }
    }
    @Override public void close() throws IOException {
        listener.close();
        for (var socket : sockets) socket.close();
        workers.shutdownNow();
    }
}
