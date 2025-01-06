package server;

import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import io.netty.util.concurrent.ScheduledFuture;

public class TimerManager
{
    private static final Logger log = LoggerFactory.getLogger(TimerManager.class);
    private static final TimerManager instance = new TimerManager();
    private NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
    private EventLoop eventLoop;

    public static TimerManager getInstance() {
        return instance;
    }

    private TimerManager() {
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
