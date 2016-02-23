/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.petstore;

import akka.http.javadsl.server.RequestContext;
import akka.http.javadsl.server.RouteResult;

import java.util.Map;

public class PetStoreController {
    private Map<Integer, Pet> dataStore;

    public PetStoreController(Map<Integer, Pet> dataStore) {
        this.dataStore = dataStore;
    }
    public RouteResult deletePet(RequestContext ctx, int petId) {
        dataStore.remove(petId);
        return ctx.completeWithStatus(200);
    }
}
