/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;
//#second-jackson-example
import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static akka.http.javadsl.server.PathMatchers.longSegment;

public class JacksonExampleTest extends AllDirectives {

  public static void main(String[] args) throws Exception {
    // boot up server using the route as defined below
    ActorSystem system = ActorSystem.create("routes");

    final Http http = Http.get(system);
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    //In order to access all directives we need an instance where the routes are define.
    JacksonExampleTest app = new JacksonExampleTest();

    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);
    final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow,
      ConnectHttp.toHost("localhost", 8080), materializer);

    System.out.println("Server online at http://localhost:8080/\nPress RETURN to stop...");
    System.in.read(); // let it run until user presses return

    binding
      .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
      .thenAccept(unbound -> system.terminate()); // and shutdown when done
  }

  // (fake) async database query api
  private CompletionStage<Optional<Item>> fetchItem(long itemId) {
    return CompletableFuture.completedFuture(Optional.of(new Item("foo", itemId)));
  }

  // (fake) async database query api
  private CompletionStage<Done> saveOrder(final Order order) {
    return CompletableFuture.completedFuture(Done.getInstance());
  }

  private Route createRoute() {

    return route(
      get(() ->
        pathPrefix("item", () ->
          path(longSegment(), (Long id) -> {
            final CompletionStage<Optional<Item>> futureMaybeItem = fetchItem(id);
            return onSuccess(() -> futureMaybeItem, maybeItem ->
              maybeItem.map(item -> completeOK(item, Jackson.marshaller()))
                .orElseGet(() -> complete(StatusCodes.NOT_FOUND, "Not Found"))
            );
          }))),
      post(() ->
        path("create-order", () ->
          entity(Jackson.unmarshaller(Order.class), order -> {
            CompletionStage<Done> futureSaved = saveOrder(order);
            return onSuccess(() -> futureSaved, done ->
              complete("order created")
            );
          })))
    );
  }

  private static class Item {

    final String name;
    final long id;

    @JsonCreator
    Item(@JsonProperty("name") String name,
         @JsonProperty("id") long id) {
      this.name = name;
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public long getId() {
      return id;
    }
  }

  private static class Order {

    final List<Item> items;

    @JsonCreator
    Order(@JsonProperty("items") List<Item> items) {
      this.items = items;
    }

    public List<Item> getItems() {
      return items;
    }
  }
}
//#second-jackson-example
