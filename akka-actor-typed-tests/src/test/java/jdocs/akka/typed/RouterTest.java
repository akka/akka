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

    static final Behavior<Command> behavior =
        Behaviors.setup(
            context -> {
              context.getLog().info("Starting worker");

              return Behaviors.receive(Command.class)
                  .onMessage(
                      DoLog.class,
                      (notUsed, doLog) -> {
                        context.getLog().info("Got message {}", doLog.text);
                        return Behaviors.same();
                      })
                  .build();
            });
  }

  // #pool

  static Behavior<Void> showPoolRouting() {
    return Behaviors.setup(
        context -> {
          // #pool
          // make sure the workers are restarted if they fail
          Behavior<Worker.Command> supervisedWorker =
              Behaviors.supervise(Worker.behavior).onFailure(SupervisorStrategy.restart());
          PoolRouter<Worker.Command> pool = Routers.pool(4, supervisedWorker);
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
    ServiceKey<Worker.Command> serviceKey = ServiceKey.create(Worker.Command.class, "log-worker");
    return Behaviors.setup(
        context -> {
          // #group
          // this would likely happen elsewhere - if we create it locally we
          // can just as well use a pool
          ActorRef<Worker.Command> worker = context.spawn(Worker.behavior, "worker");
          context.getSystem().receptionist().tell(Receptionist.register(serviceKey, worker));

          GroupRouter<Worker.Command> group = Routers.group(serviceKey);
          ActorRef<Worker.Command> router = context.spawn(group, "worker-group");

          // note that since registration of workers goes through the receptionist there is no
          // guarantee the router has seen any workers yet if we hit it directly like this and
          // these messages may end up in dead letters - in a real application you would not use
          // a group router like this - it is to keep the sample simple
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
