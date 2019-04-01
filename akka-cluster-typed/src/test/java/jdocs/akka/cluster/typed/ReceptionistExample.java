/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.typed;

// #import
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
// #import
import akka.actor.typed.ActorSystem;

public class ReceptionistExample {

  // #ping-service
  public static class PingService {

    static final ServiceKey<Ping> pingServiceKey = ServiceKey.create(Ping.class, "pingService");

    public static class Pong {}

    public static class Ping {
      private final ActorRef<Pong> replyTo;

      public Ping(ActorRef<Pong> replyTo) {
        this.replyTo = replyTo;
      }
    }

    static Behavior<Ping> createBehavior() {
      return Behaviors.setup(
          context -> {
            context
                .getSystem()
                .receptionist()
                .tell(Receptionist.register(pingServiceKey, context.getSelf()));

            return Behaviors.receive(Ping.class).onMessage(Ping.class, PingService::onPing).build();
          });
    }

    private static Behavior<Ping> onPing(ActorContext<Ping> context, Ping msg) {
      context.getLog().info("Pinged by {}", msg.replyTo);
      msg.replyTo.tell(new Pong());
      return Behaviors.same();
    }
  }
  // #ping-service

  // #pinger
  public static class Pinger {
    static Behavior<PingService.Pong> createBehavior(ActorRef<PingService.Ping> pingService) {
      return Behaviors.setup(
          (ctx) -> {
            pingService.tell(new PingService.Ping(ctx.getSelf()));
            return Behaviors.receive(PingService.Pong.class)
                .onMessage(PingService.Pong.class, Pinger::onPong)
                .build();
          });
    }

    private static Behavior<PingService.Pong> onPong(
        ActorContext<PingService.Pong> context, PingService.Pong msg) {
      context.getLog().info("{} was ponged!!", context.getSelf());
      return Behaviors.stopped();
    }
  }
  // #pinger

  // #pinger-guardian
  public static Behavior<Void> createGuardianBehavior() {
    return Behaviors.setup(
            context -> {
              context
                  .getSystem()
                  .receptionist()
                  .tell(
                      Receptionist.subscribe(
                          PingService.pingServiceKey, context.getSelf().narrow()));
              context.spawnAnonymous(PingService.createBehavior());
              return Behaviors.receive(Object.class)
                  .onMessage(
                      Receptionist.Listing.class,
                      (c, msg) -> {
                        msg.getServiceInstances(PingService.pingServiceKey)
                            .forEach(
                                pingService ->
                                    context.spawnAnonymous(Pinger.createBehavior(pingService)));
                        return Behaviors.same();
                      })
                  .build();
            })
        .narrow();
  }
  // #pinger-guardian

  public static void main(String[] args) throws Exception {
    ActorSystem<Void> system = ActorSystem.create(createGuardianBehavior(), "ReceptionistExample");
    Thread.sleep(10000);
    system.terminate();
  }
}
