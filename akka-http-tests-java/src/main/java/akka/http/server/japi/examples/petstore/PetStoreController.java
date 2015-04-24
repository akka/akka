/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi.examples.petstore;

import akka.http.server.japi.RequestContext;
import akka.http.server.japi.RouteResult;

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
