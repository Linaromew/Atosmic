/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

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
package scripting.event;

import client.Character;
import client.Skill;
import client.SkillFactory;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import net.server.coordinator.world.EventRecallCoordinator;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.AbstractPlayerInteraction;
import scripting.event.scheduler.EventScriptScheduler;
import server.ItemInformationProvider;
import server.Scheduler;
import server.StatEffect;
import server.expeditions.Expedition;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.NPC;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;
import tools.PacketCreator;
import tools.Pair;

import javax.script.ScriptException;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * @author Matze
 * @author Ronan
 */
public class EventInstanceManager {
    private static final Logger log = LoggerFactory.getLogger(EventInstanceManager.class);
    private final Map<Integer, Character> chars = new HashMap<>();
    private int leaderId = -1;
    private final List<Monster> mobs = new LinkedList<>();
    private final Map<Character, Integer> killCount = new HashMap<>();
    private EventManager em;
    private EventScriptScheduler ess;
    private MapManager mapManager;
    private String name;
    private final Properties props = new Properties();
    private final Map<String, Object> objectProps = new HashMap<>();
    private long timeStarted = 0;
    private long eventTime = 0;
    private Expedition expedition = null;
    private final List<Integer> mapIds = new LinkedList<>();
    private final Lock readLock;
    private final Lock writeLock;

    private ScheduledFuture<?> event_schedule = null;
    private boolean disposed = false;
    private boolean eventCleared = false;
    private boolean eventStarted = false;

    // multi-leveled PQ rewards!
    private final Map<Integer, List<Integer>> collectionSet = new HashMap<>(YamlConfig.config.server.MAX_EVENT_LEVELS);
    private final Map<Integer, List<Integer>> collectionQty = new HashMap<>(YamlConfig.config.server.MAX_EVENT_LEVELS);
    private final Map<Integer, Integer> collectionExp = new HashMap<>(YamlConfig.config.server.MAX_EVENT_LEVELS);

    // Exp/Meso rewards by CLEAR on a stage
    private final List<Integer> onMapClearExp = new ArrayList<>();
    private final List<Integer> onMapClearMeso = new ArrayList<>();

    // registers player status on an event (null on this Map structure equals to 0)
    private final Map<Integer, Integer> playerGrid = new HashMap<>();

    // registers all opened gates on the event. Will help late characters to encounter next stages gates already opened
    private final Map<Integer, Pair<String, Integer>> openedGates = new HashMap<>();

    // forces deletion of items not supposed to be held outside of the event, dealt on a player's leaving moment.
    private final Set<Integer> exclusiveItems = new HashSet<>();

    public EventInstanceManager(EventManager em, String name) {
        this.em = em;
        this.name = name;
        this.ess = new EventScriptScheduler();
        this.mapManager = new MapManager(this, em.getWorldServer().getId(), em.getChannelServer().getId());

        ReadWriteLock readWriteLock = new ReentrantReadWriteLock(true);
        this.readLock = readWriteLock.readLock();
        this.writeLock = readWriteLock.writeLock();
    }

    public void setName(String name) {
        this.name = name;
    }

    public EventManager getEm() {
        return em;
    }

    public int getEventPlayersJobs() {
        //Bits -> 0: BEGINNER 1: WARRIOR 2: MAGICIAN
        //        3: BOWMAN 4: THIEF 5: PIRATE

        int mask = 0;
        for (Character chr : getPlayers()) {
            mask |= (1 << chr.getJob().getJobNiche());
        }

        return mask;
    }

    public void applyEventPlayersItemBuff(int itemId) {
        List<Character> players = getPlayerList();
        StatEffect mse = ItemInformationProvider.getInstance().getItemEffect(itemId);

        if (mse != null) {
            for (Character player : players) {
                mse.applyTo(player);
            }
        }
    }

    public void applyEventPlayersSkillBuff(int skillId) {
        applyEventPlayersSkillBuff(skillId, Integer.MAX_VALUE);
    }

