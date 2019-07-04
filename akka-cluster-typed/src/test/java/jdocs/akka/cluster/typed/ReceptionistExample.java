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

  public
  // #ping-service
  static class PingService {

    private final ActorContext<Ping> context;

    static final ServiceKey<Ping> pingServiceKey = ServiceKey.create(Ping.class, "pingService");

    private PingService(ActorContext<Ping> context) {
      this.context = context;
    }

    public static class Pong {}

    public static class Ping {
      private final ActorRef<Pong> replyTo;

      public Ping(ActorRef<Pong> replyTo) {
        this.replyTo = replyTo;
      }
    }

    public static Behavior<Ping> createBehavior() {
      return Behaviors.setup(
          context -> {
            context
                .getSystem()
                .receptionist()
                .tell(Receptionist.register(pingServiceKey, context.getSelf()));

            return new PingService(context).behavior();
          });
    }

    private Behavior<Ping> behavior() {
      return Behaviors.receive(Ping.class).onMessage(Ping.class, this::onPing).build();
    }

    private Behavior<Ping> onPing(Ping msg) {
      context.getLog().info("Pinged by {}", msg.replyTo);
      msg.replyTo.tell(new Pong());
      return Behaviors.same();
    }
  }
  // #ping-service

  public
  // #pinger
  static class Pinger {
    private final ActorContext<PingService.Pong> context;
    private final ActorRef<PingService.Ping> pingService;

    private Pinger(ActorContext<PingService.Pong> context, ActorRef<PingService.Ping> pingService) {
      this.context = context;
      this.pingService = pingService;
    }

    public static Behavior<PingService.Pong> createBehavior(
        ActorRef<PingService.Ping> pingService) {
      return Behaviors.setup(
          ctx -> {
            pingService.tell(new PingService.Ping(ctx.getSelf()));
            return new Pinger(ctx, pingService).behavior();
          });
    }

    private Behavior<PingService.Pong> behavior() {
      return Behaviors.receive(PingService.Pong.class)
          .onMessage(PingService.Pong.class, this::onPong)
          .build();
    }

    private Behavior<PingService.Pong> onPong(PingService.Pong msg) {
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
                      msg -> {
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
