/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.testkit._
import akka.routing._
import akka.actor._
import akka.remote.routing._
import com.typesafe.config._

object RemoteRouterSpec {
  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender() ! self
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteRouterSpec extends AkkaSpec("""
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.remote.netty.tcp {
      hostname = localhost
      port = 0
    }
    akka.actor.deployment {
      /remote-override {
        router = round-robin-pool
        nr-of-instances = 4
      }
    }""") {

  import RemoteRouterSpec._

  val port = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress.port.get
  val sysName = system.name
  val conf = ConfigFactory.parseString(
    s"""
    akka {
      actor.deployment {
        /blub {
          router = round-robin-pool
          nr-of-instances = 2
          target.nodes = ["akka.tcp://${sysName}@localhost:${port}"]
        }
        /elastic-blub {
          router = round-robin-pool
          resizer {
            lower-bound = 2
            upper-bound = 3
          }
          target.nodes = ["akka.tcp://${sysName}@localhost:${port}"]
        }
        /remote-blub {
          remote = "akka.tcp://${sysName}@localhost:${port}"
          router = round-robin-pool
          nr-of-instances = 2
        }
        /local-blub {
          remote = "akka://MasterRemoteRouterSpec"
          router = round-robin-pool
          nr-of-instances = 2
          target.nodes = ["akka.tcp://${sysName}@localhost:${port}"]
        }
        /local-blub2 {
          router = round-robin-pool
          nr-of-instances = 4
          target.nodes = ["akka.tcp://${sysName}@localhost:${port}"]
        }
      }
    }""").withFallback(system.settings.config)
  val masterSystem = ActorSystem("Master" + sysName, conf)

  override def afterTermination() {
    shutdown(masterSystem)
  }

  "A Remote Router" must {

    "deploy its children on remote host driven by configuration" in {
      val probe = TestProbe()(masterSystem)
      val router = masterSystem.actorOf(RoundRobinPool(2).props(Props[Echo]), "blub")
      val replies = for (i ← 1 to 5) yield {
        router.tell("", probe.ref)
        probe.expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children should have size 2
      children.map(_.parent) should have size 1
      children foreach (_.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}"))
      masterSystem.stop(router)
    }

    "deploy its children on remote host driven by programatic definition" in {
      val probe = TestProbe()(masterSystem)
      val router = masterSystem.actorOf(new RemoteRouterConfig(RoundRobinPool(2),
        Seq(Address("akka.tcp", sysName, "localhost", port))).props(Props[Echo]), "blub2")
      val replies = for (i ← 1 to 5) yield {
        router.tell("", probe.ref)
        probe.expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children should have size 2
      children.map(_.parent) should have size 1
      children foreach (_.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}"))
      masterSystem.stop(router)
    }

    "deploy dynamic resizable number of children on remote host driven by configuration" in {
      val probe = TestProbe()(masterSystem)
      val router = masterSystem.actorOf(FromConfig.props(Props[Echo]), "elastic-blub")
      val replies = for (i ← 1 to 5000) yield {
        router.tell("", probe.ref)
        probe.expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children.size should be >= 2
      children.map(_.parent) should have size 1
      children foreach (_.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}"))
      masterSystem.stop(router)
    }

    "deploy remote routers based on configuration" in {
      val probe = TestProbe()(masterSystem)
      val router = masterSystem.actorOf(FromConfig.props(Props[Echo]), "remote-blub")
      router.path.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}")
      val replies = for (i ← 1 to 5) yield {
        router.tell("", probe.ref)
        probe.expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children should have size 2
      val parents = children.map(_.parent)
      parents should have size 1
      parents.head should ===(router.path)
      children foreach (_.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}"))
      masterSystem.stop(router)
    }

    "deploy remote routers based on explicit deployment" in {
      val probe = TestProbe()(masterSystem)
      val router = masterSystem.actorOf(RoundRobinPool(2).props(Props[Echo])
        .withDeploy(Deploy(scope = RemoteScope(AddressFromURIString(s"akka.tcp://${sysName}@localhost:${port}")))), "remote-blub2")
      router.path.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}")
      val replies = for (i ← 1 to 5) yield {
        router.tell("", probe.ref)
        probe.expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children should have size 2
      val parents = children.map(_.parent)
      parents should have size 1
      parents.head should ===(router.path)
      children foreach (_.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}"))
      masterSystem.stop(router)
    }

    "let remote deployment be overridden by local configuration" in {
      val probe = TestProbe()(masterSystem)
      val router = masterSystem.actorOf(RoundRobinPool(2).props(Props[Echo])
        .withDeploy(Deploy(scope = RemoteScope(AddressFromURIString(s"akka.tcp://${sysName}@localhost:${port}")))), "local-blub")
      router.path.address.toString should ===("akka://MasterRemoteRouterSpec")
      val replies = for (i ← 1 to 5) yield {
        router.tell("", probe.ref)
        probe.expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children should have size 2
      val parents = children.map(_.parent)
      parents should have size 1
      parents.head.address should ===(Address("akka.tcp", sysName, "localhost", port))
      children foreach (_.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}"))
      masterSystem.stop(router)
    }

    "let remote deployment router be overridden by local configuration" in {
      val probe = TestProbe()(masterSystem)
      val router = masterSystem.actorOf(RoundRobinPool(2).props(Props[Echo])
        .withDeploy(Deploy(scope = RemoteScope(AddressFromURIString(s"akka.tcp://${sysName}@localhost:${port}")))), "local-blub2")
      router.path.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}")
      val replies = for (i ← 1 to 5) yield {
        router.tell("", probe.ref)
        probe.expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children should have size 4
      val parents = children.map(_.parent)
      parents should have size 1
      parents.head should ===(router.path)
      children foreach (_.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}"))
      masterSystem.stop(router)
    }

    "let remote deployment be overridden by remote configuration" in {
      val probe = TestProbe()(masterSystem)
      val router = masterSystem.actorOf(RoundRobinPool(2).props(Props[Echo])
        .withDeploy(Deploy(scope = RemoteScope(AddressFromURIString(s"akka.tcp://${sysName}@localhost:${port}")))), "remote-override")
      router.path.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}")
      val replies = for (i ← 1 to 5) yield {
        router.tell("", probe.ref)
        probe.expectMsgType[ActorRef].path
      }
      val children = replies.toSet
      children should have size 4
      val parents = children.map(_.parent)
      parents should have size 1
      parents.head should ===(router.path)
      children foreach (_.address.toString should ===(s"akka.tcp://${sysName}@localhost:${port}"))
      masterSystem.stop(router)
    }

    "set supplied supervisorStrategy" in {
      val probe = TestProbe()(masterSystem)
      val escalator = OneForOneStrategy() {
        case e ⇒ probe.ref ! e; SupervisorStrategy.Escalate
      }
      val router = masterSystem.actorOf(new RemoteRouterConfig(
        RoundRobinPool(1, supervisorStrategy = escalator),
        Seq(Address("akka.tcp", sysName, "localhost", port))).props(Props.empty), "blub3")

      router.tell(GetRoutees, probe.ref)
      EventFilter[ActorKilledException](occurrences = 1).intercept {
        probe.expectMsgType[Routees].routees.head.send(Kill, testActor)
      }(masterSystem)
      probe.expectMsgType[ActorKilledException]
    }

  }

}
