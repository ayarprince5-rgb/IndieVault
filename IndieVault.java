import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*; 
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

// 1. CUSTOM EXCEPTIONS
class InvalidTicketPriceException extends Exception {
    public InvalidTicketPriceException(String msg) { super(msg); }
}

class RentalExpiredException extends Exception {
    public RentalExpiredException(String msg) { super(msg); }
}

class PaymentFailedException extends Exception {
    public PaymentFailedException(String msg) { super(msg); }
}

// 2. INTERFACE & INHERITANCE
interface Payable {
    double calculateTotal();
    double calculateCreatorShare();
}

abstract class DigitalPass implements Payable {
    protected String passId;
    protected String viewerName;
    protected double basePrice;
    protected double tipAmount;
    protected LocalDateTime expiresAt;
    protected boolean active;

    public DigitalPass(String viewerName, double basePrice, double tipAmount, int hours) {
        this.passId = "PASS-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        this.viewerName = viewerName;
        this.basePrice = basePrice;
        this.tipAmount = tipAmount;
        this.expiresAt = LocalDateTime.now().plusHours(hours);
        this.active = true;
    }

    public abstract String getPassType();
    public abstract String getTitle();

    @Override
    public double calculateTotal() {
        return Math.round((basePrice + tipAmount) * 100.0) / 100.0;
    }

    @Override
    public double calculateCreatorShare() {
        return Math.round(((basePrice * 0.80) + (tipAmount * 0.95)) * 100.0) / 100.0;
    }

    public boolean checkActive() {
        if (LocalDateTime.now().isAfter(expiresAt)) {
            this.active = false;
        }
        return this.active;
    }
}

class SingleFilmPass extends DigitalPass {
    private final String filmId;
    private final String filmTitle;

    public SingleFilmPass(String viewerName, String filmId, String filmTitle, double price, double tip) {
        super(viewerName, price, tip, 48);
        this.filmId = filmId;
        this.filmTitle = filmTitle;
    }

    @Override public String getPassType() { return "48-Hour Single Film Pass"; }
    @Override public String getTitle() { return filmTitle; }
    public String getFilmId() { return filmId; }
}

class FestivalBundlePass extends DigitalPass {
    public FestivalBundlePass(String viewerName, double price, double tip) {
        super(viewerName, price, tip, 72);
    }

    @Override public String getPassType() { return "72-Hour 3-Film Festival Bundle"; }
    @Override public String getTitle() { return "Weekend Indie Festival Pass"; }
}

class ShortFilm {
    String id, title, director, language, genre, posterUrl, streamUrl;
    int duration;
    double price, creatorEarnings;

    public ShortFilm(String id, String title, String director, String language, String genre,
                     int duration, double price, String posterUrl, String streamUrl) throws InvalidTicketPriceException {
        if (price < 29 || price > 99) {
            throw new InvalidTicketPriceException("Ticket price must be between ₹29 and ₹99.");
        }
        this.id = id;
        this.title = title;
        this.director = director;
        this.language = language;
        this.genre = genre;
        this.duration = duration;
        this.price = price;
        this.posterUrl = posterUrl;
        this.streamUrl = streamUrl;
    }
}

