package docs.akka.cluster.typed

import java.util.concurrent.ThreadLocalRandom

import akka.actor.Address
import akka.actor.typed._
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.scaladsl._
import akka.cluster.ClusterEvent._
import akka.cluster.typed.{ Cluster, Join, Subscribe }
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpec
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable.Set

object RandomRouter {

  def router[T](serviceKey: ServiceKey[T]): Behavior[T] =
    Behaviors.deferred[Any] { ctx ⇒
      ctx.system.receptionist ! Receptionist.Subscribe(serviceKey, ctx.self)

      def routingBehavior(routees: Vector[ActorRef[T]]): Behavior[Any] =
        Behaviors.immutable { (_, msg) ⇒
          msg match {
            case Listing(_, services: Set[ActorRef[T]]) ⇒
              routingBehavior(services.toVector)
            case other: T @unchecked ⇒
              if (routees.isEmpty)
                Behaviors.unhandled
              else {
                val i = ThreadLocalRandom.current.nextInt(routees.size)
                routees(i) ! other
                Behaviors.same
              }
          }
        }

      routingBehavior(Vector.empty)
    }.narrow[T]

  private final case class WrappedReachabilityEvent(event: ReachabilityEvent)

  // same as above, but also subscribes to cluster reachability events and
  // avoids routees that are unreachable
  def clusterRouter[T](serviceKey: ServiceKey[T]): Behavior[T] =
    Behaviors.deferred[Any] { ctx ⇒
      ctx.system.receptionist ! Receptionist.Subscribe(serviceKey, ctx.self)

      val cluster = Cluster(ctx.system)
      // typically you have to map such external messages into this
      // actor's protocol with a message adapter
      val reachabilityAdapter: ActorRef[ReachabilityEvent] = ctx.messageAdapter(WrappedReachabilityEvent.apply)
      cluster.subscriptions ! Subscribe(reachabilityAdapter, classOf[ReachabilityEvent])

      def routingBehavior(routees: Vector[ActorRef[T]], unreachable: Set[Address]): Behavior[Any] =
        Behaviors.immutable { (_, msg) ⇒
          msg match {
            case Listing(_, services: Set[ActorRef[T]]) ⇒
              routingBehavior(services.toVector, unreachable)
            case WrappedReachabilityEvent(event) ⇒ event match {
              case UnreachableMember(m) ⇒
                routingBehavior(routees, unreachable + m.address)
              case ReachableMember(m) ⇒
                routingBehavior(routees, unreachable - m.address)
            }

            case other: T @unchecked ⇒
              if (routees.isEmpty)
                Behaviors.unhandled
              else {
                val reachableRoutes =
                  if (unreachable.isEmpty) routees
                  else routees.filterNot { r ⇒ unreachable(r.path.address) }

                val i = ThreadLocalRandom.current.nextInt(reachableRoutes.size)
                reachableRoutes(i) ! other
                Behaviors.same
              }
          }
        }

      routingBehavior(Vector.empty, Set.empty)
    }.narrow[T]
}

object PingPongExample {
  //#ping-service
  val PingServiceKey = ServiceKey[Ping]("pingService")

  final case class Ping(replyTo: ActorRef[Pong.type])
  final case object Pong

  val pingService: Behavior[Ping] =
    Behaviors.deferred { ctx ⇒
      ctx.system.receptionist ! Receptionist.Register(PingServiceKey, ctx.self, ctx.system.deadLetters)
      Behaviors.immutable[Ping] { (_, msg) ⇒
        msg match {
          case Ping(replyTo) ⇒
            replyTo ! Pong
            Behaviors.stopped
        }
      }
    }
  //#ping-service

  //#pinger
  def pinger(pingService: ActorRef[Ping]) = Behaviors.deferred[Pong.type] { ctx ⇒
    pingService ! Ping(ctx.self)
    Behaviors.immutable { (_, msg) ⇒
      println("I was ponged!!" + msg)
      Behaviors.same
    }
  }
  //#pinger

  //#pinger-guardian
  val guardian: Behavior[Listing[Ping]] = Behaviors.deferred { ctx ⇒
    ctx.system.receptionist ! Receptionist.Subscribe(PingServiceKey, ctx.self)
    val ps = ctx.spawnAnonymous(pingService)
    ctx.watch(ps)
    Behaviors.immutablePartial[Listing[Ping]] {
      case (c, Listing(PingServiceKey, listings)) if listings.nonEmpty ⇒
        listings.foreach(ps ⇒ ctx.spawnAnonymous(pinger(ps)))
        Behaviors.same
    } onSignal {
      case (_, Terminated(`ps`)) ⇒
        println("Ping service has shut down")
        Behaviors.stopped
    }
  }
  //#pinger-guardian

  //#pinger-guardian-pinger-service
  val guardianJustPingService: Behavior[Listing[Ping]] = Behaviors.deferred { ctx ⇒
    val ps = ctx.spawnAnonymous(pingService)
    ctx.watch(ps)
    Behaviors.immutablePartial[Listing[Ping]] {
      case (c, Listing(PingServiceKey, listings)) if listings.nonEmpty ⇒
        listings.foreach(ps ⇒ ctx.spawnAnonymous(pinger(ps)))
        Behaviors.same
    } onSignal {
      case (_, Terminated(`ps`)) ⇒
        println("Ping service has shut down")
        Behaviors.stopped
    }
  }
  //#pinger-guardian-pinger-service

  //#pinger-guardian-just-pinger
  val guardianJustPinger: Behavior[Listing[Ping]] = Behaviors.deferred { ctx ⇒
    ctx.system.receptionist ! Receptionist.Subscribe(PingServiceKey, ctx.self)
    Behaviors.immutablePartial[Listing[Ping]] {
      case (c, Listing(PingServiceKey, listings)) if listings.nonEmpty ⇒
        listings.foreach(ps ⇒ ctx.spawnAnonymous(pinger(ps)))
        Behaviors.same
    }
  }
  //#pinger-guardian-just-pinger

}

object ReceptionistExampleSpec {
  val clusterConfig = ConfigFactory.parseString(
    s"""
#config
akka {
  actor {
    provider = "cluster"
  }
  cluster.jmx.multi-mbeans-in-same-jvm = on
  remote {
    netty.tcp {
      hostname = "127.0.0.1"
      port = 2551
    }
  }
}
#config
akka.remote.netty.tcp.port = 0
     """)

}

class ReceptionistExampleSpec extends WordSpec with ScalaFutures {

  import ReceptionistExampleSpec._
  import PingPongExample._

  "A local basic example" must {
    "show register" in {
      val system = ActorSystem(guardian, "PingPongExample")
      system.whenTerminated.futureValue
    }
  }

  "A remote basic example" must {
    "show register" in {
      // FIXME cannot use guardian as it touches receptionist #24279
      import scaladsl.adapter._
      val system1 = akka.actor.ActorSystem("PingPongExample", clusterConfig)
      val system2 = akka.actor.ActorSystem("PingPongExample", clusterConfig)

      system1.spawnAnonymous(guardianJustPingService)
      system2.spawnAnonymous(guardianJustPinger)

      val cluster1 = Cluster(system1.toTyped)
      val cluster2 = Cluster(system2.toTyped)

      cluster1.manager ! Join(cluster1.selfMember.address)
      cluster1.manager ! Join(cluster2.selfMember.address)
    }
  }
}
