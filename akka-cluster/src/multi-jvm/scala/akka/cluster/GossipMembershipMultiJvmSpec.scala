// package akka.cluster

// import akka.actor.Actor
// import akka.remote._
// import akka.routing._
// import akka.routing.Routing.Broadcast

// object GossipMembershipMultiJvmSpec {
//   val NrOfNodes = 4
//   class SomeActor extends Actor with Serializable {
//     def receive = {
//       case "hit" ⇒ sender ! system.nodename
//       case "end" ⇒ self.stop()
//     }
//   }

//   import com.typesafe.config.ConfigFactory
//   val commonConfig = ConfigFactory.parseString("""
//     akka {
//       loglevel = "WARNING"
//       cluster {
//         seed-nodes = ["localhost:9991"]
//       }
//       remote.server.hostname = "localhost"
//     }""")

//   val node1Config = ConfigFactory.parseString("""
//     akka {
//       remote.server.port = "9991"
//       cluster.nodename = "node1"
//     }""") withFallback commonConfig

//   val node2Config = ConfigFactory.parseString("""
//     akka {
//       remote.server.port = "9992"
//       cluster.nodename = "node2"
//     }""") withFallback commonConfig

//   val node3Config = ConfigFactory.parseString("""
//     akka {
//       remote.server.port = "9993"
//       cluster.nodename = "node3"
//     }""") withFallback commonConfig

//   val node4Config = ConfigFactory.parseString("""
//     akka {
//       remote.server.port = "9994"
//       cluster.nodename = "node4"
//     }""") withFallback commonConfig
// }

// class GossipMembershipMultiJvmNode1 extends AkkaRemoteSpec(GossipMembershipMultiJvmSpec.node1Config) {
//   import GossipMembershipMultiJvmSpec._
//   val nodes = NrOfNodes
//   "A cluster" must {
//     "allow new node to join and should reach convergence with new membership table" in {

//       barrier("setup")
//       remote.start()

//       barrier("start")
//       val actor = system.actorOf(Props[SomeActor]("service-hello")
//       actor.isInstanceOf[RoutedActorRef] must be(true)

//       val connectionCount = NrOfNodes - 1
//       val iterationCount = 10

//       var replies = Map(
//         "node1" -> 0,
//         "node2" -> 0,
//         "node3" -> 0)

//       for (i ← 0 until iterationCount) {
//         for (k ← 0 until connectionCount) {
//           val nodeName = (actor ? "hit").as[String].getOrElse(fail("No id returned by actor"))
//           replies = replies + (nodeName -> (replies(nodeName) + 1))
//         }
//       }

//       barrier("broadcast-end")
//       actor ! Broadcast("end")

//       barrier("end")
//       replies.values foreach { _ must be > (0) }

//       barrier("done")
//     }
//   }
// }

// class GossipMembershipMultiJvmNode2 extends AkkaRemoteSpec(GossipMembershipMultiJvmSpec.node2Config) {
//   import GossipMembershipMultiJvmSpec._
//   val nodes = NrOfNodes
//   "___" must {
//     "___" in {
//       barrier("setup")
//       remote.start()
//       barrier("start")
//       barrier("broadcast-end")
//       barrier("end")
//       barrier("done")
//     }
//   }
// }

// class GossipMembershipMultiJvmNode3 extends AkkaRemoteSpec(GossipMembershipMultiJvmSpec.node3Config) {
//   import GossipMembershipMultiJvmSpec._
//   val nodes = NrOfNodes
//   "___" must {
//     "___" in {
//       barrier("setup")
//       remote.start()
//       barrier("start")
//       barrier("broadcast-end")
//       barrier("end")
//       barrier("done")
//     }
//   }
// }

// class GossipMembershipMultiJvmNode4 extends AkkaRemoteSpec(GossipMembershipMultiJvmSpec.node4Config) {
//   import GossipMembershipMultiJvmSpec._
//   val nodes = NrOfNodes
//   "___" must {
//     "___" in {
//       barrier("setup")
//       remote.start()
//       barrier("start")
//       barrier("broadcast-end")
//       barrier("end")
//       barrier("done")
//     }
//   }
// }
