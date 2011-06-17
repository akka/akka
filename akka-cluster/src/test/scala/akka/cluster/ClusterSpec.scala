/*
package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Spec }

import org.I0Itec.zkclient._

import akka.actor._
import akka.actor.Actor._
import akka.serialization.{ Serializers, SerializerBasedActorFormat }
import akka.util.Helpers._
import akka.actor.DeploymentConfig._

import java.util.concurrent.{ CyclicBarrier, TimeUnit }

import scala.collection.JavaConversions._

class MyJavaSerializableActor extends Actor with Serializable {
  var count = 0

  def receive = {
    case "hello" ⇒
      count = count + 1
      self.reply("world " + count)
  }
}

object BinaryFormatMyJavaSerializableActor {
  implicit object MyJavaSerializableActorFormat extends SerializerBasedActorFormat[MyJavaSerializableActor] with Serializable {
    val serializer = Serializers.Java
  }
}
class ClusterSpec extends WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import Cluster._

  val dataPath = "_akka_cluster/data"
  val logPath  = "_akka_cluster/log"

  var zkServer: ZkServer = _

  "A ClusterNode" should {
    "and another cluster node should be able to agree on a leader election when starting up" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-1-1", port = 9001))
      node1.start()

      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-1-2", port = 9002))
      node2.start()

      node1.leader must be(node1.leaderLock.getId)
      node1.leader must be(node2.leader)

      node1.stop()
      node2.stop()
      node1.isRunning must be(false)
      node2.isRunning must be(false)
    }

    "and two another cluster nodes should be able to agree on a leader election when starting up" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-2-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-2-2", port = 9002))
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-2-3", port = 9003))

      node1.start()
      node2.start()
      node3.start()

      node1.leader must be(node1.leaderLock.getId)
      node1.leader must be(node2.leader)
      node2.leader must be(node3.leader)
      node3.leader must be(node1.leader)

      node1.stop()
      node2.stop()
      node3.stop()
      node1.isRunning must be(false)
      node2.isRunning must be(false)
      node3.isRunning must be(false)
    }

    "and two another cluster nodes should be able to agree on a leader election the first is shut down" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-3-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-3-2", port = 9002))
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-3-3", port = 9003))

      node1.start()
      node2.start()
      node3.start()

      node1.leader must be(node1.leaderLock.getId)
      node1.leader must be(node2.leader)
      node2.leader must be(node3.leader)
      node3.leader must be(node1.leader)

      node1.stop()
      node1.isRunning must be(false)
      Thread.sleep(500)
      node2.leader must be(node2.leaderLock.getId)
      node2.leader must be(node2.leader)

      node2.stop()
      node2.isRunning must be(false)
      Thread.sleep(500)
      node3.leader must be(node3.leaderLock.getId)

      node3.stop()
      node3.isRunning must be(false)
    }

    "and two another cluster nodes should be able to agree on a leader election the second is shut down" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-4-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-4-2", port = 9002))
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-4-3", port = 9003))

      node1.start()
      node2.start()
      node3.start()

      node1.leader must be(node1.leaderLock.getId)
      node1.leader must be(node2.leader)
      node2.leader must be(node3.leader)
      node3.leader must be(node1.leader)

      node2.stop()
      node2.isRunning must be(false)
      Thread.sleep(500)
      node1.leader must be(node1.leaderLock.getId)
      node3.leader must be(node1.leader)

      node3.stop()
      node3.isRunning must be(false)
      Thread.sleep(500)
      node1.leader must be(node1.leaderLock.getId)

      node1.stop()
      node1.isRunning must be(false)
    }

    "and two another cluster nodes should be able to agree on a leader election the third is shut down" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-5-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-5-2", port = 9002))
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "leader-5-3", port = 9003))

      node1.start()
      node2.start()
      node3.start()

      node1.leader must be(node1.leaderLock.getId)
      node1.leader must be(node2.leader)
      node2.leader must be(node3.leader)
      node3.leader must be(node1.leader)

      node3.stop()
      node3.isRunning must be(false)
      Thread.sleep(500)
      node1.leader must be(node1.leaderLock.getId)
      node2.leader must be(node1.leader)

      node2.stop()
      Thread.sleep(500)
      node2.isRunning must be(false)
      node1.leader must be(node1.leaderLock.getId)

      node1.stop()
      node1.isRunning must be(false)
    }

    "be able to cluster an actor by ActorRef" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor]("actor-address").start

      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "cluster-actor-1", port = 9001))
      node.start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)

      node.isClustered(actorRef.address) must be(true)
      node.uuidsForClusteredActors.exists(_ == actorRef.uuid) must be(true)

      node.stop
    }

    "be able to remove an actor by actor uuid" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor]("actor-address").start

      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-uuid", port = 9001))
      node.start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)

      node.uuidsForClusteredActors.exists(_ == actorRef.uuid) must be(true)

      // deregister actor
      node.remove(actorRef.uuid)
      node.uuidsForClusteredActors.exists(_ == actorRef.uuid) must be(false)

      node.stop
    }

    "be able to remove an actor by actor address" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor]("actor-address").start

      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-id", port = 9001))
      node.start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)

      node.isClustered(actorRef.address) must be(true)
      node.addressesForClusteredActors.exists(_ == actorRef.address) must be(true)

      // deregister actor
      node.remove(actorRef.address)
      node.addressesForClusteredActors.exists(_ == actorRef.address) must be(false)

      node.stop
    }

    "be able to use an actor by actor address" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "use-actor-id", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor]("actor-address").start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(actorRef1.address) must be(true)
      node.addressesForClusteredActors.exists(_ == actorRef1.address) must be(true)

      // check out actor
      val actorRef2 = node.use(actorRef1.address).head
      node.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "use-actor-id")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to release an actor by address" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "release-actor-id", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor]("actor-address").start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(actorRef1.address) must be(true)
      node.addressesForClusteredActors.exists(_ == actorRef1.address) must be(true)

      // check out actor
      val actorRef2 = node.use(actorRef1.address).head
      node.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "release-actor-id")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      // check in actor
      node.release(actorRef2.address)
      node.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "release-actor-id")) must be(false)

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to release used actor on remove an actor by actor address" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor]("actor-address").start

      val node  = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-id", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-uuid-2", port = 9002)).start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)
      val actorRef2 = node2.use(actorRef.address).head

      node2.isClustered(actorRef.address) must be(true)
      node.addressesForClusteredActors.exists(_ == actorRef.address) must be(true)
      node.nodesForActorsInUseWithAddress(actorRef.address) must have length (1)

      // deregister actor
      node.remove(actorRef.address)
      node.addressesForClusteredActors.exists(_ == actorRef.address) must be(false)

      node.nodesForActorsInUseWithAddress(actorRef.address) must have length (0)
      node.stop
    }

    "be able to get home address for a clustered actor" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "get-home-address", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor]("actor-address").start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(actorRef1.address) must be(true)
      node.uuidsForClusteredActors.exists(_ == actorRef1.uuid) must be(true)

      // check out actor
      val actorRef2 = node.use(actorRef1.address).head
      node.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "get-home-address")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      val addresses = node.addressesForActor(actorRef1.address)
      addresses.length must be > (0)
      addresses(0)._2.getPort must equal(9001)

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to migrate an actor between two nodes using address" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-id-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-id-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor]("actor-address").start
      val actorRef2 = actorOf[MyJavaSerializableActor]("actor-address").start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(actorRef1.address) must be(true)
      node1.addressesForClusteredActors.exists(_ == actorRef1.address) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(actorRef1.address).head
      val actorRef2_2 = node1.use(actorRef2.address).head
      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)
      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)

      node1.addressesForActorsInUse.exists(_ == actorRef1.address) must be(true)
      node1.addressesForActorsInUse.exists(_ == actorRef2.address) must be(true)
      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)
      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)
      node1.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)
      node1.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)

      node2.addressesForActorsInUse.exists(_ == actorRef1.address) must be(false)
      node2.addressesForActorsInUse.exists(_ == actorRef2.address) must be(false)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-2")) must be(false)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-2")) must be(false)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-id-2")) must be(false)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-id-2")) must be(false)

      // migrate to node2
      node1.migrate(node1.nodeAddress, node2.nodeAddress, actorRef1_2.address)

      val actorRef1_3 = node2.use(actorRef1.address).head
      val actorRef2_3 = node2.use(actorRef2.address).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node1.addressesForActorsInUse.exists(_ == actorRef1.address) must be(false)
      node1.addressesForActorsInUse.exists(_ == actorRef2.address) must be(false)
      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(false)
      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(false)
      node1.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(false)
      node1.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-id-1")) must be(false)

      node2.addressesForActorsInUse.exists(_ == actorRef1.address) must be(true)
      node2.addressesForActorsInUse.exists(_ == actorRef2.address) must be(true)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-2")) must be(true)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-id-2")) must be(true)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-id-2")) must be(true)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-id-2")) must be(true)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node1.stop
      node2.stop
    }

    "automatically migrate actors of a failed node in a cluster of two nodes using address" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-2-id-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-2-id-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor]("actor-address").start
      val actorRef2 = actorOf[MyJavaSerializableActor]("actor-address").start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(actorRef1.address) must be(true)
      node1.addressesForClusteredActors.exists(_ == actorRef1.address) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(actorRef1.address).head
      val actorRef2_2 = node1.use(actorRef2.address).head

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.addressesForActorsInUse.exists(_ == actorRef1.address) must be(true)
      node1.addressesForActorsInUse.exists(_ == actorRef2.address) must be(true)
      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-2-id-1")) must be(true)
      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-2-id-1")) must be(true)
      node1.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-2-id-1")) must be(true)
      node1.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-2-id-1")) must be(true)

      node2.addressesForActorsInUse.exists(_ == actorRef1.address) must be(false)
      node2.addressesForActorsInUse.exists(_ == actorRef2.address) must be(false)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(false)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(false)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(false)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(false)

      // should migrate to node2
      node1.stop
      node1.isRunning must be(false)
      Thread.sleep(500)

      val actorRef1_3 = node2.use(actorRef1.address).head
      val actorRef2_3 = node2.use(actorRef2.address).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node2.addressesForActorsInUse.exists(_ == actorRef1.address) must be(true)
      node2.addressesForActorsInUse.exists(_ == actorRef2.address) must be(true)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(true)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(true)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(true)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(true)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node2.stop
    }

    "automatically migrate actors of a failed node in a cluster of three nodes using address" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-id-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-id-2", port = 9002))
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-id-3", port = 9003))
      node1.start
      node2.start
      node3.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor]("actor-address").start
      val actorRef2 = actorOf[MyJavaSerializableActor]("actor-address").start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(actorRef1.address) must be(true)
      node1.addressesForClusteredActors.exists(_ == actorRef1.address) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(actorRef1.address).head
      val actorRef2_2 = node1.use(actorRef2.address).head
      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.addressesForActorsInUse.exists(_ == actorRef1.address) must be(true)
      node1.addressesForActorsInUse.exists(_ == actorRef2.address) must be(true)
      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-1")) must be(true)
      node1.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-1")) must be(true)
      node1.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-1")) must be(true)
      node1.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-1")) must be(true)

      node2.addressesForActorsInUse.exists(_ == actorRef1.address) must be(false)
      node2.addressesForActorsInUse.exists(_ == actorRef2.address) must be(false)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)

      node3.addressesForActorsInUse.exists(_ == actorRef1.address) must be(false)
      node3.addressesForActorsInUse.exists(_ == actorRef2.address) must be(false)
      node3.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(false)
      node3.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(false)
      node3.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(false)
      node3.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(false)

      // should migrate to node2
      node1.stop
      node1.isRunning must be(false)
      Thread.sleep(500)

      val actorRef1_3 = node3.use(actorRef1.address).head
      val actorRef2_3 = node3.use(actorRef2.address).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node3.addressesForActorsInUse.exists(_ == actorRef1.address) must be(true)
      node3.addressesForActorsInUse.exists(_ == actorRef2.address) must be(true)
      node3.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(true)
      node3.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(true)
      node3.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(true)
      node3.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(true)

      node2.addressesForActorsInUse.exists(_ == actorRef1.address) must be(false)
      node2.addressesForActorsInUse.exists(_ == actorRef2.address) must be(false)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(actorRef1.address, node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(actorRef2.address, node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node2.stop
      node3.stop
    }

    "be able to migrate an actor between two nodes using address and see that 'ref' to it is redirected and continue to work" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-id-and-see-ref-failover-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-id-and-see-ref-failover-2", port = 9002))
      node1.start
      node2.start

      // create actors
      Deployer.deploy(Deploy(
        "actor-address", Direct,
        Clustered(Home("localhost", 2552), NoReplicas, Stateless)))
      val actorRef1 = actorOf[MyJavaSerializableActor]("actor-address").start

      Thread.sleep(500)

      // register actors
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1)

      Thread.sleep(500)

      // use on node1
      node1.use(actorRef1.address)

      node1.isInUseOnNode(actorRef1.address, node = node1.nodeAddress) must be(true)

      // check out actor ref on node2
      val actorRef1_2 = node2.ref(actorRef1.address, router = Router.Direct)

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")

      // migrate to node2
      node1.migrate(node1.nodeAddress, node2.nodeAddress, actorRef1.address)

      Thread.sleep(500)

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 2")

      actorRef1.stop
      actorRef1_2.stop

      node1.stop
      node2.stop
    }

    "be able to set and get config elements" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "set-get-config-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "set-get-config-2", port = 9002))
      node1.start
      node2.start

      node1.setConfigElement("key1", "value1".getBytes)
      node2.getConfigElement("key1") must be("value1".getBytes)

      node2.setConfigElement("key2", "value2".getBytes)
      node1.getConfigElement("key2") must be("value2".getBytes)

      node1.stop
      node2.stop
    }

    "be able to remove config elements" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-config-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-config-2", port = 9002))
      node1.start
      node2.start

      node1.setConfigElement("key1", "value1".getBytes)
      node2.getConfigElement("key1") must be("value1".getBytes)

      node2.removeConfigElement("key1")
      node1.getConfigElement("key1") must be(null)

      node1.stop
      node2.stop
    }

    "be able to replicate an actor" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor]("actor-address").start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "replicate-actor-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "replicate-actor-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "replicate-actor-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 3
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      node1.isInUseOnNode(actorRef.address, node = NodeAddress("test-cluster", "replicate-actor-1", port = 9001)) must be(true)
      node2.isInUseOnNode(actorRef.address, node = NodeAddress("test-cluster", "replicate-actor-2", port = 9002)) must be(true)
      node3.isInUseOnNode(actorRef.address, node = NodeAddress("test-cluster", "replicate-actor-3", port = 9003)) must be(true)

      node1.stop
      node2.stop
      node3.stop
    }

    "be able to create a reference to a replicated actor by address using Router.Direct routing" in {
      Deployer.deploy(Deploy(
        "actor-address", Direct,
        Clustered(Home("localhost", 2552), NoReplicas, Stateless)))
      val actorRef = actorOf[MyJavaSerializableActor]("actor-address").start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-direct-actor-by-id-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-direct-actor-by-id-2", port = 9002)).start

      Thread.sleep(500)

      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 1
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(actorRef.address, router = Router.Direct)

      (ref !! "hello").getOrElse("_") must equal("world 1")
      (ref !! "hello").getOrElse("_") must equal("world 2")

      node1.stop
      node2.stop
    }

   "be able to create a reference to a replicated actor by address using Router.Random routing" in {
      // create actor
     Deployer.deploy(Deploy(
       "actor-address", Direct,
       Clustered(Home("localhost", 2552), NoReplicas, Stateless)))
      val actorRef = actorOf[MyJavaSerializableActor]("actor-address").start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-id-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-id-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-id-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 2
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(actorRef.address, router = Router.Random)

      (ref !! "hello").getOrElse("_") must equal("world 1")

      node1.stop
      node2.stop
      node3.stop
    }

   "be able to create a reference to a replicated actor by address using Router.RoundRobin routing" in {
      // create actor
     val actorRef = actorOf[MyJavaSerializableActor]("actor-address").start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-id-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-id-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-id-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 3
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(actorRef.address, router = Router.RoundRobin)

      node1.isInUseOnNode(actorRef.address, node = NodeAddress("test-cluster", "router-round-robin-actor-by-id-1", port = 9001)) must be(true)
      node2.isInUseOnNode(actorRef.address, node = NodeAddress("test-cluster", "router-round-robin-actor-by-id-2", port = 9002)) must be(true)
      node3.isInUseOnNode(actorRef.address, node = NodeAddress("test-cluster", "router-round-robin-actor-by-id-3", port = 9003)) must be(true)

      val addresses = node1.addressesForActor(actorRef.address)
      addresses.length must equal(3)

      (ref !! "hello").getOrElse("_") must equal("world 1")
      (ref !! "hello").getOrElse("_") must equal("world 1")
      (ref !! "hello").getOrElse("_") must equal("world 1")

      (ref !! "hello").getOrElse("_") must equal("world 2")
      (ref !! "hello").getOrElse("_") must equal("world 2")
      (ref !! "hello").getOrElse("_") must equal("world 2")

      (ref !! "hello").getOrElse("_") must equal("world 3")
      (ref !! "hello").getOrElse("_") must equal("world 3")
      (ref !! "hello").getOrElse("_") must equal("world 3")

      node1.stop
      node2.stop
      node3.stop
    }
  }

  override def beforeAll() = {
    zkServer = Cluster.startLocalCluster(dataPath, logPath)
  }

  override def afterAll() = {
    Cluster.shutdownLocalCluster
    Actor.registry.local.shutdownAll
  }
}
*/
/*
    "be able to subscribe to actor location change events" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "test-node1", port = 9991)
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "test-node2", port = 9992)

      val barrier = new CyclicBarrier(2)

      node2.register(ActorLocationsChildChange, new ChangeListener() {
          def notify(node: ClusterNode) = barrier.await
        })

      try {
        node1.start
        node2.start

        // create actors
        val actorRef1 = actorOf[MyJavaSerializableActor]("actor-address").start
        val actorRef2 = actorOf[MyJavaSerializableActor]("actor-address").start

        // register actors
        var serializeMailbox = true
        import BinaryFormatMyJavaSerializableActor._
        node1.store(actorRef1, serializeMailbox)
        node1.store(actorRef2, serializeMailbox)

        node1.isClustered(ActorAddress(actorRef1.uuid)) must be (true)
        node1.uuidsForClusteredActors.exists(_ == actorRef1.uuid) must be (true)

        // check out actor
        val actorRef1_2 = node1.use(actorRef1.uuid)
        val actorRef2_2 = node1.use(actorRef2.uuid)

        // should migrate to node2
        node1.stop
        node1.isRunning must be (false)

        barrier.await(20, TimeUnit.SECONDS)

        actorRef1.stop
        actorRef2.stop
        actorRef1_2.stop
        actorRef2_2.stop

      } finally {
        node2.stop
        node2.isRunning must be (false)
      }
    }

*/
