/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

//#actor-interaction
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import akka.util.Timeout;
import scala.concurrent.duration.FiniteDuration;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static akka.pattern.PatternsCS.ask;


public class HttpServerActorInteractionExample extends AllDirectives {

  private final ActorRef auction;

  public static void main(String[] args) throws Exception {
    // boot up server using the route as defined below
    ActorSystem system = ActorSystem.create("routes");

    final Http http = Http.get(system);
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    //In order to access all directives we need an instance where the routes are define.
    HttpServerActorInteractionExample app = new HttpServerActorInteractionExample(system);

    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);
    final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow,
      ConnectHttp.toHost("localhost", 8080), materializer);

    System.out.println("Server online at http://localhost:8080/\nPress RETURN to stop...");
    System.in.read(); // let it run until user presses return

    binding
      .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
      .thenAccept(unbound -> system.terminate()); // and shutdown when done
  }

  private HttpServerActorInteractionExample(final ActorSystem system) {
    auction = system.actorOf(Auction.props(), "auction");
  }

  private Route createRoute() {
    return route(
      path("auction", () -> route(
        put(() ->
          parameter(StringUnmarshallers.INTEGER, "bid", bid ->
            parameter("user", user -> {
              // place a bid, fire-and-forget
              auction.tell(new Bid(user, bid), ActorRef.noSender());
              return complete(StatusCodes.ACCEPTED, "bid placed");
            })
          )),
        get(() -> {
          final Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(5, TimeUnit.SECONDS));
          // query the actor for the current auction state
          CompletionStage<Bids> bids = ask(auction, new GetBids(), timeout).thenApply((Bids.class::cast));
          return completeOKWithFuture(bids, Jackson.marshaller());
        }))));
  }

  static class Bid {
    final String userId;
    final int bid;

    Bid(String userId, int bid) {
      this.userId = userId;
      this.bid = bid;
    }
  }

  static class GetBids {

  }

  static class Bids {
    final List<Bid> bids;

    Bids(List<Bid> bids) {
      this.bids = bids;
    }
  }

  //#actor-interaction
  static class Auction extends UntypedActor {
    static Props props() {
      return Props.create(Auction.class);
    }

    @Override
    public void onReceive(Object message) throws Throwable {

    }
  }
  //#actor-interaction
}
//#actor-interaction
