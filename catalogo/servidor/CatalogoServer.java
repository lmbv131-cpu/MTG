import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.net.http.*;
import java.time.Duration;

/** Serves the catalog folder on the home network (with byte ranges, which iPad Safari needs for video). */
public class CatalogoServer {
  static Path root;
  static final Map<String, String> TYPES = Map.ofEntries(
    Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
    Map.entry("css", "text/css; charset=utf-8"), Map.entry("json", "application/json"),
    Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("jpg", "image/jpeg"),
    Map.entry("png", "image/png"), Map.entry("svg", "image/svg+xml"), Map.entry("md", "text/plain; charset=utf-8"));

  static int port = 8090;
  public static void main(String[] a) throws Exception {
    root = Paths.get(a.length > 0 ? a[0] : ".").toAbsolutePath().normalize();
    port = a.length > 1 ? Integer.parseInt(a[1]) : 8090;
    // frames left by a window that was closed mid-recording
    try (var w = Files.walk(root.resolve("servidor/tmp"))) { w.sorted(Comparator.reverseOrder()).forEach((f) -> f.toFile().delete()); } catch (Exception e) { /* none */ }
    HttpServer s = HttpServer.create(new InetSocketAddress(port), 64);
    s.createContext("/", CatalogoServer::handle);
    s.createContext("/simular", CatalogoServer::simular);
    s.createContext("/simular-listo", CatalogoServer::listo);
    s.createContext("/guardar", CatalogoServer::guardar);
    s.createContext("/auto", Auto::auto);
    s.createContext("/auto-estado", Auto::estado);
    s.createContext("/auto-vivo", Auto::vivo);
    s.createContext("/auto-motores", Auto::motores);
    s.createContext("/auto-todo", Auto::todo);
    s.createContext("/grabados", CatalogoServer::grabados);
    s.createContext("/espejo-aviso", Espejo::aviso);
    Runtime.getRuntime().addShutdownHook(new Thread(Auto::stop));
    s.setExecutor(Executors.newFixedThreadPool(16));
    s.start();
    Espejo.iniciar();
    System.out.println("Catalogo servido desde " + root);
    System.out.println("En este PC:   http://localhost:" + port + "/");
    for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
      for (InetAddress ad : Collections.list(ni.getInetAddresses()))
        if (ad instanceof Inet4Address && ad.isSiteLocalAddress())
          System.out.println("En el iPad:   http://" + ad.getHostAddress() + ":" + port + "/   (" + ni.getDisplayName() + ")");
    }
    System.out.println("Deja esta ventana abierta mientras lo uses. Cierrala para parar.");
  }

  static void handle(HttpExchange x) throws IOException {
    try {
      String p = URLDecoder.decode(x.getRequestURI().getRawPath(), "UTF-8");
      Path f = root.resolve(p.substring(1)).normalize();
      if (!f.startsWith(root)) { send(x, 403, "no"); return; }
      if (Files.isDirectory(f)) f = f.resolve(Files.exists(f.resolve("index.html")) ? "index.html" : "ABRIR.html");
      if (!Files.isRegularFile(f)) { send(x, 404, "No existe: " + p); return; }
      String n = f.getFileName().toString(), ext = n.substring(n.lastIndexOf('.') + 1).toLowerCase();
      Headers h = x.getResponseHeaders();
      h.set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
      h.set("Accept-Ranges", "bytes");
      h.set("Cache-Control", "no-cache");
      long len = Files.size(f), from = 0, to = len - 1;
      String r = x.getRequestHeaders().getFirst("Range");
      boolean part = false;
      if (r != null && r.startsWith("bytes=")) {
        String[] se = r.substring(6).split(",")[0].trim().split("-", -1);
        if (se[0].isEmpty()) { from = Math.max(0, len - Long.parseLong(se[1])); }
        else { from = Long.parseLong(se[0]); if (!se[1].isEmpty()) to = Math.min(len - 1, Long.parseLong(se[1])); }
        if (from > to || from >= len) { h.set("Content-Range", "bytes */" + len); x.sendResponseHeaders(416, -1); x.close(); return; }
        part = true; h.set("Content-Range", "bytes " + from + "-" + to + "/" + len);
      }
      long n2 = to - from + 1;
      boolean head = "HEAD".equals(x.getRequestMethod());
      x.sendResponseHeaders(part ? 206 : 200, head ? -1 : n2);
      if (!head) try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r"); OutputStream o = x.getResponseBody()) {
        raf.seek(from); byte[] b = new byte[65536]; long left = n2;
        while (left > 0) { int k = raf.read(b, 0, (int) Math.min(b.length, left)); if (k < 0) break; o.write(b, 0, k); left -= k; }
      }
    } catch (IOException e) { /* client went away */ } finally { x.close(); }
  }

  // ---- Simular: the catalog starts the game engine itself (hidden, port 8091, --sim) so Luis doesn't have to open MTG Local ----
  static final int MOTOR = 8091;
  static boolean up(int port) { try (Socket so = new Socket()) { so.connect(new InetSocketAddress("127.0.0.1", port), 300); return true; } catch (IOException e) { return false; } }
  // motor 1 of Auto (several play at once for «Simular todo»); this page's own Simular uses that one
  static String arrancar() { return Auto.lanes[0].arrancar(); }
  static int simCode() { return Auto.lanes[0].simCode(); }
  static void simular(HttpExchange x) throws IOException {
    String q = x.getRequestURI().getRawQuery(); if (q == null) q = "";
    // each Simular gets its own motor (a second one no longer replaces the first), up to the number allowed now
    Auto.Lane l = Auto.manual();
    String err = l == null ? (Auto.jugando ? "Mientras juegas solo se abre una simulación a la vez: cierra la otra pestaña de Simular." : "Ya hay " + Auto.objetivo() + " simulaciones abiertas: cierra una.") : l.arrancar();
    int m = l == null ? 0 : l.n;
    String host = Optional.ofNullable(x.getRequestHeaders().getFirst("Host")).orElse("localhost").replaceAll(":\\d+$", "");
    // Under the game's /catalogo (Raspberry Pi, behind the game login, 2026-10-24): the page sends pre=/catalogo; the game
    // passes /catalogo/* to this server and /catalogo/motor/* to the Simular engine
    String pre = ("&" + q + "&").matches(".*&pre=(/|%2F|%2f)catalogo&.*") ? "/catalogo" : "";
    String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Simular</title>"
      + "<body style='margin:0;background:#10141B;color:#E9E3D5;font:16px system-ui;display:grid;place-items:center;height:100vh'><div style='text-align:center'>"
      + (err != null ? "<p>" + err + "</p>" : "<p style='font-size:20px'>Preparando la partida…</p><p id=t style='color:#929BAB'>La primera vez tarda un poco en cargar las cartas.</p>"
      + "<script>const q=" + jsStr(q) + ",h=" + jsStr(host) + ",pre=" + jsStr(pre) + ";(async function w(){try{const r=await fetch(pre+'/simular-listo?m=" + m + "',{cache:'no-store'});const p=+(await r.text());if(p>0){location.replace(pre?pre+'/motor/sim.html?'+q:'http://'+h+':'+p+'/sim.html?'+q);return}if(p==-2){document.querySelector('p').textContent='Tu MTG Local todavía no trae la simulación.';document.getElementById('t').textContent='Llega con el próximo paquete de la app. Cuando lo instales, Simular funcionará.';return}if(p<0){document.getElementById('t').textContent='El juego no pudo arrancar. Mira catalogo\\servidor\\motor" + (m == 0 ? "" : String.valueOf(m + 1)) + ".log.';return}}catch(e){}setTimeout(w,1000)})();</script>")
      + "</div></body>";
    byte[] b = html.getBytes("UTF-8");
    x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8"); x.getResponseHeaders().set("Cache-Control", "no-store");
    x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
  }
  static void listo(HttpExchange x) throws IOException {
    int m = 0; try { m = Math.max(0, Math.min(Auto.MAX - 1, Integer.parseInt(Auto.query(x).getOrDefault("m", "0")))); } catch (NumberFormatException e) { /* motor 1 */ }
    Auto.Lane l = Auto.lanes[m];
    int code = l.simCode();
    int p = code == 200 ? l.port : code == 404 ? -2 : (l.motor != null && !l.motor.isAlive() && !up(l.port)) ? -1 : 0;
    x.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    send(x, 200, String.valueOf(p));
  }
  // ---- Captures and recordings made from the catalog page itself (screen capture in the browser) ----
  static boolean cors(HttpExchange x) throws IOException {
    Headers h = x.getResponseHeaders();
    h.set("Access-Control-Allow-Origin", "*"); h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    h.set("Access-Control-Allow-Headers", "Content-Type"); h.set("Access-Control-Allow-Private-Network", "true");
    if ("OPTIONS".equals(x.getRequestMethod())) { x.sendResponseHeaders(204, -1); x.close(); return true; }
    return false;
  }
  // POST /guardar?f=img/<id>.jpg or grab/<a|k>-<id>/<role>.webm
  static void guardar(HttpExchange x) throws IOException {
    if (cors(x)) return;
    String q = x.getRequestURI().getRawQuery(), f = null;
    if (q != null) for (String kv : q.split("&")) if (kv.startsWith("f=")) f = URLDecoder.decode(kv.substring(2), "UTF-8");
    if (!"POST".equals(x.getRequestMethod()) || f == null || !f.matches("img/[a-z0-9-]+\\.jpg|grab/[ak]-[a-z0-9_-]+/(mesa|causa|tu|otro)\\.webm")) { send(x, 400, "no"); return; }
    Path out = root.resolve(f).normalize();
    if (!out.startsWith(root)) { send(x, 403, "no"); return; }
    // Windows can hold a just-written file (antivirus, a video still playing it): retry instead of dropping the connection
    try {
      Files.createDirectories(out.getParent());
      Path tmp = out.resolveSibling(out.getFileName() + "." + System.nanoTime() + ".part");
      try (InputStream in = x.getRequestBody()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
      IOException last = null;
      for (int i = 0; i < 20; i++) {
        try { Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING); last = null; break; }
        catch (IOException e) { last = e; try { Thread.sleep(500); } catch (InterruptedException ie) { break; } }
      }
      if (last != null) { try { Files.copy(tmp, out, StandardCopyOption.REPLACE_EXISTING); Files.deleteIfExists(tmp); last = null; } catch (IOException e) { last = e; } }
      if (last != null) throw last;
      System.out.println("Guardado " + f);
      Version.marcar(out);
      Espejo.avisar();
      send(x, 200, "ok");
    } catch (Throwable e) {
      System.out.println("No se pudo guardar " + f + ": " + e);
      try { send(x, 500, "No se pudo guardar " + f + ": " + e); } catch (IOException ignored) { }
    }
  }
  // GET /grabados -> {"a-lanzar":["mesa","tu"],...}
  static void grabados(HttpExchange x) throws IOException {
    if (cors(x)) return;
    StringBuilder b = new StringBuilder("{"), ver = new StringBuilder();
    Path g = root.resolve("grab");
    if (Files.isDirectory(g)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(g)) {
      for (Path d : ds) { if (!Files.isDirectory(d)) continue; StringBuilder r = new StringBuilder();
        for (String role : new String[] { "mesa", "causa", "tu", "otro" }) if (Files.exists(d.resolve(role + ".webm"))) r.append(r.length() > 0 ? "," : "").append('"').append(role).append('"');
        if (r.length() > 0) { b.append(b.length() > 1 ? "," : "").append('"').append(d.getFileName()).append("\":[").append(r).append(']');
          long t = 0; for (String role : new String[] { "mesa", "causa", "tu", "otro" }) try { t = Math.max(t, Files.getLastModifiedTime(d.resolve(role + ".webm")).toMillis()); } catch (IOException e) { /* not there */ }
          ver.append(ver.length() > 0 ? "," : "").append('"').append(d.getFileName()).append("\":").append(t); } }
    }
    // "_v": when each scene's videos were last saved, so the page asks for a new file only when it changed
    b.append(b.length() > 1 ? "," : "").append("\"_v\":{").append(ver).append('}');
    // "_img": when each window capture (img/<id>.jpg) was last saved, so the page can mark the ones from an older game version
    StringBuilder im = new StringBuilder(); Path ig = root.resolve("img");
    if (Files.isDirectory(ig)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(ig, "*.jpg")) {
      for (Path f : ds) { String n = f.getFileName().toString(); try { im.append(im.length() > 0 ? "," : "").append(jsStr(n.substring(0, n.length() - 4))).append(':').append(Files.getLastModifiedTime(f).toMillis()); } catch (IOException e) { /* gone */ } } }
    b.append(",\"_img\":{").append(im).append('}');
    // "_ver" / "_imgver": the game version each scene / capture was recorded with (Luis 2026-10-25 11:48: the Raspberry Pi
    // keeps its own copy when it is from a newer version)
    StringBuilder gv = new StringBuilder(), iv = new StringBuilder();
    if (Files.isDirectory(g)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(g)) {
      for (Path d : ds) { if (!Files.isDirectory(d)) continue; String v = Version.de(d.resolve("mesa.webm")); if (v.isEmpty()) for (String role : new String[] { "causa", "tu", "otro" }) if (v.isEmpty()) v = Version.de(d.resolve(role + ".webm"));
        if (!v.isEmpty()) gv.append(gv.length() > 0 ? "," : "").append(jsStr(d.getFileName().toString())).append(':').append(jsStr(v)); } }
    if (Files.isDirectory(ig)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(ig, "*.jpg")) {
      for (Path f : ds) { String n = f.getFileName().toString(), v = Version.de(f); if (!v.isEmpty()) iv.append(iv.length() > 0 ? "," : "").append(jsStr(n.substring(0, n.length() - 4))).append(':').append(jsStr(v)); } }
    b.append(",\"_ver\":{").append(gv).append("},\"_imgver\":{").append(iv).append('}');
    x.getResponseHeaders().set("Content-Type", "application/json"); x.getResponseHeaders().set("Cache-Control", "no-store");
    send(x, 200, b.append('}').toString());
  }
  // ---- Espejo (Luis 2026-10-25 11:35): what is recorded or captured on the PC also ends up on the Raspberry Pi, over the
  // home network. Pull only, so nothing new accepts files from the network: after a save the PC just pings the Pi
  // (GET /espejo-aviso?p=<its port>, no data); the Pi then reads the PC's GET /grabados and fetches the new or newer
  // grab/<a|k>-<id>/<rol>.webm and img/<id>.jpg with the PC's normal GET, written to a temp file and renamed into place.
  // It also looks every 2 minutes. The Pi never deletes: clips a newer take of the PC no longer has go to grab-anterior/.
  //   PC (Windows): pings servidor/espejo.txt (one line: http://<pi>:8090, or «no»), else http://mtg-pi:8090.
  //   Pi (Linux): pulls from servidor/espejo-desde.txt if present (http://<pc>:8090), else from the address its last ping
  //   came from (home network or Tailscale only), remembered in servidor/espejo-pc.txt. Skips while a game is running.
  static class Espejo {
    static final List<String> ROLES = List.of("mesa", "causa", "tu", "otro");
    static final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(3)).build();
    static ThreadFactory hilo(String n) { return (r) -> { Thread t = new Thread(r, n); t.setDaemon(true); return t; }; }
    static final ScheduledExecutorService avisos = Executors.newSingleThreadScheduledExecutor(hilo("espejo-aviso"));
    static final ExecutorService traer = Executors.newSingleThreadExecutor(hilo("espejo"));
    static final AtomicBoolean pendiente = new AtomicBoolean(), otra = new AtomicBoolean();
    static volatile String origen;

    /** First line of servidor/<file> (or the environment variable): an http address, or null for none or «no». */
    static String linea(String env, String file) {
      String v = env == null ? null : System.getenv(env);
      if (v == null) try { v = Files.readAllLines(root.resolve("servidor/" + file)).stream().map(String::trim).filter((l) -> !l.isEmpty() && !l.startsWith("#")).findFirst().orElse(null); } catch (IOException e) { /* not there */ }
      v = v == null ? null : v.trim().replaceAll("/+$", "");
      return v == null || v.isEmpty() || v.equalsIgnoreCase("no") ? null : v.matches("https?://[A-Za-z0-9.:\\[\\]-]+") ? v : null;
    }
    static boolean win() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    static void iniciar() {
      if (recibe()) avisos.scheduleWithFixedDelay(Espejo::tirar, 20, 120, TimeUnit.SECONDS);
    }

    // ---- PC side: a harmless ping a few seconds after the last save (a «Simular todo» saves many: one ping for a burst)
    static String destino() {
      if (System.getenv("CATALOGO_ESPEJO") != null) return linea("CATALOGO_ESPEJO", "-");
      if (!win()) return null; // the Pi gets servidor/ from the PC through GitHub (espejo.txt too): it never pings itself
      return Files.exists(root.resolve("servidor/espejo.txt")) ? linea(null, "espejo.txt") : "http://mtg-pi:8090";
    }
    static void avisar() {
      String d = destino();
      if (d == null || !pendiente.compareAndSet(false, true)) return;
      avisos.schedule(() -> {
        pendiente.set(false);
        // the port the Pi can reach us on: the network door (8090) in front of this server (8088)
        int p = port == 8088 ? 8090 : port;
        try { p = Integer.parseInt(System.getenv().getOrDefault("CATALOGO_PUERTA", String.valueOf(p))); } catch (NumberFormatException e) { /* keep */ }
        try { http.send(HttpRequest.newBuilder(URI.create(d + "/espejo-aviso?p=" + p)).timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.discarding()); }
        catch (Exception e) { /* the Pi is off or away: it catches up on its own next look */ }
      }, 3, TimeUnit.SECONDS);
    }

    // ---- Pi side
    static boolean recibe() { return !win() || linea("CATALOGO_ESPEJO_DESDE", "espejo-desde.txt") != null; }
    static String pc() {
      String f = linea("CATALOGO_ESPEJO_DESDE", "espejo-desde.txt");
      if (f != null) return f;
      if (origen == null) origen = linea(null, "espejo-pc.txt");
      return origen;
    }
    static boolean casa(String ip) {
      if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
      try { InetAddress a = InetAddress.getByName(ip); byte[] b = a.getAddress();
        if (a.isLoopbackAddress()) return "1".equals(System.getenv("CATALOGO_ESPEJO_PRUEBA")); // only for testing on one machine
        return a.isSiteLocalAddress() || ((b[0] & 255) == 100 && (b[1] & 0xC0) == 64); // 10/8, 172.16/12, 192.168/16, Tailscale 100.64/10
      } catch (IOException e) { return false; }
    }
    static void aviso(HttpExchange x) throws IOException {
      if (!recibe()) { send(x, 404, "no"); return; }
      Headers rq = x.getRequestHeaders();
      // only straight from the home network or Tailscale: never through Cloudflare, Tailscale Funnel or another proxy
      for (String h : new String[] { "Cf-Connecting-Ip", "Cf-Ray", "X-Forwarded-For", "X-Real-Ip", "Forwarded", "Tailscale-Funnel-Request" })
        if (rq.containsKey(h)) { send(x, 403, "no"); return; }
      InetAddress ra = x.getRemoteAddress().getAddress();
      String ip = ra.getHostAddress(), fwd = rq.getFirst("X-Espejo-Origen");
      if (ra.isLoopbackAddress() && fwd != null) ip = fwd.trim(); // set by sin_motor_proxy.py (the 8090 door) to who called it
      int p = 8090;
      try { p = Integer.parseInt(Auto.query(x).getOrDefault("p", "8090")); } catch (NumberFormatException e) { /* 8090 */ }
      if (linea("CATALOGO_ESPEJO_DESDE", "espejo-desde.txt") == null && casa(ip) && p > 0 && p < 65536) {
        String o = "http://" + ip + ":" + p;
        if (!o.equals(pc())) { origen = o; try { Files.writeString(root.resolve("servidor/espejo-pc.txt"), o + "\n"); } catch (IOException e) { /* kept in memory */ } System.out.println("Espejo: el PC esta en " + o); }
      }
      tirar();
      send(x, 200, "ok");
    }
    static void tirar() { otra.set(true); traer.execute(() -> { if (otra.getAndSet(false)) try { ronda(); } catch (Throwable e) { System.out.println("Espejo: " + e); } }); }

    /** A game (or Estadísticas) running on this Pi: the game says so at /partida-en-curso, asked by the address in config.env. */
    static boolean partida() {
      if (win()) return false;
      String host = System.getenv("PARTIDA_HOST"), pt = System.getenv("PARTIDA_PORT");
      try { for (String l : Files.readAllLines(Paths.get("/opt/mtglocal/config.env"))) {
        if (host == null && l.startsWith("IP=")) host = l.substring(3).trim().replace("\"", "");
        if (pt == null && l.startsWith("PUERTO=")) pt = l.substring(7).trim().replace("\"", ""); } } catch (IOException e) { /* not a Pi */ }
      try {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://" + (host == null || host.isEmpty() ? "127.0.0.1" : host) + ":" + (pt == null || pt.isEmpty() ? "8080" : pt) + "/partida-en-curso")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 && r.body().trim().equals("1");
      } catch (Exception e) { return false; }
    }
    static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
    static long mtime(Path f) { try { return Files.getLastModifiedTime(f).toMillis(); } catch (IOException e) { return 0; } }
    static boolean bajar(String url, Path tmp, long t) {
      try {
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200 || Files.size(tmp) == 0) { Files.deleteIfExists(tmp); return false; }
        Files.setLastModifiedTime(tmp, java.nio.file.attribute.FileTime.fromMillis(t)); // the PC's time: the same file is not fetched again
        return true;
      } catch (Exception e) { try { Files.deleteIfExists(tmp); } catch (IOException ignored) { } return false; }
    }
    static void poner(Path tmp, Path out) throws IOException {
      try { Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
      catch (AtomicMoveNotSupportedException e) { Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING); }
    }
    static void ronda() throws Exception {
      String pc = pc();
      if (pc == null || partida()) return;
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(pc + "/grabados")).timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 200) return;
      Map<String, Object> m = Auto.Json.obj(Auto.Json.parse(r.body())), v = Auto.Json.obj(m.get("_v")), im = Auto.Json.obj(m.get("_img")),
          gv = Auto.Json.obj(m.get("_ver")), iv = Auto.Json.obj(m.get("_imgver"));
      int n = 0;
      for (Map.Entry<String, Object> e : m.entrySet()) {
        String g = e.getKey(); long t = num(v.get(g));
        if (!g.matches("[ak]-[A-Za-z0-9_-]+") || t <= 0) continue;
        List<String> rs = new ArrayList<>(); for (Object o : Auto.Json.arr(e.getValue())) if (ROLES.contains(o)) rs.add((String) o);
        Path d = root.resolve("grab").resolve(g);
        long mine = 0; for (String role : ROLES) mine = Math.max(mine, mtime(d.resolve(role + ".webm")));
        // the game version decides first (Luis 2026-10-25 11:48): a take from a newer version on the Pi stays; one from an
        // older version is replaced; with the same version the newer take wins
        String pcv = gv.get(g) instanceof String s0 ? s0 : "", miv = "";
        for (String role : ROLES) if (miv.isEmpty()) miv = Version.de(d.resolve(role + ".webm"));
        int cmp = Integer.compare(Version.num(pcv), Version.num(miv));
        if (rs.isEmpty() || cmp < 0 || (cmp == 0 && mine >= t)) continue;
        if (partida()) return;
        // all the take's clips or none: a half-fetched take is tried again next time
        Files.createDirectories(d);
        Map<String, Path> got = new LinkedHashMap<>();
        for (String role : rs) { Path tmp = d.resolve(role + ".webm.espejo.part"); if (!bajar(pc + "/grab/" + g + "/" + role + ".webm", tmp, t)) break; got.put(role, tmp); }
        if (got.size() < rs.size()) { for (Path tmp : got.values()) Files.deleteIfExists(tmp); System.out.println("Espejo: no se pudo traer grab/" + g); continue; }
        for (Map.Entry<String, Path> gp : got.entrySet()) { poner(gp.getValue(), d.resolve(gp.getKey() + ".webm")); Version.poner(d.resolve(gp.getKey() + ".webm"), pcv); }
        // only the newest take stays visible (like on the PC); older clips of roles it does not have are kept aside
        for (String role : ROLES) { Path f = d.resolve(role + ".webm"); if (!rs.contains(role) && Files.exists(f)) { Path a = root.resolve("grab-anterior").resolve(g); Files.createDirectories(a); poner(f, a.resolve(role + ".webm")); } }
        System.out.println("Espejo: traido grab/" + g + " " + rs); n++;
      }
      for (Map.Entry<String, Object> e : im.entrySet()) {
        String k = e.getKey(); long t = num(e.getValue());
        if (!k.matches("[A-Za-z0-9_-]+") || t <= 0) continue;
        Path out = root.resolve("img").resolve(k + ".jpg");
        String pcv = iv.get(k) instanceof String s1 ? s1 : "";
        int cmp = Integer.compare(Version.num(pcv), Version.num(Version.de(out)));
        if (cmp < 0 || (cmp == 0 && mtime(out) >= t)) continue;
        if (partida()) return;
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(k + ".jpg.espejo.part");
        if (bajar(pc + "/img/" + k + ".jpg", tmp, t)) { poner(tmp, out); Version.poner(out, pcv); System.out.println("Espejo: traido img/" + k + ".jpg"); n++; }
      }
      if (n > 0) System.out.println("Espejo: " + n + " cosas nuevas desde " + pc);
    }
  }

  // ---- Version: which game version each recording or capture was made with (Luis 2026-10-25 11:48). Kept next to the
  // file as <file>.ver («p171»); a file without one counts as the installed version if it is newer than the install,
  // else as unknown (0).
  static class Version {
    static Path ver(Path f) { return f.resolveSibling(f.getFileName() + ".ver"); }
    static Path txt() { return Auto.juegoDir().resolve("VERSION.txt"); }
    /** The installed game's version («p171»), or "" when unknown. */
    static String instalada() {
      try { String l = Files.readAllLines(txt(), java.nio.charset.StandardCharsets.UTF_8).get(0).replace("\uFEFF", "").trim(); String v = l.split(" ")[0]; return v.matches("p\\d+") ? v : ""; }
      catch (Exception e) { return ""; }
    }
    static int num(String v) { return v != null && v.matches("p\\d{1,6}") ? Integer.parseInt(v.substring(1)) : 0; }
    static String de(Path f) {
      if (!Files.exists(f)) return "";
      try { String v = Files.readString(ver(f)).trim(); if (v.matches("p\\d{1,6}")) return v; } catch (IOException e) { /* none */ }
      try { if (Files.getLastModifiedTime(f).toMillis() >= Files.getLastModifiedTime(txt()).toMillis()) return instalada(); } catch (IOException e) { /* no game */ }
      return "";
    }
    static void poner(Path f, String v) { try { if (num(v) > 0) Files.writeString(ver(f), v); else Files.deleteIfExists(ver(f)); } catch (IOException e) { /* best effort */ } }
    static void marcar(Path f) { poner(f, instalada()); }
  }

  static String jsStr(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""; }

  static void send(HttpExchange x, int code, String msg) throws IOException {
    byte[] b = msg.getBytes("UTF-8"); x.sendResponseHeaders(code, b.length); x.getResponseBody().write(b); x.close();
  }
}
