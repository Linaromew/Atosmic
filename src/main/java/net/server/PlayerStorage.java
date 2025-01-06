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
package net.server;

import client.Character;
import client.Client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PlayerStorage {
    private final Map<Integer, Character> storage = new LinkedHashMap<>();
    private final Map<String, Character> nameStorage = new LinkedHashMap<>();

    public PlayerStorage() {
    }

    public void addPlayer(Character chr) {
        storage.put(chr.getId(), chr);
        nameStorage.put(chr.getName().toLowerCase(), chr);
    }

    public Character removePlayer(int chr) {
        Character mc = storage.remove(chr);
        if (mc != null) {
            nameStorage.remove(mc.getName().toLowerCase());
        }

        return mc;
    }

    public Character getCharacterByName(String name) {
        return nameStorage.get(name.toLowerCase());
    }

    public Character getCharacterById(int id) {
        return storage.get(id);
    }

    public Collection<Character> getAllCharacters() {
        return new ArrayList<>(storage.values());
    }

    public final void disconnectAll() {
        List<Character> chrList;
        chrList = new ArrayList<>(storage.values());

        for (Character mc : chrList) {
            Client client = mc.getClient();
            if (client != null) {
                client.forceDisconnect();
            }
        }

        storage.clear();
    }

    public int getSize() {
        return storage.size();
    }
}
