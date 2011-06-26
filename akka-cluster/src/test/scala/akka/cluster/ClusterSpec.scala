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
