package server;

import config.YamlConfig;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.SECONDS;

public class ExpLogger {
    private static final LinkedBlockingQueue<ExpLogRecord> expLoggerQueue = new LinkedBlockingQueue<>();
    private static final short EXP_LOGGER_THREAD_SLEEP_DURATION_SECONDS = 60;
    private static final short EXP_LOGGER_THREAD_SHUTDOWN_WAIT_DURATION_MINUTES = 5;

    public record ExpLogRecord(int worldExpRate, int expCoupon, long gainedExp, int currentExp,
                               Timestamp expGainTime, int charid) {}

    public static void putExpLogRecord(ExpLogRecord expLogRecord) {
        try {
            expLoggerQueue.put(expLogRecord);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // This is what we run periodically to flush logs
    private static final Runnable saveExpLoggerToDBRunnable = new Runnable() {
        @Override
        public void run() {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "INSERT INTO characterexplogs (world_exp_rate, exp_coupon, gained_exp, current_exp, exp_gain_time, charid) VALUES (?, ?, ?, ?, ?, ?)"
                 ))
            {
                List<ExpLogRecord> drainedExpLogs = new ArrayList<>();
                expLoggerQueue.drainTo(drainedExpLogs);

                for (ExpLogRecord expLogRecord : drainedExpLogs) {
                    ps.setInt(1, expLogRecord.worldExpRate);
                    ps.setInt(2, expLogRecord.expCoupon);
                    ps.setLong(3, expLogRecord.gainedExp);
                    ps.setInt(4, expLogRecord.currentExp);
                    ps.setTimestamp(5, expLogRecord.expGainTime);
                    ps.setInt(6, expLogRecord.charid);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException sqle) {
                sqle.printStackTrace();
            }
        }
    };

    // We'll store the scheduled future so we can cancel or flush it at shutdown if needed
    private static ScheduledFuture<?> flushTask;

    static {
        if (YamlConfig.config.server.USE_EXP_GAIN_LOG) {
            startExpLogger();
        }
    }

    private static void startExpLogger() {
        // Suppose TimerManager's method is:
        // register(Runnable task, long repeatMillis, long initialDelayMillis)
        long periodMs = SECONDS.toMillis(EXP_LOGGER_THREAD_SLEEP_DURATION_SECONDS);
        flushTask = Scheduler.getInstance().register(saveExpLoggerToDBRunnable, periodMs, periodMs);

        // Optionally add a shutdown hook that performs a final flush
        // But let's do it via TimerManager's single thread instead of new thread
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopExpLogger();
        }));
    }

    private static boolean stopExpLogger() {
        // Cancel the periodic flush
        if (flushTask != null) {
            flushTask.cancel(false);
        }

        // Now do a final flush on the same single thread:
        Scheduler.getInstance().execute(() -> {
            saveExpLoggerToDBRunnable.run();
        });

        // Because TimerManager is single-threaded, once it finishes the above task, logs are fully saved
        // Optionally, wait for some acknowledgement if your TimerManager supports that,
        // but typically it's enough to queue the final flush.
        return true;
    }
}
