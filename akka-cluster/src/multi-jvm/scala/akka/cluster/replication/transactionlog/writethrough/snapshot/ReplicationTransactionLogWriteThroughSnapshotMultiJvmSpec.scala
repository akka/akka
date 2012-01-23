/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

// package akka.cluster.replication.transactionlog.writethrough.snapshot

// import akka.actor._
// import akka.cluster._
// import Cluster._
// import akka.config.Config
// import akka.cluster.LocalCluster._

// object ReplicationTransactionLogWriteThroughSnapshotMultiJvmSpec {
//   var NrOfNodes = 2

//   sealed trait TransactionLogMessage extends Serializable
//   case class Count(nr: Int) extends TransactionLogMessage
//   case class Log(full: String) extends TransactionLogMessage
//   case object GetLog extends TransactionLogMessage

//   class HelloWorld extends Actor with Serializable {
//     var log = ""
//     def receive = {
//       case Count(nr) ⇒
//         log += nr.toString
//         reply("World from node [" + Config.nodename + "]")
//       case GetLog ⇒
//         reply(Log(log))
//     }
//   }
// }

// class ReplicationTransactionLogWriteThroughSnapshotMultiJvmNode1 extends ClusterTestNode {
//   import ReplicationTransactionLogWriteThroughSnapshotMultiJvmSpec._

//   "A cluster" must {

//     "be able to replicate an actor with a transaction log and replay transaction log after actor migration" ignore {

//       barrier("start-node1", NrOfNodes) {
//         Cluster.node.start()
//       }

//       barrier("create-actor-on-node1", NrOfNodes) {
//         val actorRef = Actor.actorOf(Props[HelloWorld]("hello-world-write-through-snapshot")
//         node.isInUseOnNode("hello-world-write-through-snapshot") must be(true)
//         actorRef.address must be("hello-world-write-through-snapshot")
//         var counter = 0
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//         counter += 1
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//         counter += 1
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//         counter += 1
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//         counter += 1
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//         counter += 1
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//         counter += 1
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//         counter += 1
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//         counter += 1
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//         counter += 1
//         (actorRef ? Count(counter)).as[String].get must be("World from node [node1]")
//       }

//       barrier("start-node2", NrOfNodes).await()

//       node.shutdown()
//     }
//   }
// }

// class ReplicationTransactionLogWriteThroughSnapshotMultiJvmNode2 extends MasterClusterTestNode {
//   import ReplicationTransactionLogWriteThroughSnapshotMultiJvmSpec._

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
//         node.isInUseOnNode("hello-world-write-through-snapshot") must be(true)
//         val actorRef = Actor.registry.local.actorFor("hello-world-write-through-snapshot").getOrElse(fail("Actor should have been in the local actor registry"))
//         actorRef.address must be("hello-world-write-through-snapshot")
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
