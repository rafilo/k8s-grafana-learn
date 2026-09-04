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

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(env("PORT", "8080"));
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", ProductionSimulator::handleRequest);
    server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}\n", "application/json"));
    server.createContext("/metrics", ProductionSimulator::metrics);
    server.setExecutor(Executors.newFixedThreadPool(8));
    Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(ProductionSimulator::simulateTraffic, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    System.out.printf("{\"timestamp\":\"%s\",\"level\":\"INFO\",\"service\":\"%s\",\"message\":\"server_started\",\"port\":%d}%n", Instant.now(), SERVICE, port);
    server.start();
  }

  private static void handleRequest(HttpExchange exchange) throws IOException {
    long started = System.nanoTime();
    boolean failure = ThreadLocalRandom.current().nextInt(100) < 3;
    long id = REQUESTS.incrementAndGet();
    if (failure) ERRORS.incrementAndGet();
    long duration = Math.max(1, (System.nanoTime() - started) / 1_000_000 + ThreadLocalRandom.current().nextLong(8, 75));
    LATENCY_MS.addAndGet(duration);
    int status = failure ? 503 : 200;
    System.out.printf("{\"timestamp\":\"%s\",\"level\":\"%s\",\"service\":\"%s\",\"environment\":\"%s\",\"request_id\":\"req-%d\",\"method\":\"%s\",\"path\":\"%s\",\"status\":%d,\"duration_ms\":%d,\"message\":\"request_completed\"}%n", Instant.now(), failure ? "ERROR" : "INFO", SERVICE, ENVIRONMENT, id, exchange.getRequestMethod(), exchange.getRequestURI().getPath(), status, duration);
    respond(exchange, status, String.format("{\"service\":\"%s\",\"status\":%d}\n", SERVICE, status), "application/json");
  }

  private static void simulateTraffic() {
    int requests = ThreadLocalRandom.current().nextInt(4, 14);
    for (int i = 0; i < requests; i++) {
      REQUESTS.incrementAndGet();
      long latency = ThreadLocalRandom.current().nextLong(12, 240);
      LATENCY_MS.addAndGet(latency);
      if (ThreadLocalRandom.current().nextInt(100) < 2) {
        ERRORS.incrementAndGet();
        System.out.printf("{\"timestamp\":\"%s\",\"level\":\"WARN\",\"service\":\"%s\",\"environment\":\"%s\",\"message\":\"upstream_timeout\",\"duration_ms\":%d}%n", Instant.now(), SERVICE, ENVIRONMENT, latency);
      }
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
    String labels = String.format("service=\"%s\",environment=\"%s\"", SERVICE, ENVIRONMENT);
    StringBuilder out = new StringBuilder();
    metric(out, "application_requests_total", "Counter", "Simulated completed HTTP requests.", labels, total);
    metric(out, "application_errors_total", "Counter", "Simulated failed HTTP requests.", labels, ERRORS.get());
    metric(out, "application_request_duration_ms_sum", "Counter", "Cumulative simulated request latency.", labels, LATENCY_MS.get());
    metric(out, "application_request_duration_ms_count", "Counter", "Number of simulated request latencies.", labels, total);
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
    out.append("# HELP ").append(name).append(' ').append(help).append('\n');
    out.append("# TYPE ").append(name).append(' ').append(type.toLowerCase(Locale.ROOT)).append('\n');
    out.append(name).append('{').append(labels).append("} ").append(value).append('\n');
  }

  private static void respond(HttpExchange exchange, int code, String body, String contentType) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream stream = exchange.getResponseBody()) { stream.write(bytes); }
  }
  private static String env(String key, String fallback) { String value = System.getenv(key); return value == null || value.isBlank() ? fallback : value; }
  private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