    public void applyEventPlayersSkillBuff(int skillId, int skillLv) {
        List<Character> players = getPlayerList();
        Skill skill = SkillFactory.getSkill(skillId);

        if (skill != null) {
            StatEffect mse = skill.getEffect(Math.min(skillLv, skill.getMaxLevel()));
            if (mse != null) {
                for (Character player : players) {
                    mse.applyTo(player);
                }
            }
        }
    }

    public void giveEventPlayersExp(int gain) {
        giveEventPlayersExp(gain, -1);
    }

    public void giveEventPlayersExp(int gain, int mapId) {
        if (gain == 0) {
            return;
        }

        List<Character> players = getPlayerList();

        if (mapId == -1) {
            for (Character mc : players) {
                mc.gainExp(gain * mc.getExpRate(), true, true);
            }
        } else {
            for (Character mc : players) {
                if (mc.getMapId() == mapId) {
                    mc.gainExp(gain * mc.getExpRate(), true, true);
                }
            }
        }
    }

    public void giveEventPlayersMeso(int gain) {
        giveEventPlayersMeso(gain, -1);
    }

    public void giveEventPlayersMeso(int gain, int mapId) {
        if (gain == 0) {
            return;
        }

        List<Character> players = getPlayerList();

        if (mapId == -1) {
            for (Character mc : players) {
                mc.gainMeso(gain * mc.getMesoRate());
            }
        } else {
            for (Character mc : players) {
                if (mc.getMapId() == mapId) {
                    mc.gainMeso(gain * mc.getMesoRate());
                }
            }
        }

    }

    public Object invokeScriptFunction(String name, Object... args) throws ScriptException, NoSuchMethodException {
        if (!disposed) {
            return em.getIv().invokeFunction(name, args);
        } else {
            return null;
        }
    }

    public synchronized void registerPlayer(final Character chr) {
        registerPlayer(chr, true);
    }

    public synchronized void registerPlayer(final Character chr, boolean runEntryScript) {
        if (chr == null || !chr.isLoggedinWorld() || disposed) {
            return;
        }

        if (chars.containsKey(chr.getId())) {
            return;
        }

        chars.put(chr.getId(), chr);
        chr.setEventInstance(this);

        if (runEntryScript) {
            try {
                invokeScriptFunction("playerEntry", EventInstanceManager.this, chr);
            } catch (ScriptException | NoSuchMethodException ex) {
                ex.printStackTrace();
            }
        }
    }

    public void exitPlayer(final Character chr) {
        if (chr == null || !chr.isLoggedin()) {
            return;
        }

        unregisterPlayer(chr);

        try {
            invokeScriptFunction("playerExit", EventInstanceManager.this, chr);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }
    }

    public void dropMessage(int type, String message) {
        for (Character chr : getPlayers()) {
            chr.dropMessage(type, message);
        }
    }

    public void restartEventTimer(long time) {
        stopEventTimer();
        startEventTimer(time);
    }

    public void startEventTimer(long time) {
        timeStarted = System.currentTimeMillis();
        eventTime = time;

        for (Character chr : getPlayers()) {
            chr.sendPacket(PacketCreator.getClock((int) (time / 1000)));
        }

        event_schedule = Scheduler.getInstance().schedule(() -> {
            dismissEventTimer();

            try {
                invokeScriptFunction("scheduledTimeout", EventInstanceManager.this);
            } catch (ScriptException | NoSuchMethodException ex) {
                log.error("Event script {} does not implement the scheduledTimeout function", em.getName(), ex);
            }
        }, time);
    }

    public void addEventTimer(long time) {
        if (event_schedule != null) {
            if (event_schedule.cancel(false)) {
                long nextTime = getTimeLeft() + time;
                eventTime += time;

                event_schedule = Scheduler.getInstance().schedule(() -> {
                    dismissEventTimer();

                    try {
                        invokeScriptFunction("scheduledTimeout", EventInstanceManager.this);
                    } catch (ScriptException | NoSuchMethodException ex) {
                        log.error("Event script {} does not implement the scheduledTimeout function", em.getName(), ex);
                    }
                }, nextTime);
            }
        } else {
            startEventTimer(time);
        }
    }

