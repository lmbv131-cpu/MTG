import com.sun.net.httpserver.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// "Tomar captura" and "Grabar" without Luis doing anything (Luis 04:57): the catalog server plays the scene in the
// game's sim.html inside a hidden browser (Chrome, Brave or Edge, driven over the DevTools protocol) and saves the
// picture (img/<ventana>.jpg) or one video per screen (grab/<a|k>-<id>/<rol>.webm) itself.
// Luis 19/10 06:52: «Simular todo» plays several scenes at once. Each "motor" is a hidden game (ports 8091-8094) with its
// own hidden browser; up to 4 when he is not playing, only 1 at idle priority while he plays (like the game tests).
public class Auto {
  static final int MAX = 4;
  static final Lane[] lanes = new Lane[MAX];
  static { for (int i = 0; i < MAX; i++) lanes[i] = new Lane(i); }
  static final ExecutorService worker = Executors.newCachedThreadPool();
  static final Map<Integer, Job> jobs = new ConcurrentHashMap<>();
  static final AtomicInteger seq = new AtomicInteger();

  static class Job {
    final int id; final Map<String, String> q; volatile String estado = "En cola…", fase = "preparando"; volatile boolean fin, ok, saving; volatile Lane lane; final List<String> hechos = new CopyOnWriteArrayList<>(), pasos = new CopyOnWriteArrayList<>();
    Job(int id, Map<String, String> q) { this.id = id; this.q = q; }
    String json() { return "{\"id\":" + id + ",\"fase\":" + Json.str(fase) + ",\"estado\":" + Json.str(estado) + ",\"fin\":" + fin + ",\"ok\":" + ok + ",\"motor\":" + (lane == null ? 0 : lane.n + 1) + ",\"hechos\":" + Json.str(hechos) + ",\"pasos\":" + Json.str(pasos) + "}"; }
  }

