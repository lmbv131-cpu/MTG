import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

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
    Runtime.getRuntime().addShutdownHook(new Thread(Auto::stop));
    s.setExecutor(Executors.newFixedThreadPool(16));
    s.start();
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
    x.getResponseHeaders().set("Content-Type", "application/json"); x.getResponseHeaders().set("Cache-Control", "no-store");
    send(x, 200, b.append('}').toString());
  }
  static String jsStr(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""; }

  static void send(HttpExchange x, int code, String msg) throws IOException {
    byte[] b = msg.getBytes("UTF-8"); x.sendResponseHeaders(code, b.length); x.getResponseBody().write(b); x.close();
  }
}
