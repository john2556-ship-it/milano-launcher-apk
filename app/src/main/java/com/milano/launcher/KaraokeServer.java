package com.milano.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Máy chủ Karaoke chạy ngay trên box TV (không cần NAS).
 * - Điện thoại quét QR trên TV → mở http://IP-TV:8686/?t=TOKEN để chọn bài.
 * - TV mở http://127.0.0.1:8686/tv để phát YouTube.
 * - Đồng bộ bằng long-poll (/api/poll), lệnh gửi bằng POST /api/cmd.
 * Chỉ dùng thư viện có sẵn của Java/Android (org.json có sẵn trong Android).
 */
public class KaraokeServer {

    public interface Assets {
        InputStream open(String name) throws IOException;
    }

    private static final Pattern VID = Pattern.compile("^[\\w-]{11}$");
    private static final Pattern YT_LINK = Pattern.compile("(?:youtu\\.be/|[?&]v=|/shorts/|/embed/|/live/)([\\w-]{11})");
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9._-]+$");
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final Assets assets;
    private final File stateFile;
    private final String token;
    private final String appName;
    private int port;
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;

    // ---- Trạng thái (khoá bằng lock) ----
    private final Object lock = new Object();
    private JSONObject current = null;
    private final List<JSONObject> queue = new ArrayList<>();
    private final List<JSONObject> history = new ArrayList<>();
    private final List<String> singers = new ArrayList<>();   // danh sách tên người hát (dùng chung mọi điện thoại)
    private int volume = 80;
    private boolean paused = false;
    private long seq = 1;
    private final LinkedList<Object[]> events = new LinkedList<>(); // {seq, role|null, msg}

    private final Map<String, Object[]> cache = Collections.synchronizedMap(new LinkedHashMap<String, Object[]>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) { return size() > 200; }
    });

    public KaraokeServer(Assets assets, File dataDir, String appName) {
        this.assets = assets;
        this.appName = appName;
        dataDir.mkdirs();
        this.stateFile = new File(dataDir, "karaoke_state.json");
        this.token = loadToken(new File(dataDir, "karaoke_token.txt"));
        loadState();
    }

    // ================= Khởi động / dừng =================

    public synchronized void start(int preferredPort) throws IOException {
        if (running) return;
        IOException last = null;
        for (int p = preferredPort; p < preferredPort + 10; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                server = s;
                port = p;
                break;
            } catch (IOException e) { last = e; }
        }
        if (server == null) throw last != null ? last : new IOException("no port");
        running = true;
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "karaoke");
            t.setDaemon(true);
            return t;
        });
        pool.execute(this::acceptLoop);
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
        server = null;
    }

    public boolean isRunning() { return running; }
    public int getPort() { return port; }
    public String localTvUrl() { return "http://127.0.0.1:" + port + "/tv"; }
    public String remoteUrl() { return "http://" + lanIp() + ":" + port + "/?t=" + token; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handleSocket(s));
            } catch (IOException e) {
                if (!running) return;
            } catch (Exception ignored) { }
        }
    }

    // ================= HTTP tối giản =================

    private static class Req {
        String method, path, body = "";
        Map<String, String> q = new HashMap<>();
        boolean local;
    }

    private void handleSocket(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            Req r = new Req();
            r.method = parts[0];
            String target = parts[1];
            int len = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    try { len = Integer.parseInt(h.substring(c + 1).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            if (len > 0) {
                len = Math.min(len, 64 * 1024);
                byte[] b = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = in.read(b, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
                r.body = new String(b, 0, off, StandardCharsets.UTF_8);
            }
            int qi = target.indexOf('?');
            r.path = qi >= 0 ? target.substring(0, qi) : target;
            if (qi >= 0) {
                for (String kv : target.substring(qi + 1).split("&")) {
                    int e = kv.indexOf('=');
                    String k = e >= 0 ? kv.substring(0, e) : kv;
                    String v = e >= 0 ? kv.substring(e + 1) : "";
                    try { r.q.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8")); } catch (Exception ignored) { }
                }
            }
            InetAddress a = sock.getInetAddress();
            r.local = a != null && a.isLoopbackAddress();
            route(r, sock.getOutputStream());
        } catch (Exception ignored) { }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void json(OutputStream out, int code, Object o) throws IOException {
        respond(out, code, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void route(Req r, OutputStream out) throws IOException {
        String p = r.path;
        if (p.equals("/") || p.equals("/index.html")) { asset(out, "remote.html"); return; }
        if (p.equals("/tv")) { asset(out, "tv.html"); return; }
        if (!p.startsWith("/api/")) {
            String name = p.substring(1);
            if (SAFE_NAME.matcher(name).matches()) asset(out, name);
            else respond(out, 404, "text/plain", "404".getBytes());
            return;
        }
        // API: TV (máy này) luôn được, điện thoại phải có đúng token trong mã QR
        if (!r.local && !token.equals(r.q.get("t"))) {
            json(out, 403, "{\"error\":\"token\"}");
            return;
        }
        try {
            switch (p) {
                case "/api/info":
                    json(out, 200, new JSONObject().put("name", appName).put("remoteUrl", remoteUrl()));
                    return;
                case "/api/poll":
                    json(out, 200, poll(parseLong(r.q.get("since"), -1), r.q.get("role")));
                    return;
                case "/api/search":
                    try {
                        json(out, 200, search(r.q.get("q"), "1".equals(r.q.get("k"))));
                    } catch (Exception e) {
                        json(out, 502, "{\"error\":\"search\"}");
                    }
                    return;
                case "/api/cmd":
                    if (!"POST".equals(r.method)) break;
                    JSONObject m = new JSONObject(r.body);
                    JSONObject reply = handle(m);
                    json(out, 200, reply != null ? reply : new JSONObject().put("ok", true));
                    return;
            }
        } catch (JSONException e) {
            json(out, 400, "{\"error\":\"bad json\"}");
            return;
        }
        respond(out, 404, "text/plain", "404".getBytes());
    }

    private void asset(OutputStream out, String name) throws IOException {
        try (InputStream in = assets.open("karaoke/" + name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                    : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : name.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
            respond(out, 200, type, b.toByteArray());
        } catch (IOException e) {
            respond(out, 404, "text/plain", "404".getBytes());
        }
    }

    // ================= Đồng bộ (long-poll) =================

    private JSONObject poll(long since, String role) throws JSONException {
        synchronized (lock) {
            if (since >= 0 && since <= seq) {
                long end = System.currentTimeMillis() + 25000;
                while (seq <= since && running) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    try { lock.wait(left); } catch (InterruptedException e) { break; }
                }
            }
            JSONArray ev = new JSONArray();
            if (since >= 0) {
                for (Object[] e : events) {
                    long s = (Long) e[0];
                    String rr = (String) e[1];
                    if (s > since && (rr == null || rr.equals(role))) ev.put(e[2]);
                }
            }
            return new JSONObject().put("seq", seq).put("state", stateJson()).put("events", ev);
        }
    }

    private void event(String role, JSONObject msg) {
        synchronized (lock) {
            seq++;
            events.add(new Object[]{seq, role, msg});
            while (events.size() > 100) events.removeFirst();
            lock.notifyAll();
        }
    }

    private void toast(String msg) {
        try { event(null, new JSONObject().put("type", "toast").put("msg", msg)); } catch (JSONException ignored) { }
    }

    /** Gọi khi đang giữ lock. */
    private void changed() {
        seq++;
        lock.notifyAll();
        saveState();
    }

    private JSONObject stateJson() throws JSONException {
        return new JSONObject()
                .put("current", current == null ? JSONObject.NULL : current)
                .put("queue", new JSONArray(queue))
                .put("history", new JSONArray(history))
                .put("singers", new JSONArray(singers))
                .put("volume", volume)
                .put("paused", paused);
    }

    // ================= Lệnh =================

    private JSONObject handle(JSONObject m) throws JSONException {
        String type = m.optString("type");
        switch (type) {
            case "add": {
                JSONObject it = makeItem(m.optJSONObject("item"), m.optString("singer"));
                if (it != null) addItem(it, m.optBoolean("next"));
                break;
            }
            case "addLink": {
                String id = parseYouTube(m.optString("url"));
                if (id == null) return new JSONObject().put("error", "⚠️ Link YouTube không hợp lệ");
                JSONObject v = new JSONObject().put("videoId", id);
                String title = m.optString("title").trim();
                if (title.isEmpty()) {
                    JSONObject meta = oembed(id);
                    if (meta != null) { v.put("title", meta.optString("title")); v.put("channel", meta.optString("author_name")); }
                } else v.put("title", title);
                addItem(makeItem(v, m.optString("singer")), m.optBoolean("next"));
                break;
            }
            case "readd": {
                synchronized (lock) {
                    for (JSONObject h : history) {
                        if (h.optString("id").equals(m.optString("id"))) {
                            JSONObject c = new JSONObject(h.toString());
                            c.put("id", UUID.randomUUID().toString());
                            c.put("addedAt", System.currentTimeMillis());
                            c.remove("sungAt");
                            String sg = clip(m.optString("singer"), 30);
                            if (!sg.isEmpty()) c.put("singer", sg);
                            addItem(c, false);
                            break;
                        }
                    }
                }
                break;
            }
            case "remove":
                synchronized (lock) {
                    Iterator<JSONObject> i = queue.iterator();
                    while (i.hasNext()) if (i.next().optString("id").equals(m.optString("id"))) i.remove();
                    changed();
                }
                break;
            case "move":
                synchronized (lock) {
                    int i = indexOf(m.optString("id"));
                    if (i >= 0) {
                        JSONObject it = queue.remove(i);
                        String dir = m.optString("dir");
                        int to = "top".equals(dir) ? 0 : "up".equals(dir) ? Math.max(0, i - 1) : Math.min(queue.size(), i + 1);
                        queue.add(to, it);
                        changed();
                    }
                }
                break;
            case "clear":
                synchronized (lock) { queue.clear(); changed(); }
                break;
            case "skip":
                advance();
                break;
            case "ended":
            case "error":
                synchronized (lock) {
                    if (current != null && current.optString("id").equals(m.optString("id"))) {
                        if (type.equals("error")) toast("⚠️ Bài này không phát được trên TV, chuyển bài tiếp");
                        advance();
                    }
                }
                break;
            case "restart": {
                // Hát lại từ đầu: đổi người hát thành tên đang chọn trên điện thoại
                String sg = clip(m.optString("singer"), 30);
                synchronized (lock) {
                    if (current != null && !sg.isEmpty() && !sg.equals(current.optString("singer"))) {
                        current.put("singer", sg);
                        rememberSinger(sg);
                        toast("🎤 " + sg + " hát lại từ đầu");
                        changed();
                    }
                }
                event("tv", new JSONObject().put("type", "cmd").put("cmd", "restart"));
                break;
            }
            case "toggle":
                event("tv", new JSONObject().put("type", "cmd").put("cmd", type));
                break;
            case "addSinger":
                synchronized (lock) { rememberSinger(clip(m.optString("name"), 30)); changed(); }
                break;
            case "removeSinger":
                synchronized (lock) { singers.remove(clip(m.optString("name"), 30)); changed(); }
                break;
            case "volume":
                synchronized (lock) {
                    volume = Math.max(0, Math.min(100, volume + m.optInt("delta")));
                    event("tv", new JSONObject().put("type", "cmd").put("cmd", "volume").put("value", volume));
                    changed();
                }
                break;
            case "status":
                synchronized (lock) {
                    if (m.has("paused") && m.optBoolean("paused") != paused) { paused = m.optBoolean("paused"); changed(); }
                }
                break;
            case "tvSearch": {
                String q = clip(m.optString("q"), 120);
                if (q.isEmpty()) break;
                event("tv", new JSONObject().put("type", "tvSearch").put("q", q).put("loading", true));
                JSONObject res = new JSONObject().put("type", "tvSearch").put("q", q);
                try { res.put("results", search(q, m.optBoolean("k", true))); }
                catch (Exception e) { res.put("results", new JSONArray()).put("error", true); }
                event("tv", res);
                break;
            }
            case "tvSearchClose":
                event("tv", new JSONObject().put("type", "tvSearchClose"));
                break;
        }
        return null;
    }

    private int indexOf(String id) {
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).optString("id").equals(id)) return i;
        return -1;
    }

    /** Thêm tên vào danh sách người hát (không trùng, tối đa 40 tên). Gọi khi đang giữ lock. */
    private void rememberSinger(String name) {
        if (name == null || name.isEmpty() || name.equalsIgnoreCase("Khách")) return;
        for (String x : singers) if (x.equalsIgnoreCase(name)) return;
        singers.add(name);
        while (singers.size() > 40) singers.remove(0);
    }

    private void addItem(JSONObject it, boolean next) {
        if (it == null) return;
        synchronized (lock) {
            rememberSinger(it.optString("singer"));
            if (next) queue.add(0, it); else queue.add(it);
            String sg = it.optString("singer");
            toast("🎤 Đã thêm: " + it.optString("title") + (sg.isEmpty() ? "" : " — " + sg));
            if (current == null) advance(); else changed();
        }
    }

    private void advance() {
        synchronized (lock) {
            if (current != null) {
                try { current.put("sungAt", System.currentTimeMillis()); } catch (JSONException ignored) { }
                history.add(0, current);
                while (history.size() > 60) history.remove(history.size() - 1);
            }
            current = queue.isEmpty() ? null : queue.remove(0);
            paused = false;
            changed();
        }
    }

    private static String clip(String s, int n) {
        s = s == null ? "" : s.trim();
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static JSONObject makeItem(JSONObject v, String singer) {
        if (v == null) return null;
        String id = v.optString("videoId");
        if (!VID.matcher(id).matches()) return null;
        try {
            String title = clip(v.optString("title"), 200);
            return new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("videoId", id)
                    .put("title", title.isEmpty() ? "YouTube · " + id : title)
                    .put("channel", clip(v.optString("channel"), 80))
                    .put("duration", clip(v.optString("duration"), 12))
                    .put("thumb", "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg")
                    .put("singer", clip(singer, 30))
                    .put("addedAt", System.currentTimeMillis());
        } catch (JSONException e) { return null; }
    }

    static String parseYouTube(String url) {
        String s = url == null ? "" : url.trim();
        if (VID.matcher(s).matches()) return s;
        Matcher m = YT_LINK.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    // ================= YouTube =================

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8");
        c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject oembed(String id) {
        try {
            return new JSONObject(httpGet("https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=" + id));
        } catch (Exception e) { return null; }
    }

    JSONArray search(String q, boolean karaokeOnly) throws Exception {
        String query = clip(q, 120);
        if (query.isEmpty()) return new JSONArray();
        if (karaokeOnly && !Pattern.compile("karaoke|beat|instrumental|kara", Pattern.CASE_INSENSITIVE).matcher(query).find()) {
            query += " karaoke";
        }
        String key = query.toLowerCase(Locale.ROOT);
        Object[] hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 10 * 60 * 1000) return (JSONArray) hit[1];
        String html = httpGet("https://www.youtube.com/results?hl=vi&gl=US&search_query=" + URLEncoder.encode(query, "UTF-8"));
        JSONArray res = parseResults(html);
        if (res.length() > 0) cache.put(key, new Object[]{System.currentTimeMillis(), res});
        return res;
    }

    /** Lấy danh sách video từ trang kết quả tìm kiếm YouTube (ytInitialData). */
    static JSONArray parseResults(String html) throws JSONException {
        JSONArray out = new JSONArray();
        int i = html.indexOf("ytInitialData");
        if (i < 0) return out;
        int start = html.indexOf('{', i);
        if (start < 0) return out;
        String jsonText = extractObject(html, start);
        if (jsonText == null) return out;
        List<JSONObject> found = new ArrayList<>();
        collect(new JSONObject(jsonText), found, 0);
        for (JSONObject v : found) {
            String id = v.optString("videoId");
            if (!VID.matcher(id).matches()) continue;
            if (!v.has("lengthText")) continue;            // bỏ live / phát trực tiếp
            JSONObject item = new JSONObject()
                    .put("videoId", id)
                    .put("title", text(v.optJSONObject("title")))
                    .put("channel", text(v.optJSONObject("ownerText")))
                    .put("duration", text(v.optJSONObject("lengthText")))
                    .put("thumb", "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg");
            out.put(item);
            if (out.length() >= 25) break;
        }
        return out;
    }

    private static void collect(Object o, List<JSONObject> found, int depth) {
        if (depth > 40 || found.size() >= 40) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONObject vr = j.optJSONObject("videoRenderer");
            if (vr != null) { found.add(vr); return; }
            Iterator<String> keys = j.keys();
            while (keys.hasNext()) collect(j.opt(keys.next()), found, depth + 1);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int k = 0; k < a.length(); k++) collect(a.opt(k), found, depth + 1);
        }
    }

    private static String text(JSONObject t) {
        if (t == null) return "";
        String s = t.optString("simpleText");
        if (!s.isEmpty()) return s;
        JSONArray runs = t.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) b.append(r.optString("text"));
        }
        return b.toString();
    }

    /** Cắt đúng một object JSON bắt đầu tại vị trí start (tính cả chuỗi có dấu ngoặc). */
    private static String extractObject(String s, int start) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return null;
    }

    // ================= Lưu trữ, mạng =================

    private static String loadToken(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[64];
            int n = in.read(b);
            String t = n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : "";
            if (t.length() >= 8) return t;
        } catch (IOException ignored) { }
        byte[] r = new byte[6];
        new SecureRandom().nextBytes(r);
        StringBuilder t = new StringBuilder();
        for (byte x : r) t.append(String.format(Locale.ROOT, "%02x", x));
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(t.toString().getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        return t.toString();
    }

    private void loadState() {
        try (InputStream in = new java.io.FileInputStream(stateFile)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONObject s = new JSONObject(b.toString("UTF-8"));
            current = s.optJSONObject("current");
            volume = s.optInt("volume", 80);
            JSONArray q = s.optJSONArray("queue"), h = s.optJSONArray("history");
            JSONArray sg = s.optJSONArray("singers");
            if (sg != null) for (int i = 0; i < sg.length(); i++) if (!sg.optString(i).isEmpty()) singers.add(sg.optString(i));
            if (q != null) for (int i = 0; i < q.length(); i++) if (q.optJSONObject(i) != null) queue.add(q.optJSONObject(i));
            if (h != null) for (int i = 0; i < h.length(); i++) if (h.optJSONObject(i) != null) history.add(h.optJSONObject(i));
        } catch (Exception ignored) { }
    }

    /** Gọi khi đang giữ lock. File nhỏ nên ghi luôn. */
    private void saveState() {
        try (FileOutputStream o = new FileOutputStream(stateFile)) {
            o.write(stateJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** IP của TV trong mạng nhà (Wi-Fi hoặc dây mạng). */
    static String lanIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase(Locale.ROOT);
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (!a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) { }
        return fallback != null ? fallback : "127.0.0.1";
    }
}
