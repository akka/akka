/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.petstore;

import akka.actor.ActorSystem;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.PathMatcher;
import akka.http.javadsl.server.values.PathMatchers;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static akka.http.javadsl.server.Directives.*;

public class PetStoreExample {
  static PathMatcher<Integer> petId = PathMatchers.intValue();
  static RequestVal<Pet> petEntity = RequestVals.entityAs(Jackson.jsonAs(Pet.class));

  public static Route appRoute(final Map<Integer, Pet> pets) {
    PetStoreController controller = new PetStoreController(pets);

    final RequestVal<Pet> existingPet = RequestVals.lookupInMap(petId, Pet.class, pets);

    Handler1<Pet> putPetHandler = (Handler1<Pet>) (ctx, thePet) -> {
      pets.put(thePet.getId(), thePet);
      return ctx.completeAs(Jackson.json(), thePet);
    };

    return
      route(
        path().route(
          getFromResource("web/index.html")
        ),
        path("pet", petId).route(
          // demonstrates three different ways of handling requests:

          // 1. using a predefined route that completes with an extraction
          get(extractAndComplete(Jackson.<Pet>json(), existingPet)),

          // 2. using a handler
          put(handleWith1(petEntity, putPetHandler)),

          // 3. calling a method of a controller instance reflectively
          delete(handleReflectively(controller, "deletePet", petId))
        )
      );
  }

  public static void main(String[] args) throws IOException {
    Map<Integer, Pet> pets = new ConcurrentHashMap<>();
    Pet dog = new Pet(0, "dog");
    Pet cat = new Pet(1, "cat");
    pets.put(0, dog);
    pets.put(1, cat);

    ActorSystem system = ActorSystem.create();
    try {
      HttpService.bindRoute("localhost", 8080, appRoute(pets), system);
      System.out.println("Type RETURN to exit");
      System.in.read();
    } finally {
      system.terminate();
    }
  }
}