    private void dismissEventTimer() {
        for (Character chr : getPlayers()) {
            chr.sendPacket(PacketCreator.removeClock());
        }

        event_schedule = null;
        eventTime = 0;
        timeStarted = 0;
    }

    public void stopEventTimer() {
        if (event_schedule != null) {
            event_schedule.cancel(false);
            event_schedule = null;
        }

        dismissEventTimer();
    }

    public boolean isTimerStarted() {
        return eventTime > 0 && timeStarted > 0;
    }

    public long getTimeLeft() {
        return eventTime - (System.currentTimeMillis() - timeStarted);
    }

    public void registerParty(Character chr) {
        if (chr.isPartyLeader()) {
            registerParty(chr.getParty(), chr.getMap());
        }
    }

    public void registerParty(Party party, MapleMap map) {
        for (PartyCharacter mpc : party.getEligibleMembers()) {
            if (mpc.isOnline()) {   // thanks resinate
                Character chr = map.getCharacterById(mpc.getId());
                if (chr != null) {
                    registerPlayer(chr);
                }
            }
        }
    }

    public void registerExpedition(Expedition exped) {
        expedition = exped;
        registerExpeditionTeam(exped, exped.getRecruitingMap().getId());
    }

    private void registerExpeditionTeam(Expedition exped, int recruitMap) {
        expedition = exped;

        for (Character chr : exped.getActiveMembers()) {
            if (chr.getMapId() == recruitMap) {
                registerPlayer(chr);
            }
        }
    }

    public void unregisterPlayer(final Character chr) {
        try {
            invokeScriptFunction("playerUnregistered", EventInstanceManager.this, chr);
        } catch (ScriptException | NoSuchMethodException ex) {
            log.error("Event script {} does not implement the playerUnregistered function", em.getName(), ex);
        }

        chars.remove(chr.getId());
        chr.setEventInstance(null);

        gridRemove(chr);
        dropExclusiveItems(chr);
    }

    public int getPlayerCount() {
        return chars.size();
    }

    public Character getPlayerById(int id) {
        return chars.get(id);
    }

    public List<Character> getPlayers() {
        return new ArrayList<>(chars.values());
    }

    private List<Character> getPlayerList() {
        return new LinkedList<>(chars.values());
    }

    public void registerMonster(Monster mob) {
        if (!mob.getStats().isFriendly()) { //We cannot register moon bunny
            mobs.add(mob);
        }
    }

    public void movePlayer(final Character chr) {
        try {
            invokeScriptFunction("moveMap", EventInstanceManager.this, chr);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }
    }

    public void changedMap(final Character chr, final int mapId) {
        try {
            invokeScriptFunction("changedMap", EventInstanceManager.this, chr, mapId);
        } catch (ScriptException | NoSuchMethodException ex) {
        } // optional
    }

    public void afterChangedMap(final Character chr, final int mapId) {
        try {
            invokeScriptFunction("afterChangedMap", EventInstanceManager.this, chr, mapId);
        } catch (ScriptException | NoSuchMethodException ex) {
        } // optional
    }

    public synchronized void changedLeader(final PartyCharacter ldr) {
        try {
            invokeScriptFunction("changedLeader", EventInstanceManager.this, ldr);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }

        leaderId = ldr.getId();
    }

