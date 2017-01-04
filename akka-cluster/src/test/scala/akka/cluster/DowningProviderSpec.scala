/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import java.util.concurrent.atomic.AtomicBoolean

import akka.ConfigurationException
import akka.actor.{ ActorSystem, Props }
import akka.testkit.TestKit.{ awaitCond, shutdownActorSystem }
import akka.testkit.{ TestKit, TestProbe }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Futures
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.duration._

class FailingDowningProvider(system: ActorSystem) extends DowningProvider {
  override val downRemovalMargin: FiniteDuration = 20.seconds
  override def downingActorProps: Option[Props] = {
    throw new ConfigurationException("this provider never works")
  }
}

class DummyDowningProvider(system: ActorSystem) extends DowningProvider {
  override val downRemovalMargin: FiniteDuration = 20.seconds

  val actorPropsAccessed = new AtomicBoolean(false)
  override val downingActorProps: Option[Props] = {
    actorPropsAccessed.set(true)
    None
  }
}

class DowningProviderSpec extends WordSpec with Matchers {

  val baseConf = ConfigFactory.parseString(
    """
      akka {
        loglevel = WARNING
        actor.provider = "cluster"
        remote {
          netty.tcp {
            hostname = "127.0.0.1"
            port = 0
          }
        }
      }
    """).withFallback(ConfigFactory.load())

  "The downing provider mechanism" should {

    "default to akka.cluster.NoDowning" in {
      val system = ActorSystem("default", baseConf)
      Cluster(system).downingProvider shouldBe an[NoDowning]
      shutdownActorSystem(system)
    }

    "use akka.cluster.AutoDowning if 'auto-down-unreachable-after' is configured" in {
      val system = ActorSystem("auto-downing", ConfigFactory.parseString(
        """
          akka.cluster.auto-down-unreachable-after = 18d
        """).withFallback(baseConf))
      Cluster(system).downingProvider shouldBe an[AutoDowning]
      shutdownActorSystem(system)
    }

    "use the specified downing provider" in {
      val system = ActorSystem("auto-downing", ConfigFactory.parseString(
        """
          akka.cluster.downing-provider-class="akka.cluster.DummyDowningProvider"
        """).withFallback(baseConf))

      Cluster(system).downingProvider shouldBe a[DummyDowningProvider]
      awaitCond(Cluster(system).downingProvider.asInstanceOf[DummyDowningProvider].actorPropsAccessed.get(), 3.seconds)
      shutdownActorSystem(system)
    }

    "stop the cluster if the downing provider throws exception in props method" in {
      val system = ActorSystem("auto-downing", ConfigFactory.parseString(
        """
          akka.cluster.downing-provider-class="akka.cluster.FailingDowningProvider"
        """).withFallback(baseConf))
      val cluster = Cluster(system)
      cluster.join(cluster.selfAddress)

      awaitCond(cluster.isTerminated, 3.seconds)
      shutdownActorSystem(system)
    }

  }
}
