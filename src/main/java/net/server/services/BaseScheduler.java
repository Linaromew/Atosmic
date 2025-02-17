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
package net.server.services;

import config.YamlConfig;
import net.server.Server;
import server.Scheduler;
import tools.Pair;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;

/**
 * @author Ronan
 */
public abstract class BaseScheduler {
    private int idleProcs = 0;
    private final List<SchedulerListener> listeners = new LinkedList<>();
    private final Map<Object, Pair<Runnable, Long>> registeredEntries = new HashMap<>();

    private ScheduledFuture<?> schedulerTask = null;
    private final Runnable monitorTask = () -> runBaseSchedule();

    protected BaseScheduler() {
    }

    protected void addListener(SchedulerListener listener) {
        listeners.add(listener);
    }

    private void runBaseSchedule() {
        List<Object> toRemove;
        Map<Object, Pair<Runnable, Long>> registeredEntriesCopy;

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
        registeredEntriesCopy = new HashMap<>(registeredEntries);

        long timeNow = Server.getInstance().getCurrentTime();
        toRemove = new LinkedList<>();
        for (Entry<Object, Pair<Runnable, Long>> rmd : registeredEntriesCopy.entrySet()) {
            Pair<Runnable, Long> r = rmd.getValue();

            if (r.getRight() < timeNow) {
                r.getLeft().run();  // runs the scheduled action
                toRemove.add(rmd.getKey());
            }
        }

        if (!toRemove.isEmpty()) {
            for (Object o : toRemove) {
                registeredEntries.remove(o);
            }
        }

        dispatchRemovedEntries(toRemove, true);
    }

    protected void registerEntry(Object key, Runnable removalAction, long duration) {
        idleProcs = 0;
        if (schedulerTask == null) {
            schedulerTask = Scheduler.getInstance().register(monitorTask, YamlConfig.config.server.MOB_STATUS_MONITOR_PROC, YamlConfig.config.server.MOB_STATUS_MONITOR_PROC);
        }

        registeredEntries.put(key, new Pair<>(removalAction, Server.getInstance().getCurrentTime() + duration));
    }

    protected void interruptEntry(Object key) {
        Runnable toRun = null;

        Pair<Runnable, Long> rm = registeredEntries.remove(key);
        if (rm != null) {
            toRun = rm.getLeft();
        }

        if (toRun != null) {
            toRun.run();
        }

        dispatchRemovedEntries(Collections.singletonList(key), false);
    }

    private void dispatchRemovedEntries(List<Object> toRemove, boolean fromUpdate) {
        for (SchedulerListener listener : listeners.toArray(new SchedulerListener[listeners.size()])) {
            listener.removedScheduledEntries(toRemove, fromUpdate);
        }
    }

    public void dispose() {
        if (schedulerTask != null) {
            schedulerTask.cancel(false);
            schedulerTask = null;
        }

        listeners.clear();
        registeredEntries.clear();
    }
}