// 3. MAIN APPLICATION WITH MULTITHREADING, FILE I/O & WEB SERVER
public class IndieVault {
    private static final Map<String, ShortFilm> films = new ConcurrentHashMap<>();
    private static final Map<String, DigitalPass> passes = new ConcurrentHashMap<>();
    private static final AtomicInteger orderSeq = new AtomicInteger(1001);
    private static final Path DATA_DIR = Paths.get("indievault_data");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA_DIR);
        seedFilms();
        saveFilmsToFile();

        // Multithreading: Background daemon thread checking pass expiry every 15 seconds
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PassExpiryMonitor");
            t.setDaemon(true);
            return t;
        });
        monitor.scheduleAtFixedRate(() -> passes.values().forEach(DigitalPass::checkActive), 10, 15, TimeUnit.SECONDS);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10)); // Multithreaded HTTP request handling

        server.createContext("/", IndieVault::handleHome);
        server.createContext("/api/health", IndieVault::handleHealth);
        server.createContext("/api/films", IndieVault::handleFilms);
        server.createContext("/api/upload", IndieVault::handleUpload);
        server.createContext("/api/checkout", IndieVault::handleCheckout);
        server.createContext("/api/watch", IndieVault::handleWatch);

        server.start();
        System.out.println("==========================================");
        System.out.println(" IndieVault Server running at:");
        System.out.println(" http://localhost:" + port);
        System.out.println("==========================================");
    }

    private static void seedFilms() throws InvalidTicketPriceException {
        films.put("F1", new ShortFilm("F1", "Last Local to Sabarmati", "Aarav Patel", "Gujarati", "Drama", 22, 49,
                "https://images.unsplash.com/photo-1485846234645-a62644f84728?w=600",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"));
        films.put("F2", new ShortFilm("F2", "Monsoon Over Munnar", "Meera Nair", "Malayalam", "Documentary", 28, 79,
                "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=600",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"));
        films.put("F3", new ShortFilm("F3", "Midnight Chai & Script", "Rohan Joshi", "Hindi", "Thriller", 18, 29,
                "https://images.unsplash.com/photo-1478720568477-152d9b164e26?w=600",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"));
    }

    // File Management: Save catalog & order receipts to CSV files
    private static synchronized void saveFilmsToFile() {
        try (BufferedWriter bw = Files.newBufferedWriter(DATA_DIR.resolve("films.csv"))) {
            bw.write("ID,Title,Director,Language,Genre,Price,CreatorEarnings\n");
            for (ShortFilm f : films.values()) {
                bw.write(String.format("%s,%s,%s,%s,%s,%.2f,%.2f\n",
                        f.id, f.title, f.director, f.language, f.genre, f.price, f.creatorEarnings));
            }
        } catch (IOException e) {
            System.err.println("File write error: " + e.getMessage());
        }
    }

    private static synchronized void logOrderToFile(String orderId, DigitalPass p) {
        try (BufferedWriter bw = Files.newBufferedWriter(DATA_DIR.resolve("orders.csv"),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            bw.write(String.format("%s,%s,%s,%s,%.2f,%.2f,%s\n",
                    orderId, p.passId, p.viewerName, p.getTitle(), p.calculateTotal(), p.calculateCreatorShare(), p.expiresAt));
        } catch (IOException e) {
            System.err.println("Order log error: " + e.getMessage());
        }
    }

    // HTTP Handlers
    private static void handleHealth(HttpExchange ex) throws IOException {
        sendJson(ex, 200, "{\"status\":\"UP\",\"activeThreads\":" + Thread.activeCount() + "}");
    }

    private static void handleFilms(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (ShortFilm f : films.values()) {
            if (i++ > 0) sb.append(",");
            sb.append(String.format("{\"id\":\"%s\",\"title\":\"%s\",\"director\":\"%s\",\"language\":\"%s\",\"genre\":\"%s\",\"duration\":%d,\"price\":%.0f,\"poster\":\"%s\"}",
                    esc(f.id), esc(f.title), esc(f.director), esc(f.language), esc(f.genre), f.duration, f.price, esc(f.posterUrl)));
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    private static void handleUpload(HttpExchange ex) throws IOException {
        Map<String, String> params = parseForm(ex);
        try {
            String title = params.getOrDefault("title", "").trim();
            String director = params.getOrDefault("director", "").trim();
            String lang = params.getOrDefault("language", "Gujarati");
            String genre = params.getOrDefault("genre", "Drama");
            int dur = Integer.parseInt(params.getOrDefault("duration", "20"));
            double price = Double.parseDouble(params.getOrDefault("price", "0"));

            if (title.isEmpty() || director.isEmpty()) {
                throw new InvalidTicketPriceException("Title and Director name are required.");
            }
            String id = "F" + (films.size() + 1);
            ShortFilm sf = new ShortFilm(id, title, director, lang, genre, dur, price,
                    "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=600",
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoy.mp4");
            films.put(id, sf);
            saveFilmsToFile();
            sendJson(ex, 200, "{\"message\":\"Published '" + esc(title) + "' at ₹" + (int) price + "\"}");
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private static void handleCheckout(HttpExchange ex) throws IOException {
        Map<String, String> params = parseForm(ex);
        try {
            String viewer = params.getOrDefault("viewer", "").trim();
            String type = params.getOrDefault("type", "SINGLE");
            String filmId = params.getOrDefault("filmId", "F1");
            double tip = Double.parseDouble(params.getOrDefault("tip", "0"));
            boolean fail = Boolean.parseBoolean(params.getOrDefault("fail", "false"));

            if (viewer.isEmpty()) throw new PaymentFailedException("Viewer name is required.");
            if (tip < 0) throw new PaymentFailedException("Tip cannot be negative.");
            if (fail) throw new PaymentFailedException("Sandbox Payment Declined (Simulated Error).");

            DigitalPass pass;
            if ("BUNDLE".equals(type)) {
                pass = new FestivalBundlePass(viewer, 129, tip);
            } else {
                ShortFilm f = films.get(filmId);
                if (f == null) throw new PaymentFailedException("Film not found.");
                pass = new SingleFilmPass(viewer, f.id, f.title, f.price, tip);
                f.creatorEarnings += pass.calculateCreatorShare();
                saveFilmsToFile();
            }

            passes.put(pass.passId, pass);
            String orderId = "ORD-" + orderSeq.getAndIncrement();
            logOrderToFile(orderId, pass);

            String json = String.format("{\"orderId\":\"%s\",\"passId\":\"%s\",\"title\":\"%s\",\"total\":%.2f,\"creatorShare\":%.2f,\"expiresAt\":\"%s\"}",
                    orderId, pass.passId, esc(pass.getTitle()), pass.calculateTotal(), pass.calculateCreatorShare(), pass.expiresAt);
            sendJson(ex, 200, json);
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private static void handleWatch(HttpExchange ex) throws IOException {
        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        try {
            String passId = params.getOrDefault("passId", "").trim();
            DigitalPass pass = passes.get(passId);
            if (pass == null) throw new RentalExpiredException("Invalid Pass ID. Please buy a pass first.");
            if (!pass.checkActive()) throw new RentalExpiredException("48-hour pass has expired!");

            String url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4";
            if (pass instanceof SingleFilmPass sfp && films.containsKey(sfp.getFilmId())) {
                url = films.get(sfp.getFilmId()).streamUrl;
            }
            sendJson(ex, 200, String.format("{\"title\":\"%s\",\"expiresAt\":\"%s\",\"streamUrl\":\"%s\"}",
                    esc(pass.getTitle()), pass.expiresAt, esc(url)));
        } catch (RentalExpiredException e) {
            sendJson(ex, 403, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private static void handleHome(HttpExchange ex) throws IOException {
        Path htmlPath = Paths.get("indievault.html");
        if (!Files.exists(htmlPath)) {
            htmlPath = Paths.get("c:\\Users\\PRINCE\\Downloads\\IndieVault\\indievault.html");
        }
        byte[] bytes = Files.readAllBytes(htmlPath);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseQuery(body);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        if (q == null || q.isEmpty()) return map;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}