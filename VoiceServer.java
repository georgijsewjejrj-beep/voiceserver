import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/**
 * TCP войс чат сервер для Railway
 */
public class VoiceServer {

    private static final int TCP_PORT  = Integer.parseInt(System.getenv().getOrDefault("PORT", "25566"));
    private static final int HTTP_PORT = 8080;
    private static final int RADIUS    = 50;

    private static final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("[VoiceServer] TCP порт: " + TCP_PORT);
        startHealthCheck();

        ServerSocket server = new ServerSocket(TCP_PORT);
        System.out.println("[VoiceServer] Ожидаю подключений...");

        ExecutorService pool = Executors.newCachedThreadPool();
        while (true) {
            Socket client = server.accept();
            pool.submit(new ClientHandler(client));
        }
    }

    static class ClientHandler implements Runnable {
        final Socket socket;
        DataInputStream  in;
        DataOutputStream out;
        String nick = "";
        float x, y, z;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                while (!socket.isClosed()) {
                    // Читаем тип пакета
                    int type = in.read();
                    if (type == -1) break;

                    switch (type) {
                        case 0x01: handleConnect(); break;
                        case 0x02: handleAudio();   break;
                        case 0x03: handlePosition(); break;
                        case 0x04: handleDisconnect(); return;
                        case 0x05: // PING -> PONG
                            out.write(0x06);
                            out.flush();
                            break;
                    }
                }
            } catch (Exception e) {
                // клиент отключился
            } finally {
                if (!nick.isEmpty()) {
                    clients.remove(nick);
                    System.out.println("[VoiceServer] Отключился: " + nick + " (" + clients.size() + " онлайн)");
                }
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        private void handleConnect() throws IOException {
            int nickLen = in.read();
            byte[] nickBytes = new byte[nickLen];
            in.readFully(nickBytes);
            nick = new String(nickBytes);
            x = in.readFloat();
            y = in.readFloat();
            z = in.readFloat();
            clients.put(nick, this);
            System.out.println("[VoiceServer] Подключился: " + nick + " (" + clients.size() + " онлайн)");
        }

        private void handleAudio() throws IOException {
            int nickLen = in.read();
            byte[] nickBytes = new byte[nickLen];
            in.readFully(nickBytes);
            String senderNick = new String(nickBytes);

            // Читаем длину аудио
            int audioLen = in.readInt();
            byte[] audio = new byte[audioLen];
            in.readFully(audio);

            ClientHandler sender = clients.get(senderNick);
            if (sender == null) return;

            // Рассылаем всем в радиусе
            for (ClientHandler client : clients.values()) {
                if (client.nick.equals(senderNick)) continue;
                double dist = Math.sqrt(
                    Math.pow(client.x - sender.x, 2) +
                    Math.pow(client.y - sender.y, 2) +
                    Math.pow(client.z - sender.z, 2)
                );
                if (dist > RADIUS) continue;

                float volume = (float)(1.0 - (dist / RADIUS));
                byte volByte = (byte)(volume * 255);

                try {
                    synchronized (client.out) {
                        client.out.write(0x02);
                        client.out.write(nickBytes.length);
                        client.out.write(nickBytes);
                        client.out.write(volByte);
                        client.out.writeInt(audioLen);
                        client.out.write(audio);
                        client.out.flush();
                    }
                } catch (Exception ignored) {}
            }
        }

        private void handlePosition() throws IOException {
            int nickLen = in.read();
            byte[] nickBytes = new byte[nickLen];
            in.readFully(nickBytes);
            String n = new String(nickBytes);
            float nx = in.readFloat();
            float ny = in.readFloat();
            float nz = in.readFloat();
            ClientHandler c = clients.get(n);
            if (c != null) { c.x = nx; c.y = ny; c.z = nz; }
        }

        private void handleDisconnect() throws IOException {
            int nickLen = in.read();
            byte[] nickBytes = new byte[nickLen];
            in.readFully(nickBytes);
            String n = new String(nickBytes);
            clients.remove(n);
            System.out.println("[VoiceServer] Отключился: " + n + " (" + clients.size() + " онлайн)");
        }
    }

    private static void startHealthCheck() {
        new Thread(() -> {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
                server.createContext("/", exchange -> {
                    String response = "VoiceServer OK, clients: " + clients.size();
                    exchange.sendResponseHeaders(200, response.length());
                    exchange.getResponseBody().write(response.getBytes());
                    exchange.getResponseBody().close();
                });
                server.start();
                System.out.println("[VoiceServer] HTTP healthcheck на порту " + HTTP_PORT);
            } catch (Exception e) {
                System.out.println("[VoiceServer] Healthcheck ошибка: " + e.getMessage());
            }
        }, "HealthCheck").start();
    }
}

