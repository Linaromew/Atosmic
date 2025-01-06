package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.Job;
import client.SkinColor;
import client.creator.CharacterFactory;
import client.creator.CharacterFactoryRecipe;

import java.util.Random;

public class FakeCharacterCreator extends CharacterFactory
{
    private static CharacterFactoryRecipe createRecipe(Job job, int level, int map, int top, int bottom, int shoes, int weapon)
    {
        CharacterFactoryRecipe recipe = new CharacterFactoryRecipe(job, level, map, top, bottom, shoes, weapon);
        return recipe;
    }
    public static Character createFakeCharacter(Client client, String name, int mapId)
    {
        Character newCharacter = Character.getDefault(client);

        newCharacter.setWorld(client.getWorld());
        newCharacter.setSkinColor(SkinColor.getById(randomSkin()));
        newCharacter.setGender(0);
        newCharacter.setName(name);
        newCharacter.setHair(randomHair());
        newCharacter.setFace(randomFace());

        newCharacter.setLevel(1);
        newCharacter.setJob(Job.getById(0));
        newCharacter.setMapId(100000000);

        return newCharacter;
    }

    private static int randomFace() {
        Random rand = new Random();
        int[] faces = {20000, 20001, 20002}; // Example face IDs
        return faces[rand.nextInt(faces.length)];
    }

    private static int randomHair() {
        Random rand = new Random();
        int[] hairs = {30000, 30010, 30020}; // Example hair IDs
        return hairs[rand.nextInt(hairs.length)];
    }

    private static int randomSkin() {
        Random rand = new Random();
        int[] skins = {0, 1, 2}; // Example skin color IDs
        return skins[rand.nextInt(skins.length)];
    }
}
