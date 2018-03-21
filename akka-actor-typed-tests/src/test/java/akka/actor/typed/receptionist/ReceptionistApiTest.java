/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.receptionist;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.util.Timeout;

import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class ReceptionistApiTest {

  public void compileOnlyApiTest() {
    // some dummy prerequisites
    final Timeout timeout = Timeout.apply(3, TimeUnit.SECONDS);
    final ActorRef<String> service = null;
    final ServiceKey<String> key = ServiceKey.create(String.class, "id");
    final ActorSystem<Void> system = null;

    // registration from outside, without ack, should be rare
    system.receptionist().tell(Receptionist.register(key, service));

    // registration from outside with ack, should be rare
    CompletionStage<Receptionist.Registered> registeredCS = AskPattern.ask(
      system.receptionist(),
      sendRegTo -> Receptionist.register(key, service, sendRegTo),
      timeout,
      system.scheduler()
    );
    registeredCS.whenComplete((r, failure) -> {
      if (r != null) {
        r.getServiceInstance(key);
      } else {
        throw new RuntimeException("Not registered");
      }
    });

    // one-off ask outside of actor, should be uncommon but not rare
    CompletionStage<Receptionist.Listing> result = AskPattern.ask(
      system.receptionist(),
      sendListingTo -> Receptionist.find(key, sendListingTo),
      timeout,
      system.scheduler()
    );
    result.whenComplete((listing, throwable) -> {
      if (listing != null && listing.isForKey(key)) {
        Set<ActorRef<String>> serviceInstances = listing.getServiceInstances(key);
      } else {
        throw new RuntimeException("not what I wanted");
      }
    });

    Behaviors.setup(ctx -> {
      // oneoff ask inside of actor
      // this is somewhat verbose, however this should be a rare use case
      ctx.ask(
        Receptionist.Listing.class,
        ctx.getSystem().receptionist(),
        timeout,
        resRef -> Receptionist.find(key, resRef),
        (listing, throwable) -> {
          if (listing != null) return listing.getServiceInstances(key);
          else return "listing failed";
        }
      );

      // this is a more "normal" use case which is clean
      ctx.getSystem().receptionist().tell(Receptionist.subscribe(key, ctx.getSelf().narrow()));

      // another more "normal" is subscribe using an adapter
      ActorRef<Receptionist.Listing> listingAdapter = ctx.messageAdapter(
        Receptionist.Listing.class,
        (listing) -> listing.serviceInstances(key)
      );
      ctx.getSystem().receptionist().tell(Receptionist.subscribe(key, listingAdapter));

      // ofc this doesn't make sense to do in the same actor, this is just
      // to cover as much of the API as possible
      ctx.getSystem().receptionist().tell(Receptionist.register(key, ctx.getSelf().narrow(), ctx.getSelf().narrow()));

      return Behaviors.receive(Object.class)
        // matching is done best using the predicate version
        .onMessage(Receptionist.Listing.class, listing -> listing.isForKey(key), (msgCtx, listing) -> {
          Set<ActorRef<String>> services = listing.getServiceInstances(key);
          return Behaviors.same();
        }).onMessage(Receptionist.Registered.class, registered -> registered.isForKey(key), (msgCtx, registered) -> {
          ActorRef<String> registree = registered.getServiceInstance(key);
          return Behaviors.same();
        }).build();
    });
  }
}
