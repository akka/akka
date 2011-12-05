package akka.remote

import akka.actor.Actor
import akka.remote._
import akka.routing._
import akka.routing.Routing.Broadcast

object RandomRoutedRemoteActorMultiJvmSpec {
  val NrOfNodes = 4
  class SomeActor extends Actor with Serializable {
    def receive = {
      case "hit" ⇒ sender ! system.nodename
      case "end" ⇒ self.stop()
    }
  }

  import com.typesafe.config.ConfigFactory
  val commonConfig = ConfigFactory.parseString("""
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /app/service-hello.router = "random"
          /app/service-hello.nr-of-instances = 3
          /app/service-hello.remote.nodes = ["localhost:9991","localhost:9992","localhost:9993"]
        }
      }
      remote.server.hostname = "localhost"
    }""")

  val node1Config = ConfigFactory.parseString("""
    akka {
      remote.server.port = "9991"
      cluster.nodename = "node1"
    }""") withFallback commonConfig

  val node2Config = ConfigFactory.parseString("""
    akka {
      remote.server.port = "9992"
      cluster.nodename = "node2"
    }""") withFallback commonConfig

  val node3Config = ConfigFactory.parseString("""
    akka {
      remote.server.port = "9993"
      cluster.nodename = "node3"
    }""") withFallback commonConfig

  val node4Config = ConfigFactory.parseString("""
    akka {
      remote.server.port = "9994"
      cluster.nodename = "node4"
    }""") withFallback commonConfig
}

class RandomRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(RandomRoutedRemoteActorMultiJvmSpec.node1Config) {
  import RandomRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("setup")
      remote.start()
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class RandomRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(RandomRoutedRemoteActorMultiJvmSpec.node2Config) {
  import RandomRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("setup")
      remote.start()
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class RandomRoutedRemoteActorMultiJvmNode3 extends AkkaRemoteSpec(RandomRoutedRemoteActorMultiJvmSpec.node3Config) {
  import RandomRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("setup")
      remote.start()
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class RandomRoutedRemoteActorMultiJvmNode4 extends AkkaRemoteSpec(RandomRoutedRemoteActorMultiJvmSpec.node4Config) {
  import RandomRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "A new remote actor configured with a Random router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      barrier("setup")
      remote.start()

      barrier("start")
      val actor = system.actorOf[SomeActor]("service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)

      val connectionCount = NrOfNodes - 1
      val iterationCount = 10

      var replies = Map(
        "node1" -> 0,
        "node2" -> 0,
        "node3" -> 0)

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          val nodeName = (actor ? "hit").as[String].getOrElse(fail("No id returned by actor"))
          replies = replies + (nodeName -> (replies(nodeName) + 1))
        }
      }

      barrier("broadcast-end")
      actor ! Broadcast("end")

      barrier("end")
      replies.values foreach { _ must be > (0) }

      barrier("done")
    }
  }
}

