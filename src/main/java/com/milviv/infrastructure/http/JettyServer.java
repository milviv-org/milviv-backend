package com.milviv.infrastructure.http;

import org.eclipse.jetty.server.Server;
import java.util.concurrent.Executors;

import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.milviv.infrastructure.config.AppConfig;
import com.milviv.infrastructure.http.router.Router;

public class JettyServer {

    private static final Logger logger = LoggerFactory.getLogger(JettyServer.class);

    private final Server server;

    public JettyServer(AppConfig config, Router router) {
        // Запрос к AI держит поток до 90 с (§5): обработка запросов — на виртуальных потоках,
        // ожидание ответа провайдера не занимает платформенный поток из пула
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server = new Server(threadPool);

        ServerConnector connector = new ServerConnector(server);
        connector.setHost(config.getServerHost());
        connector.setPort(config.getServerPort());
        server.addConnector(connector);

        server.setHandler(router);
    }

    public void start() throws Exception {
        server.start();
        logger.info("Server started on {}:{}", server.getURI().getHost(), server.getURI().getPort());
    }

    public void stop() throws Exception {
        server.stop();
        logger.info("Server stopped");
    }

}
