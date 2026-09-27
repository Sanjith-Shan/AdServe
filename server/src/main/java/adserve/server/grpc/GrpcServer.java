package adserve.server.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Netty gRPC server whose handlers run, by default, on virtual threads: the one blocking call on the decision
 * path (the counter fetch) parks a virtual thread instead of holding a platform thread.
 */
public class GrpcServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);
    private final int port;
    private final BindableService service;
    private final ExecutorService executor;
    private final HealthStatusManager health = new HealthStatusManager();
    private Server server;

    public GrpcServer(int port, BindableService service, String executorSpec) {
        this.port = port;
        this.service = service;
        if (executorSpec == null || executorSpec.equals("virtual")) {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
        } else if (executorSpec.startsWith("platform:")) {
            this.executor = Executors.newFixedThreadPool(Integer.parseInt(executorSpec.substring(9)));
        } else {
            throw new IllegalArgumentException("adserve.executor must be virtual or platform:N, got " + executorSpec);
        }
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder.forPort(port)
                    .executor(executor)
                    .addService(service)
                    .addService(health.getHealthService())
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
            log.info("gRPC AdDecision listening on {}", port);
        } catch (IOException e) {
            throw new IllegalStateException("could not start gRPC on " + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            health.enterTerminalState();
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executor.shutdown();
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    public int port() {
        return server == null ? port : server.getPort();
    }
}
