/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.datareplication

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent
import akka.cluster.ClusterEvent.LeaderChanged
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.GSet
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.Replicator.GetReplicaCount
import akka.cluster.ddata.Replicator.ReplicaCount
import akka.cluster.ddata.STMultiNodeSpec
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.cluster.ddata.GSetKey
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.Key

object ReplicatedServiceRegistrySpec extends MultiNodeConfig {
  val node1 = role("node-1")
  val node2 = role("node-2")
  val node3 = role("node-3")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.log-dead-letters-during-shutdown = off
    """))

  class Service extends Actor {
    def receive = {
      case s: String ⇒ sender() ! self.path.name + ": " + s
    }
  }

}

object ReplicatedServiceRegistry {
  import akka.cluster.ddata.Replicator._

  val props: Props = Props[ReplicatedServiceRegistry]

  /**
   * Register a `service` with a `name`. Several services
   * can be registered with the same `name`.
   * It will be removed when it is terminated.
   */
  final case class Register(name: String, service: ActorRef)
  /**
   * Lookup services registered for a `name`. [[Bindings]] will
   * be sent to `sender()`.
   */
  final case class Lookup(name: String)
  /**
   * Reply for [[Lookup]]
   */
  final case class Bindings(name: String, services: Set[ActorRef])
  /**
   * Published to `System.eventStream` when services are changed.
   */
  final case class BindingChanged(name: String, services: Set[ActorRef])

  final case class ServiceKey(serviceName: String) extends Key[ORSet[ActorRef]](serviceName)

  private val AllServicesKey = GSetKey[ServiceKey]("service-keys")

}

class ReplicatedServiceRegistry() extends Actor with ActorLogging {
  import akka.cluster.ddata.Replicator._
  import ReplicatedServiceRegistry._

  val replicator = DistributedData(context.system).replicator
  implicit val cluster = Cluster(context.system)

  var keys = Set.empty[ServiceKey]
  var services = Map.empty[String, Set[ActorRef]]
  var leader = false

  def serviceKey(serviceName: String): ServiceKey =
    ServiceKey("service:" + serviceName)

  override def preStart(): Unit = {
    replicator ! Subscribe(AllServicesKey, self)
    cluster.subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[ClusterEvent.LeaderChanged])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  def receive = {
    case Register(name, service) ⇒
      val dKey = serviceKey(name)
      // store the service names in a separate GSet to be able to
      // get notifications of new names
      if (!keys(dKey))
        replicator ! Update(AllServicesKey, GSet(), WriteLocal)(_ + dKey)
      // add the service
      replicator ! Update(dKey, ORSet(), WriteLocal)(_ + service)

    case Lookup(key) ⇒
      sender() ! Bindings(key, services.getOrElse(key, Set.empty))

    case c @ Changed(AllServicesKey) ⇒
      val newKeys = c.get(AllServicesKey).elements
      log.debug("Services changed, added: {}, all: {}", (newKeys -- keys), newKeys)
      (newKeys -- keys).foreach { dKey ⇒
        // subscribe to get notifications of when services with this name are added or removed
        replicator ! Subscribe(dKey, self)
      }
      keys = newKeys

    case c @ Changed(ServiceKey(serviceName)) ⇒
      val name = serviceName.split(":").tail.mkString
      val newServices = c.get(serviceKey(name)).elements
      log.debug("Services changed for name [{}]: {}", name, newServices)
      services = services.updated(name, newServices)
      context.system.eventStream.publish(BindingChanged(name, newServices))
      if (leader)
        newServices.foreach(context.watch) // watch is idempotent

    case LeaderChanged(node) ⇒
      // Let one node (the leader) be responsible for removal of terminated services
      // to avoid redundant work and too many death watch notifications.
      // It is not critical to only do it from one node.
      val wasLeader = leader
      leader = node.exists(_ == cluster.selfAddress)
      // when used with many (> 500) services you must increase the system message buffer
      // `akka.remote.system-message-buffer-size`
      if (!wasLeader && leader)
        for (refs ← services.valuesIterator; ref ← refs)
          context.watch(ref)
      else if (wasLeader && !leader)
        for (refs ← services.valuesIterator; ref ← refs)
          context.unwatch(ref)

    case Terminated(ref) ⇒
      val names = services.collect { case (name, refs) if refs.contains(ref) ⇒ name }
      names.foreach { name ⇒
        log.debug("Service with name [{}] terminated: {}", name, ref)
        replicator ! Update(serviceKey(name), ORSet(), WriteLocal)(_ - ref)
      }

    case _: UpdateResponse[_] ⇒ // ok
  }

}

class ReplicatedServiceRegistrySpecMultiJvmNode1 extends ReplicatedServiceRegistrySpec
class ReplicatedServiceRegistrySpecMultiJvmNode2 extends ReplicatedServiceRegistrySpec
class ReplicatedServiceRegistrySpecMultiJvmNode3 extends ReplicatedServiceRegistrySpec

class ReplicatedServiceRegistrySpec extends MultiNodeSpec(ReplicatedServiceRegistrySpec) with STMultiNodeSpec with ImplicitSender {
  import ReplicatedServiceRegistrySpec._
  import ReplicatedServiceRegistry._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)
  val registry = system.actorOf(ReplicatedServiceRegistry.props)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Demo of a replicated service registry" must {
    "join cluster" in within(10.seconds) {
      join(node1, node1)
      join(node2, node1)
      join(node3, node1)

      awaitAssert {
        DistributedData(system).replicator ! GetReplicaCount
        expectMsg(ReplicaCount(roles.size))
      }
      enterBarrier("after-1")
    }

    "replicate service entry" in within(10.seconds) {
      runOn(node1) {
        val a1 = system.actorOf(Props[Service], name = "a1")
        registry ! Register("a", a1)
      }

      awaitAssert {
        val probe = TestProbe()
        registry.tell(Lookup("a"), probe.ref)
        probe.expectMsgType[Bindings].services.map(_.path.name) should be(Set("a1"))
      }

      enterBarrier("after-2")
    }

    "replicate updated service entry, and publish to even bus" in {
      val probe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[BindingChanged])

      runOn(node2) {
        val a2 = system.actorOf(Props[Service], name = "a2")
        registry ! Register("a", a2)
      }

      probe.within(10.seconds) {
        probe.expectMsgType[BindingChanged].services.map(_.path.name) should be(Set("a1", "a2"))
        registry.tell(Lookup("a"), probe.ref)
        probe.expectMsgType[Bindings].services.map(_.path.name) should be(Set("a1", "a2"))
      }

      enterBarrier("after-4")
    }

    "remove terminated service" in {
      val probe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[BindingChanged])

      runOn(node2) {
        registry.tell(Lookup("a"), probe.ref)
        val a2 = probe.expectMsgType[Bindings].services.find(_.path.name == "a2").get
        a2 ! PoisonPill
      }

      probe.within(10.seconds) {
        probe.expectMsgType[BindingChanged].services.map(_.path.name) should be(Set("a1"))
        registry.tell(Lookup("a"), probe.ref)
        probe.expectMsgType[Bindings].services.map(_.path.name) should be(Set("a1"))
      }

      enterBarrier("after-5")
    }

    "replicate many service entries" in within(10.seconds) {
      for (i ← 100 until 200) {
        val service = system.actorOf(Props[Service], name = myself.name + "_" + i)
        registry ! Register("a" + i, service)
      }

      awaitAssert {
        val probe = TestProbe()
        for (i ← 100 until 200) {
          registry.tell(Lookup("a" + i), probe.ref)
          probe.expectMsgType[Bindings].services.map(_.path.name) should be(roles.map(_.name + "_" + i).toSet)
        }
      }

      enterBarrier("after-6")
    }

  }

}

