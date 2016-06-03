/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.petstore;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static akka.http.javadsl.server.Directives.*;
import static akka.http.javadsl.server.StringUnmarshallers.INTEGER;

public class PetStoreExample {

  private static Route putPetHandler(Map<Integer, Pet> pets, Pet thePet) {
      pets.put(thePet.getId(), thePet);
      return complete(StatusCodes.OK, thePet, Jackson.<Pet>marshaller());
  }
  
  private static Route alternativeFuturePutPetHandler(Map<Integer, Pet> pets, Pet thePet) {
      pets.put(thePet.getId(), thePet);
    CompletableFuture<Pet> futurePet = CompletableFuture.supplyAsync(() -> thePet);
      return completeOKWithFuture(futurePet, Jackson.<Pet>marshaller());
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
            // 2.1. using a method, and internally handling a Future value
            path("alternate", () ->
              put(() -> 
                entity(Jackson.unmarshaller(Pet.class), thePet -> 
                  putPetHandler(pets, thePet)
                )
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

    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final ConnectHttp host = ConnectHttp.toHost("127.0.0.1");

    Http.get(system).bindAndHandle(appRoute(pets).flow(system, materializer), host, materializer);

    System.console().readLine("Type RETURN to exit...");
    system.terminate();
  }
}
