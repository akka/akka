/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.concurrent.duration._
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.actor.Address
import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorLogging

object StartupWithOneThreadSpec {
  val config = """
    akka.actor.provider = "cluster"
    akka.actor.creation-timeout = 10s
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0

    akka.actor.default-dispatcher {
      executor = thread-pool-executor
      thread-pool-executor {
        fixed-pool-size = 1
      }
    }
    """

  final case class GossipTo(address: Address)

  def testProps = Props(new Actor with ActorLogging {
    val cluster = Cluster(context.system)
    log.debug(s"started ${cluster.selfAddress} ${Thread.currentThread().getName}")
    def receive = {
      case msg ⇒ sender() ! msg
    }
  })
}

class StartupWithOneThreadSpec(startTime: Long) extends AkkaSpec(StartupWithOneThreadSpec.config) with ImplicitSender {
  import StartupWithOneThreadSpec._

  def this() = this(System.nanoTime())

  "A Cluster" must {

    "startup with one dispatcher thread" in {
      // This test failed before fixing #17253 when adding a sleep before the
      // Await of GetClusterCoreRef in the Cluster extension constructor.
      // The reason was that other cluster actors were started too early and
      // they also tried to get the Cluster extension and thereby blocking
      // dispatcher threads.
      // Note that the Cluster extension is started via ClusterActorRefProvider
      // before ActorSystem.apply returns, i.e. in the constructor of AkkaSpec.
      (System.nanoTime - startTime).nanos.toMillis should be <
        (system.settings.CreationTimeout.duration - 2.second).toMillis
      system.actorOf(testProps) ! "hello"
      system.actorOf(testProps) ! "hello"
      system.actorOf(testProps) ! "hello"

      val cluster = Cluster(system)
      (System.nanoTime - startTime).nanos.toMillis should be <
        (system.settings.CreationTimeout.duration - 2.second).toMillis

      expectMsg("hello")
      expectMsg("hello")
      expectMsg("hello")
    }

  }
}
