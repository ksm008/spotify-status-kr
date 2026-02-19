package spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neovisionaries.i18n.CountryCode;
import com.sun.net.httpserver.HttpServer;
import io.github.cdimascio.dotenv.Dotenv;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlaying;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class snipKOR {
    private static final Dotenv dotenv = Dotenv.configure()
            .directory("/")
            .filename(".env")
            .load();

    private static final String CLIENT_ID = dotenv.get("SPOTIFY_CLIENT_ID");
    private static final String CLIENT_SECRET = dotenv.get("SPOTIFY_CLIENT_SECRET");
    private static final String REDIRECT_URI = "http://127.0.0.1:8080/callback";

    private static final SpotifyApi spotifyApi = new SpotifyApi.Builder()
            .setClientId(CLIENT_ID)
            .setClientSecret(CLIENT_SECRET)
            .setRedirectUri(URI.create(REDIRECT_URI))
            .build();
    private static final CountDownLatch latch = new CountDownLatch(1);
    private static TrayIcon trayIcon;

    public static void main(String[] args) {
        setupSystemTray();
        Runtime.getRuntime().addShutdownHook(new Thread(snipKOR::clearFiles));

        try {
            File tokenFile = new File("refresh_token.txt");
            if (tokenFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(tokenFile))) {
                    String refreshToken = reader.readLine();
                    spotifyApi.setRefreshToken(refreshToken);
                }
                refreshAccessToken();
                new Thread(snipKOR::startMusicTracking).start();
            } else {
                int choice = JOptionPane.showConfirmDialog(null,
                        "Spotify 연동이 필요합니다.\n브라우저를 열까요?",
                        "snipKOR 설정", JOptionPane.YES_NO_OPTION);

                if (choice == JOptionPane.YES_OPTION) {
                    startLoginProcess();
                } else {
                    System.exit(0);
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "오류 발생:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private static void setupSystemTray() {
        if (!SystemTray.isSupported()) return;

        try {
            SystemTray tray = SystemTray.getSystemTray();

            int size = 16;
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(30, 215, 96));
            g.fillOval(0, 0, size, size);
            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.BOLD, 12));
            g.drawString("S", 4, 13);
            g.dispose();

            PopupMenu popup = new PopupMenu();
            MenuItem exitItem = new MenuItem("EXIT");
            exitItem.addActionListener(e -> System.exit(0));
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, "snipKOR - Ready", popup);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);

        } catch (Exception ignored) {
        }
    }

    private static void startMusicTracking() {
        if (trayIcon != null) trayIcon.setToolTip("음악 정보를 가져오는 중...");

        while (true) {
            try {
                CurrentlyPlaying currentlyPlaying = spotifyApi.getUsersCurrentlyPlayingTrack()
                        .market(CountryCode.KR)
                        .build()
                        .execute();

                if (currentlyPlaying == null || currentlyPlaying.getItem() == null) {
                    clearFiles();
                    if (trayIcon != null) trayIcon.setToolTip("일시 정지");
                } else {
                    String trackId = currentlyPlaying.getItem().getId();
                    String trackJson = fetchTrackJsonDirectly(trackId, spotifyApi.getAccessToken());

                    if (trackJson != null) {
                        JsonObject root = JsonParser.parseString(trackJson).getAsJsonObject();


                        String title = root.get("name").getAsString();

                        // 콤마로 합치기
                        JsonArray artistArray = root.getAsJsonArray("artists");
                        List<String> artistNames = new ArrayList<>();
                        for (JsonElement artistEl : artistArray) {
                            artistNames.add(artistEl.getAsJsonObject().get("name").getAsString());
                        }
                        String artist = String.join(", ", artistNames);


                        String album = root.getAsJsonObject("album").get("name").getAsString();


                        String imageUrl = "";
                        JsonArray images = root.getAsJsonObject("album").getAsJsonArray("images");
                        for (int i = 0; i < images.size(); i++) {
                            JsonObject img = images.get(i).getAsJsonObject();
                            if (img.get("height").getAsInt() == 300) {
                                imageUrl = img.get("url").getAsString();
                                break;
                            }
                        }

                        saveTextFile("Title.txt", title);
                        saveTextFile("Artist.txt", artist);
                        saveTextFile("Album.txt", album);
                        saveTextFile("snipKOR.txt", title + " ― " + artist);
                        saveImageFile("Cover.png", imageUrl);

                        if (trayIcon != null) trayIcon.setToolTip("🎵 " + title + " - " + artist);
                    }
                }
                Thread.sleep(3000);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("401")) refreshAccessToken();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private static String fetchTrackJsonDirectly(String trackId, String accessToken) throws IOException {
        URL url = new URL("https://api.spotify.com/v1/tracks/" + trackId + "?market=KR");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Accept-Language", "ko");

        if (conn.getResponseCode() != 200) return null;

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    private static void refreshAccessToken() {
        try {
            var credentials = spotifyApi.authorizationCodeRefresh().build().execute();
            spotifyApi.setAccessToken(credentials.getAccessToken());
        } catch (Exception ignored) {
        }
    }

    private static void saveTextFile(String fileName, String content) {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileName), StandardCharsets.UTF_8))) {
            writer.write(content);
        } catch (IOException ignored) {
        }
    }

    private static void saveImageFile(String fileName, String urlString) {
        try {
            BufferedImage image = ImageIO.read(new URL(urlString));
            if (image != null) ImageIO.write(image, "png", new File(fileName));
        } catch (Exception ignored) {
        }
    }

    private static void clearFiles() {
        saveTextFile("Title.txt", "");
        saveTextFile("Artist.txt", "");
        saveTextFile("Album.txt", "");
        saveTextFile("snipKOR.txt", "");

        BufferedImage transparentImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = transparentImage.createGraphics();
        g2d.setComposite(AlphaComposite.Clear);
        g2d.fillRect(0, 0, 1, 1);
        g2d.dispose();

        try {
            ImageIO.write(transparentImage, "png", new File("Cover.png"));
        } catch (IOException ignored) {
        }
    }

    private static void startLoginProcess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/callback", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String code = query.split("code=")[1].split("&")[0];

            String response = "<html><head><meta charset=\"UTF-8\"></head><body style='text-align:center;padding-top:50px;font-family:sans-serif;'><h1>✅ 인증 성공!</h1><p>이제 창을 닫아도 됩니다.</p></body></html>";

            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();

            try {
                var creds = spotifyApi.authorizationCode(code).build().execute();
                spotifyApi.setAccessToken(creds.getAccessToken());
                spotifyApi.setRefreshToken(creds.getRefreshToken());
                try (FileWriter fw = new FileWriter("refresh_token.txt")) {
                    fw.write(creds.getRefreshToken());
                }
            } catch (Exception ignored) {
            }
            latch.countDown();
        });
        server.start();

        Desktop.getDesktop().browse(spotifyApi.authorizationCodeUri().scope("user-read-currently-playing").build().execute());

        latch.await();
        server.stop(0);

        new Thread(snipKOR::startMusicTracking).start();
    }
}