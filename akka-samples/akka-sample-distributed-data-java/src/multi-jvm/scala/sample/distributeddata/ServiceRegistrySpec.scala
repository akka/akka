package sample.distributeddata

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.Replicator.GetReplicaCount
import akka.cluster.ddata.Replicator.ReplicaCount
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._

object ServiceRegistrySpec extends MultiNodeConfig {
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
      case s: String => sender() ! self.path.name + ": " + s
    }
  }

}

class ServiceRegistrySpecMultiJvmNode1 extends ServiceRegistrySpec
class ServiceRegistrySpecMultiJvmNode2 extends ServiceRegistrySpec
class ServiceRegistrySpecMultiJvmNode3 extends ServiceRegistrySpec

class ServiceRegistrySpec extends MultiNodeSpec(ServiceRegistrySpec) with STMultiNodeSpec with ImplicitSender {
  import ServiceRegistrySpec._
  import ServiceRegistry._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)
  val registry = system.actorOf(ServiceRegistry.props)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Demo of a replicated service registry" must {
    "join cluster" in within(20.seconds) {
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
        registry ! new Register("a", a1)
      }

      awaitAssert {
        val probe = TestProbe()
        registry.tell(new Lookup("a"), probe.ref)
        probe.expectMsgType[Bindings].services.asScala.map(_.path.name).toSet should be(Set("a1"))
      }

      enterBarrier("after-2")
    }

    "replicate updated service entry, and publish to even bus" in {
      val probe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[BindingChanged])

      runOn(node2) {
        val a2 = system.actorOf(Props[Service], name = "a2")
        registry ! new Register("a", a2)
      }

      probe.within(10.seconds) {
        probe.expectMsgType[BindingChanged].services.asScala.map(_.path.name).toSet should be(Set("a1", "a2"))
        registry.tell(new Lookup("a"), probe.ref)
        probe.expectMsgType[Bindings].services.asScala.map(_.path.name).toSet should be(Set("a1", "a2"))
      }

      enterBarrier("after-4")
    }

    "remove terminated service" in {
      val probe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[BindingChanged])

      runOn(node2) {
        registry.tell(new Lookup("a"), probe.ref)
        val a2 = probe.expectMsgType[Bindings].services.asScala.find(_.path.name == "a2").get
        a2 ! PoisonPill
      }

      probe.within(10.seconds) {
        probe.expectMsgType[BindingChanged].services.asScala.map(_.path.name).toSet should be(Set("a1"))
        registry.tell(new Lookup("a"), probe.ref)
        probe.expectMsgType[Bindings].services.asScala.map(_.path.name).toSet should be(Set("a1"))
      }

      enterBarrier("after-5")
    }

    "replicate many service entries" in within(10.seconds) {
      for (i ← 100 until 200) {
        val service = system.actorOf(Props[Service], name = myself.name + "_" + i)
        registry ! new Register("a" + i, service)
      }

      awaitAssert {
        val probe = TestProbe()
        for (i ← 100 until 200) {
          registry.tell(new Lookup("a" + i), probe.ref)
          probe.expectMsgType[Bindings].services.asScala.map(_.path.name).toSet should be(roles.map(_.name + "_" + i).toSet)
        }
      }

      enterBarrier("after-6")
    }

  }

}

