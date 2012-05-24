/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.actor.ActorSystem
import akka.util._
import akka.util.duration._

import akka.testkit.AkkaSpec
import akka.testkit.TestEvent._
import akka.testkit.EventFilter

import com.typesafe.config.{ Config, ConfigFactory }

object ClusterSpec {
  val testConf: Config = ConfigFactory.parseString("""
    akka {
      event-handlers = ["akka.testkit.TestEventListener"]
      loglevel = "WARNING"
      stdout-loglevel = "WARNING"
      actor {
        default-dispatcher {
          executor = "fork-join-executor"
          fork-join-executor {
            parallelism-min = 8
            parallelism-factor = 2.0
            parallelism-max = 8
          }
        }
      }
      remote.netty.hostname = localhost
      cluster {
        failure-detector.threshold = 3
        auto-down = on
      }
    }
    """)
}

abstract class ClusterSpec(_system: ActorSystem) extends AkkaSpec(_system) {
  case class PortPrefix(port: Int) {
    def withPortPrefix: Int = (portPrefix.toString + port.toString).toInt
  }

  implicit def intToPortPrefix(port: Int) = PortPrefix(port)

  def portPrefix: Int

  def this(config: Config) = this(ActorSystem(AkkaSpec.getCallerName(classOf[ClusterSpec]), config.withFallback(ClusterSpec.testConf)))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this() = this(ActorSystem(AkkaSpec.getCallerName(classOf[ClusterSpec]), ClusterSpec.testConf))

  def awaitConvergence(nodes: Iterable[Cluster], maxWaitTime: Duration = 60 seconds) {
    val deadline = maxWaitTime.fromNow
    while (nodes map (_.convergence.isDefined) exists (_ == false)) {
      if (deadline.isOverdue) throw new IllegalStateException("Convergence could no be reached within " + maxWaitTime)
      Thread.sleep(1000)
    }
    nodes foreach { n ⇒ println("Converged: " + n.self + " == " + n.convergence.isDefined) }
  }

  override def atStartup {
    system.eventStream.publish(Mute(EventFilter[java.net.ConnectException]()))
    system.eventStream.publish(Mute(EventFilter[java.nio.channels.ClosedChannelException]()))
  }
}
