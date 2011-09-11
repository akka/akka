/*
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

// package akka.cluster.replication.transactionlog.writebehind.snapshot

// import akka.actor._
// import akka.cluster._
// import Cluster._
// import akka.config.Config
// import akka.cluster.LocalCluster._

// object ReplicationTransactionLogWriteBehindSnapshotMultiJvmSpec {
//   var NrOfNodes = 2

//   sealed trait TransactionLogMessage extends Serializable
//   case class Count(nr: Int) extends TransactionLogMessage
//   case class Log(full: String) extends TransactionLogMessage
//   case object GetLog extends TransactionLogMessage

//   class HelloWorld extends Actor with Serializable {
//     var log = ""
//     //println("Creating HelloWorld log =======> " + log)
//     def receive = {
//       case Count(nr) ⇒
//         log += nr.toString
//         //println("Message to HelloWorld log =======> " + log)
//         self.reply("World from node [" + Config.nodename + "]")
//       case GetLog ⇒
//         self.reply(Log(log))
//     }
//   }
// }

// class ReplicationTransactionLogWriteBehindSnapshotMultiJvmNode1 extends ClusterTestNode {
//   import ReplicationTransactionLogWriteBehindSnapshotMultiJvmSpec._

//   "A cluster" must {

//     "be able to replicate an actor with a transaction log and replay transaction log after actor migration" ignore {

//       barrier("start-node1", NrOfNodes) {
//         Cluster.node.start()
//       }

//       barrier("create-actor-on-node1", NrOfNodes) {
//         val actorRef = Actor.actorOf[HelloWorld]("hello-world-write-behind-snapshot")
//         node.isInUseOnNode("hello-world-write-behind-snapshot") must be(true)
//         actorRef.address must be("hello-world-write-behind-snapshot")
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

// class ReplicationTransactionLogWriteBehindSnapshotMultiJvmNode2 extends MasterClusterTestNode {
//   import ReplicationTransactionLogWriteBehindSnapshotMultiJvmSpec._

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
//         node.isInUseOnNode("hello-world-write-behind-snapshot") must be(true)
//         val actorRef = Actor.registry.local.actorFor("hello-world-write-behind-snapshot").getOrElse(fail("Actor should have been in the local actor registry"))
//         actorRef.address must be("hello-world-write-behind-snapshot")
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
