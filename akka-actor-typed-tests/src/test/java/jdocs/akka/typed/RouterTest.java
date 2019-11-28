/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;
/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.actor.typed.ActorSystem;
// #pool
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.GroupRouter;
import akka.actor.typed.javadsl.PoolRouter;
import akka.actor.typed.javadsl.Routers;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;

// #pool

public class RouterTest {

  static // #pool
  class Worker {
    interface Command {}

    static class DoLog implements Command {
      public final String text;

      public DoLog(String text) {
        this.text = text;
      }
    }

    static final Behavior<Command> create() {
      return Behaviors.setup(
          context -> {
            context.getLog().info("Starting worker");

            return Behaviors.receive(Command.class)
                .onMessage(DoLog.class, doLog -> onDoLog(context, doLog))
                .build();
          });
    }

    private static Behavior<Command> onDoLog(ActorContext<Command> context, DoLog doLog) {
      context.getLog().info("Got message {}", doLog.text);
      return Behaviors.same();
    }
  }

  // #pool

  static Behavior<Void> showPoolRouting() {
    return Behaviors.setup(
        context -> {
          // #pool
          int poolSize = 4;
          PoolRouter<Worker.Command> pool =
              Routers.pool(
                  poolSize,
                  // make sure the workers are restarted if they fail
                  Behaviors.supervise(Worker.create()).onFailure(SupervisorStrategy.restart()));
          ActorRef<Worker.Command> router = context.spawn(pool, "worker-pool");

          for (int i = 0; i < 10; i++) {
            router.tell(new Worker.DoLog("msg " + i));
          }
          // #pool

          // #strategy
          PoolRouter<Worker.Command> alternativePool = pool.withPoolSize(2).withRoundRobinRouting();
          // #strategy

          return Behaviors.empty();
        });
  }

  static Behavior<Void> showGroupRouting() {
    // #group
    ServiceKey<Worker.Command> serviceKey = ServiceKey.create(Worker.Command.class, "log-worker");

    // #group
    return Behaviors.setup(
        context -> {
          // #group
          // this would likely happen elsewhere - if we create it locally we
          // can just as well use a pool
          ActorRef<Worker.Command> worker = context.spawn(Worker.create(), "worker");
          context.getSystem().receptionist().tell(Receptionist.register(serviceKey, worker));

          GroupRouter<Worker.Command> group = Routers.group(serviceKey);
          ActorRef<Worker.Command> router = context.spawn(group, "worker-group");

          // the group router will stash messages until it sees the first listing of registered
          // services from the receptionist, so it is safe to send messages right away
          for (int i = 0; i < 10; i++) {
            router.tell(new Worker.DoLog("msg " + i));
          }
          // #group

          return Behaviors.empty();
        });
  }

  public static void main(String[] args) {
    ActorSystem<Void> system =
        ActorSystem.create(
            Behaviors.setup(
                context -> {
                  context.spawn(showPoolRouting(), "pool-router-setup");
                  context.spawn(showGroupRouting(), "group-router-setup");

                  return Behaviors.empty();
                }),
            "RouterTest");
  }
}
