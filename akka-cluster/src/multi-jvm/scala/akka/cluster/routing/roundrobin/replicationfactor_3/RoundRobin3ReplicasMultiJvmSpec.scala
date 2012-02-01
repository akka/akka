// /**
//  *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
//  */

// package akka.cluster.routing.roundrobin.replicationfactor_3

// import org.scalatest.WordSpec
// import org.scalatest.matchers.MustMatchers
// import org.scalatest.BeforeAndAfterAll

// import akka.cluster._
// import akka.actor._
// import akka.actor.Actor._
// import scala.util.duration._
// import scala.util.{ Duration, Timer }
// import akka.config.Config
// import akka.cluster.LocalCluster._
// import Cluster._

// /**
//  * When a MultiJvmNode is started, will it automatically be part of the cluster (so will it automatically be eligible
//  * for running actors, or will it be just a 'client' talking to the cluster.
//  */
// object RoundRobin3ReplicasMultiJvmSpec {
//   val NrOfNodes = 3

//   class HelloWorld extends Actor with Serializable {
//     def receive = {
//       case "Hello" ⇒
//         reply("World from node [" + Config.nodename + "]")
//     }
//   }
// }

// /**
//  * What is the purpose of this node? Is this just a node for the cluster to make use of?
//  */
// class RoundRobin3ReplicasMultiJvmNode1 extends MasterClusterTestNode {
//   import RoundRobin3ReplicasMultiJvmSpec._

//   val testNodes = NrOfNodes

//   "Round Robin: A cluster" must {

//     "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {

//       //wait till node 1 has started.
//       barrier("start-node1", NrOfNodes) {
//         Cluster.node.boot()
//       }

//       //wait till ndoe 2 has started.
//       barrier("start-node2", NrOfNodes).await()

//       //wait till node 3 has started.
//       barrier("start-node3", NrOfNodes).await()

//       //wait till an actor reference on node 2 has become available.
//       barrier("get-ref-to-actor-on-node2", NrOfNodes) {
//         val timer = Timer(30.seconds, true)
//         while (timer.isTicking && !node.isInUseOnNode("service-hello")) {}
//       }

//       //wait till the node 2 has send a message to the replica's.
//       barrier("send-message-from-node2-to-replicas", NrOfNodes).await()

//       node.shutdown()
//     }
//   }
// }

// class RoundRobin3ReplicasMultiJvmNode2 extends ClusterTestNode {
//   import RoundRobin3ReplicasMultiJvmSpec._
//   import Cluster._

//   "Round Robin: A cluster" must {

//     "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {

//       //wait till node 1 has started.
//       barrier("start-node1", NrOfNodes).await()

//       //wait till node 2 has started.
//       barrier("start-node2", NrOfNodes) {
//         Cluster.node.start()
//       }

//       //wait till node 3 has started.
//       barrier("start-node3", NrOfNodes).await()

//       //check if the actorRef is the expected remoteActorRef.
//       var hello: ActorRef = null
//       barrier("get-ref-to-actor-on-node2", NrOfNodes) {
//         hello = Actor.actorOf(Props[HelloWorld]("service-hello")
//         hello must not equal (null)
//         hello.address must equal("service-hello")
//         hello.isInstanceOf[ClusterActorRef] must be(true)
//       }

//       barrier("send-message-from-node2-to-replicas", NrOfNodes) {
//         //todo: is there a reason to check for null again since it already has been done in the previous block.
//         hello must not equal (null)

//         val replies = collection.mutable.Map.empty[String, Int]
//         def count(reply: String) = {
//           if (replies.get(reply).isEmpty) replies.put(reply, 1)
//           else replies.put(reply, replies(reply) + 1)
//         }

//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node2")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node3")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node2")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node3")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node2")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node3")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node2")))
//         count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node3")))

//         replies("World from node [node1]") must equal(4)
//         replies("World from node [node2]") must equal(4)
//         replies("World from node [node3]") must equal(4)
//       }

//       node.shutdown()
//     }
//   }
// }

// class RoundRobin3ReplicasMultiJvmNode3 extends ClusterTestNode {
//   import RoundRobin3ReplicasMultiJvmSpec._
//   import Cluster._

//   "Round Robin: A cluster" must {

//     "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {
//       barrier("start-node1", NrOfNodes).await()

//       barrier("start-node2", NrOfNodes).await()

//       barrier("start-node3", NrOfNodes) {
//         Cluster.node.start()
//       }

//       barrier("get-ref-to-actor-on-node2", NrOfNodes) {
//         val timer = Timer(30.seconds, true)
//         while (timer.isTicking && !node.isInUseOnNode("service-hello")) {}
//       }

//       barrier("send-message-from-node2-to-replicas", NrOfNodes).await()

//       node.shutdown()
//     }
//   }
// }
