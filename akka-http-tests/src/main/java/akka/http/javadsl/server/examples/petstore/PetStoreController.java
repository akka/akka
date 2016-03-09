/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.petstore;

import akka.http.javadsl.model.StatusCodes;
import static akka.http.javadsl.server.Directives.*;

import akka.http.javadsl.server.Route;

import java.util.Map;

public class PetStoreController {
    private Map<Integer, Pet> dataStore;

    public PetStoreController(Map<Integer, Pet> dataStore) {
        this.dataStore = dataStore;
    }
    
    public Route deletePet(int petId) {
        dataStore.remove(petId);
        return complete(StatusCodes.OK);
    }
}
