/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

// package akka.cluster.replication.transactionlog.writethrough.nosnapshot

// import akka.actor._
// import akka.cluster._
// import Cluster._
// import akka.config.Config
// import akka.cluster.LocalCluster._

// object ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmSpec {
//   var NrOfNodes = 2

//   sealed trait TransactionLogMessage extends Serializable
//   case class Count(nr: Int) extends TransactionLogMessage
//   case class Log(full: String) extends TransactionLogMessage
//   case object GetLog extends TransactionLogMessage

//   class HelloWorld extends Actor with Serializable {
//     var log = ""
//     def receive = {
//       case Count(nr) ⇒
//         println("Received number: " + nr + " on " + self.address)
//         log += nr.toString
//         reply("World from node [" + Config.nodename + "]")
//       case GetLog ⇒
//         println("Received getLog on " + uuid)
//         reply(Log(log))
//     }
//   }
// }

// class ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmNode1 extends ClusterTestNode {
//   import ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmSpec._

//   "A cluster" must {

//     "be able to replicate an actor with a transaction log and replay transaction log after actor migration" ignore {

//       barrier("start-node1", NrOfNodes) {
//         Cluster.node.start()
//       }

//       barrier("create-actor-on-node1", NrOfNodes) {
//         val actorRef = Actor.actorOf(Props[HelloWorld]("hello-world-write-through-nosnapshot")
//         actorRef.address must be("hello-world-write-through-nosnapshot")
//         for (i ← 0 until 10)
//           (actorRef ? Count(i)).as[String] must be(Some("World from node [node1]"))
//       }

//       barrier("start-node2", NrOfNodes).await()

//       node.shutdown()
//     }
//   }
// }

// class ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmNode2 extends MasterClusterTestNode {
//   import ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmSpec._

//   val testNodes = NrOfNodes

//   "A cluster" must {

//     "be able to replicate an actor with a transaction log and replay transaction log after actor migration" ignore {

//       barrier("start-node1", NrOfNodes).await()

//       barrier("create-actor-on-node1", NrOfNodes).await()

//       barrier("start-node2", NrOfNodes) {
//         Cluster.node.start()
//       }

//       Thread.sleep(5000) // wait for fail-over from node1 to node2

//       barrier("check-fail-over-to-node2", NrOfNodes - 1) {
//         // both remaining nodes should now have the replica
//         node.isInUseOnNode("hello-world-write-through-nosnapshot") must be(true)
//         val actorRef = Actor.registry.local.actorFor("hello-world-write-through-nosnapshot").getOrElse(fail("Actor should have been in the local actor registry"))
//         actorRef.address must be("hello-world-write-through-nosnapshot")
//         (actorRef ? GetLog).as[Log].get must be(Log("0123456789"))
//       }

//       node.shutdown()
//     }
//   }

//   override def onReady() {
//     LocalBookKeeperEnsemble.start()
//   }

//   override def onShutdown() {
//     TransactionLog.shutdown()
//     LocalBookKeeperEnsemble.shutdown()
//   }
// }
