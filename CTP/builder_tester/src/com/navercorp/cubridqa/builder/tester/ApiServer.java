package com.navercorp.cubridqa.builder.tester;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.navercorp.cubridqa.builder.BuilderConfig;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class ApiServer {
    private static final Logger logger = Logger.getLogger(ApiServer.class.getName());
    
    private final BuilderConfig config;
    private final HttpServer server;
    
    public ApiServer(BuilderConfig config) throws IOException {
        this.config = config;
        this.server = HttpServer.create(new InetSocketAddress(config.getTesterPort()), 0);
        
        int maxThreads = Math.max(1, config.getMaxConcurrentTests());
        this.server.setExecutor(Executors.newFixedThreadPool(maxThreads));
    }
    
    public void registerHandler(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }
    
    public void start() {
        server.start();
        logger.info("API Server started on port " + config.getTesterPort());
    }
    
    public void stop() {
        server.stop(0);
        logger.info("API Server stopped");
    }
}