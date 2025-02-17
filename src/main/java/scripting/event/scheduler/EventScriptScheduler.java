/*
 This file is part of the HeavenMS MapleStory Server
 Copyleft (L) 2016 - 2019 RonanLana

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package scripting.event.scheduler;

import config.YamlConfig;
import net.server.Server;
import server.Scheduler;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Ronan
 */
public class EventScriptScheduler
{
    private boolean disposed = false;
    private int idleProcs = 0;
    private final Map<Runnable, Long> registeredEntries = new ConcurrentHashMap<>();
    private ScheduledFuture<?> schedulerTask = null;
    private final Runnable monitorTask = () -> runBaseSchedule();

    private void runBaseSchedule() {
        if (registeredEntries.isEmpty()) {
            idleProcs++;

            if (idleProcs >= YamlConfig.config.server.MOB_STATUS_MONITOR_LIFE) {
                if (schedulerTask != null) {
                    schedulerTask.cancel(false);
                    schedulerTask = null;
                }
            }

            return;
        }

        idleProcs = 0;
        long timeNow = Server.getInstance().getCurrentTime();
        List<Runnable> toRemove = new LinkedList<>();

        for (Entry<Runnable, Long> rmd : registeredEntries.entrySet()) {
            if (rmd.getValue() < timeNow) {
                Runnable r = rmd.getKey();
                r.run();  // runs the scheduled action
                toRemove.add(r);
            }
        }

        if (!toRemove.isEmpty()) {
            for (Runnable r : toRemove) {
                registeredEntries.remove(r);
            }
        }
    }

    public void registerEntry(final Runnable scheduledAction, final long duration)
    {
        Scheduler.getInstance().execute(() ->
        {
            idleProcs = 0;
            if (schedulerTask == null)
            {
                if (disposed)
                {
                    return;
                }

                schedulerTask = Scheduler.getInstance().register(monitorTask, YamlConfig.config.server.MOB_STATUS_MONITOR_PROC, YamlConfig.config.server.MOB_STATUS_MONITOR_PROC);
            }

            registeredEntries.put(scheduledAction, Server.getInstance().getCurrentTime() + duration);
        });
    }

    public void cancelEntry(final Runnable scheduledAction)
    {
        Scheduler.getInstance().execute(() ->
        {
            registeredEntries.remove(scheduledAction);
        });
    }

    public void dispose()
    {
        Scheduler.getInstance().execute(() ->
        {
            if (schedulerTask != null)
            {
                schedulerTask.cancel(false);
                schedulerTask = null;
            }

            registeredEntries.clear();
            disposed = true;
        });
    }
}
