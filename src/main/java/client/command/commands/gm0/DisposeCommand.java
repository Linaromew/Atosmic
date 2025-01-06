/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
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

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm0;

import java.awt.*;
import client.Character;
import client.Client;
import client.Job;
import client.SkinColor;
import client.command.Command;
import client.creator.CharacterFactory;
import client.creator.CharacterFactoryRecipe;
import scripting.npc.NPCScriptManager;
import scripting.quest.QuestScriptManager;
import server.maps.AbstractAnimatedMapObject;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.util.Random;

import static tools.Randomizer.rand;

public class DisposeCommand extends Command {
    {
        setDescription("Dispose to fix NPC chat.");
    }

    @Override
    public void execute(Client c, String[] params) {
        NPCScriptManager.getInstance().dispose(c);
        QuestScriptManager.getInstance().dispose(c);
        c.sendPacket(PacketCreator.enableActions());
        c.removeClickedNPC();
        c.getPlayer().message("You've been disposed.");
    }
}

