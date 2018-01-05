package jdocs.akka.cluster.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Actor;
import akka.actor.typed.receptionist.Receptionist;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;

public class ReceptionistExampleTest {

  public static class PingPongExample {
    //#ping-service
    static Receptionist.ServiceKey<Ping> PingServiceKey =
      Receptionist.ServiceKey.create(Ping.class, "pingService");

    public static class Pong {}
    public static class Ping {
      private final ActorRef<Pong> replyTo;
      Ping(ActorRef<Pong> replyTo) {
        this.replyTo = replyTo;
      }
    }

    static Behavior<Ping> pingService() {
      return Actor.deferred((ctx) -> {
        ctx.getSystem().receptionist()
          .tell(Receptionist.Register.create(PingServiceKey, ctx.getSelf(), ctx.getSystem().deadLetters()));
        return Actor.immutable(Ping.class)
          .onMessage(Ping.class, (c, msg) -> {
            msg.replyTo.tell(new Pong());
            return Actor.same();
          }).build();
      });
    }
    //#ping-service

    //#pinger
    static Behavior<Pong> pinger(ActorRef<Ping> pingService) {
      return Actor.deferred((ctx) -> {
        pingService.tell(new Ping(ctx.getSelf()));
        return Actor.immutable(Pong.class)
          .onMessage(Pong.class, (c, msg) -> {
            System.out.println("I was ponged! " + msg);
            return Actor.same();
          }).build();
      });
    }
    //#pinger

    //#pinger-guardian
    static Behavior<Receptionist.Listing<Ping>> guardian() {
      return Actor.deferred((ctx) -> {
        ctx.getSystem().receptionist()
          .tell(Receptionist.Subscribe.create(PingServiceKey, ctx.getSelf()));
        ActorRef<Ping> ps = ctx.spawnAnonymous(pingService());
        ctx.watch(ps);
        return Actor.immutable((c, msg) -> {
          msg.getServiceInstances().forEach(ar -> ctx.spawnAnonymous(pinger(ar)));
          return Actor.same();
        });
      });
    }
    //#pinger-guardian
  }

  public void workPlease() throws Exception {
    ActorSystem<Receptionist.Listing<PingPongExample.Ping>> system =
      ActorSystem.create(PingPongExample.guardian(), "ReceptionistExample");

    Await.ready(system.terminate(), Duration.create(2, TimeUnit.SECONDS));
  }
}
