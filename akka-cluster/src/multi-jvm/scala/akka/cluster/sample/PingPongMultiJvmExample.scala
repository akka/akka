/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.sample

import akka.cluster._

import akka.actor._
import scala.util.duration._

object PingPongMultiJvmExample {
  val PING_ADDRESS = "ping"
  val PONG_ADDRESS = "pong"

  val ClusterName = "ping-pong-cluster"
  val NrOfNodes = 5
  val Pause = true
  val PauseTimeout = 5 minutes

  // -----------------------------------------------
  // Messages
  // -----------------------------------------------

  sealed trait PingPong extends Serializable
  case object Ping extends PingPong
  case object Pong extends PingPong
  case object Stop extends PingPong

  case class Serve(player: ActorRef)

  // -----------------------------------------------
  // Actors
  // -----------------------------------------------

  class PingActor extends Actor with Serializable {
    var pong: ActorRef = _
    var play = true

    def receive = {
      case Pong ⇒
        if (play) {
          println("---->> PING")
          pong ! Ping
        } else {
          println("---->> GAME OVER")
        }
      case Serve(player) ⇒
        pong = player
        println("---->> SERVE")
        pong ! Ping
      case Stop ⇒
        play = false
    }
  }

  class PongActor extends Actor with Serializable {
    def receive = {
      case Ping ⇒
        println("---->> PONG")
        reply(Pong)
    }
  }
}

/*
object PingPongMultiJvmNode1 {
  import PingPong._
  import BinaryFormats._

  val PingService = classOf[PingActor].getName
  val PongService = classOf[PongActor].getName

  def main(args: Array[String]) { run }

  def run = {
    // -----------------------------------------------
    // Start monitoring
    // -----------------------------------------------

    //MonitoringServer.start
    //Monitoring.startLocalDaemons

    // -----------------------------------------------
    // Start cluster
    // -----------------------------------------------

    Cluster.startLocalCluster()

    // create node
    val node = Cluster.newNode(NodeAddress(ClusterName, "node1", port = 9991))

    def pause(name: String, message: String) = {
      node.barrier("user-prompt-" + name, NrOfNodes, PauseTimeout) {
        println(message)
        if (Pause) {
          println("Press enter to continue (timeout of %s) ..." format PauseTimeout)
          System.in.read
        }
      }
    }

    pause("start", "Ready to start all nodes")
    println("Starting nodes ...")

    Cluster.node.start()

    node.barrier("start", NrOfNodes) {
      // wait for others to start
    }

    // -----------------------------------------------
    // Store pong actors in the cluster
    // -----------------------------------------------

    pause("create", "Ready to create all actors")
    println("Creating actors ...")

    // store the ping actor in the cluster, but do not deploy it anywhere
    node.store(classOf[PingActor], PING_ADDRESS)

    // store the pong actor in the cluster and replicate it on all nodes
    node.store(classOf[PongActor], PONG_ADDRESS, NrOfNodes)

    // give some time for the deployment
    Thread.sleep(3000)

    // -----------------------------------------------
    // Get actor references
    // -----------------------------------------------

    // check out a local ping actor
    val ping = node.use[PingActor](PING_ADDRESS).head

    // get a reference to all the pong actors through a round-robin router actor ref
    val pong = node.ref(PONG_ADDRESS, router = Router.RoundRobin)

    // -----------------------------------------------
    // Play the game
    // -----------------------------------------------

    pause("play", "Ready to play ping pong")

    ping ! Serve(pong)

    // let them play for 3 seconds
    Thread.sleep(3000)

    ping ! Stop

    // give some time for the game to finish
    Thread.sleep(3000)

    // -----------------------------------------------
    // Stop actors
    // -----------------------------------------------

    pause("stop", "Ready to stop actors")
    println("Stopping actors ...")

    ping.stop
    pong.stop

    // give remote actors time to stop
    Thread.sleep(5000)

    // -----------------------------------------------
    // Stop everything
    // -----------------------------------------------

    pause("shutdown", "Ready to shutdown")
    println("Stopping everything ...")

    //Monitoring.stopLocalDaemons
    //MonitoringServer.stop

    Actor.remote.shutdown
    Actor.registry.local.shutdownAll

    node.stop

    Cluster.shutdownLocalCluster
  }
}

object PingPongMultiJvmNode2 extends PongNode(2)
object PingPongMultiJvmNode3 extends PongNode(3)
object PingPongMultiJvmNode4 extends PongNode(4)
object PingPongMultiJvmNode5 extends PongNode(5)

class PongNode(number: Int) {
  import PingPong._

  def main(args: Array[String]) { run }

  def run = {
    val node = Cluster.newNode(NodeAddress(ClusterName, "node" + number, port = 9990 + number))

    def pause(name: String) = {
      node.barrier("user-prompt-" + name, NrOfNodes, PauseTimeout) {
        // wait for user prompt
      }
    }

    pause("start")

    node.barrier("start", NrOfNodes) {
      Cluster.node.start()
    }

    pause("create")

    pause("play")

    pause("stop")

    pause("shutdown")

    // clean up and stop

    Actor.remote.shutdown
    Actor.registry.local.shutdownAll

    node.stop
  }
}
*/
