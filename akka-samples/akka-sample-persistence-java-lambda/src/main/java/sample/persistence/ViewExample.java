/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;
import akka.persistence.*;
import scala.PartialFunction;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import java.util.concurrent.TimeUnit;

public class ViewExample {
  public static class ExamplePersistentActor extends AbstractPersistentActor {
    private int count = 1;

    @Override
    public String persistenceId() {
      return "persistentActor-5";
    }

    @Override
    public PartialFunction<Object, BoxedUnit> receiveCommand() {
      return ReceiveBuilder.
        match(String.class, s -> {
          System.out.println(String.format("persistentActor received %s (nr = %d)", s, count));
          persist(s + count, evt -> {
            count += 1;
          });
        }).
        build();
    }

    @Override
    public PartialFunction<Object, BoxedUnit> receiveRecover() {
      return ReceiveBuilder.
        match(String.class, s -> count += 1).
        build();
    }
  }

  public static class ExampleView extends AbstractView {

    private int numReplicated = 0;

    @Override
    public String viewId() {
      return "view-5";
    }

    @Override
    public String persistenceId() {
      return "persistentActor-5";
    }

    public ExampleView() {
      receive(ReceiveBuilder.
        match(Persistent.class, p -> {
          numReplicated += 1;
          System.out.println(String.format("view received %s (num replicated = %d)",
            p.payload(),
            numReplicated));
        }).
        match(SnapshotOffer.class, so -> {
          numReplicated = (Integer) so.snapshot();
          System.out.println(String.format("view received snapshot offer %s (metadata = %s)",
            numReplicated,
            so.metadata()));
        }).
        match(String.class, s -> s.equals("snap"), s -> saveSnapshot(numReplicated)).build()
      );
    }
  }

  public static void main(String... args) throws Exception {
    final ActorSystem system = ActorSystem.create("example");
    final ActorRef persistentActor = system.actorOf(Props.create(ExamplePersistentActor.class));
    final ActorRef view = system.actorOf(Props.create(ExampleView.class));

    system.scheduler()
      .schedule(Duration.Zero(),
        Duration.create(2, TimeUnit.SECONDS),
        persistentActor,
        "scheduled",
        system.dispatcher(),
        null);
    system.scheduler()
      .schedule(Duration.Zero(), Duration.create(5, TimeUnit.SECONDS), view, "snap", system.dispatcher(), null);
  }
}
