/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.cluster

import akka.cloud.cluster._

import akka.actor._
import akka.actor.Actor._
import akka.serialization.{Serializer, SerializerBasedActorFormat}

import java.util.concurrent.CountDownLatch

object PingPong {

  val NrOfPings = 5

  // ------------------------
  // Messages
  // ------------------------

  sealed trait PingPong extends Serializable
  case object Ball extends PingPong
  case object Stop extends PingPong
  case class Latch(latch: CountDownLatch) extends PingPong

  // ------------------------
  // Actors
  // ------------------------

  class PingActor extends Actor with Serializable {
    var count = 0
    var gameOverLatch: CountDownLatch = _

    def receive = {
      case Ball =>
        if (count < NrOfPings) {
          println("---->> PING (%s)" format count)
          count += 1
          self reply Ball
        } else {
          self.sender.foreach(_ !! Stop)
          gameOverLatch.countDown
          self.stop
        }
      case Latch(latch) =>
        gameOverLatch = latch
    }
  }

  class PongActor extends Actor with Serializable {
    def receive = {
      case Ball =>
        self reply Ball
      case Stop =>
        self reply Stop
        self.stop
    }
  }

  // ------------------------
  // Serialization
  // ------------------------

  object BinaryFormats {
    implicit object PingActorFormat extends SerializerBasedActorFormat[PingActor] with Serializable {
      val serializer = Serializer.Java
    }

    implicit object PongActorFormat extends SerializerBasedActorFormat[PongActor] with Serializable {
      val serializer = Serializer.Java
    }
  }
}

object ClusteredPingPongSample {
  import PingPong._
  import BinaryFormats._

  val CLUSTER_NAME = "test-cluster"
  val PING_SERVICE = classOf[PingActor].getName
  val PONG_SERVICE = classOf[PongActor].getName

  def main(args: Array[String]) = run

  def run = {

    // ------------------------
    // Start cluster of 5 nodes
    // ------------------------

    Cluster.startLocalCluster()
    val localNode   = Cluster.newNode(NodeAddress(CLUSTER_NAME, "node0", port = 9991)).start
    val remoteNodes = Cluster.newNode(NodeAddress(CLUSTER_NAME, "node1", port = 9992)).start ::
                      Cluster.newNode(NodeAddress(CLUSTER_NAME, "node2", port = 9993)).start ::
                      Cluster.newNode(NodeAddress(CLUSTER_NAME, "node3", port = 9994)).start ::
                      Cluster.newNode(NodeAddress(CLUSTER_NAME, "node4", port = 9995)).start :: Nil

    // ------------------------
    // Store the actors in the cluster
    // ------------------------

    // Store the PingActor in the cluster, but do not deploy it anywhere
    localNode.store(classOf[PingActor])

    // Store the PongActor in the cluster and deploy it
    // to 5 (replication factor) nodes in the cluster
    localNode.store(classOf[PongActor], 5)

    Thread.sleep(1000) // let the deployment finish

    // ------------------------
    // Get the actors from the cluster
    // ------------------------

    // Check out a local PingActor instance (not reference)
    val ping = localNode.use[PingActor](ActorAddress(actorId = PING_SERVICE)).head

    // Get a reference to all the pong actors through a round-robin router ActorRef
    val pong = localNode.ref(ActorAddress(actorId = PONG_SERVICE), router = Router.RoundRobin)

    // ------------------------
    // Play the game
    // ------------------------

    val latch = new CountDownLatch(1)
    ping ! Latch(latch)               // register latch for actor to know when to stop

    println("---->> SERVE")

    implicit val replyTo = Some(pong) // set the reply address to the PongActor
    ping ! Ball                       // serve

    latch.await                       // wait for game to finish

    println("---->> GAME OVER")

    // ------------------------
    // Clean up
    // ------------------------

    localNode.stop
    remoteNodes.foreach(_.stop)
    Cluster.shutdownLocalCluster()
  }
}
