package akka.cloud.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Spec }

import org.I0Itec.zkclient._

import akka.actor._
import akka.actor.Actor._
import akka.serialization.{Serializer, SerializerBasedActorFormat}

import akka.cloud.common.Util._

import java.util.concurrent.{ CyclicBarrier, TimeUnit }

import scala.collection.JavaConversions._

// FIXME: Test sending all funs

class MyJavaSerializableActor extends Actor with Serializable {
  var count = 0

  def receive = {
    case "hello" =>
      count = count + 1
      self.reply("world " + count)
  }
}

object BinaryFormatMyJavaSerializableActor {
  implicit object MyJavaSerializableActorFormat extends SerializerBasedActorFormat[MyJavaSerializableActor] with Serializable {
    val serializer = Serializer.Java
  }
}

class ClusterSpec extends WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import Cluster._

  val dataPath = "_akka_cluster/data"
  val logPath  = "_akka_cluster/log"

  var zkServer: ZkServer = _

  "A ClusterNode" should {
    "be able to start and stop - one node" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "start-stop-1", port = 9001))
      node.start()

      Thread.sleep(500)
      node.membershipNodes.size must be(1)

      node.stop()

      Thread.sleep(500)
      node.membershipNodes.size must be(0)
      node.isRunning must be(false)
    }

    "be able to start and stop - two nodes" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "start-stop-2-1", port = 9001))
      node1.start()

      Thread.sleep(500)
      node1.membershipNodes.size must be(1)

      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "start-stop-2-2", port = 9002))
      node2.start()

      Thread.sleep(500)
      node1.leader must be(node2.leader)
      node1.membershipNodes.size must be(2)

      node1.stop()
      Thread.sleep(500)
      node2.membershipNodes.size must be(1)

      node2.stop()
      Thread.sleep(500)
      node1.isRunning must be(false)
      node2.isRunning must be(false)
    }

    "be able to subscribe to new connection of membership node events" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "new-membership-connection-1", port = 9001))
      // register listener
      val barrier = new CyclicBarrier(2)
      node.register(new ChangeListener {
        override def nodeConnected(node: String, client: ClusterNode) = barrier.await
      })

      // start node
      node.start()
      barrier.await(20, TimeUnit.SECONDS)
    }

    "be able to subscribe to new disconnection of membership node events" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "new-membership-disconnection-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "new-membership-disconnection-2", port = 9002))
      // register listener
      val barrier = new CyclicBarrier(2)
      node1.register(new ChangeListener {
        override def nodeDisconnected(node: String, client: ClusterNode) = barrier.await
      })

      // start node
      node1.start()
      node2.start()
      node2.stop()
      node2.isRunning must be(false)
      barrier.await(20, TimeUnit.SECONDS)
    }

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
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "cluster-actor-1", port = 9001))
      node.start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)

      node.isClustered(ActorAddress(actorUuid = actorRef.uuid)) must be(true)
      node.uuidsForClusteredActors.exists(_ == actorRef.uuid) must be(true)

      node.stop
    }

    "be able to cluster an actor by class" in {
      // create actor
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "cluster-actor-1", port = 9001))
      node.start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      node.store(classOf[MyJavaSerializableActor])

      node.isClustered(ActorAddress(actorClassName = classOf[MyJavaSerializableActor].getName)) must be(true)

      node.stop
    }

    "be able to remove an actor by actor uuid" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-uuid", port = 9001))
      node.start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)

      node.isClustered(ActorAddress(actorUuid = actorRef.uuid)) must be(true)
      node.uuidsForClusteredActors.exists(_ == actorRef.uuid) must be(true)

      // deregister actor
      node.remove(ActorAddress(actorUuid = actorRef.uuid))
      node.uuidsForClusteredActors.exists(_ == actorRef.uuid) must be(false)

      node.stop
    }

    "be able to remove an actor by actor id" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-id", port = 9001))
      node.start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)

      node.isClustered(ActorAddress(actorId = actorRef.id)) must be(true)
      node.idsForClusteredActors.exists(_ == actorRef.id) must be(true)

      // deregister actor
      node.remove(ActorAddress(actorId = actorRef.id))
      node.idsForClusteredActors.exists(_ == actorRef.id) must be(false)

      node.stop
    }

    "be able to remove an actor by actor class name" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-classname", port = 9001))
      node.start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)

      node.isClustered(ActorAddress(actorClassName = actorRef.actorClassName)) must be(true)
      node.classNamesForClusteredActors.exists(_ == actorRef.actorClassName) must be(true)

      // deregister actor
      node.remove(ActorAddress(actorClassName = actorRef.actorClassName))
      node.classNamesForClusteredActors.exists(_ == actorRef.actorClassName) must be(false)

      node.stop
    }

    "be able to use an actor by actor uuid" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "use-actor-uuid", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(ActorAddress(actorUuid = actorRef1.uuid)) must be(true)
      node.uuidsForClusteredActors.exists(_ == actorRef1.uuid) must be(true)

      // check out actor
      val actorRef2 = node.use(ActorAddress(actorUuid = actorRef1.uuid)).head
      node.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "use-actor-uuid")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to use an actor by actor id" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "use-actor-id", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(ActorAddress(actorId = actorRef1.id)) must be(true)
      node.idsForClusteredActors.exists(_ == actorRef1.id) must be(true)

      // check out actor
      val actorRef2 = node.use(ActorAddress(actorId = actorRef1.id)).head
      node.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "use-actor-id")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to use an actor by actor class name" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "use-actor-classname", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(ActorAddress(actorClassName = actorRef1.actorClassName)) must be(true)
      node.classNamesForClusteredActors.exists(_ == actorRef1.actorClassName) must be(true)

      // check out actor
      val actorRef2 = node.use(ActorAddress(actorClassName = actorRef1.actorClassName)).head
      node.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "use-actor-classname")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to release an actor by uuid" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "release-actor-uuid", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(ActorAddress(actorUuid = actorRef1.uuid)) must be(true)
      node.uuidsForClusteredActors.exists(_ == actorRef1.uuid) must be(true)

      // check out actor
      val actorRef2 = node.use(ActorAddress(actorUuid = actorRef1.uuid)).head
      node.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "release-actor-uuid")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      // check in actor
      node.release(ActorAddress(actorUuid = actorRef2.uuid))
      node.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "release-actor-uuid")) must be(false)

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to release an actor by id" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "release-actor-id", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(ActorAddress(actorId = actorRef1.id)) must be(true)
      node.idsForClusteredActors.exists(_ == actorRef1.id) must be(true)

      // check out actor
      val actorRef2 = node.use(ActorAddress(actorId = actorRef1.id)).head
      node.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "release-actor-id")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      // check in actor
      node.release(ActorAddress(actorId = actorRef2.id))
      node.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "release-actor-id")) must be(false)

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to release an actor by class name" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "release-actor-classname", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(ActorAddress(actorClassName = actorRef1.actorClassName)) must be(true)
      node.classNamesForClusteredActors.exists(_ == actorRef1.actorClassName) must be(true)

      // check out actor
      val actorRef2 = node.use(ActorAddress(actorClassName = actorRef1.actorClassName)).head
      node.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "release-actor-classname")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      // check in actor
      node.release(ActorAddress(actorClassName = actorRef2.actorClassName))
      node.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "release-actor-classname")) must be(false)

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to release used actor on remove an actor by actor uuid" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node  = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-uuid", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-uuid-2", port = 9002)).start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)
      val actorRef2 = node2.use(ActorAddress(actorUuid = actorRef.uuid)).head

      node2.isClustered(ActorAddress(actorUuid = actorRef.uuid)) must be(true)
      node.uuidsForClusteredActors.exists(_ == actorRef.uuid) must be(true)
      node.nodesForActorsInUseWithUuid(actorRef.uuid) must have length (1)

      // deregister actor
      node.remove(ActorAddress(actorUuid = actorRef.uuid))
      node.uuidsForClusteredActors.exists(_ == actorRef.uuid) must be(false)

      node.nodesForActorsInUseWithUuid(actorRef.uuid) must have length (0)
      node.stop
    }

    "be able to release used actor on remove an actor by actor id" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node  = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-id", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-uuid-2", port = 9002)).start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)
      val actorRef2 = node2.use(ActorAddress(actorId = actorRef.id)).head

      node2.isClustered(ActorAddress(actorId = actorRef.id)) must be(true)
      node.idsForClusteredActors.exists(_ == actorRef.id) must be(true)
      node.nodesForActorsInUseWithId(actorRef.id) must have length (1)

      // deregister actor
      node.remove(ActorAddress(actorId = actorRef.id))
      node.idsForClusteredActors.exists(_ == actorRef.id) must be(false)

      node.nodesForActorsInUseWithId(actorRef.id) must have length (0)
      node.stop
    }

    "be able to release used actor on remove an actor by actor class name" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-classname", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "remove-actor-uuid-2", port = 9002)).start
      node.start

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      var serializeMailbox = true
      node.store(actorRef, serializeMailbox)
      val actorRef2 = node2.use(ActorAddress(actorClassName = actorRef.actorClassName)).head

      node2.isClustered(ActorAddress(actorClassName = actorRef.actorClassName)) must be(true)
      node.classNamesForClusteredActors.exists(_ == actorRef.actorClassName) must be(true)
      node.nodesForActorsInUseWithClassName(actorRef.actorClassName) must have length (1)

      // deregister actor
      node.remove(ActorAddress(actorClassName = actorRef.actorClassName))
      node.classNamesForClusteredActors.exists(_ == actorRef.actorClassName) must be(false)
      node.nodesForActorsInUseWithClassName(actorRef.actorClassName) must have length (0)

      node.stop
    }

    "be able to get home address for a clustered actor" in {
      val node = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "get-home-address", port = 9001))
      node.start

      // create actor
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      (actorRef1 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef1 !! "hello").getOrElse("_") must equal("world 2")

      // register actor
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node.store(actorRef1, serializeMailbox)
      node.isClustered(ActorAddress(actorUuid = actorRef1.uuid)) must be(true)
      node.uuidsForClusteredActors.exists(_ == actorRef1.uuid) must be(true)

      // check out actor
      val actorRef2 = node.use(ActorAddress(actorUuid = actorRef1.uuid)).head
      node.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "get-home-address")) must be(true)
      (actorRef2 !! "hello").getOrElse("_") must equal("world 3")

      val addresses = node.addressesForActor(ActorAddress(actorUuid = actorRef1.uuid))
      addresses.length must be > (0)
      addresses(0)._2.getPort must equal(9001)

      actorRef1.stop
      actorRef2.stop

      node.stop
    }

    "be able to migrate an actor between two nodes using uuid" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-uuid-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-uuid-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(ActorAddress(actorUuid = actorRef1.uuid)) must be(true)
      node1.uuidsForClusteredActors.exists(_ == actorRef1.uuid) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(ActorAddress(actorUuid = actorRef1.uuid)).head
      val actorRef2_2 = node1.use(ActorAddress(actorUuid = actorRef2.uuid)).head
      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(true)

      node1.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(true)
      node1.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(true)

      node2.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(false)
      node2.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-uuid-2")) must be(false)

      // migrate to node2
      node1.migrate(node1.nodeAddress, node2.nodeAddress, ActorAddress(actorUuid = actorRef1_2.uuid))
      node1.migrate(node1.nodeAddress, node2.nodeAddress, ActorAddress(actorUuid = actorRef2_2.uuid))

      val actorRef1_3 = node2.use(ActorAddress(actorUuid = actorRef1.uuid)).head
      val actorRef2_3 = node2.use(ActorAddress(actorUuid = actorRef2.uuid)).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node1.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(false)
      node1.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(false)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(false)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(false)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(false)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-uuid-1")) must be(false)

      node2.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(true)
      node2.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-uuid-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-uuid-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-uuid-2")) must be(true)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node1.stop
      node2.stop
    }

    "be able to migrate an actor between two nodes using id" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-id-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-id-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(ActorAddress(actorId = actorRef1.id)) must be(true)
      node1.idsForClusteredActors.exists(_ == actorRef1.id) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(ActorAddress(actorId = actorRef1.id)).head
      val actorRef2_2 = node1.use(ActorAddress(actorId = actorRef2.id)).head
      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)

      node1.idsForActorsInUse.exists(_ == actorRef1.id) must be(true)
      node1.idsForActorsInUse.exists(_ == actorRef2.id) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(true)

      node2.idsForActorsInUse.exists(_ == actorRef1.id) must be(false)
      node2.idsForActorsInUse.exists(_ == actorRef2.id) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-id-2")) must be(false)

      // migrate to node2
      node1.migrate(node1.nodeAddress, node2.nodeAddress, ActorAddress(actorId = actorRef1_2.id))

      val actorRef1_3 = node2.use(ActorAddress(actorId = actorRef1.id)).head
      val actorRef2_3 = node2.use(ActorAddress(actorId = actorRef2.id)).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node1.idsForActorsInUse.exists(_ == actorRef1.id) must be(false)
      node1.idsForActorsInUse.exists(_ == actorRef2.id) must be(false)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(false)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(false)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(false)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-id-1")) must be(false)

      node2.idsForActorsInUse.exists(_ == actorRef1.id) must be(true)
      node2.idsForActorsInUse.exists(_ == actorRef2.id) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-id-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-id-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-id-2")) must be(true)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node1.stop
      node2.stop
    }

    "be able to migrate an actor between two nodes using actor class name" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-class-name-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-class-name-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(ActorAddress(actorClassName = actorRef1.actorClassName)) must be(true)
      node1.classNamesForClusteredActors.exists(_ == actorRef1.actorClassName) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(ActorAddress(actorClassName = actorRef1.actorClassName)).head
      val actorRef2_2 = node1.use(ActorAddress(actorClassName = actorRef2.actorClassName)).head
      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-1")) must be(true)

      node1.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(true)
      node1.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-1")) must be(true)

      node2.idsForActorsInUse.exists(_ == actorRef1.actorClassName) must be(false)
      node2.idsForActorsInUse.exists(_ == actorRef2.actorClassName) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-2")) must be(false)

      // migrate to node2
      node1.migrate(node1.nodeAddress, node2.nodeAddress, ActorAddress(actorClassName = actorRef1_2.actorClassName))

      val actorRef1_3 = node2.use(ActorAddress(actorClassName = actorRef1.actorClassName)).head
      val actorRef2_3 = node2.use(ActorAddress(actorClassName = actorRef2.actorClassName)).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node1.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(false)
      node1.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(false)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-1")) must be(false)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-1")) must be(false)

      node2.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(true)
      node2.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(true)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-class-name-2")) must be(true)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node1.stop
      node2.stop
    }

    "automatically migrate actors of a failed node in a cluster of two nodes using uuid" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-2-uuid-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-2-uuid-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(ActorAddress(actorUuid = actorRef1.uuid)) must be(true)
      node1.uuidsForClusteredActors.exists(_ == actorRef1.uuid) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(ActorAddress(actorUuid = actorRef1.uuid)).head
      val actorRef2_2 = node1.use(ActorAddress(actorUuid = actorRef2.uuid)).head

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(true)
      node1.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-1")) must be(true)

      node2.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(false)
      node2.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-2")) must be(false)

      // should migrate to node2
      node1.stop
      node1.isRunning must be(false)
      Thread.sleep(500)

      val actorRef1_3 = node2.use(ActorAddress(actorUuid = actorRef1.uuid)).head
      val actorRef2_3 = node2.use(ActorAddress(actorUuid = actorRef2.uuid)).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node2.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(true)
      node2.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-2-uuid-2")) must be(true)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node1.stop
    }

    "automatically migrate actors of a failed node in a cluster of two nodes using id" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-2-id-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-2-id-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(ActorAddress(actorClassName = actorRef1.id)) must be(true)
      node1.idsForClusteredActors.exists(_ == actorRef1.id) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(ActorAddress(actorId = actorRef1.id)).head
      val actorRef2_2 = node1.use(ActorAddress(actorId = actorRef2.id)).head

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.idsForActorsInUse.exists(_ == actorRef1.id) must be(true)
      node1.idsForActorsInUse.exists(_ == actorRef2.id) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-2-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-2-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-2-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-2-id-1")) must be(true)

      node2.idsForActorsInUse.exists(_ == actorRef1.id) must be(false)
      node2.idsForActorsInUse.exists(_ == actorRef2.id) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(false)

      // should migrate to node2
      node1.stop
      node1.isRunning must be(false)
      Thread.sleep(500)

      val actorRef1_3 = node2.use(ActorAddress(actorId = actorRef1.id)).head
      val actorRef2_3 = node2.use(ActorAddress(actorId = actorRef2.id)).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node2.idsForActorsInUse.exists(_ == actorRef1.id) must be(true)
      node2.idsForActorsInUse.exists(_ == actorRef2.id) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-2-id-2")) must be(true)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node2.stop
    }

    "automatically migrate actors of a failed node in a cluster of two nodes using class name" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-2-classname-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-2-classname-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(ActorAddress(actorClassName = actorRef1.actorClassName)) must be(true)
      node1.classNamesForClusteredActors.exists(_ == actorRef1.actorClassName) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(ActorAddress(actorClassName = actorRef1.actorClassName)).head
      val actorRef2_2 = node1.use(ActorAddress(actorClassName = actorRef2.actorClassName)).head

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(true)
      node1.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-1")) must be(true)

      node2.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(false)
      node2.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-2")) must be(false)

      // should migrate to node2
      node1.stop
      node1.isRunning must be(false)
      Thread.sleep(500)

      val actorRef1_3 = node2.use(ActorAddress(actorClassName = actorRef1.actorClassName)).head
      val actorRef2_3 = node2.use(ActorAddress(actorClassName = actorRef2.actorClassName)).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node2.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(true)
      node2.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(true)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-2")) must be(true)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-2-classname-2")) must be(true)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node2.stop
    }

    "automatically migrate actors of a failed node in a cluster of three nodes using uuid" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-uuid-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-uuid-2", port = 9002))
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-uuid-3", port = 9003))
      node1.start
      node2.start
      node3.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(ActorAddress(actorUuid = actorRef1.uuid)) must be(true)
      node1.uuidsForClusteredActors.exists(_ == actorRef1.uuid) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(ActorAddress(actorUuid = actorRef1.uuid)).head
      val actorRef2_2 = node1.use(ActorAddress(actorUuid = actorRef2.uuid)).head
      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(true)
      node1.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-1")) must be(true)

      node2.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(false)
      node2.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-2")) must be(false)

      node3.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(false)
      node3.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(false)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-3")) must be(false)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-3")) must be(false)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-3")) must be(false)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-3")) must be(false)

      // should migrate to node2
      node1.stop
      node1.isRunning must be(false)
      Thread.sleep(500)

      val actorRef1_3 = node3.use(ActorAddress(actorUuid = actorRef1.uuid)).head
      val actorRef2_3 = node3.use(ActorAddress(actorUuid = actorRef2.uuid)).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node3.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(true)
      node3.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(true)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-3")) must be(true)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-3")) must be(true)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-3")) must be(true)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-3")) must be(true)

      node2.uuidsForActorsInUse.exists(_ == actorRef1.uuid) must be(false)
      node2.uuidsForActorsInUse.exists(_ == actorRef2.uuid) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = NodeAddress("test-cluster", "migrate-3-uuid-2")) must be(false)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node2.stop
      node3.stop
    }

    "automatically migrate actors of a failed node in a cluster of three nodes using id" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-id-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-id-2", port = 9002))
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-id-3", port = 9003))
      node1.start
      node2.start
      node3.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(ActorAddress(actorId = actorRef1.id)) must be(true)
      node1.idsForClusteredActors.exists(_ == actorRef1.id) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(ActorAddress(actorId = actorRef1.id)).head
      val actorRef2_2 = node1.use(ActorAddress(actorId = actorRef2.id)).head
      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.idsForActorsInUse.exists(_ == actorRef1.id) must be(true)
      node1.idsForActorsInUse.exists(_ == actorRef2.id) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-1")) must be(true)

      node2.idsForActorsInUse.exists(_ == actorRef1.id) must be(false)
      node2.idsForActorsInUse.exists(_ == actorRef2.id) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)

      node3.idsForActorsInUse.exists(_ == actorRef1.id) must be(false)
      node3.idsForActorsInUse.exists(_ == actorRef2.id) must be(false)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(false)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(false)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(false)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(false)

      // should migrate to node2
      node1.stop
      node1.isRunning must be(false)
      Thread.sleep(500)

      val actorRef1_3 = node3.use(ActorAddress(actorId = actorRef1.id)).head
      val actorRef2_3 = node3.use(ActorAddress(actorId = actorRef2.id)).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node3.idsForActorsInUse.exists(_ == actorRef1.id) must be(true)
      node3.idsForActorsInUse.exists(_ == actorRef2.id) must be(true)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(true)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(true)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(true)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-3")) must be(true)

      node2.idsForActorsInUse.exists(_ == actorRef1.id) must be(false)
      node2.idsForActorsInUse.exists(_ == actorRef2.id) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef2.id), node = NodeAddress("test-cluster", "migrate-3-id-2")) must be(false)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node2.stop
      node3.stop
    }

    "automatically migrate actors of a failed node in a cluster of three nodes using class name" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-classname-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-classname-2", port = 9002))
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-3-classname-3", port = 9003))
      node1.start
      node2.start
      node3.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      // register actors
      var serializeMailbox = true
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1, serializeMailbox)
      node1.store(actorRef2, serializeMailbox)

      node1.isClustered(ActorAddress(actorClassName = actorRef1.actorClassName)) must be(true)
      node1.classNamesForClusteredActors.exists(_ == actorRef1.actorClassName) must be(true)

      // check out actor
      val actorRef1_2 = node1.use(ActorAddress(actorClassName = actorRef1.actorClassName)).head
      val actorRef2_2 = node1.use(ActorAddress(actorClassName = actorRef2.actorClassName)).head
      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      node1.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(true)
      node1.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-1")) must be(true)
      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-1")) must be(true)

      node2.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(false)
      node2.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-2")) must be(false)

      node3.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(false)
      node3.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(false)
      node3.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-3")) must be(false)
      node3.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-3")) must be(false)
      node3.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-3")) must be(false)
      node3.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-3")) must be(false)

      // should migrate to node2
      node1.stop
      node1.isRunning must be(false)
      Thread.sleep(500)

      val actorRef1_3 = node3.use(ActorAddress(actorClassName = actorRef1.actorClassName)).head
      val actorRef2_3 = node3.use(ActorAddress(actorClassName = actorRef2.actorClassName)).head
      (actorRef1_3 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_3 !! "hello").getOrElse("_") must equal("world 1")

      node3.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(true)
      node3.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(true)
      node3.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-3")) must be(true)
      node3.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-3")) must be(true)
      node3.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-3")) must be(true)
      node3.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-3")) must be(true)

      node2.classNamesForActorsInUse.exists(_ == actorRef1.actorClassName) must be(false)
      node2.classNamesForActorsInUse.exists(_ == actorRef2.actorClassName) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-2")) must be(false)
      node2.isInUseOnNode(ActorAddress(actorClassName = actorRef2.actorClassName), node = NodeAddress("test-cluster", "migrate-3-classname-2")) must be(false)

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node2.stop
      node3.stop
    }

    "be able to migrate an actor between two nodes using uuid and see that 'ref' to it is redirected and continue to work" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-uuid-and-see-ref-failover-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-uuid-and-see-ref-failover-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start
      val actorRef2 = actorOf[MyJavaSerializableActor].start

      Thread.sleep(500)

      // register actors
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1)
      node1.store(actorRef2)

      Thread.sleep(500)

      // use on node1
      node1.use(ActorAddress(actorUuid = actorRef1.uuid))
      node1.use(ActorAddress(actorUuid = actorRef2.uuid))

      Thread.sleep(500)

      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef1.uuid), node = node1.nodeAddress) must be(true)
      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef2.uuid), node = node1.nodeAddress) must be(true)

      // check out actor ref on node2
      val actorRef1_2 = node2.ref(ActorAddress(actorUuid = actorRef1.uuid), router = Router.Direct)
      val actorRef2_2 = node2.ref(ActorAddress(actorUuid = actorRef2.uuid), router = Router.Direct)

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 1")

      // migrate to node2
      node1.migrate(node1.nodeAddress, node2.nodeAddress, ActorAddress(actorUuid = actorRef1.uuid))
      node1.migrate(node1.nodeAddress, node2.nodeAddress, ActorAddress(actorUuid = actorRef2.uuid))

      Thread.sleep(500)

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 2")
      (actorRef2_2 !! "hello").getOrElse("_") must equal("world 2")

      actorRef1.stop
      actorRef2.stop
      actorRef1_2.stop
      actorRef2_2.stop

      node1.stop
      node2.stop
    }

    "be able to migrate an actor between two nodes using id and see that 'ref' to it is redirected and continue to work" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-id-and-see-ref-failover-1", port = 9001))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-id-and-see-ref-failover-2", port = 9002))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start

      Thread.sleep(500)

      // register actors
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1)

      Thread.sleep(500)

      // use on node1
      node1.use(ActorAddress(actorId = actorRef1.id))

      node1.isInUseOnNode(ActorAddress(actorId = actorRef1.id), node = node1.nodeAddress) must be(true)

      // check out actor ref on node2
      val actorRef1_2 = node2.ref(ActorAddress(actorId = actorRef1.id), router = Router.Direct)

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")

      // migrate to node2
      node1.migrate(node1.nodeAddress, node2.nodeAddress, ActorAddress(actorId = actorRef1.id))

      Thread.sleep(500)

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 2")

      actorRef1.stop
      actorRef1_2.stop

      node1.stop
      node2.stop
    }

    "be able to migrate an actor between two nodes using class name and see that 'ref' to it is redirected and continue to work" in {
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-classname-and-see-ref-failover-1", port = 9011))
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "migrate-classname-and-see-ref-failover-2", port = 9012))
      node1.start
      node2.start

      // create actors
      val actorRef1 = actorOf[MyJavaSerializableActor].start

      Thread.sleep(500)

      // register actors
      import BinaryFormatMyJavaSerializableActor._
      node1.store(actorRef1)

      Thread.sleep(500)

      // use on node1
      node1.use(ActorAddress(actorClassName = actorRef1.actorClassName))

      node1.isInUseOnNode(ActorAddress(actorClassName = actorRef1.actorClassName), node = node1.nodeAddress) must be(true)

      Thread.sleep(500)

      // check out actor ref on node2
      val actorRef1_2 = node2.ref(ActorAddress(actorClassName = actorRef1.actorClassName), router = Router.Direct)

      (actorRef1_2 !! "hello").getOrElse("_") must equal("world 1")

      // migrate to node2
      node1.migrate(node1.nodeAddress, node2.nodeAddress, ActorAddress(actorClassName = actorRef1.actorClassName))

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
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "replicate-actor-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "replicate-actor-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "replicate-actor-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 3
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef.uuid), node = NodeAddress("test-cluster", "replicate-actor-1", port = 9001)) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef.uuid), node = NodeAddress("test-cluster", "replicate-actor-2", port = 9002)) must be(true)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef.uuid), node = NodeAddress("test-cluster", "replicate-actor-3", port = 9003)) must be(true)

      node1.stop
      node2.stop
      node3.stop
    }

    "be able to create a reference to a replicated actor by UUID using Router.Direct routing" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-direct-actor-by-uuid-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-direct-actor-by-uuid-2", port = 9002)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 1
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(ActorAddress(actorUuid = actorRef.uuid), router = Router.Direct)

      (ref !! "hello").getOrElse("_") must equal("world 1")
      (ref !! "hello").getOrElse("_") must equal("world 2")

      node1.stop
      node2.stop
    }

    "be able to create a reference to a replicated actor by ID using Router.Direct routing" in {
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-direct-actor-by-id-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-direct-actor-by-id-2", port = 9002)).start

      Thread.sleep(500)

      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 1
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(ActorAddress(actorId = actorRef.id), router = Router.Direct)

      (ref !! "hello").getOrElse("_") must equal("world 1")
      (ref !! "hello").getOrElse("_") must equal("world 2")

      node1.stop
      node2.stop
    }

    "be able to create a reference to a replicated actor by ClassName using Router.Direct routing" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-direct-actor-by-classname-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-direct-actor-by-classname-2", port = 9002)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 1
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(ActorAddress(actorClassName = actorRef.actorClassName), router = Router.Direct)

      (ref !! "hello").getOrElse("_") must equal("world 1")
      (ref !! "hello").getOrElse("_") must equal("world 2")

      node1.stop
      node2.stop
    }

    "be able to create a reference to a replicated actor by UUID using Router.Random routing" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-uuid-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-uuid-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-uuid-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 2
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(ActorAddress(actorUuid = actorRef.uuid), router = Router.Random)

      (ref !! "hello").getOrElse("_") must equal("world 1")

      node1.stop
      node2.stop
      node3.stop
   }

   "be able to create a reference to a replicated actor by ID using Router.Random routing" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-id-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-id-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-id-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 2
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(ActorAddress(actorId = actorRef.id), router = Router.Random)

      (ref !! "hello").getOrElse("_") must equal("world 1")

      node1.stop
      node2.stop
      node3.stop
    }

   "be able to create a reference to a replicated actor by class name using Router.Random routing" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-classname-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-classname-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-random-actor-by-classname-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 2
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(ActorAddress(actorClassName = actorRef.actorClassName), router = Router.Random)

      (ref !! "hello").getOrElse("_") must equal("world 1")

      node1.stop
      node2.stop
      node3.stop
    }

   "be able to create a reference to a replicated actor by UUID using Router.RoundRobin routing" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-uuid-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-uuid-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-uuid-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 3
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(ActorAddress(actorUuid = actorRef.uuid), router = Router.RoundRobin)

      node1.isInUseOnNode(ActorAddress(actorUuid = actorRef.uuid), node = NodeAddress("test-cluster", "router-round-robin-actor-by-uuid-1", port = 9001)) must be(true)
      node2.isInUseOnNode(ActorAddress(actorUuid = actorRef.uuid), node = NodeAddress("test-cluster", "router-round-robin-actor-by-uuid-2", port = 9002)) must be(true)
      node3.isInUseOnNode(ActorAddress(actorUuid = actorRef.uuid), node = NodeAddress("test-cluster", "router-round-robin-actor-by-uuid-3", port = 9003)) must be(true)

      val addresses = node1.addressesForActor(ActorAddress(actorUuid = actorRef.uuid))
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

   "be able to create a reference to a replicated actor by ID using Router.RoundRobin routing" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-id-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-id-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-id-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 3
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(ActorAddress(actorId = actorRef.id), router = Router.RoundRobin)

      node1.isInUseOnNode(ActorAddress(actorId = actorRef.id), node = NodeAddress("test-cluster", "router-round-robin-actor-by-id-1", port = 9001)) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef.id), node = NodeAddress("test-cluster", "router-round-robin-actor-by-id-2", port = 9002)) must be(true)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef.id), node = NodeAddress("test-cluster", "router-round-robin-actor-by-id-3", port = 9003)) must be(true)

      val addresses = node1.addressesForActor(ActorAddress(actorId = actorRef.id))
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

   "be able to create a reference to a replicated actor by class name using Router.RoundRobin routing" in {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start

      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-classname-1", port = 9001)).start
      val node2 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-classname-2", port = 9002)).start
      val node3 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-classname-3", port = 9003)).start

      Thread.sleep(500)

      // register actor
      import BinaryFormatMyJavaSerializableActor._
      val replicationFactor = 3
      node1.store(actorRef, replicationFactor)

      Thread.sleep(500) // since deployment is async (daemon ! command), we have to wait some before checking

      val ref = node1.ref(ActorAddress(actorId = actorRef.id), router = Router.RoundRobin)

      node1.isInUseOnNode(ActorAddress(actorId = actorRef.id), node = NodeAddress("test-cluster", "router-round-robin-actor-by-classname-1", port = 9001)) must be(true)
      node2.isInUseOnNode(ActorAddress(actorId = actorRef.id), node = NodeAddress("test-cluster", "router-round-robin-actor-by-classname-2", port = 9002)) must be(true)
      node3.isInUseOnNode(ActorAddress(actorId = actorRef.id), node = NodeAddress("test-cluster", "router-round-robin-actor-by-classname-3", port = 9003)) must be(true)

      val addresses = node1.addressesForActor(ActorAddress(actorId = actorRef.id))
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

    "last dummy test" in withPrintStackTraceOnError {
      // create actor
      val actorRef = actorOf[MyJavaSerializableActor].start
      Thread.sleep(1000)
      val node1 = Cluster.newNode(nodeAddress = NodeAddress("test-cluster", "router-round-robin-actor-by-classname-1", port = 9001)).start
      node1.stop
    }
  }

  override def beforeAll() = {
    zkServer = Cluster.startLocalCluster(dataPath, logPath)
  }

  override def beforeEach() = {
    Cluster.reset
  }

  override def afterAll() = {
    Cluster.shutdownLocalCluster
    Actor.registry.shutdownAll
  }
}

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
        val actorRef1 = actorOf[MyJavaSerializableActor].start
        val actorRef2 = actorOf[MyJavaSerializableActor].start

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
