import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/**
 * Простой UDP войс чат сервер
 * Получает аудио от клиентов и раздаёт всем остальным в радиусе
 */
public class VoiceServer {

    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "25566"));
    private static final int RADIUS = 50; // блоки — радиус слышимости

    // Клиент: ник -> данные
    private static final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("[VoiceServer] Запуск на порту " + PORT);

        DatagramSocket socket = new DatagramSocket(PORT);
        byte[] buf = new byte[4096];

        // HTTP healthcheck для Railway
        startHealthCheck();

        System.out.println("[VoiceServer] Готов к подключениям");

        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);

            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            InetAddress addr = packet.getAddress();
            int port = packet.getPort();

            handlePacket(socket, data, addr, port);
        }
    }

    private static void handlePacket(DatagramSocket socket, byte[] data, InetAddress addr, int port) {
        try {
            if (data.length < 1) return;
            byte type = data[0];

            switch (type) {
                case 0x01: // CONNECT: [0x01][nick_len][nick][x_float][y_float][z_float]
                    handleConnect(data, addr, port);
                    break;

                case 0x02: // AUDIO: [0x02][nick_len][nick][audio_data...]
                    handleAudio(socket, data, addr, port);
                    break;

                case 0x03: // POSITION UPDATE: [0x03][nick_len][nick][x_float][y_float][z_float]
                    handlePosition(data, addr, port);
                    break;

                case 0x04: // DISCONNECT: [0x04][nick_len][nick]
                    handleDisconnect(data);
                    break;

                case 0x05: // PING
                    socket.send(new DatagramPacket(new byte[]{0x06}, 1, addr, port));
                    break;
            }
        } catch (Exception e) {
            System.out.println("[VoiceServer] Ошибка обработки пакета: " + e.getMessage());
        }
    }

    private static void handleConnect(byte[] data, InetAddress addr, int port) {
        try {
            int nickLen = data[1] & 0xFF;
            String nick = new String(data, 2, nickLen);
            float x = ByteBuffer.wrap(data, 2 + nickLen, 4).getFloat();
            float y = ByteBuffer.wrap(data, 6 + nickLen, 4).getFloat();
            float z = ByteBuffer.wrap(data, 10 + nickLen, 4).getFloat();

            clients.put(nick, new ClientInfo(nick, addr, port, x, y, z));
            System.out.println("[VoiceServer] Подключился: " + nick + " (" + clients.size() + " онлайн)");
        } catch (Exception e) {
            System.out.println("[VoiceServer] Ошибка connect: " + e.getMessage());
        }
    }

    private static void handleAudio(DatagramSocket socket, byte[] data, InetAddress addr, int port) {
        try {
            int nickLen = data[1] & 0xFF;
            String senderNick = new String(data, 2, nickLen);
            byte[] audioData = Arrays.copyOfRange(data, 2 + nickLen, data.length);

            ClientInfo sender = clients.get(senderNick);
            if (sender == null) return;

            // Обновляем адрес на случай смены
            sender.address = addr;
            sender.port = port;

            // Отправляем аудио всем в радиусе
            for (ClientInfo client : clients.values()) {
                if (client.nick.equals(senderNick)) continue;

                double dist = Math.sqrt(
                    Math.pow(client.x - sender.x, 2) +
                    Math.pow(client.y - sender.y, 2) +
                    Math.pow(client.z - sender.z, 2)
                );

                if (dist <= RADIUS) {
                    // Пакет: [0x02][nick_len][nick][volume_byte][audio...]
                    float volume = (float)(1.0 - (dist / RADIUS));
                    byte volByte = (byte)(volume * 255);

                    byte[] nickBytes = senderNick.getBytes();
                    byte[] outPacket = new byte[2 + nickBytes.length + 1 + audioData.length];
                    outPacket[0] = 0x02;
                    outPacket[1] = (byte) nickBytes.length;
                    System.arraycopy(nickBytes, 0, outPacket, 2, nickBytes.length);
                    outPacket[2 + nickBytes.length] = volByte;
                    System.arraycopy(audioData, 0, outPacket, 3 + nickBytes.length, audioData.length);

                    socket.send(new DatagramPacket(outPacket, outPacket.length, client.address, client.port));
                }
            }
        } catch (Exception e) {
            System.out.println("[VoiceServer] Ошибка audio: " + e.getMessage());
        }
    }

    private static void handlePosition(byte[] data, InetAddress addr, int port) {
        try {
            int nickLen = data[1] & 0xFF;
            String nick = new String(data, 2, nickLen);
            float x = ByteBuffer.wrap(data, 2 + nickLen, 4).getFloat();
            float y = ByteBuffer.wrap(data, 6 + nickLen, 4).getFloat();
            float z = ByteBuffer.wrap(data, 10 + nickLen, 4).getFloat();

            ClientInfo client = clients.get(nick);
            if (client != null) {
                client.x = x; client.y = y; client.z = z;
                client.address = addr; client.port = port;
            }
        } catch (Exception ignored) {}
    }

    private static void handleDisconnect(byte[] data) {
        try {
            int nickLen = data[1] & 0xFF;
            String nick = new String(data, 2, nickLen);
            clients.remove(nick);
            System.out.println("[VoiceServer] Отключился: " + nick + " (" + clients.size() + " онлайн)");
        } catch (Exception ignored) {}
    }

    private static void startHealthCheck() {
        // Railway требует HTTP endpoint для healthcheck
        new Thread(() -> {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
                server.createContext("/", exchange -> {
                    String response = "VoiceServer OK, clients: " + clients.size();
                    exchange.sendResponseHeaders(200, response.length());
                    exchange.getResponseBody().write(response.getBytes());
                    exchange.getResponseBody().close();
                });
                server.start();
                System.out.println("[VoiceServer] HTTP healthcheck на порту 8080");
            } catch (Exception e) {
                System.out.println("[VoiceServer] Healthcheck ошибка: " + e.getMessage());
            }
        }, "HealthCheck").start();
    }

    static class ClientInfo {
        String nick;
        InetAddress address;
        int port;
        float x, y, z;

        ClientInfo(String nick, InetAddress address, int port, float x, float y, float z) {
            this.nick = nick;
            this.address = address;
            this.port = port;
            this.x = x; this.y = y; this.z = z;
        }
    }
}
