/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

//#actor-interaction
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
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
import akka.japi.pf.ReceiveBuilder;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import akka.util.Timeout;
import scala.concurrent.duration.FiniteDuration;

import java.util.ArrayList;
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
    final int offer;

    Bid(String userId, int offer) {
      this.userId = userId;
      this.offer = offer;
    }
  }

  static class GetBids {

  }

  static class Bids {
    public final List<Bid> bids;

    Bids(List<Bid> bids) {
      this.bids = bids;
    }
  }

  //#actor-interaction
  /* TODO: replace with code that works for both 2.4 and 2.5, see #821
  //#actor-interaction
  // compiles only against Akka 2.4, see migration guide for how to rewrite for Akka 2.5
  static class Auction extends AbstractActor {

    private final LoggingAdapter log = Logging.getLogger(context().system(), this);

    List<HttpServerActorInteractionExample.Bid> bids = new ArrayList<>();

    static Props props() {
      return Props.create(Auction.class);
    }

    public Auction() {
      receive(ReceiveBuilder.
        match(HttpServerActorInteractionExample.Bid.class, bid -> {
          bids.add(bid);
          log.info("Bid complete: {}, {}", bid.userId, bid.offer);
        }).
        match(HttpServerActorInteractionExample.GetBids.class, m -> {
          sender().tell(new HttpServerActorInteractionExample.Bids(bids), self());
        }).
        matchAny(o -> log.info("Invalid message")).
        build()
      );
    }
  }
  //#actor-interaction
  */

  static class Auction {
    static Props props() { return null; }
  }
  //#actor-interaction
}
//#actor-interaction
