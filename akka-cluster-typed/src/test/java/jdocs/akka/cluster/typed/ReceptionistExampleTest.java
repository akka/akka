/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.typed;

import akka.actor.Address;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import akka.cluster.ClusterEvent;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Subscribe;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class ReceptionistExampleTest extends JUnitSuite {

  static class RandomRouter {

    private static class RouterBehavior<T> extends AbstractBehavior<Object> {
      private final Class<T> messageClass;
      private final ServiceKey<T> serviceKey;
      private final List<ActorRef<T>> routees = new ArrayList<>();

      public RouterBehavior(
          ActorContext<Object> ctx, Class<T> messageClass, ServiceKey<T> serviceKey) {
        this.messageClass = messageClass;
        this.serviceKey = serviceKey;

        ctx.getSystem()
            .receptionist()
            .tell(Receptionist.subscribe(serviceKey, ctx.getSelf().narrow()));
      }

      @Override
      public Receive<Object> createReceive() {
        return newReceiveBuilder()
            .onMessage(
                Receptionist.Listing.class,
                listing -> listing.isForKey(serviceKey),
                (listing) -> {
                  routees.clear();
                  routees.addAll(listing.getServiceInstances(serviceKey));
                  return this;
                })
            .onMessage(
                messageClass,
                (msg) -> {
                  int i = ThreadLocalRandom.current().nextInt(routees.size());
                  routees.get(i).tell(msg);
                  return this;
                })
            .build();
      }
    }

    public static <T> Behavior<T> router(ServiceKey<T> serviceKey, Class<T> messageClass) {
      return Behaviors.setup(ctx -> new RouterBehavior<T>(ctx, messageClass, serviceKey)).narrow();
    }
  }

  // same as above, but also subscribes to cluster reachability events and
  // avoids routees that are unreachable
  static class ClusterRouter {

    private static class WrappedReachabilityEvent {
      final ClusterEvent.ReachabilityEvent event;

      public WrappedReachabilityEvent(ClusterEvent.ReachabilityEvent event) {
        this.event = event;
      }
    }

    private static class ClusterRouterBehavior<T> extends AbstractBehavior<Object> {
      private final Class<T> messageClass;
      private final ServiceKey<T> serviceKey;
      private final List<ActorRef<T>> routees = new ArrayList<>();
      private final Set<Address> unreachable = new HashSet<>();
      private final List<ActorRef<T>> reachable = new ArrayList<>();

      public ClusterRouterBehavior(
          ActorContext<Object> ctx, Class<T> messageClass, ServiceKey<T> serviceKey) {
        this.messageClass = messageClass;
        this.serviceKey = serviceKey;
        ctx.getSystem()
            .receptionist()
            .tell(Receptionist.subscribe(serviceKey, ctx.getSelf().narrow()));

        Cluster cluster = Cluster.get(ctx.getSystem());
        // typically you have to map such external messages into this
        // actor's protocol with a message adapter
        /* this is how it is done in the scala sample, but that is awkward with Java
        ActorRef<ClusterEvent.ReachabilityEvent> reachabilityAdapter =
          ctx.messageAdapter(
            ClusterEvent.ReachabilityEvent.class,
            (event) -> new WrappedReachabilityEvent(event));
        cluster.subscriptions().tell(Subscribe.create(reachabilityAdapter, ClusterEvent.ReachabilityEvent.class));
        */
        cluster
            .subscriptions()
            .tell(Subscribe.create(ctx.getSelf().narrow(), ClusterEvent.UnreachableMember.class));
        cluster
            .subscriptions()
            .tell(Subscribe.create(ctx.getSelf().narrow(), ClusterEvent.ReachableMember.class));
      }

      private void updateReachable() {
        reachable.clear();
        for (ActorRef<T> routee : routees) {
          if (!unreachable.contains(routee.path().address())) {
            reachable.add(routee);
          }
        }
      }

      @Override
      public Receive<Object> createReceive() {
        return newReceiveBuilder()
            .onMessage(
                Receptionist.Listing.class,
                listing -> listing.isForKey(serviceKey),
                listing -> {
                  routees.clear();
                  routees.addAll(listing.getServiceInstances(serviceKey));
                  updateReachable();
                  return this;
                })
            .onMessage(
                ClusterEvent.ReachableMember.class,
                reachableMember -> {
                  unreachable.remove(reachableMember.member().address());
                  updateReachable();
                  return this;
                })
            .onMessage(
                ClusterEvent.UnreachableMember.class,
                unreachableMember -> {
                  unreachable.add(unreachableMember.member().address());
                  updateReachable();
                  return this;
                })
            .onMessage(
                messageClass,
                msg -> {
                  int i = ThreadLocalRandom.current().nextInt(reachable.size());
                  reachable.get(i).tell(msg);
                  return this;
                })
            .build();
      }
    }

    public static <T> Behavior<T> clusterRouter(ServiceKey<T> serviceKey, Class<T> messageClass) {
      return Behaviors.setup((ctx) -> new ClusterRouterBehavior<T>(ctx, messageClass, serviceKey))
          .narrow();
    }
  }

  public static class PingPongExample {
    // #ping-service
    static final ServiceKey<Ping> PingServiceKey = ServiceKey.create(Ping.class, "pingService");

    public static class Pong {}

    public static class Ping {
      private final ActorRef<Pong> replyTo;

      Ping(ActorRef<Pong> replyTo) {
        this.replyTo = replyTo;
      }
    }

    static Behavior<Ping> pingService() {
      return Behaviors.setup(
          (ctx) -> {
            ctx.getSystem()
                .receptionist()
                .tell(Receptionist.register(PingServiceKey, ctx.getSelf()));
            return Behaviors.receive(Ping.class)
                .onMessage(
                    Ping.class,
                    (c, msg) -> {
                      msg.replyTo.tell(new Pong());
                      return Behaviors.same();
                    })
                .build();
          });
    }
    // #ping-service

    // #pinger
    static Behavior<Pong> pinger(ActorRef<Ping> pingService) {
      return Behaviors.setup(
          (ctx) -> {
            pingService.tell(new Ping(ctx.getSelf()));
            return Behaviors.receive(Pong.class)
                .onMessage(
                    Pong.class,
                    (c, msg) -> {
                      System.out.println("I was ponged! " + msg);
                      return Behaviors.same();
                    })
                .build();
          });
    }
    // #pinger

    // #pinger-guardian
    static Behavior<Void> guardian() {
      return Behaviors.setup(
              (ctx) -> {
                ctx.getSystem()
                    .receptionist()
                    .tell(Receptionist.subscribe(PingServiceKey, ctx.getSelf().narrow()));
                ActorRef<Ping> ps = ctx.spawnAnonymous(pingService());
                ctx.watch(ps);
                return Behaviors.receive(Object.class)
                    .onMessage(
                        Receptionist.Listing.class,
                        listing -> listing.isForKey(PingServiceKey),
                        (c, msg) -> {
                          msg.getServiceInstances(PingServiceKey)
                              .forEach(ar -> ctx.spawnAnonymous(pinger(ar)));
                          return Behaviors.same();
                        })
                    .build();
              })
          .narrow();
    }
    // #pinger-guardian
  }

  @Test
  public void workPlease() throws Exception {
    ActorSystem<Void> system =
        ActorSystem.create(PingPongExample.guardian(), "ReceptionistExample");

    Await.ready(system.terminate(), Duration.create(2, TimeUnit.SECONDS));
  }
}
