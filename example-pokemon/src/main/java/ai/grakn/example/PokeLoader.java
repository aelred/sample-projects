/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License:
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.example;

import ai.grakn.GraknGraph;
import ai.grakn.concept.Entity;
import ai.grakn.concept.EntityType;
import ai.grakn.concept.Instance;
import ai.grakn.concept.RelationType;
import ai.grakn.concept.Resource;
import ai.grakn.concept.ResourceType;
import ai.grakn.concept.RoleType;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Loads the pokemons
 * Big thanks to https://pokeapi.co/ for the data
 */
public class PokeLoader {
    private String pokemonDir;
    private GraknGraph graknGraph;


    public PokeLoader(GraknGraph graknGraph, String pokemonDir){
        this.graknGraph = graknGraph;
        this.pokemonDir = pokemonDir;
    }

    /**
     * Creates all the pokemon listed in pokemon.json
     */
    public void loadPokemon(){
        try {
            JSONParser parser = new JSONParser();
            JSONObject obj = (JSONObject) parser.parse(new FileReader(pokemonDir + "pokemon.json"));
            JSONArray array = (JSONArray) obj.get("pokemon_entries");

            //Pokedex Number to Pokemon Id
            Map<Long, String> pokeIds = new HashMap<>();

            for(Object object: array){
                JSONObject pokeDexEntry = (JSONObject) object;
                JSONObject pokemon = (JSONObject) pokeDexEntry.get("pokemon_species");
                Long pokDexNum = Long.valueOf(pokeDexEntry.get("entry_number").toString());
                String restId =  pokemon.get("url").toString().split("/")[6];
                pokeIds.put(pokDexNum, restId);
            }

            for(Map.Entry<Long, String> entry: pokeIds.entrySet()) {
                try {
                    extractPokemonInfo(entry.getKey(), entry.getValue().toLowerCase());
                } catch(FileNotFoundException e){
                    System.out.println("Missing pokemon info for entry [" + entry.getKey() + "]");
                }
            }

        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
    }

    /**
     * Extracts pokemon info from our sampled data.
     */
    private void extractPokemonInfo(Long pokeDexNumber, String restId) throws IOException, ParseException {
        JSONParser parser = new JSONParser();
        JSONObject pokemonSpecies = (JSONObject) parser.parse(new FileReader(pokemonDir + "pokemon-" + restId + ".json"));
        JSONObject pokemonDetails = (JSONObject) parser.parse(new FileReader(pokemonDir + "pokemon-details-" + restId + ".json"));

        String name = pokemonSpecies.get("name").toString().toLowerCase();
        String description = getDescription(pokemonSpecies, "en");
        Long height = Long.valueOf(pokemonDetails.get("height").toString());
        Long weight = Long.valueOf(pokemonDetails.get("weight").toString());
        String evolvesFrom = getEvolvesFrom(pokemonSpecies);
        Set<String>  types = getTypes(pokemonDetails);

        //Create pokemon with resources and relation:
        System.out.println("Creating [" + name + "]");
        System.out.println("    Pokedex Number [" + pokeDexNumber + "]");
        System.out.println("    Evolves From  [" + evolvesFrom +"]");
        System.out.println("    Description   [" + description + "]");
        System.out.println("    Height        [" + height + "]");
        System.out.println("    Weight        [" + weight + "]");
        System.out.println("    Types: ");
        types.forEach(type -> System.out.println("        -> [" + type + "]"));

        createPokemon(name, pokeDexNumber, evolvesFrom, description, height, weight, types);
    }

    /**
     * Creates a pokemon with the given relationships.
     */
    private void createPokemon(String name, Long pokeDexId, String evolvesFrom, String desc, Long height, Long weight, Set<String> types){
        //------------------------------- Creating the Instances of Things you need ------------------------------------
        EntityType pokemonType = graknGraph.getEntityType(PokeConstants.POKEMON);
        EntityType pokemonTypeType = graknGraph.getEntityType(PokeConstants.POKEMON_TYPE);
        ResourceType<String> nameType = graknGraph.getResourceType(PokeConstants.NAME);

        ResourceType<Long> pokeDexNumberType = graknGraph.getResourceType(PokeConstants.POKEDEX_NO);
        ResourceType<String> descriptionType = graknGraph.getResourceType(PokeConstants.DESCRIPTION);
        ResourceType<Long> heightType = graknGraph.getResourceType(PokeConstants.HEIGHT);
        ResourceType<Long> weightType = graknGraph.getResourceType(PokeConstants.WEIGHT);

        //Create all the instances of things relating to the pokemon
        Entity pokemon = pokemonType.addEntity();
        Resource<String> pokemonName = nameType.putResource(name);
        Resource<Long> pokemonPokeDex = pokeDexNumberType.putResource(pokeDexId);
        Resource<String> pokemonDesc = descriptionType.putResource(desc);
        Resource<Long> pokemonHeight = heightType.putResource(height);
        Resource<Long> pokemonWeight = weightType.putResource(weight);

        Set<Instance> pokemonTypes = new HashSet<>();
        for(String type: types){
            Instance pokemonTypeInstance = pokemonTypeType.addEntity();
            Resource<String> resourceName = nameType.putResource(type);
            pokemonTypeInstance.hasResource(resourceName);

            pokemonTypes.add(pokemonTypeInstance); // We use a put to make sure the type is already loaded
        }

        //------------------------------- Creating the Relations between everything ------------------------------------
        RoleType pokemonWithType = graknGraph.putRoleType(PokeConstants.POKEMON_WITH_TYPE);
        RoleType typeOfPokemon = graknGraph.putRoleType(PokeConstants.TYPE_OF_POKEMON);
        RelationType hasType = graknGraph.getRelationType(PokeConstants.HAS_TYPE);

        // Resources
        pokemon.hasResource(pokemonName);
        pokemon.hasResource(pokemonPokeDex);
        pokemon.hasResource(pokemonDesc);
        pokemon.hasResource(pokemonHeight);
        pokemon.hasResource(pokemonWeight);

        // Relations
        pokemonTypes.forEach(typeInstance -> {
            hasType.addRelation().putRolePlayer(pokemonWithType, pokemon).putRolePlayer(typeOfPokemon, typeInstance);
        });

        if(evolvesFrom != null){
            RoleType ancestor = graknGraph.putRoleType(PokeConstants.ANCESTOR);
            RoleType descendant = graknGraph.putRoleType(PokeConstants.DESCENDANT);
            RelationType evolution = graknGraph.getRelationType(PokeConstants.EVOLUTION);

            Resource<String> otherPokemonName = nameType.getResource(evolvesFrom);
            Entity otherPokemon;
            if(otherPokemonName == null){
                otherPokemon = pokemonType.addEntity();
                otherPokemon.hasResource(nameType.putResource(evolvesFrom));
            } else {
                otherPokemon = otherPokemonName.owner().asEntity();
            }

            evolution.addRelation().putRolePlayer(descendant, pokemon).putRolePlayer(ancestor, otherPokemon);
        }

    }

    /**
     * Helper method to get a description of a specific language
     */
    private static String getDescription(JSONObject pokemon, String languageCode){
        JSONArray array = (JSONArray) pokemon.get("flavor_text_entries");
        for(Object obj: array){
            JSONObject flavour = (JSONObject) obj;
            JSONObject language = (JSONObject) flavour.get("language");
            if(language.get("name").toString().equals(languageCode)){
                return flavour.get("flavor_text").toString();
            }
        }

        return null;
    }

    /**
     * Helper method to get types of a pokemon
     */
    private static Set<String> getTypes(JSONObject pokemon){
        Set<String> typeNames = new HashSet<>();
        JSONArray types = (JSONArray) pokemon.get("types");
        for(Object obj: types){
            JSONObject type = (JSONObject) obj;
            String typeName = ((JSONObject)type.get("type")).get("name").toString();
            typeNames.add(typeName);
        }

        return typeNames;
    }

    /**
     * Helper method to get which pokemon it evolves form
     */
    private static String getEvolvesFrom(JSONObject pokemon){
        JSONObject obj = (JSONObject) pokemon.get("evolves_from_species");
        if(obj != null){
            return obj.get("name").toString();
        }
        return null;
    }
}
