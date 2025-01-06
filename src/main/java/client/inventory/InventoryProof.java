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
package client.inventory;

import client.Character;

/**
 * @author Ronan
 */
public class InventoryProof extends Inventory {

    public InventoryProof(Character mc) {
        super(mc, InventoryType.CANHOLD, (byte) 0);
    }

    public void cloneContents(Inventory inv) {

            inventory.clear();
            this.setSlotLimit(inv.getSlotLimit());

            for (Item it : inv.list()) {
                Item item = new Item(it.getItemId(), it.getPosition(), it.getQuantity());
                inventory.put(item.getPosition(), item);
            }

    }

    public void flushContents() {
        inventory.clear();
    }

    @Override
    protected short addSlot(Item item) {
        if (item == null) {
            return -1;
        }

        short slotId = getNextFreeSlot();
        if (slotId < 0) {
            return -1;
        }
        inventory.put(slotId, item);

        return slotId;
    }

    @Override
    protected void addSlotFromDB(short slot, Item item) {
        inventory.put(slot, item);
    }

    @Override
    public void removeSlot(short slot) {
        inventory.remove(slot);
    }
}
