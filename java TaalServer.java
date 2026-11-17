import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Taal Junior and Senior High School enrollment server.
 *
 * Needs only the JDK (11 or newer). No libraries, no build step.
 *
 *   Run from the website folder:   java TaalServer.java
 *   Then open:                     http://localhost:8080
 *
 * Optional settings (environment variables):
 *   PORT        default 8080
 *   HOST        default 127.0.0.1 (this computer only). Use 0.0.0.0 to allow other devices.
 *   ADMIN_USER  default admin01
 *   ADMIN_PASS  default admin0623   (change this before real use!)
 *
 * Enrollments are saved in data/enrollments.log (one record per line).
 */
public class TaalServer {

    static final Path ROOT = Paths.get(".").toAbsolutePath().normalize();
    static final Path DATA_DIR = ROOT.resolve("data");
    static final Path DATA_FILE = DATA_DIR.resolve("enrollments.log");

    static final String ADMIN_USER = env("ADMIN_USER", "admin01");
    static final String ADMIN_PASS = env("ADMIN_PASS", "admin0623");

    static final long TOKEN_LIFETIME_MS = 2L * 60 * 60 * 1000; // 2 hours
    static final int MAX_BODY = 64 * 1024;

    static final Map<String, Long> tokens = new ConcurrentHashMap<>();
    static final List<Map<String, String>> records = new ArrayList<>();
    static final Object LOCK = new Object();
    static final SecureRandom RANDOM = new SecureRandom();

    static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"));

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        String host = env("HOST", "127.0.0.1");

        loadRecords();

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", TaalServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Taal HS server running at http://" + host + ":" + port);
        System.out.println("Serving files from: " + ROOT);
        System.out.println("Enrollments saved in: " + DATA_FILE);
        System.out.println("Loaded " + records.size() + " existing record(s). Press Ctrl+C to stop.");
        if (ADMIN_PASS.equals("admin0623")) {
            System.out.println("WARNING: using the default admin password. Set ADMIN_PASS before real use.");
        }
    }

    // ------------------------------------------------------------ routing

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            if (path.startsWith("/api/")) {
                api(ex, method, path);
            } else if (method.equals("GET") || method.equals("HEAD")) {
                serveFile(ex, path);
            } else {
                json(ex, 405, "{\"error\":\"Method not allowed\"}");
            }
        } catch (BadRequest e) {
            json(ex, 400, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, "{\"error\":\"Server error\"}");
        } finally {
            ex.close();
        }
    }

    static void api(HttpExchange ex, String method, String path) throws IOException {
        switch (path) {
            case "/api/health" -> json(ex, 200, "{\"ok\":true}");

            case "/api/enroll" -> {
                if (!method.equals("POST")) { json(ex, 405, "{\"error\":\"Use POST\"}"); return; }
                enroll(ex);
            }

            case "/api/admin/login" -> {
                if (!method.equals("POST")) { json(ex, 405, "{\"error\":\"Use POST\"}"); return; }
                adminLogin(ex);
            }

            case "/api/admin/enrollments" -> {
                if (!method.equals("GET")) { json(ex, 405, "{\"error\":\"Use GET\"}"); return; }
                if (!validToken(ex.getRequestHeaders().getFirst("X-Admin-Token"))) {
                    json(ex, 401, "{\"error\":\"Admin login required\"}");
                    return;
                }
                listEnrollments(ex);
            }

            default -> json(ex, 404, "{\"error\":\"Not found\"}");
        }
    }

    // ------------------------------------------------------------ enrollment

    static void enroll(HttpExchange ex) throws IOException {
        Map<String, String> data = parseForm(readBody(ex));

        String name = data.getOrDefault("Student Name", "").trim();
        String lrn = data.getOrDefault("LRN", "").trim();

        if (name.isEmpty()) throw new BadRequest("Student Name is required");
        if (!lrn.matches("[0-9]{12}")) throw new BadRequest("LRN must be exactly 12 digits");
        if (data.size() > 60) throw new BadRequest("Too many fields");

        // Server decides the submission time, not the browser.
        data.put("Submission Date",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        synchronized (LOCK) {
            for (Map<String, String> r : records) {
                boolean sameLrn = clean(r.get("LRN")).equals(clean(lrn));
                boolean sameNameDob = clean(r.get("Student Name")).equals(clean(name))
                        && clean(r.get("Birth Date")).equals(clean(data.get("Birth Date")));
                if (sameLrn || sameNameDob) {
                    json(ex, 409, "{\"error\":\"Duplicate enrollment detected. This record was not saved.\"}");
                    return;
                }
            }

            Files.createDirectories(DATA_DIR);
            Files.writeString(DATA_FILE, encodeForm(data) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            records.add(data);
        }

        json(ex, 201, "{\"ok\":true,\"submissionDate\":" + q(data.get("Submission Date")) + "}");
    }

    static void listEnrollments(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        synchronized (LOCK) {
            for (int i = 0; i < records.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, String> e : records.get(i).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(q(e.getKey())).append(':').append(q(e.getValue()));
                }
                sb.append('}');
            }
        }
        sb.append(']');
        json(ex, 200, sb.toString());
    }

    // ------------------------------------------------------------ admin

    static void adminLogin(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(readBody(ex));

        boolean ok = safeEquals(form.getOrDefault("user", ""), ADMIN_USER)
                & safeEquals(form.getOrDefault("pass", ""), ADMIN_PASS);

        if (!ok) {
            try { Thread.sleep(600); } catch (InterruptedException ignored) { }
            json(ex, 401, "{\"error\":\"Incorrect admin login.\"}");
            return;
        }

        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(token, System.currentTimeMillis() + TOKEN_LIFETIME_MS);
        json(ex, 200, "{\"token\":" + q(token) + "}");
    }

    static boolean validToken(String token) {
        if (token == null) return false;
        Long expires = tokens.get(token);
        if (expires == null) return false;
        if (expires < System.currentTimeMillis()) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    static boolean safeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ static files

    static void serveFile(HttpExchange ex, String urlPath) throws IOException {
        String rel = urlPath.equals("/") ? "index.html" : urlPath.substring(1);
        Path file = ROOT.resolve(rel).normalize();

        String ext = "";
        int dot = file.getFileName() == null ? -1 : file.getFileName().toString().lastIndexOf('.');
        if (dot >= 0) ext = file.getFileName().toString().substring(dot + 1).toLowerCase();

        boolean allowed = file.startsWith(ROOT)
                && !file.startsWith(DATA_DIR)
                && TYPES.containsKey(ext)
                && Files.isRegularFile(file);

        if (!allowed) {
            byte[] msg = "Not found".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
            return;
        }

        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", TYPES.get(ext));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");

        if (ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ------------------------------------------------------------ helpers

    static void loadRecords() throws IOException {
        if (!Files.exists(DATA_FILE)) return;
        for (String line : Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) records.add(parseForm(line));
        }
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) throw new BadRequest("Request too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    static String encodeForm(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static String clean(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /** Quote a string as JSON. */
    static String q(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    static class BadRequest extends RuntimeException {
        BadRequest(String message) { super(message); }
    }
}