    public void monsterKilled(final Monster mob, final boolean hasKiller) {
        int scriptResult = 0;

        mobs.remove(mob);

        if (eventStarted) {
            scriptResult = 1;

            if (mobs.isEmpty()) {
                scriptResult = 2;
            }
        }

        if (scriptResult > 0) {
            try {
                invokeScriptFunction("monsterKilled", mob, EventInstanceManager.this, hasKiller);
            } catch (ScriptException | NoSuchMethodException ex) {
                ex.printStackTrace();
            }

            if (scriptResult > 1) {
                try {
                    invokeScriptFunction("allMonstersDead", EventInstanceManager.this, hasKiller);
                } catch (ScriptException | NoSuchMethodException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    public void friendlyKilled(final Monster mob, final boolean hasKiller) {
        try {
            invokeScriptFunction("friendlyKilled", mob, EventInstanceManager.this, hasKiller);
        } catch (ScriptException | NoSuchMethodException ex) {
        } //optional
    }

    public void friendlyDamaged(final Monster mob) {
        try {
            invokeScriptFunction("friendlyDamaged", EventInstanceManager.this, mob);
        } catch (ScriptException | NoSuchMethodException ex) {
        } // optional
    }

    public void friendlyItemDrop(final Monster mob) {
        try {
            invokeScriptFunction("friendlyItemDrop", EventInstanceManager.this, mob);
        } catch (ScriptException | NoSuchMethodException ex) {
        } // optional
    }

    public void playerKilled(final Character chr) {
        Scheduler.getInstance().execute(() -> {
            try {
                invokeScriptFunction("playerDead", EventInstanceManager.this, chr);
            } catch (ScriptException | NoSuchMethodException ex) {
            } // optional
        });
    }

    public void reviveMonster(final Monster mob) {
        try {
            invokeScriptFunction("monsterRevive", EventInstanceManager.this, mob);
        } catch (ScriptException | NoSuchMethodException ex) {
        } // optional
    }

    public boolean revivePlayer(final Character chr) {
        try {
            Object b = invokeScriptFunction("playerRevive", EventInstanceManager.this, chr);
            if (b instanceof Boolean) {
                return (Boolean) b;
            }
        } catch (ScriptException | NoSuchMethodException ex) {
        } // optional

        return true;
    }

    public void playerDisconnected(final Character chr) {
        try {
            invokeScriptFunction("playerDisconnected", EventInstanceManager.this, chr);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }

        EventRecallCoordinator.getInstance().storeEventInstance(chr.getId(), this);
    }

    public void monsterKilled(Character chr, final Monster mob) {
        try {
            final int inc = (int) invokeScriptFunction("monsterValue", EventInstanceManager.this, mob.getId());

            if (inc != 0) {
                Integer kc = killCount.get(chr);
                if (kc == null) {
                    kc = inc;
                } else {
                    kc += inc;
                }
                killCount.put(chr, kc);
                if (expedition != null) {
                    expedition.monsterKilled(chr, mob);
                }
            }
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }
    }

    public int getKillCount(Character chr) {
        Integer kc = killCount.get(chr);
        return (kc == null) ? 0 : kc;
    }

    public void dispose() {
        for (Character chr : chars.values()) {
            chr.setEventInstance(null);
        }

        dispose(false);
    }

    public synchronized void dispose(boolean shutdown) {    // should not trigger any event script method after disposed
        if (disposed) {
            return;
        }

        try {
            invokeScriptFunction("dispose", EventInstanceManager.this);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }
        disposed = true;

        ess.dispose();

        for (Character chr : chars.values()) {
            chr.setEventInstance(null);
        }
        chars.clear();
        mobs.clear();
        ess = null;

        if (event_schedule != null) {
            event_schedule.cancel(false);
            event_schedule = null;
        }

        killCount.clear();
        mapIds.clear();
        props.clear();
        objectProps.clear();

        disposeExpedition();

        if (!eventCleared) {
            em.disposeInstance(name);
        }

        Scheduler.getInstance().schedule(() -> {
            mapManager.dispose();   // issues from instantly disposing some event objects found thanks to MedicOP
            mapManager = null;
            em = null;
        }, MINUTES.toMillis(1));
    }

    public MapManager getMapFactory() {
        return mapManager;
    }

    public void schedule(final String methodName, long delay) {
        if (ess != null) {
            Runnable r = () -> {
                try {
                    invokeScriptFunction(methodName, EventInstanceManager.this);
                } catch (ScriptException | NoSuchMethodException ex) {
                    ex.printStackTrace();
                }
            };

            ess.registerEntry(r, delay);
        }
    }

    public String getName() {
        return name;
    }

    public MapleMap getMapInstance(int mapId) {
        MapleMap map = mapManager.getMap(mapId);
        map.setEventInstance(this);

        if (!mapManager.isMapLoaded(mapId)) {
            if (em.getProperty("shuffleReactors") != null && em.getProperty("shuffleReactors").equals("true")) {
                map.shuffleReactors();
            }
        }
        return map;
    }

    public void setIntProperty(String key, Integer value) {
        setProperty(key, value);
    }

    public void setProperty(String key, Integer value) {
        setProperty(key, "" + value);
    }

    public void setProperty(String key, String value) {
        props.setProperty(key, value);
    }

    public Object setProperty(String key, String value, boolean prev) {
        return props.setProperty(key, value);
    }

    public void setObjectProperty(String key, Object obj) {
        objectProps.put(key, obj);
    }

    public String getProperty(String key) {
        return props.getProperty(key);
    }

    public int getIntProperty(String key) {
        return Integer.parseInt(props.getProperty(key));
    }

    public Object getObjectProperty(String key) {
        return objectProps.get(key);
    }

    public void leftParty(final Character chr) {
        try {
            invokeScriptFunction("leftParty", EventInstanceManager.this, chr);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }
    }

    public void disbandParty() {
        try {
            invokeScriptFunction("disbandParty", EventInstanceManager.this);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }
    }

    public void clearPQ() {
        try {
            invokeScriptFunction("clearPQ", EventInstanceManager.this);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }
    }

    public void removePlayer(final Character chr) {
        try {
            invokeScriptFunction("playerExit", EventInstanceManager.this, chr);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }
    }

    public boolean isLeader(Character chr) {
        return (chr.getParty().getLeaderId() == chr.getId());
    }

    public boolean isEventLeader(Character chr) {
        return (chr.getId() == getLeaderId());
    }

    public final MapleMap getInstanceMap(final int mapid) {
        if (disposed) {
            return null;
        }
        mapIds.add(mapid);
        return getMapFactory().getMap(mapid);
    }

    public final boolean disposeIfPlayerBelow(final byte size, final int towarp) {
        if (disposed) {
            return true;
        }
        if (chars == null) {
            return false;
        }

        MapleMap map = null;
        if (towarp > 0) {
            map = this.getMapFactory().getMap(towarp);
        }

        List<Character> players = getPlayerList();

        try {
            if (players.size() < size) {
                for (Character chr : players) {
                    if (chr == null) {
                        continue;
                    }

                    unregisterPlayer(chr);
                    if (towarp > 0) {
                        chr.changeMap(map, map.getPortal(0));
                    }
                }

                dispose();
                return true;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return false;
    }

    public void spawnNpc(int npcId, Point pos, MapleMap map) {
        NPC npc = LifeFactory.getNPC(npcId);
        if (npc != null) {
            npc.setPosition(pos);
            npc.setCy(pos.y);
            npc.setRx0(pos.x + 50);
            npc.setRx1(pos.x - 50);
            npc.setFh(map.getFootholds().findBelow(pos).getId());
            map.addMapObject(npc);
            map.broadcastMessage(PacketCreator.spawnNPC(npc));
        }
    }

    public void dispatchRaiseQuestMobCount(int mobid, int mapid) {
        Map<Integer, Character> mapChars = getInstanceMap(mapid).getMapPlayers();
        if (!mapChars.isEmpty()) {
            List<Character> eventMembers = getPlayers();

            for (Character evChr : eventMembers) {
                Character chr = mapChars.get(evChr.getId());

                if (chr != null && chr.isLoggedinWorld()) {
                    chr.raiseQuestMobCount(mobid);
                }
            }
        }
    }

    public Monster getMonster(int mid) {
        return (LifeFactory.getMonster(mid));
    }

    private List<Integer> convertToIntegerList(List<Object> objects) {
        List<Integer> intList = new ArrayList<>();

        for (Object object : objects) {
            intList.add((Integer) object);
        }

        return intList;
    }

    public void setEventClearStageExp(List<Object> gain) {
        onMapClearExp.clear();
        onMapClearExp.addAll(convertToIntegerList(gain));
    }

    public void setEventClearStageMeso(List<Object> gain) {
        onMapClearMeso.clear();
        onMapClearMeso.addAll(convertToIntegerList(gain));
    }

    public Integer getClearStageExp(int stage) {    //stage counts from ONE.
        if (stage > onMapClearExp.size()) {
            return 0;
        }
        return onMapClearExp.get(stage - 1);
    }

    public Integer getClearStageMeso(int stage) {   //stage counts from ONE.
        if (stage > onMapClearMeso.size()) {
            return 0;
        }
        return onMapClearMeso.get(stage - 1);
    }

    public List<Integer> getClearStageBonus(int stage) {
        List<Integer> list = new ArrayList<>();
        list.add(getClearStageExp(stage));
        list.add(getClearStageMeso(stage));

        return list;
    }

    private void dropExclusiveItems(Character chr) {
        AbstractPlayerInteraction api = chr.getAbstractPlayerInteraction();

        for (Integer item : exclusiveItems) {
            api.removeAll(item);
        }
    }

    public final void setExclusiveItems(List<Object> items) {
        List<Integer> exclusive = convertToIntegerList(items);

        exclusiveItems.addAll(exclusive);
    }

    public final void setEventRewards(List<Object> rwds, List<Object> qtys, int expGiven) {
        setEventRewards(1, rwds, qtys, expGiven);
    }

    public final void setEventRewards(List<Object> rwds, List<Object> qtys) {
        setEventRewards(1, rwds, qtys);
    }

    public final void setEventRewards(int eventLevel, List<Object> rwds, List<Object> qtys) {
        setEventRewards(eventLevel, rwds, qtys, 0);
    }

    public final void setEventRewards(int eventLevel, List<Object> rwds, List<Object> qtys, int expGiven) {
        // fixed EXP will be rewarded at the same time the random item is given

        if (eventLevel <= 0 || eventLevel > YamlConfig.config.server.MAX_EVENT_LEVELS) {
            return;
        }
        eventLevel--;    //event level starts from 1

        List<Integer> rewardIds = convertToIntegerList(rwds);
        List<Integer> rewardQtys = convertToIntegerList(qtys);

        //rewardsSet and rewardsQty hold temporary values
        collectionSet.put(eventLevel, rewardIds);
        collectionQty.put(eventLevel, rewardQtys);
        collectionExp.put(eventLevel, expGiven);
    }

    private byte getRewardListRequirements(int level) {
        if (level >= collectionSet.size()) {
            return 0;
        }

        byte rewardTypes = 0;
        List<Integer> list = collectionSet.get(level);

        for (Integer itemId : list) {
            rewardTypes |= (1 << ItemConstants.getInventoryType(itemId).getType());
        }

        return rewardTypes;
    }

    private boolean hasRewardSlot(Character player, int eventLevel) {
        byte listReq = getRewardListRequirements(eventLevel);   //gets all types of items present in the event reward list

        //iterating over all valid inventory types
        for (byte type = 1; type <= 5; type++) {
            if ((listReq >> type) % 2 == 1 && !player.hasEmptySlot(type)) {
                return false;
            }
        }

        return true;
    }

    public final boolean giveEventReward(Character player) {
        return giveEventReward(player, 1);
    }

    //gives out EXP & a random item in a similar fashion of when clearing KPQ, LPQ, etc.
    public final boolean giveEventReward(Character player, int eventLevel) {
        List<Integer> rewardsSet, rewardsQty;
        Integer rewardExp;

        eventLevel--;       //event level starts counting from 1
        if (eventLevel >= collectionSet.size()) {
            return true;
        }

        rewardsSet = collectionSet.get(eventLevel);
        rewardsQty = collectionQty.get(eventLevel);

        rewardExp = collectionExp.get(eventLevel);

        if (rewardExp == null) {
            rewardExp = 0;
        }

        if (rewardsSet == null || rewardsSet.isEmpty()) {
            if (rewardExp > 0) {
                player.gainExp(rewardExp);
            }
            return true;
        }

        if (!hasRewardSlot(player, eventLevel)) {
            return false;
        }

        AbstractPlayerInteraction api = player.getAbstractPlayerInteraction();
        int rnd = (int) Math.floor(Math.random() * rewardsSet.size());

        api.gainItem(rewardsSet.get(rnd), rewardsQty.get(rnd).shortValue());
        if (rewardExp > 0) {
            player.gainExp(rewardExp);
        }
        return true;
    }

    private void disposeExpedition() {
        if (expedition != null) {
            expedition.dispose(eventCleared);

            expedition.removeChannelExpedition(em.getChannelServer());

            expedition = null;
        }
    }

    public final synchronized void startEvent() {
        eventStarted = true;

        try {
            invokeScriptFunction("afterSetup", EventInstanceManager.this);
        } catch (ScriptException | NoSuchMethodException ex) {
            ex.printStackTrace();
        }
    }

    public final void setEventCleared() {
        eventCleared = true;

        for (Character chr : getPlayers()) {
            chr.awardQuestPoint(YamlConfig.config.server.QUEST_POINT_PER_EVENT_CLEAR);
        }

        em.disposeInstance(name);

        disposeExpedition();
    }

    public final boolean isEventCleared() {
        return eventCleared;
    }

    public final boolean isEventDisposed() {
        return disposed;
    }

    private boolean isEventTeamLeaderOn() {
        for (Character chr : getPlayers()) {
            if (chr.getId() == getLeaderId()) {
                return true;
            }
        }

        return false;
    }

    public final boolean checkEventTeamLacking(boolean leavingEventMap, int minPlayers) {
        if (eventCleared && getPlayerCount() > 1) {
            return false;
        }

        if (!eventCleared && leavingEventMap && !isEventTeamLeaderOn()) {
            return true;
        }
        return getPlayerCount() < minPlayers;
    }

    public final boolean isExpeditionTeamLackingNow(boolean leavingEventMap, int minPlayers, Character quitter) {
        if (eventCleared) {
            return leavingEventMap && getPlayerCount() <= 1;
        } else {
            // thanks Conrad for noticing expeditions don't need to have neither the leader nor meet the minimum requirement inside the event
            return getPlayerCount() <= 1;
        }
    }

    public final boolean isEventTeamLackingNow(boolean leavingEventMap, int minPlayers, Character quitter) {
        if (eventCleared) {
            return leavingEventMap && getPlayerCount() <= 1;
        } else {
            if (leavingEventMap && getLeaderId() == quitter.getId()) {
                return true;
            }
            return getPlayerCount() <= minPlayers;
        }
    }

    public final boolean isEventTeamTogether() {
        if (chars.size() <= 1) {
            return true;
        }

        Iterator<Character> iterator = chars.values().iterator();
        Character mc = iterator.next();
        int mapId = mc.getMapId();

        for (; iterator.hasNext(); ) {
            mc = iterator.next();
            if (mc.getMapId() != mapId) {
                return false;
            }
        }

        return true;
    }

    public final void warpEventTeam(int warpFrom, int warpTo) {
        List<Character> players = getPlayerList();

        for (Character chr : players) {
            if (chr.getMapId() == warpFrom) {
                chr.changeMap(warpTo);
            }
        }
    }

    public final void warpEventTeam(int warpTo) {
        List<Character> players = getPlayerList();

        for (Character chr : players) {
            chr.changeMap(warpTo);
        }
    }

    public final void warpEventTeamToMapSpawnPoint(int warpFrom, int warpTo, int toSp) {
        List<Character> players = getPlayerList();

        for (Character chr : players) {
            if (chr.getMapId() == warpFrom) {
                chr.changeMap(warpTo, toSp);
            }
        }
    }

    public final void warpEventTeamToMapSpawnPoint(int warpTo, int toSp) {
        List<Character> players = getPlayerList();

        for (Character chr : players) {
            chr.changeMap(warpTo, toSp);
        }
    }

    public final int getLeaderId() {
        return leaderId;
    }

    public Character getLeader() {
        return chars.get(leaderId);
    }

    public final void setLeader(Character chr) {
        leaderId = chr.getId();
    }

    public final void showWrongEffect() {
        showWrongEffect(getLeader().getMapId());
    }

    public final void showWrongEffect(int mapId) {
        MapleMap map = getMapInstance(mapId);
        map.broadcastMessage(PacketCreator.showEffect("quest/party/wrong_kor"));
        map.broadcastMessage(PacketCreator.playSound("Party1/Failed"));
    }

    public final void showClearEffect() {
        showClearEffect(false);
    }

    public final void showClearEffect(boolean hasGate) {
        Character leader = getLeader();
        if (leader != null) {
            showClearEffect(hasGate, leader.getMapId());
        }
    }

    public final void showClearEffect(int mapId) {
        showClearEffect(false, mapId);
    }

    public final void showClearEffect(boolean hasGate, int mapId) {
        showClearEffect(hasGate, mapId, "gate", 2);
    }

    public final void showClearEffect(int mapId, String mapObj, int newState) {
        showClearEffect(true, mapId, mapObj, newState);
    }

    public final void showClearEffect(boolean hasGate, int mapId, String mapObj, int newState) {
        MapleMap map = getMapInstance(mapId);
        map.broadcastMessage(PacketCreator.showEffect("quest/party/clear"));
        map.broadcastMessage(PacketCreator.playSound("Party1/Clear"));
        if (hasGate) {
            map.broadcastMessage(PacketCreator.environmentChange(mapObj, newState));
            openedGates.put(map.getId(), new Pair<>(mapObj, newState));
        }
    }

    public final void recoverOpenedGate(Character chr, int thisMapId) {
        Pair<String, Integer> gateData = null;

        if (openedGates.containsKey(thisMapId)) {
            gateData = openedGates.get(thisMapId);
        }

        if (gateData != null) {
            chr.sendPacket(PacketCreator.environmentChange(gateData.getLeft(), gateData.getRight()));
        }
    }

    public final void giveEventPlayersStageReward(int thisStage) {
        List<Integer> list = getClearStageBonus(thisStage);     // will give bonus exp & mesos to everyone in the event
        giveEventPlayersExp(list.get(0));
        giveEventPlayersMeso(list.get(1));
    }

    public final void linkToNextStage(int thisStage, String eventFamily, int thisMapId) {
        giveEventPlayersStageReward(thisStage);
        thisStage--;    //stages counts from ONE, scripts from ZERO

        MapleMap nextStage = getMapInstance(thisMapId);
        Portal portal = nextStage.getPortal("next00");
        if (portal != null) {
            portal.setScriptName(eventFamily + thisStage);
        }
    }

    public final void linkPortalToScript(int thisStage, String portalName, String scriptName, int thisMapId) {
        giveEventPlayersStageReward(thisStage);
        thisStage--;    //stages counts from ONE, scripts from ZERO

        MapleMap nextStage = getMapInstance(thisMapId);
        Portal portal = nextStage.getPortal(portalName);
        if (portal != null) {
            portal.setScriptName(scriptName);
        }
    }

    // registers a player status in an event
    public final void gridInsert(Character chr, int newStatus) {
        playerGrid.put(chr.getId(), newStatus);
    }

    // unregisters a player status in an event
    public final void gridRemove(Character chr) {
        playerGrid.remove(chr.getId());
    }

    // checks a player status
    public final int gridCheck(Character chr) {
        Integer i = playerGrid.get(chr.getId());
        return (i != null) ? i : -1;
    }

    public final int gridSize() {
        return playerGrid.size();
    }

    public final void gridClear() {
        playerGrid.clear();
    }

    public boolean activatedAllReactorsOnMap(int mapId, int minReactorId, int maxReactorId) {
        return activatedAllReactorsOnMap(this.getMapInstance(mapId), minReactorId, maxReactorId);
    }

    public boolean activatedAllReactorsOnMap(MapleMap map, int minReactorId, int maxReactorId) {
        if (map == null) {
            return true;
        }

        for (Reactor mr : map.getReactorsByIdRange(minReactorId, maxReactorId)) {
            if (mr.getReactorType() != -1) {
                return false;
            }
        }

        return true;
    }
}
