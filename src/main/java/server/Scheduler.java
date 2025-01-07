package server;

import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import io.netty.util.concurrent.ScheduledFuture;

public class Scheduler
{
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private static final Scheduler instance = new Scheduler();
    private NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
    private EventLoop eventLoop;

    public static Scheduler getInstance() {
        return instance;
    }

    private Scheduler() {
        System.out.println("Initializing TimerManager");
        eventLoop = eventLoopGroup.next();
    }

    public void stop() {
        if (!eventLoopGroup.isShuttingDown()) {
            eventLoopGroup.shutdownGracefully();
        }
    }

    public ScheduledFuture<?> register(Runnable task, long repeatTime, long delay) {
        return eventLoop.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("[ERROR] Scheduled task failed: ", t);
            }
        }, delay, repeatTime, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> register(Runnable task, long repeatTime) {
        return eventLoop.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("[ERROR] Scheduled task failed: ", t);
            }
        }, 0, repeatTime, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay) {
        return eventLoop.schedule(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("[ERROR] Scheduled task failed: ", t);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public void execute(Runnable task) {
        eventLoop.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("[ERROR] Executed task failed: ", t);
            }
        });
    }

    public boolean isShutdown() {
        return eventLoopGroup.isShuttingDown();
    }
}
