/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.petstore;

import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.delete;
import static akka.http.javadsl.server.Directives.entity;
import static akka.http.javadsl.server.Directives.get;
import static akka.http.javadsl.server.Directives.getFromResource;
import static akka.http.javadsl.server.Directives.path;
import static akka.http.javadsl.server.Directives.pathPrefix;
import static akka.http.javadsl.server.Directives.put;
import static akka.http.javadsl.server.Directives.reject;
import static akka.http.javadsl.server.Directives.route;
import static akka.http.javadsl.server.StringUnmarshallers.INTEGER;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import akka.actor.ActorSystem;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.HttpService;
import akka.http.javadsl.server.Route;

public class PetStoreExample {

  private static Route putPetHandler(Map<Integer, Pet> pets, Pet thePet) {
      pets.put(thePet.getId(), thePet);
      return complete(StatusCodes.OK, thePet, Jackson.<Pet>marshaller());
  }
    
  public static Route appRoute(final Map<Integer, Pet> pets) {
    PetStoreController controller = new PetStoreController(pets);

    // Defined as Function in order to refere to [pets], but this could also be an ordinary method.
    Function<Integer, Route> existingPet = petId -> {
        Pet pet = pets.get(petId);
        return (pet == null) ? reject() : complete(StatusCodes.OK, pet, Jackson.<Pet>marshaller());
    };
      
    // The directives here are statically imported, but you can also inherit from AllDirectives.
    return
      route(
        path("", () ->
          getFromResource("web/index.html")
        ),
        pathPrefix("pet", () -> 
          path(INTEGER, petId -> route(
            // demonstrates different ways of handling requests:

            // 1. using a Function
            get(() -> existingPet.apply(petId)),

            // 2. using a method
            put(() -> 
              entity(Jackson.unmarshaller(Pet.class), thePet -> 
                putPetHandler(pets, thePet)
              )
            ),
            
            // 3. calling a method of a controller instance
            delete(() -> controller.deletePet(petId))
          ))
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