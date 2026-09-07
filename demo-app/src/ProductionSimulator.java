import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class ProductionSimulator {
  private static final long STARTED = System.currentTimeMillis();
  private static final AtomicLong REQUESTS = new AtomicLong();
  private static final AtomicLong ERRORS = new AtomicLong();
  private static final AtomicLong LATENCY_MS = new AtomicLong();
  private static final String SERVICE = env("SERVICE_NAME", "checkout-api");
  private static final String ENVIRONMENT = env("ENVIRONMENT", "production");
  private static final AtomicLong IN_FLIGHT = new AtomicLong();
  private static final Map<String, Long> COUNTERS = new TreeMap<>();
  private static final Map<String, Histogram> HISTOGRAMS = new TreeMap<>();
  private static final double[] BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.2, 0.3, 0.5, 0.75, 1, 2, 3, 5, 10};
  private static final String[] ROUTES = {"/api/payments", "/api/cards/authorize", "/api/payments/{id}/refund"};

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(env("PORT", "8080"));
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", ProductionSimulator::handleRequest);
    server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}\n", "application/json"));
    server.createContext("/metrics", ProductionSimulator::metrics);
    server.setExecutor(Executors.newFixedThreadPool(8));
    if (Boolean.parseBoolean(env("SIMULATE_TRAFFIC", "true")))
      Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(ProductionSimulator::simulateTraffic, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    System.out.printf("{\"timestamp\":\"%s\",\"level\":\"INFO\",\"service\":\"%s\",\"message\":\"server_started\",\"port\":%d}%n", Instant.now(), SERVICE, port);
    server.start();
  }

  private static void handleRequest(HttpExchange exchange) throws IOException {
    long started = System.nanoTime();
    IN_FLIGHT.incrementAndGet();
    boolean failure = ThreadLocalRandom.current().nextInt(100) < 3;
    int status = failure ? 503 : 200;
    String route = route(exchange.getRequestURI().getPath());
    String method = switch (exchange.getRequestMethod()) {
      case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS" -> exchange.getRequestMethod();
      default -> "OTHER";
    };
    try {
      respond(exchange, status, String.format("{\"status\":%d}\n", status), "application/json");
    } catch (IOException e) {
      status = 500;
      increment("http_response_write_errors_total", "source=\"actual\"", 1);
      throw e;
    } finally {
      double seconds = (System.nanoTime() - started) / 1e9;
      recordRequest("actual", route, method, status, seconds);
      IN_FLIGHT.decrementAndGet();
      System.out.printf(Locale.ROOT, "{\"timestamp\":\"%s\",\"level\":\"%s\",\"message\":\"request_completed\",\"source\":\"actual\",\"route\":\"%s\",\"method\":\"%s\",\"status\":%d,\"duration_ms\":%.3f}%n",
          Instant.now(), status >= 500 ? "ERROR" : "INFO", route, method, status, seconds * 1000);
    }
  }

  private static String route(String path) {
    for (String route : ROUTES) if (route.equals(path)) return route;
    if (path.matches("/api/payments/[^/]+/refund")) return ROUTES[2];
    return path.equals("/") ? "/" : "other";
  }

  private static synchronized void recordRequest(String source, String route, String method, int status, double seconds) {
    REQUESTS.incrementAndGet();
    if (status >= 500) ERRORS.incrementAndGet();
    LATENCY_MS.addAndGet(Math.round(seconds * 1000));
    String labels = "source=\"" + source + "\",route=\"" + route + "\",method=\"" + method + "\"";
    increment("http_server_requests_total", labels + ",status=\"" + status + "\"", 1);
    observe("http_server_request_duration_seconds", labels, seconds);
  }

  private static synchronized void increment(String name, String labels, long value) {
    COUNTERS.merge(name + "{" + labels, value, Long::sum);
  }

  private static synchronized void observe(String name, String labels, double seconds) {
    HISTOGRAMS.computeIfAbsent(name + "{" + labels, key -> new Histogram()).observe(seconds);
  }

  private static final class Histogram {
    final long[] buckets = new long[BUCKETS.length];
    long count;
    double sum;
    void observe(double seconds) {
      count++;
      sum += seconds;
      for (int i = 0; i < BUCKETS.length; i++) if (seconds <= BUCKETS[i]) buckets[i]++;
    }
  }

  private static void simulateTraffic() {
    int requests = ThreadLocalRandom.current().nextInt(4, 14);
    for (int i = 0; i < requests; i++) {
      var random = ThreadLocalRandom.current();
      int operation = random.nextInt(ROUTES.length);
      int roll = random.nextInt(100);
      String outcome = roll < 2 ? "timeout" : roll < 4 ? "error" : roll < 12 ? "declined" : "approved";
      int status = switch (outcome) { case "timeout" -> 504; case "error" -> 503; case "declined" -> 422; default -> 200; };
      double latency = outcome.equals("timeout") ? 3.0 : random.nextDouble(0.012, roll < 17 ? 1.5 : 0.24);
      recordRequest("simulated", ROUTES[operation], "POST", status, latency);
      String operationName = new String[]{"payment", "authorization", "refund"}[operation];
      increment("banking_operations_total", "source=\"simulated\",operation=\"" + operationName + "\",outcome=\"" + outcome + "\"", 1);
      String dependency = "source=\"simulated\",dependency=\"card_processor\"";
      increment("dependency_requests_total", dependency + ",outcome=\"" + (status >= 500 ? outcome : "success") + "\"", 1);
      observe("dependency_request_duration_seconds", dependency, latency * 0.8);
      increment("dependency_timeouts_total", dependency, outcome.equals("timeout") ? 1 : 0);
      increment("banking_idempotency_replays_total", "source=\"simulated\"", random.nextInt(100) < 3 ? 1 : 0);
      increment("network_probe_packets_sent_total", "source=\"simulated\",target=\"card_processor\"", 100);
      increment("network_probe_packets_lost_total", "source=\"simulated\",target=\"card_processor\"", random.nextInt(100) < 5 ? random.nextInt(1, 6) : 0);
      if (status >= 500)
        System.out.printf("{\"timestamp\":\"%s\",\"level\":\"WARN\",\"message\":\"dependency_failure\",\"source\":\"simulated\",\"outcome\":\"%s\"}%n", Instant.now(), outcome);
    }
    double work = 0;
    for (int i = 0; i < 180_000; i++) work += Math.sqrt(i);
    if (work < 0) System.out.println(work);
  }

  private static void metrics(HttpExchange exchange) throws IOException {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    MemoryUsage heap = memory.getHeapMemoryUsage();
    MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
    OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    long total = REQUESTS.get();
    String labels = String.format("service=\"%s\",environment=\"%s\"", escape(SERVICE), escape(ENVIRONMENT));
    StringBuilder out = new StringBuilder();
    metric(out, "application_requests_total", "Counter", "Completed actual and simulated HTTP requests.", labels, total);
    metric(out, "application_errors_total", "Counter", "Actual and simulated HTTP server errors.", labels, ERRORS.get());
    metric(out, "application_request_duration_ms_sum", "Counter", "Cumulative actual and simulated request latency in milliseconds.", labels, LATENCY_MS.get());
    metric(out, "application_request_duration_ms_count", "Counter", "Number of actual and simulated request latencies.", labels, total);
    metric(out, "http_server_requests_in_flight", "Gauge", "Actual application handlers currently executing; excludes health and metrics.", labels, IN_FLIGHT.get());
    exportBankingMetrics(out, labels);
    exportNetworkMetrics(out, labels);
    metric(out, "process_cpu_usage_ratio", "Gauge", "JVM process CPU usage ratio.", labels, Math.max(0, os.getProcessCpuLoad()));
    metric(out, "system_cpu_usage_ratio", "Gauge", "Host CPU usage ratio visible to the JVM.", labels, Math.max(0, os.getCpuLoad()));
    metric(out, "jvm_memory_heap_used_bytes", "Gauge", "Current JVM heap usage.", labels, heap.getUsed());
    metric(out, "jvm_memory_heap_max_bytes", "Gauge", "Maximum JVM heap.", labels, heap.getMax());
    metric(out, "jvm_memory_nonheap_used_bytes", "Gauge", "Current JVM non-heap usage.", labels, nonHeap.getUsed());
    metric(out, "jvm_threads_live", "Gauge", "Live JVM thread count.", labels, ManagementFactory.getThreadMXBean().getThreadCount());
    metric(out, "jvm_uptime_seconds", "Gauge", "JVM uptime.", labels, (System.currentTimeMillis() - STARTED) / 1000.0);
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      String gcLabels = labels + ",collector=\"" + escape(gc.getName()) + "\"";
      metric(out, "jvm_gc_collection_count", "Counter", "JVM GC count.", gcLabels, Math.max(0, gc.getCollectionCount()));
      metric(out, "jvm_gc_collection_time_ms", "Counter", "JVM GC collection time.", gcLabels, Math.max(0, gc.getCollectionTime()));
    }
    respond(exchange, 200, out.toString(), "text/plain; version=0.0.4");
  }

  private static void metric(StringBuilder out, String name, String type, String help, String labels, double value) {
    metadata(out, name, type, help);
    out.append(name).append('{').append(labels).append("} ").append(value).append('\n');
  }

  private static void metadata(StringBuilder out, String name, String type, String help) {
    if (out.indexOf("# TYPE " + name + " ") >= 0) return;
    out.append("# HELP ").append(name).append(' ').append(help).append('\n');
    out.append("# TYPE ").append(name).append(' ').append(type.toLowerCase(Locale.ROOT)).append('\n');
  }

  private static synchronized void exportBankingMetrics(StringBuilder out, String common) {
    COUNTERS.forEach((key, value) -> {
      int split = key.indexOf('{');
      String name = key.substring(0, split);
      metric(out, name, "counter", "Cumulative events; source distinguishes simulated and actual observations.", common + "," + key.substring(split + 1), value);
    });
    HISTOGRAMS.forEach((key, histogram) -> {
      int split = key.indexOf('{');
      String name = key.substring(0, split);
      String labels = common + "," + key.substring(split + 1);
      metadata(out, name, "histogram", "Request duration in seconds; source distinguishes simulated and actual observations.");
      for (int i = 0; i < BUCKETS.length; i++)
        out.append(name).append("_bucket{").append(labels).append(",le=\"").append(BUCKETS[i]).append("\"} ").append(histogram.buckets[i]).append('\n');
      out.append(name).append("_bucket{").append(labels).append(",le=\"+Inf\"} ").append(histogram.count).append('\n');
      out.append(name).append("_sum{").append(labels).append("} ").append(histogram.sum).append('\n');
      out.append(name).append("_count{").append(labels).append("} ").append(histogram.count).append('\n');
    });
  }

  private static void exportNetworkMetrics(StringBuilder out, String common) {
    // Linux network namespace counters: Pod-local in normal Kubernetes networking.
    // Interface drops are not an end-to-end packet-loss measurement.
    StringBuilder network = new StringBuilder();
    try {
      for (String line : Files.readAllLines(Path.of("/proc/net/dev"))) {
        if (!line.contains(":")) continue;
        String[] parts = line.trim().split(":", 2);
        if (parts[0].trim().equals("lo")) continue;
        String[] fields = parts[1].trim().split("\\s+");
        String labels = common + ",interface=\"" + escape(parts[0].trim()) + "\"";
        for (int direction = 0; direction < 2; direction++) {
          String directionLabels = labels + ",direction=\"" + (direction == 0 ? "receive" : "transmit") + "\"";
          int offset = direction * 8;
          metric(network, "network_interface_packets_total", "counter", "Linux interface packets in this network namespace.", directionLabels, Long.parseLong(fields[offset + 1]));
          metric(network, "network_interface_errors_total", "counter", "Linux interface packet errors.", directionLabels, Long.parseLong(fields[offset + 2]));
          metric(network, "network_interface_dropped_packets_total", "counter", "Linux interface drops; not end-to-end packet loss.", directionLabels, Long.parseLong(fields[offset + 3]));
        }
      }
      out.append(network);
      metric(out, "network_interface_collection_success", "gauge", "Whether Linux network statistics could be read.", common, 1);
    } catch (IOException | RuntimeException e) {
      metric(out, "network_interface_collection_success", "gauge", "Whether Linux network statistics could be read.", common, 0);
    }
  }

  private static void respond(HttpExchange exchange, int code, String body, String contentType) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream stream = exchange.getResponseBody()) { stream.write(bytes); }
  }
  private static String env(String key, String fallback) { String value = System.getenv(key); return value == null || value.isBlank() ? fallback : value; }
  private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
}