  // ---- HTTP: /auto?tipo=captura&ventana=k&ver=Luis  |  /auto?tipo=grabar&clave=a:lanzar&escena=SC.swords&ver=mesa,Luis
  static void auto(HttpExchange x) throws IOException {
    if (CatalogoServer.cors(x)) return;
    Map<String, String> q = query(x);
    String tipo = q.getOrDefault("tipo", "");
    if (!tipo.equals("preparar") && !(tipo.equals("captura") && q.getOrDefault("ventana", "").matches("[a-z0-9-]+")) && !((tipo.equals("grabar") || tipo.equals("probar")) && q.getOrDefault("clave", "").matches("[ak]:[a-z0-9_ '-]+"))) { reply(x, 400, "{\"error\":\"no\"}"); return; }
    // preparar: boot the hidden game ahead (the Simular dialog asks for it), so the play starts at once
    if (tipo.equals("preparar") && (jobs.values().stream().anyMatch((y) -> !y.fin) || lanes[0].ready())) { reply(x, 200, "{\"listo\":true}"); return; }
    Job j = new Job(seq.incrementAndGet(), q); jobs.put(j.id, j);
    worker.submit(() -> run(j));
    reply(x, 200, j.json());
  }
  // ---- «Simular todo» kept by the server (Luis 2026-10-24 23:17, Pi: the tablet slept and the page that fed the scenes
  // stopped at 01:17). The page sends the whole list once; any page can read the progress or stop it.
  //   POST /auto-todo {"t":"a","items":[{"clave":"a:x","escena":"SC.a_x","ver":"mesa,Luis","viejo":"..","papeles":"..","titulo":".."}]}
  //   GET  /auto-todo → progress   |   GET /auto-todo?parar=1 → stop after the scenes in progress
  static class Todo {
    final String t; final List<Map<String, String>> items; final int n, max; final long desde = System.currentTimeMillis();
    final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger(), bad = new AtomicInteger(), running = new AtomicInteger(), seguidos = new AtomicInteger();
    final List<String> fails = new CopyOnWriteArrayList<>(); final Map<String, Job> now = new ConcurrentHashMap<>();
    volatile boolean stop, activo = true; volatile long fin; volatile String motivo = "";
    // window captures («Tomar todas las capturas», t = "w") go one at a time, as the page always did them
    Todo(String t, List<Map<String, String>> items) { this.t = t; this.items = items; n = items.size(); max = t.equals("w") ? 1 : 2; }
    String json() {
      List<Object> ahora = new ArrayList<>();
      for (Map.Entry<String, Job> e : now.entrySet()) { Job j = e.getValue(); Map<String, Object> m = new LinkedHashMap<>(); m.put("titulo", e.getKey()); m.put("motor", j.lane == null ? 0 : j.lane.n + 1); m.put("guardando", j.saving); m.put("fase", j.fase); ahora.add(m); }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("activo", activo); m.put("t", t); m.put("n", n); m.put("done", done.get()); m.put("bad", bad.get()); m.put("desde", desde); m.put("fin", fin);
      m.put("parando", stop && activo); m.put("motivo", motivo); m.put("aLaVez", Math.min(max, obj)); m.put("jugando", jugando); m.put("ahora", ahora); m.put("fallos", fails);
      return Json.str(m);
    }
  }
  static volatile Todo todo;
  static void todo(HttpExchange x) throws IOException {
    if (CatalogoServer.cors(x)) return;
    Todo cur = todo;
    if ("POST".equals(x.getRequestMethod())) {
      if (cur != null && cur.activo) { reply(x, 409, cur.json()); return; }
      Map<String, Object> b; try (InputStream in = x.getRequestBody()) { b = Json.obj(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))); } catch (Exception e) { reply(x, 400, "{\"error\":\"lista ilegible\"}"); return; }
      List<Map<String, String>> items = new ArrayList<>();
      for (Object o : Json.arr(b.get("items"))) {
        Map<String, Object> it = Json.obj(o); Map<String, String> q = new HashMap<>(); q.put("tipo", it.get("ventana") != null ? "captura" : "grabar");
        for (String k : new String[] { "clave", "escena", "ver", "viejo", "papeles", "titulo", "ventana" }) if (it.get(k) != null) q.put(k, String.valueOf(it.get(k)));
        if (q.get("tipo").equals("captura") ? q.get("ventana").matches("[a-z0-9-]+") : q.getOrDefault("clave", "").matches("[ak]:[a-z0-9_ '-]+")) items.add(q);
      }
      if (items.isEmpty()) { reply(x, 400, "{\"error\":\"lista vacía\"}"); return; }
      Todo nt = new Todo(String.valueOf(b.getOrDefault("t", "a")), items); todo = nt;
      worker.submit(() -> todoRun(nt));
      reply(x, 200, nt.json()); return;
    }
    if (cur != null && "1".equals(query(x).get("parar"))) { cur.stop = true; if (cur.motivo.isEmpty()) cur.motivo = "Detenido"; }
    reply(x, 200, cur == null ? "{\"activo\":false}" : cur.json());
  }
  static void todoRun(Todo td) {
    System.out.println("Simular todo: " + td.n + " escenas");
    try {
      while (!td.stop && td.next.get() < td.n) {
        int target = Math.min(td.max, objetivo());
        if (td.running.get() < target) { td.running.incrementAndGet(); worker.submit(() -> todoLane(td)); }
        Thread.sleep(1000);
      }
      while (td.running.get() > 0) Thread.sleep(1000);
    } catch (InterruptedException e) { /* closing */ }
    td.activo = false; td.fin = System.currentTimeMillis();
    System.out.println("Simular todo: " + td.done.get() + "/" + td.n + (td.bad.get() > 0 ? ", " + td.bad.get() + " no salieron" : "") + (td.motivo.isEmpty() ? "" : " (" + td.motivo + ")"));
  }
  static void todoLane(Todo td) {
    try {
      while (!td.stop) {
        int i = td.next.getAndIncrement(); if (i >= td.n) break;
        Map<String, String> q = new HashMap<>(td.items.get(i)); String title = q.getOrDefault("titulo", q.getOrDefault("clave", q.get("ventana"))); q.remove("titulo");
        Job j = new Job(seq.incrementAndGet(), q); jobs.put(j.id, j); td.now.put(title, j);
        run(j);
        while (!j.fin) Thread.sleep(500); // its videos are still being saved
        td.now.remove(title); td.done.incrementAndGet();
        if (j.ok) td.seguidos.set(0);
        else {
          td.bad.incrementAndGet(); td.fails.add(title + ": " + j.estado);
          // A game started (the door blocks the motors) or the motors broke: don't burn the whole list on failures
          if (td.seguidos.incrementAndGet() >= 3) { td.stop = true; td.motivo = "Parado tras 3 fallos seguidos: " + j.estado; }
        }
        if (td.running.get() > Math.min(td.max, objetivo())) break;
      }
    } catch (InterruptedException e) { /* closing */ } finally { td.running.decrementAndGet(); }
  }
  // ---- HTTP: /auto-vivo?id=<job>&p=mesa → the latest picture of that screen (servidor/vivo.html shows them while a job plays)
  static void vivo(HttpExchange x) throws IOException {
    if (CatalogoServer.cors(x)) return;
    Map<String, String> q = query(x);
    String p = q.getOrDefault("p", "mesa"); byte[] img = null;
    Job j = null; try { j = jobs.get(Integer.parseInt(q.getOrDefault("id", "0"))); } catch (NumberFormatException e) { /* none */ }
    Lane l = j != null && j.lane != null ? j.lane : lanes[0];
    Long at = l.lastAt.get(p);
    if (at != null && System.currentTimeMillis() - at < 1500) img = l.lastFrame.get(p); // filming: reuse the film's own frame
    else if (l.cdp != null && p.matches("[A-Za-z]+") && (l.screens.containsKey(p) || l.findScreen(p))) {
      // No clip scale (Luis 25/10): a scaled screenshot right before filming left the Pi's screencast pictures at 55-60 %
      // of their size, so the videos came out small and pixelated. A plain screenshot, a bit lower quality instead
      try { img = Base64.getDecoder().decode((String) l.cdp.call("Page.captureScreenshot", Map.of("format", "jpeg", "quality", 45), l.screens.get(p)).get("data")); }
      catch (Exception e) { img = null; }
    }
    if (img == null) { CatalogoServer.send(x, 204, ""); return; }
    x.getResponseHeaders().set("Content-Type", "image/jpeg"); x.getResponseHeaders().set("Cache-Control", "no-store");
    x.sendResponseHeaders(200, img.length); try (OutputStream o = x.getResponseBody()) { o.write(img); }
  }
  static void estado(HttpExchange x) throws IOException {
    if (CatalogoServer.cors(x)) return;
    Job j = null; try { j = jobs.get(Integer.parseInt(query(x).getOrDefault("id", "0"))); } catch (NumberFormatException e) { /* none */ }
    reply(x, j == null ? 404 : 200, j == null ? "{}" : j.json());
  }
  // ---- HTTP: /auto-motores → how many scenes the PC plays at once now (the page keeps that many busy)
  static void motores(HttpExchange x) throws IOException {
    if (CatalogoServer.cors(x)) return;
    int n = objetivo();
    reply(x, 200, "{\"motores\":" + n + ",\"jugando\":" + jugando + ",\"max\":" + MAX + "}");
  }
  static Map<String, String> query(HttpExchange x) throws IOException {
    Map<String, String> m = new HashMap<>(); String r = x.getRequestURI().getRawQuery();
    if (r != null) for (String kv : r.split("&")) { int i = kv.indexOf('='); if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8")); }
    return m;
  }
  static void reply(HttpExchange x, int code, String body) throws IOException {
    x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); x.getResponseHeaders().set("Cache-Control", "no-store");
    CatalogoServer.send(x, code, body);
  }

  // ---- how many motors: 1 while Luis plays (his game, MTG Commander or the AI training), else min(4, free RAM / 4 GB, cores / 4)
  static volatile boolean jugando; static volatile int obj = 1; static volatile long objAt; static final Object OBJ = new Object();
  static int objetivo() {
    synchronized (OBJ) {
      if (System.currentTimeMillis() - objAt < 15000) return obj;
      boolean was = jugando;
      jugando = estaJugando();
      int n = 1;
      if (!jugando) {
        double gb = 0;
        try { gb = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize() / 1e9; } catch (Throwable e) { gb = 8; }
        for (Lane l : lanes) if (l.motor != null && l.motor.isAlive()) gb += 3.5; // what our own motors use is theirs to keep
        n = (int) Math.max(1, Math.min(MAX, Math.min(Math.floor(gb / 4), Runtime.getRuntime().availableProcessors() / 4)));
      }
      obj = n; objAt = System.currentTimeMillis();
      if (was != jugando || objAt == 0) for (Lane l : lanes) l.prioridad();
      return n;
    }
  }
  static boolean estaJugando() {
    Path juego = juegoDir();
    if (CatalogoServer.up(8080)) {
      long since = System.currentTimeMillis() - 15 * 60000;
      try (var s = Files.list(juego.resolve("partidas"))) { if (s.anyMatch((f) -> f.toFile().lastModified() > since)) return true; } catch (IOException e) { /* no games folder */ }
    }
    for (ProcessHandle p : ProcessHandle.allProcesses().toList()) {
      String c = p.info().command().orElse("").toLowerCase(Locale.ROOT);
      if (c.endsWith("mtg commander.exe") || c.endsWith("commander-base.exe")) return true;
    }
    if (!win()) return false;
    // the AI training (ENTRENAR-RED): only the command line tells it apart
    String out = ps("@(Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -match 'entrenar|entrenamiento|ENTRENAR-RED|pruebas\\\\red' }).Count");
    return out != null && out.trim().matches("[1-9][0-9]*");
  }
  /** The game folder next to the catalog: Juego on the PC, juego on the Raspberry Pi (Linux tells them apart), MTGLocal before p74. */
  static Path juegoDir() {
    for (String d : new String[] { "../Juego", "../juego", "../MTGLocal" }) { Path p = CatalogoServer.root.resolve(d).normalize(); if (Files.isDirectory(p)) return p; }
    return CatalogoServer.root.resolve("../Juego").normalize();
  }
  static long pausaCaptura() { try { return Long.parseLong(System.getenv().getOrDefault("CATALOGO_PAUSA_CAPTURA_MS", "0").trim()); } catch (NumberFormatException e) { return 0; } }
  static boolean win() { return System.getProperty("os.name").toLowerCase().contains("win"); }
  static String ps(String cmd) {
    try { Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd).redirectErrorStream(true).start();
      p.getOutputStream().close(); String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8); p.waitFor(20, TimeUnit.SECONDS); return s; }
    catch (Exception e) { return null; }
  }

  // ---- a free motor for each job: one that already has a game ready first
  static final Object LANES = new Object();
  static Lane acquire(Job j) throws InterruptedException {
    for (;;) {
      int t = objetivo();
      synchronized (LANES) {
        Lane best = null;
        for (int i = 0; i < t; i++) { Lane l = lanes[i]; if (l.busy || l.aMano()) continue; if (best == null || (l.ready() && !best.ready())) best = l; }
        if (best != null) { best.busy = true; return best; }
        j.estado = "Esperando un motor libre…";
        LANES.wait(3000);
      }
    }
  }
  static void release(Lane l) {
    // a motor beyond the number allowed now (Luis started a game): closed once its videos are saved, to free the PC
    if (l.n >= objetivo()) { worker.submit(() -> { l.waitSaves(); if (l.n >= objetivo()) l.cerrar(true); synchronized (LANES) { l.busy = false; LANES.notifyAll(); } }); return; }
    synchronized (LANES) { l.busy = false; LANES.notifyAll(); }
  }

  // ---- Simular opened by hand: a motor of its own (Mejoras/Luis 06:52: a second Simular used to replace the first one).
  // First a motor with nothing on it; else one whose «Simular todo» game is idle (that game is started again later).
  static Lane manual() {
    int t = objetivo();
    synchronized (LANES) {
      if (lanes[0].simTabs() < 0) { lanes[0].dirty = true; return lanes[0]; } // older game: it can't say which tabs it has, so motor 1 as before
      Lane pick = null;
      for (int i = 0; i < t && pick == null; i++) { Lane l = lanes[i]; if (!l.busy && !l.aMano() && l.dirSession == null) pick = l; }
      for (int i = 0; i < t && pick == null; i++) { Lane l = lanes[i]; if (!l.busy && l.saves.get() == 0 && !l.aMano()) pick = l; }
      if (pick == null) return null;
      if (pick.dirSession != null) { Lane p = pick; worker.submit(() -> { synchronized (LANES) { if (p.busy) return; p.busy = true; } try { p.cerrar(false); } finally { synchronized (LANES) { p.busy = false; LANES.notifyAll(); } } }); }
      pick.reservado = System.currentTimeMillis() + 90000; pick.dirty = true;
      return pick;
    }
  }
  // Every minute: motors 2-4 with no Simular tab and no «Simular todo» game for 10 minutes are closed (they use 3 GB each)
  static final ScheduledExecutorService sweep = Executors.newSingleThreadScheduledExecutor((r) -> { Thread th = new Thread(r, "barrer"); th.setDaemon(true); return th; });
  static { sweep.scheduleWithFixedDelay(Auto::barrer, 60, 60, TimeUnit.SECONDS); }
  static void barrer() {
    try {
      for (int i = 1; i < MAX; i++) {
        Lane l = lanes[i];
        if (l.motor == null || !l.motor.isAlive() || l.busy || l.dirSession != null || l.saves.get() > 0 || l.aMano()) { l.libreDesde = 0; continue; }
        if (l.libreDesde == 0) { l.libreDesde = System.currentTimeMillis(); continue; }
        if (System.currentTimeMillis() - l.libreDesde < 10 * 60000) continue;
        synchronized (LANES) { if (l.busy) continue; l.busy = true; }
        try { l.cerrar(true); } finally { l.libreDesde = 0; synchronized (LANES) { l.busy = false; LANES.notifyAll(); } }
      }
    } catch (Throwable e) { e.printStackTrace(); }
  }

  // ---- one job
  // Each motor keeps one hidden browser and one director page (sim.html with every screen open as a tab) across jobs:
  // booting a game takes a minute, so each job only plays its scene in the game that is already running.
  static final String ALL = "mesa,Luis,Pablo,Malaguita,Calero,Albertito,Elena";
  // scenes that leave the room without a game: the next job boots a new one
  static final Set<String> LEAVE = Set.of("W.sala", "W.entrar", "W.fin", "W.mesa-fin");
  // A scene where a player loses or concedes leaves that player out of the game: the next scene needs a new game
  static boolean endsGame(String e) { e = e.startsWith("SC.") ? e.substring(3) : e; return e.startsWith("a_fin-") || e.equals("a_rendirse") || e.equals("endgame"); }

  static void run(Job j) {
    Lane l = null;
    try {
      l = acquire(j); j.lane = l;
      for (int attempt = 0; ; attempt++) {
        try { l.play(j); break; }
        catch (Exception e) { l.dirty = true; if (attempt >= 1 || j.fin) throw e; j.pasos.add("Reintento: " + e.getMessage()); }
      }
      l.curRec = null;
      if (!j.saving) finish(j, null);
    } catch (Exception e) {
      if (l != null) l.curRec = null; finish(j, e);
      try (var w = Files.walk(CatalogoServer.root.resolve("servidor/tmp/" + j.id))) { w.sorted(Comparator.reverseOrder()).forEach((f) -> f.toFile().delete()); } catch (Exception ex) { /* none */ }
    } finally { if (l != null) release(l); }
  }
  // Videos are encoded while the next scene already plays (Luis 00:09: as fast as possible)
  static final ExecutorService saver = Executors.newCachedThreadPool();
  static void finish(Job j, Exception e) {
    if (e == null) { j.ok = true; j.estado = "Listo"; }
    else { j.estado = e.getMessage() == null ? e.toString() : e.getMessage(); e.printStackTrace(); }
    j.fin = true;
    System.out.println("Tarea " + j.id + (j.lane != null ? " (motor " + (j.lane.n + 1) + ")" : "") + ": " + j.estado);
    if (jobs.values().stream().allMatch((x) -> x.fin)) idle();
  }
  // Nothing queued for 20 minutes: close the hidden browsers (and the extra motors) to free the PC
  // (until then the next Simular skips the start of the game)
  static volatile long lastJob;
  static void idle() { lastJob = System.currentTimeMillis(); long me = lastJob; CompletableFuture.delayedExecutor(20, TimeUnit.MINUTES).execute(() -> { if (lastJob == me && jobs.values().stream().allMatch((x) -> x.fin)) for (Lane l : lanes) worker.submit(() -> { synchronized (LANES) { if (l.busy) return; l.busy = true; } try { l.cerrar(l.n > 0); } finally { synchronized (LANES) { l.busy = false; LANES.notifyAll(); } } }); }); }
  static void stop() { for (Lane l : lanes) { if (l.browser != null) l.browser.destroy(); if (l.motor != null) l.motor.destroy(); } }

  static class Lane {
    final int n, port, cdpPort;
    volatile boolean busy; final AtomicInteger saves = new AtomicInteger(); volatile long reservado, libreDesde;
    volatile Process motor, browser;
    boolean cacheClean; volatile Cdp cdp; String dirTarget, dirSession; volatile boolean dirty = true; volatile Rec curRec;
    final Map<String, String> screens = new ConcurrentHashMap<>(), targetScreen = new ConcurrentHashMap<>(), targetSession = new ConcurrentHashMap<>();
    final Map<String, byte[]> lastFrame = new ConcurrentHashMap<>(); final Map<String, Long> lastAt = new ConcurrentHashMap<>();
    // motor 1 keeps the ports it always had (8091, 9333); the others: 8092-8094 and 9330-9332 (game tests use 8095+ and 9335+)
    Lane(int n) { this.n = n; port = 8091 + n; cdpPort = n == 0 ? 9333 : 9329 + n; }
    String suf() { return n == 0 ? "" : String.valueOf(n + 1); }
    boolean ready() { return !dirty && dirSession != null && cdp != null && browser != null && browser.isAlive(); }
    void waitSaves() { long t = System.currentTimeMillis() + 15 * 60000; while (saves.get() > 0 && System.currentTimeMillis() < t) try { Thread.sleep(500); } catch (InterruptedException e) { return; } }

    // ---- the hidden game of this motor (--sim: no tunnel, games not kept in partidas)
    synchronized String arrancar() {
      if (CatalogoServer.up(port)) return null;
      if (motor != null && motor.isAlive()) return null;
      // p74 renames MTGLocal -> Juego and forge -> engine (flag --engine); older installs keep MTGLocal/forge/--forge
      Path root = CatalogoServer.root;
      Path dir = juegoDir();
      Path java = dir.resolve(win() ? "runtime/bin/java.exe" : "runtime/bin/java");
      if (!Files.exists(java)) java = dir.resolve("../runtime/bin/java").normalize(); // Raspberry Pi: /opt/mtglocal/runtime
      String jar = Files.exists(dir.resolve("mtglocal.jar")) ? "mtglocal.jar" : null;
      if (jar == null) try (var ls = Files.list(dir)) { jar = ls.map(f -> f.getFileName().toString()).filter(nm -> nm.toLowerCase().endsWith(".jar")).sorted().findFirst().orElse(null); } catch (IOException e) { }
      if (jar == null) return "No encuentro el juego (carpeta Juego o MTGLocal) junto a la carpeta del catálogo.";
      boolean engine = Files.isDirectory(dir.resolve("engine"));
      try {
        // Luis 19/10: tests may run while he plays, so this engine stays out of his way (4 cores, low priority)
        ProcessBuilder pb = new ProcessBuilder(Files.exists(java) ? java.toString() : "java", "-Xmx3g", "-XX:ActiveProcessorCount=4", "-jar", jar, engine ? "--engine" : "--forge", engine ? "engine" : "forge", "--port", String.valueOf(port), "--sim");
        pb.directory(dir.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("servidor/motor" + suf() + ".log").toFile());
        motor = pb.start(); prioridad();
        System.out.println("Arrancando el juego para Simular (motor " + (n + 1) + ", puerto " + port + ")...");
        return null;
      } catch (IOException e) { return "No se pudo arrancar el juego: " + e.getMessage(); }
    }
    int simCode() {
      try { HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/sim.html").openConnection();
        c.setConnectTimeout(500); c.setReadTimeout(2000); int r = c.getResponseCode(); c.disconnect(); return r; } catch (IOException e) { return 0; }
    }
    // How many Simular tabs the game has open (its /sim-tabs answer); -1 when the game can't tell (older packages)
    int simTabs() {
      if (!CatalogoServer.up(port)) return 0;
      try { HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/sim-tabs").openConnection();
        c.setConnectTimeout(500); c.setReadTimeout(1500); int r = c.getResponseCode(); if (r != 200) { c.disconnect(); return -1; }
        String s = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim(); c.disconnect(); return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
      catch (Exception e) { return -1; }
    }
    // a Simular someone opened by hand is on this motor (or about to be): «Simular todo» leaves it alone
    boolean aMano() { if (System.currentTimeMillis() < reservado) return true; if (dirSession != null) return false; return simTabs() > 0; }
    // Below normal, or idle while Luis plays, for the motor, its browser and every process they started
    void prioridad() {
      if (!win()) return;
      List<Long> ids = new ArrayList<>(); for (Process p : new Process[] { motor, browser }) if (p != null && p.isAlive()) ids.add(p.pid());
      if (ids.isEmpty()) return;
      String cls = jugando ? "Idle" : "BelowNormal", list = String.join(",", ids.stream().map(String::valueOf).toList());
      String cmd = "$ids=@(" + list + "); $all=@(Get-CimInstance Win32_Process); do { $c=$ids.Count; $ids += @($all | Where-Object { $ids -contains $_.ParentProcessId -and $ids -notcontains $_.ProcessId } | ForEach-Object { $_.ProcessId }) } while ($ids.Count -ne $c); foreach ($i in $ids) { try { (Get-Process -Id $i -ErrorAction Stop).PriorityClass = '" + cls + "' } catch {} }";
      try { new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().getOutputStream().close(); }
      catch (IOException e) { /* runs at the priority it has */ }
    }
    // close this motor's browser (and, for motors 2-4, its game too)
    void cerrar(boolean juego) {
      quitDirector(); if (cdp != null) cdp.close(); cdp = null;
      if (browser != null) browser.destroy(); browser = null; cacheClean = false; dirty = true;
      screens.clear(); targetScreen.clear(); targetSession.clear(); lastFrame.clear(); lastAt.clear();
      if (juego && motor != null) { motor.destroy(); motor = null; System.out.println("Motor " + (n + 1) + " cerrado"); }
    }
    void abrir(Job j) throws Exception {
      if (cdp == null || browser == null || !browser.isAlive()) { j.estado = "Abriendo el navegador oculto…"; screens.clear(); targetScreen.clear(); dirTarget = null; dirty = true; cacheClean = false; cdp = Cdp.open(navegador()); listen(cdp); }
    }

    void play(Job j) throws Exception {
      j.estado = "Arrancando el juego…";
      String err = arrancar(); if (err != null) throw new Exception(err);
      long t0 = System.currentTimeMillis();
      for (;;) { int c = simCode(); if (c == 200) break; if (c == 404) { j.fin = true; throw new Exception("Tu MTG Local todavía no trae la simulación. Llega con el próximo paquete."); } if (System.currentTimeMillis() - t0 > 360000) throw new Exception("El juego no arrancó. Mira catalogo\\servidor\\motor" + suf() + ".log."); Thread.sleep(1000); }
      if (j.q.get("tipo").equals("preparar")) {
        abrir(j);
        if (dirty || dirSession == null) director(j);
        j.hechos.add("ok"); return;
      }
      boolean grabar = j.q.get("tipo").equals("grabar");
      boolean probar = j.q.get("tipo").equals("probar"); // runs the scene without filming it (to check that it works)
      String escena = grabar || probar ? j.q.getOrDefault("escena", "") : "W." + j.q.get("ventana");
      boolean[] began = { false };
      abrir(j);
      if (dirty || dirSession == null) { System.out.println("Motor " + (n + 1) + ": partida nueva (" + (dirty ? "sucia" : "sin director") + ")"); director(j); }
      String sim = dirSession;
      for (String s : ALL.split(",")) if (!screens.containsKey(s)) findScreen(s);
      j.fase = "jugando";
      // Luis 23:39: no long wait after the play (scenes wait up to 4 s for the animations to finish)
      // Luis 23:53: the clip ends when the animations end, at most half a second later (screens report FX.busy;
      // FX.defeating covers a defeat scene that is queued but not playing yet, p78)
      eval(cdp, sim, "(() => { globalThis.end = async () => { await wait(700); const pg = [T.mesa, ...Object.values(P)].filter(Boolean); const until = Date.now() + 12000; while (Date.now() < until) { const b = await Promise.all(pg.map((p) => p.evaluate(() => !!(window.FX && (FX.busy || FX.defeating))).catch(() => false))); if (!b.some((x) => x)) break; await wait(120); } await wait(450); mark('end'); }; return 1; })()");
      if (grabar) curRec = new Rec(j, this);
      eval(cdp, sim, "(() => { if (globalThis.FAST) FAST(); marks.length = 0; const f = SC[" + Json.str(escena) + "]; globalThis.__job = { done: false }; if (!f) { __job.err = 'No conozco la escena ' + " + Json.str(escena) + "; return 0; } f().then(() => { __job.done = true; }, (e) => { __job.err = String((e && e.message) || e); }); return 1; })()");
      j.estado = grabar ? "Jugando la escena…" : "Preparando la ventana…";
      int seen = 0; long start = System.currentTimeMillis();
      try {
        for (;;) {
          Thread.sleep(300);
          Map<String, Object> o = Json.obj(Json.parse((String) eval(cdp, sim, "JSON.stringify({m: marks.slice(" + seen + "), d: __job.done, e: __job.err || ''})")));
          List<Object> ms = Json.arr(o.get("m")); seen += ms.size();
          for (Object mo : ms) {
            Map<String, Object> m = Json.obj(mo); String tag = String.valueOf(m.get("tag"));
            if (tag.equals("shot") && !grabar) shot(j, String.valueOf(m.get("screen")), String.valueOf(m.get("name")));
            if (tag.equals("log")) System.out.println("log " + m);
            if (tag.equals("begin")) began[0] = true;
            if (grabar && tag.equals("begin")) curRec.begin(m);
            if (grabar && tag.equals("end")) curRec.end();
          }
          if (!String.valueOf(o.get("e")).isEmpty()) throw new Exception("La escena falló: " + o.get("e"));
          if (Boolean.TRUE.equals(o.get("d"))) break;
          if (System.currentTimeMillis() - start > 6 * 60000) throw new Exception("La escena tardó demasiado.");
        }
      } finally { if (LEAVE.contains(escena) || endsGame(escena)) dirty = true; }
      if (probar) { if (!began[0]) throw new Exception("La escena terminó sin empezar la jugada."); j.hechos.add("ok"); return; }
      if (grabar) {
        Rec r = curRec; r.end(); curRec = null;
        if (r.frames.isEmpty()) throw new Exception("La escena no grabó nada.");
        j.fase = "guardando"; j.estado = "Guardando los vídeos…"; j.saving = true;
        saves.incrementAndGet();
        saver.submit(() -> { try { r.save(); } catch (Exception e) { r.err = e; } finally { saves.decrementAndGet(); try (var w = Files.walk(CatalogoServer.root.resolve("servidor/tmp/" + j.id))) { w.sorted(Comparator.reverseOrder()).forEach((f) -> f.toFile().delete()); } catch (Exception e) { /* none */ } } try { if (r.err != null) throw r.err; if (j.hechos.isEmpty()) throw new Exception("La escena no grabó nada."); finish(j, null); } catch (Exception e) { finish(j, e); } });
        return;
      }
      if (j.hechos.isEmpty()) throw new Exception("La escena no sacó ninguna captura.");
    }
    void shot(Job j, String screen, String name) throws Exception {
      if (!name.matches("[a-z0-9-]+")) return;
      String s = screens.get(screen); if (s == null) { j.pasos.add("Sin pantalla " + screen); return; }
      // Slower machines (the Raspberry Pi: CATALOGO_PAUSA_CAPTURA_MS=1500) draw the scene later than the mark arrives:
      // wait for the screen's animations to end and two painted frames, then the extra pause, before the picture
      long pausa = pausaCaptura();
      if (pausa > 0) {
        try { eval(cdp, s, "new Promise((r) => { const t = Date.now() + " + (pausa * 4) + "; (function w() { if (!(window.FX && (FX.busy || FX.defeating)) || Date.now() > t) requestAnimationFrame(() => requestAnimationFrame(() => r(1))); else setTimeout(w, 100); })(); })"); }
        catch (Exception e) { /* shoot anyway */ }
        Thread.sleep(pausa);
      }
      String b64;
      try { b64 = (String) cdp.call("Page.captureScreenshot", Map.of("format", "jpeg", "quality", 88), s).get("data"); }
      catch (Exception e) { b64 = (String) cdp.call("Page.captureScreenshot", Map.of("format", "jpeg", "quality", 88), reattach(screen)).get("data"); }
      Path out = CatalogoServer.root.resolve("img/" + name + ".jpg"); Files.createDirectories(out.getParent());
      Files.write(out, Base64.getDecoder().decode(b64)); j.hechos.add("img/" + name + ".jpg");
      j.estado = "Captura guardada";
    }
    // Every page the director opens (one per screen) is attached so it can be shot or filmed
    void listen(Cdp c) throws Exception {
      c.on("Target.targetCreated", (p, s) -> track(c, p));
      c.on("Target.targetInfoChanged", (p, s) -> track(c, p));
      c.on("Target.targetDestroyed", (p, s) -> { String id = String.valueOf(p.get("targetId")); String sc = targetScreen.remove(id); String ss = targetSession.remove(id); if (sc != null && ss != null && ss.equals(screens.get(sc))) screens.remove(sc); });
      c.on("Page.screencastFrame", (p, s) -> { Rec r = curRec; try { c.send("Page.screencastFrameAck", Map.of("sessionId", ((Number) p.get("sessionId")).intValue()), s); } catch (Exception e) { /* next frame */ } if (r != null) r.frame(p, s); String sc = screenOf(s); if (sc != null) { lastFrame.put(sc, Base64.getDecoder().decode((String) p.get("data"))); lastAt.put(sc, System.currentTimeMillis()); } });
      c.call("Target.setDiscoverTargets", Map.of("discover", true), null);
    }
    void quitDirector() {
      if (cdp == null || dirTarget == null) return;
      try { eval(cdp, dirSession, "document.getElementById('quit').click(), 1"); Thread.sleep(3000); } catch (Exception e) { /* closing anyway */ }
      try { for (Object t : Json.arr(cdp.call("Target.getTargets", Map.of(), null).get("targetInfos"))) { Map<String, Object> ti = Json.obj(t); if ("page".equals(ti.get("type")) && !"about:blank".equals(ti.get("url")) && !String.valueOf(ti.get("url")).contains("/servidor/enc.html")) cdp.call("Target.closeTarget", Map.of("targetId", ti.get("targetId")), null); } } catch (Exception e) { /* closing anyway */ }
      dirTarget = null; dirSession = null; screens.clear(); targetScreen.clear();
      try { Thread.sleep(3000); } catch (InterruptedException e) { /* go on */ }
    }
    void director(Job j) throws Exception {
      quitDirector();
      // Pages of the game left by an earlier catalog window (its hidden browser may still be open): close them, or their
      // screens are filmed instead of the new ones (Luis 01:17: a tablet showing "No estás en la mesa")
      try { for (Object t : Json.arr(cdp.call("Target.getTargets", Map.of(), null).get("targetInfos"))) { Map<String, Object> ti = Json.obj(t); if ("page".equals(ti.get("type")) && String.valueOf(ti.get("url")).contains(":" + port + "/")) cdp.call("Target.closeTarget", Map.of("targetId", ti.get("targetId")), null); } } catch (Exception e) { /* go on */ }
      screens.clear(); targetScreen.clear();
      j.estado = "Preparando la partida…";
      String url = "http://127.0.0.1:" + port + "/sim.html?escena=W.listo&ver=" + enc(ALL) + "&grabar=0&auto=1&t=" + System.currentTimeMillis();
      dirTarget = (String) cdp.call("Target.createTarget", Map.of("url", url), null).get("targetId");
      dirSession = (String) cdp.call("Target.attachToTarget", Map.of("targetId", dirTarget, "flatten", true), null).get("sessionId");
      String sim = dirSession;
      // Luis 00:09: card pictures in Spanish. The screens ask for them unless told English; old English pictures kept
      // by this browser from before the Spanish download are dropped once per browser start
      if (!cacheClean) { try { cdp.call("Network.enable", Map.of(), sim); cdp.call("Network.clearBrowserCache", Map.of(), sim); cacheClean = true; } catch (Exception e) { /* keep going */ } }
      cdp.call("Emulation.setDeviceMetricsOverride", Map.of("width", 1600, "height", 900, "deviceScaleFactor", 1, "mobile", false), sim);
      try { waitFor(() -> Boolean.TRUE.equals(eval(cdp, sim, "document.readyState === 'complete' && !!document.getElementById('go')")), 30000); }
      catch (Exception e) { throw new Exception("La página del juego no cargó (" + eval(cdp, sim, "location.href + ' ' + document.readyState + ' ' + document.title") + ")."); }
      // Window scenes come from the catalog (grabacion/ventanas.js)
      // ...and so do the scenes added after the app package (grabacion/extra.js)
      for (String f : new String[] { "grabacion/ventanas.js", "grabacion/extra.js" }) {
        Path vj = CatalogoServer.root.resolve(f);
        if (Files.exists(vj)) eval(cdp, sim, "(async () => {" + Files.readString(vj, StandardCharsets.UTF_8) + "\n})().then(() => 'ok')");
      }
      // Scene names come as SC.x, KW.x or W.x: a lookup that understands all three, whatever sim.html expects
      eval(cdp, sim, "(() => { globalThis.W = globalThis.W || {}; W.listo = async () => {}; const t = globalThis.SC || {}; let warm = false; globalThis.SC = new Proxy(t, { get(o, k) { if (typeof k !== 'string') return o[k]; const i = k.indexOf('.'); if (i < 0 || k in o) return o[k]; const ns = k.slice(0, i), id = k.slice(i + 1); if (ns === 'SC') return o[id]; if (ns === 'W') return W[id]; if (ns === 'KW' && globalThis.KW && (KW[id] || (globalThis.KWO && KWO[id]))) return async () => { if (!warm && KW.warm) { warm = true; await KW.warm(); } return ((globalThis.KWO && KWO[id]) || KW[id])(); }; return undefined; } }); return 1; })()");
      eval(cdp, sim, "(() => { try { localStorage.setItem('mtgLang', 'es'); } catch (e) {} return 1; })()");
      eval(cdp, sim, "document.getElementById('go').click(), 1");
      long start = System.currentTimeMillis(); String last = "";
      for (;;) {
        Thread.sleep(500);
        Map<String, Object> o = Json.obj(Json.parse((String) eval(cdp, sim, "JSON.stringify({s: document.getElementById('status').textContent, e: document.getElementById('status').classList.contains('err'), a: !document.getElementById('again').disabled, k: !document.getElementById('ask').classList.contains('hidden')})")));
        if (Boolean.TRUE.equals(o.get("k"))) eval(cdp, sim, "document.getElementById('askYes').click(), 1");
        String st = String.valueOf(o.get("s")); if (!st.equals(last)) { j.pasos.add(st); last = st; if (!st.isBlank()) j.estado = st; }
        if (Boolean.TRUE.equals(o.get("e"))) throw new Exception(st);
        if (Boolean.TRUE.equals(o.get("a"))) break;
        if (System.currentTimeMillis() - start > 5 * 60000) throw new Exception("La partida tardó demasiado en prepararse.");
      }
      for (String s : ALL.split(",")) if (!screens.containsKey(s)) j.pasos.add("Pantalla sin abrir: " + s);
      Thread.sleep(4000); // the start animation on the table
      dirty = false;
    }
    boolean mesaUrl(String url) { return url.matches("https?://[^/]+:" + port + "/(\\?.*)?(#.*)?"); }
    void track(Cdp c, Map<String, Object> p) {
      Map<String, Object> ti = Json.obj(p.get("targetInfo"));
      if (!"page".equals(ti.get("type"))) return;
      String id = (String) ti.get("targetId"), url = String.valueOf(ti.get("url"));
      String screen = url.contains("/play.html?sim=") ? URLDecoder.decode(url.replaceAll(".*[?&]sim=([^&#]*).*", "$1"), StandardCharsets.UTF_8) : mesaUrl(url) ? "mesa" : null;
      if (screen == null || screen.equals(targetScreen.get(id))) return;
      targetScreen.put(id, screen);
      CompletableFuture.runAsync(() -> {
        try {
          String s = (String) c.call("Target.attachToTarget", Map.of("targetId", id, "flatten", true), null).get("sessionId");
          boolean mesa = screen.equals("mesa");
          c.call("Emulation.setDeviceMetricsOverride", Map.of("width", mesa ? 1920 : 1180, "height", mesa ? 1080 : 820, "deviceScaleFactor", mesa ? 1 : 1.5, "mobile", false), s); // tablet drawn at 1.5x: sharp text in the video (1770x1230)
          c.call("Emulation.setFocusEmulationEnabled", Map.of("enabled", true), s);
          screens.put(screen, s); targetSession.put(id, s);
        } catch (Exception e) { e.printStackTrace(); }
      });
    }
    boolean findScreen(String p) { try { reattach(p); return true; } catch (Exception e) { return false; } }
    String screenOf(String session) { for (Map.Entry<String, String> e : screens.entrySet()) if (e.getValue().equals(session)) return e.getKey(); return null; }
    // A screen whose debugging session went away (the tab was replaced or crashed): attach to its page again
    String reattach(String screen) throws Exception {
      for (Object t : Json.arr(cdp.call("Target.getTargets", Map.of(), null).get("targetInfos"))) {
        Map<String, Object> ti = Json.obj(t); String url = String.valueOf(ti.get("url"));
        boolean hit = screen.equals("mesa") ? mesaUrl(url) : url.contains("/play.html?sim=" + enc(screen)) || url.contains("/play.html?sim=" + screen);
        if (!"page".equals(ti.get("type")) || !hit) continue;
        String s = (String) cdp.call("Target.attachToTarget", Map.of("targetId", ti.get("targetId"), "flatten", true), null).get("sessionId");
        boolean mesa = screen.equals("mesa");
        cdp.call("Emulation.setDeviceMetricsOverride", Map.of("width", mesa ? 1920 : 1180, "height", mesa ? 1080 : 820, "deviceScaleFactor", mesa ? 1 : 1.5, "mobile", false), s); // tablet drawn at 1.5x: sharp text in the video (1770x1230)
        cdp.call("Emulation.setFocusEmulationEnabled", Map.of("enabled", true), s);
        screens.put(screen, s); targetScreen.put((String) ti.get("targetId"), screen); targetSession.put((String) ti.get("targetId"), s); return s;
      }
      throw new Exception("No encuentro la pantalla " + screen + ".");
    }
    // ---- the hidden browser of this motor (own profile folder: servidor/navegador, navegador2...)
    synchronized String navegador() throws Exception {
      // A hidden browser left by an earlier catalog window (Luis 02:11: no restarting the PC): close it, so this window starts clean
      if (browser == null && CatalogoServer.up(cdpPort)) {
        try { HttpResponse<String> v = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cdpPort + "/json/version")).build(), HttpResponse.BodyHandlers.ofString());
          Cdp old = Cdp.open((String) Json.obj(Json.parse(v.body())).get("webSocketDebuggerUrl")); try { old.call("Browser.close", Map.of(), null); } catch (Exception e) { /* it closes before answering */ } old.close(); }
        catch (Exception e) { /* start a new one anyway */ }
        long t = System.currentTimeMillis() + 10000; while (CatalogoServer.up(cdpPort) && System.currentTimeMillis() < t) Thread.sleep(300);
        Thread.sleep(1000); // the profile folder is released
      }
      if (!CatalogoServer.up(cdpPort) || browser == null || !browser.isAlive()) {
        String exe = buscarNavegador(); if (exe == null) throw new Exception("No encuentro Chrome, Brave ni Edge en este PC.");
        Path prof = CatalogoServer.root.resolve("servidor/navegador" + suf()); Files.createDirectories(prof);
        List<String> cmd = new ArrayList<>(List.of(exe, "--headless=new", "--remote-debugging-port=" + cdpPort, "--remote-allow-origins=*", "--user-data-dir=" + prof,
          "--no-first-run", "--no-default-browser-check", "--disable-popup-blocking", "--disable-background-timer-throttling", "--disable-renderer-backgrounding",
          "--disable-backgrounding-occluded-windows", "--autoplay-policy=no-user-gesture-required", "--mute-audio", "--hide-scrollbars", "--window-size=1920,1080", "--force-device-scale-factor=1.5", "about:blank")); // 1.5: the tablets film at 1770x1230 (sharp text); the table stays 1920x1080
        if (System.getProperty("os.name").toLowerCase().contains("linux")) cmd.add(1, "--no-sandbox");
        // Extra flags for this machine, one per line (Luis 25/10: the Pi's GPU), in servidor/navegador-flags.txt; # = comment
        try { Path ff = CatalogoServer.root.resolve("servidor/navegador-flags.txt");
          if (Files.exists(ff)) for (String f : Files.readAllLines(ff, StandardCharsets.UTF_8)) { f = f.strip(); if (f.startsWith("--")) cmd.add(1, f); } }
        catch (Exception e) { /* none then */ }
        browser = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(CatalogoServer.root.resolve("servidor/navegador" + suf() + ".log").toFile()).start();
        long t = System.currentTimeMillis() + 30000;
        while (!CatalogoServer.up(cdpPort)) { if (System.currentTimeMillis() > t) throw new Exception("El navegador oculto no arrancó (servidor\\navegador" + suf() + ".log)."); Thread.sleep(300); }
        prioridad(); // after it started its own processes, so they get it too
      }
      HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cdpPort + "/json/version")).build(), HttpResponse.BodyHandlers.ofString());
      return (String) Json.obj(Json.parse(r.body())).get("webSocketDebuggerUrl");
    }
  }
  static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
  static Object eval(Cdp cdp, String session, String expr) throws Exception {
    Map<String, Object> r = cdp.call("Runtime.evaluate", Map.of("expression", expr, "awaitPromise", true, "returnByValue", true, "userGesture", true), session);
    Map<String, Object> ex = Json.obj(r.get("exceptionDetails"));
    if (!ex.isEmpty()) throw new Exception("Error en la página: " + Json.obj(ex.get("exception")).getOrDefault("description", ex.get("text")));
    return Json.obj(r.get("result")).get("value");
  }
  interface Check { boolean ok() throws Exception; }
  static void waitFor(Check c, long ms) throws Exception { long t = System.currentTimeMillis() + ms; while (!c.ok()) { if (System.currentTimeMillis() > t) throw new Exception("La página no cargó."); Thread.sleep(250); } }

  // ---- recording: CDP screencast frames per screen between the scene's begin/end marks, encoded to webm in the browser
  static class Rec {
    final Job j; final Lane l; final String clave, tag; volatile boolean on, done; Exception err; Map<String, Object> roles = Map.of(); Set<String> want = Set.of();
    // frames go straight to disk (servidor/tmp/<job>/<screen>/<n>.jpg): with 4 motors filming at once they no longer fill the server's memory
    final Map<String, List<long[]>> times = new ConcurrentHashMap<>(); final Map<String, List<Path>> frames = new ConcurrentHashMap<>();
    Path dirOf(String screen) { return CatalogoServer.root.resolve("servidor/tmp/" + j.id + "/" + screen.replaceAll("[^A-Za-z0-9]", "_")); }
    Rec(Job j, Lane l) { this.j = j; this.l = l; this.clave = j.q.get("clave"); this.tag = clave.substring(2); want = new HashSet<>(Arrays.asList(j.q.getOrDefault("ver", "mesa").split(","))); }
    void frame(Map<String, Object> p, String s) {
      if (!on) return;
      String screen = l.screens.entrySet().stream().filter((e) -> e.getValue().equals(s)).map(Map.Entry::getKey).findFirst().orElse(null);
      if (screen == null) return;
      long at = System.currentTimeMillis();
      List<Path> fs = frames.computeIfAbsent(screen, (k) -> new CopyOnWriteArrayList<>());
      try { Path d = dirOf(screen); Files.createDirectories(d); Path f = d.resolve(fs.size() + ".jpg"); Files.write(f, Base64.getDecoder().decode((String) p.get("data"))); fs.add(f); times.computeIfAbsent(screen, (k) -> new CopyOnWriteArrayList<>()).add(new long[] { at }); }
      catch (IOException e) { /* frame lost: the next one follows */ }
    }
    void begin(Map<String, Object> m) throws Exception {
      if (on || done) return;
      List<Object> tags = Json.arr(m.get("tags"));
      // the clip of this row: its tag (actions) or the keyword's own scene
      if (!tags.isEmpty() && clave.startsWith("a:") && !tags.contains(tag)) return;
      roles = new LinkedHashMap<>(Json.obj(m.get("roles")));
      // Simular always films 4 screens (Luis 14:59): a role the scene leaves empty goes to a watching tablet
      Map<String, String> pap = new HashMap<>();
      for (String kv : j.q.getOrDefault("papeles", "").split(",")) { String[] x = kv.split(":", 2); if (x.length == 2) pap.put(x[0], x[1]); }
      for (String k : new String[] { "causa", "tu", "otro" }) {
        if (roles.get(k) != null) continue;
        String pick = pap.get(k);
        if (pick == null || roles.containsValue(pick) || !want.contains(pick)) pick = want.stream().filter((n) -> !n.equals("mesa") && !roles.containsValue(n)).findFirst().orElse(null);
        if (pick != null) roles.put(k, pick);
      }
      // film every player who has a role, even when ver= is old (on the Pi it came from DEFROLES: vid/index.js lacks the row)
      for (Object v : roles.values()) if (v != null) want.add(String.valueOf(v));
      on = true; j.estado = "● Grabando…";
      for (Map.Entry<String, String> e : l.screens.entrySet()) {
        if (!want.contains(e.getKey())) continue;
        boolean mesa = e.getKey().equals("mesa");
        Map<String, Object> o = Map.of("format", "jpeg", "quality", 92, "maxWidth", mesa ? 1920 : 1770, "maxHeight", mesa ? 1080 : 1230, "everyNthFrame", 1);
        try { l.cdp.call("Page.startScreencast", o, e.getValue()); }
        catch (Exception ex) { l.cdp.call("Page.startScreencast", o, l.reattach(e.getKey())); } // "Session with given id not found"
      }
    }
    void end() throws Exception {
      if (!on) return;
      Thread.sleep(400);
      // hold the last picture until the end mark
      for (String k : frames.keySet()) { List<Path> f = frames.get(k); if (!f.isEmpty()) { try { Path c = f.get(0).resolveSibling(f.size() + ".jpg"); Files.copy(f.get(f.size() - 1), c, StandardCopyOption.REPLACE_EXISTING); f.add(c); times.get(k).add(new long[] { System.currentTimeMillis() }); } catch (IOException e) { /* ends on the last frame */ } } }
      for (String s : l.screens.values()) try { l.cdp.call("Page.stopScreencast", Map.of(), s); } catch (Exception e) { /* gone */ }
      on = false; done = true;
    }
    void save() throws Exception {
      if (frames.isEmpty()) return;
      StringBuilder dbg = new StringBuilder("Fotogramas:"); for (String k : frames.keySet()) dbg.append(' ').append(k).append('=').append(frames.get(k).size()); System.out.println(dbg + " papeles=" + roles + " pantallas=" + l.screens.keySet());
      String g = clave.charAt(0) + "-" + clave.substring(2).toLowerCase().replaceAll("[^a-z0-9]+", "-");
      // every screen's encoder page runs at the same time (each one plays its clip in real time)
      List<String[]> open = new ArrayList<>();
      for (String screen : frames.keySet()) {
        List<String> rs = new ArrayList<>();
        if (screen.equals("mesa")) rs.add("mesa");
        else for (Map.Entry<String, Object> e : roles.entrySet()) if (screen.equals(e.getValue()) && !e.getKey().equals("mesa")) rs.add(e.getKey());
        if (rs.isEmpty()) continue;
        // frames to disk, then the encoder page turns them into a video and posts it back to /guardar
        String dir = "servidor/tmp/" + j.id + "/" + screen.replaceAll("[^A-Za-z0-9]", "_");
        Path d = CatalogoServer.root.resolve(dir); Files.createDirectories(d);
        List<long[]> ts = times.get(screen);
        StringBuilder idx = new StringBuilder("[");
        for (int i = 0; i < ts.size(); i++) idx.append(i > 0 ? "," : "").append(ts.get(i)[0]);
        Files.writeString(d.resolve("t.json"), idx.append("]").toString());
        String outs = String.join(",", rs.stream().map((r) -> "grab/" + g + "/" + r + ".webm").toList());
        String t = (String) l.cdp.call("Target.createTarget", Map.of("url", "http://127.0.0.1:" + CatalogoServer.port + "/servidor/enc.html?d=" + enc(dir) + "&out=" + enc(outs)), null).get("targetId");
        String s = (String) l.cdp.call("Target.attachToTarget", Map.of("targetId", t, "flatten", true), null).get("sessionId");
        open.add(new String[] { t, s, outs, d.toString() });
      }
      long until = System.currentTimeMillis() + 10 * 60000;
      Exception bad = null;
      for (String[] o : open) {
        try {
          for (;;) { Object title = eval(l.cdp, o[1], "document.title"); if ("OK".equals(title)) break; if (String.valueOf(title).startsWith("ERR")) throw new Exception("No se pudo hacer el vídeo: " + title); if (System.currentTimeMillis() > until) throw new Exception("El vídeo tardó demasiado."); Thread.sleep(500); }
          for (String out : o[2].split(",")) j.hechos.add(out);
        } catch (Exception e) { if (bad == null) bad = e; }
        try { l.cdp.call("Target.closeTarget", Map.of("targetId", o[0]), null); } catch (Exception e) { /* gone */ }
        try (var w = Files.walk(Path.of(o[3]))) { w.sorted(Comparator.reverseOrder()).forEach((p) -> p.toFile().delete()); }
      }
      if (bad != null) throw bad;
      // Only the newest recording stays (Luis 03:27): roles this take did not film and the row's old clips are deleted
      Path gd = CatalogoServer.root.resolve("grab/" + g);
      for (String role : new String[] { "mesa", "causa", "tu", "otro" }) if (!j.hechos.contains("grab/" + g + "/" + role + ".webm")) Files.deleteIfExists(gd.resolve(role + ".webm"));
      String old = j.q.getOrDefault("viejo", "");
      if (old.matches("vid/(kw/)?[A-Za-z0-9_-]+")) for (String role : new String[] { "mesa", "causa", "tu", "otro" }) Files.deleteIfExists(CatalogoServer.root.resolve(old + "/" + role + ".mp4"));
    }
  }
  static String buscarNavegador() {
    String own = System.getProperty("catalogo.navegador", System.getenv("CATALOGO_NAVEGADOR"));
    if (own != null && new File(own).exists()) return own;
    List<String> roots = new ArrayList<>();
    for (String v : new String[] { "ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA", "ProgramW6432" }) { String p = System.getenv(v); if (p != null) roots.add(p); }
    String[] rel = { "Google\\Chrome\\Application\\chrome.exe", "BraveSoftware\\Brave-Browser\\Application\\brave.exe", "Microsoft\\Edge\\Application\\msedge.exe" };
    for (String r : rel) for (String root : roots) { File f = new File(root, r); if (f.exists()) return f.getPath(); }
    for (String f : new String[] { "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome" }) if (new File(f).exists()) return f; // Raspberry Pi
    return null;
  }
  // ---- a tiny DevTools protocol client (flat sessions)
  static class Cdp implements WebSocket.Listener {
    interface Ev { void on(Map<String, Object> params, String session); }
    final AtomicInteger ids = new AtomicInteger(); final Map<Integer, CompletableFuture<Map<String, Object>>> wait = new ConcurrentHashMap<>();
    final Map<String, List<Ev>> evs = new ConcurrentHashMap<>(); final StringBuilder buf = new StringBuilder(); WebSocket ws;
    final ExecutorService events = Executors.newSingleThreadExecutor();
    static Cdp open(String url) throws Exception { Cdp c = new Cdp(); c.ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(url), c).get(15, TimeUnit.SECONDS); return c; }
    void on(String method, Ev e) { evs.computeIfAbsent(method, (k) -> new CopyOnWriteArrayList<>()).add(e); }
    CompletableFuture<Map<String, Object>> send(String method, Map<String, Object> params, String session) {
      int id = ids.incrementAndGet(); CompletableFuture<Map<String, Object>> f = new CompletableFuture<>(); wait.put(id, f);
      String msg = "{\"id\":" + id + ",\"method\":" + Json.str(method) + ",\"params\":" + Json.str(params) + (session != null ? ",\"sessionId\":" + Json.str(session) : "") + "}";
      synchronized (this) { ws.sendText(msg, true).join(); }
      return f;
    }
    Map<String, Object> call(String method, Map<String, Object> params, String session) throws Exception {
      Map<String, Object> r = send(method, params, session).get(60, TimeUnit.SECONDS);
      if (r.containsKey("error")) throw new Exception(method + ": " + Json.obj(r.get("error")).get("message"));
      return Json.obj(r.get("result"));
    }
    @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
      buf.append(data);
      if (last) {
        String s = buf.toString(); buf.setLength(0);
        try {
          Map<String, Object> m = Json.obj(Json.parse(s));
          if (m.containsKey("id")) { CompletableFuture<Map<String, Object>> f = wait.remove(((Number) m.get("id")).intValue()); if (f != null) f.complete(m); }
          else { List<Ev> l = evs.get(String.valueOf(m.get("method"))); if (l != null) { Map<String, Object> p = Json.obj(m.get("params")); String ses = (String) m.get("sessionId"); events.submit(() -> { for (Ev e : l) try { e.on(p, ses); } catch (Exception ex) { ex.printStackTrace(); } }); } }
        } catch (Exception e) { e.printStackTrace(); }
      }
      w.request(1); return null;
    }
    void close() { try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join(); } catch (Exception e) { /* gone */ } events.shutdown(); }
  }

  // ---- minimal JSON
  static class Json {
    @SuppressWarnings("unchecked") static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : new HashMap<>(); }
    @SuppressWarnings("unchecked") static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }
    static Object parse(String s) { int[] i = { 0 }; return val(s, i); }
    static void ws(String s, int[] i) { while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++; }
    static Object val(String s, int[] i) {
      ws(s, i); char c = s.charAt(i[0]);
      if (c == '{') { Map<String, Object> m = new LinkedHashMap<>(); i[0]++; ws(s, i); if (s.charAt(i[0]) == '}') { i[0]++; return m; }
        for (;;) { ws(s, i); String k = (String) val(s, i); ws(s, i); i[0]++; m.put(k, val(s, i)); ws(s, i); if (s.charAt(i[0]++) == '}') return m; } }
      if (c == '[') { List<Object> l = new ArrayList<>(); i[0]++; ws(s, i); if (s.charAt(i[0]) == ']') { i[0]++; return l; }
        for (;;) { l.add(val(s, i)); ws(s, i); if (s.charAt(i[0]++) == ']') return l; } }
      if (c == '"') { StringBuilder b = new StringBuilder(); i[0]++;
        for (;;) { char ch = s.charAt(i[0]++); if (ch == '"') return b.toString();
          if (ch == '\\') { char e = s.charAt(i[0]++); switch (e) { case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break; case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
            case 'u': b.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16)); i[0] += 4; break; default: b.append(e); } } else b.append(ch); } }
      if (s.startsWith("true", i[0])) { i[0] += 4; return true; } if (s.startsWith("false", i[0])) { i[0] += 5; return false; } if (s.startsWith("null", i[0])) { i[0] += 4; return null; }
      int st = i[0]; while (i[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(i[0])) >= 0) i[0]++;
      return Double.parseDouble(s.substring(st, i[0]));
    }
    static String str(Object o) {
      if (o == null) return "null";
      if (o instanceof String) { StringBuilder b = new StringBuilder("\""); for (char c : ((String) o).toCharArray()) { if (c == '"' || c == '\\') b.append('\\').append(c); else if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); } return b.append('"').toString(); }
      if (o instanceof Map) { StringBuilder b = new StringBuilder("{"); for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) b.append(b.length() > 1 ? "," : "").append(str(String.valueOf(e.getKey()))).append(':').append(str(e.getValue())); return b.append('}').toString(); }
      if (o instanceof Collection) { StringBuilder b = new StringBuilder("["); for (Object e : (Collection<?>) o) b.append(b.length() > 1 ? "," : "").append(str(e)); return b.append(']').toString(); }
      return String.valueOf(o);
    }
  }
}
