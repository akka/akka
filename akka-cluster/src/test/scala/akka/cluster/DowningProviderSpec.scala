/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.ConfigurationException
import akka.actor.ActorSystem
import akka.actor.Props
import akka.testkit.TestKit.awaitCond
import akka.testkit.TestKit.shutdownActorSystem
import akka.util.unused
import com.typesafe.config.ConfigFactory
import org.scalatest.Matchers
import org.scalatest.WordSpec

class FailingDowningProvider(@unused system: ActorSystem) extends DowningProvider {
  override val downRemovalMargin: FiniteDuration = 20.seconds
  override def downingActorProps: Option[Props] = {
    throw new ConfigurationException("this provider never works")
  }
}

class DummyDowningProvider(@unused system: ActorSystem) extends DowningProvider {
  override val downRemovalMargin: FiniteDuration = 20.seconds

  val actorPropsAccessed = new AtomicBoolean(false)
  override val downingActorProps: Option[Props] = {
    actorPropsAccessed.set(true)
    None
  }
}

class DowningProviderSpec extends WordSpec with Matchers {

  val baseConf = ConfigFactory.parseString("""
      akka {
        loglevel = WARNING
        actor.provider = "cluster"
        remote {
          artery.canonical {
            hostname = 127.0.0.1
            port = 0
          }
          classic.netty.tcp {
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

    "use the specified downing provider" in {
      val system = ActorSystem(
        "auto-downing",
        ConfigFactory.parseString("""
          akka.cluster.downing-provider-class="akka.cluster.DummyDowningProvider"
        """).withFallback(baseConf))

      Cluster(system).downingProvider shouldBe a[DummyDowningProvider]
      awaitCond(Cluster(system).downingProvider.asInstanceOf[DummyDowningProvider].actorPropsAccessed.get(), 3.seconds)
      shutdownActorSystem(system)
    }

    "stop the cluster if the downing provider throws exception in props method" in {
      try {
        val system = ActorSystem(
          "auto-downing",
          ConfigFactory.parseString("""
          akka.cluster.downing-provider-class="akka.cluster.FailingDowningProvider"
        """).withFallback(baseConf))

        val cluster = Cluster(system)
        cluster.join(cluster.selfAddress)

        awaitCond(cluster.isTerminated, 3.seconds)
        shutdownActorSystem(system)
      } catch {
        case NonFatal(e) if e.getMessage.contains("cannot create children while terminating") =>
          // FIXME #27840
          // cannot create children while terminating or terminated
          // thrown from loadExtension SystemMaterializer
          pending
      }
    }

  }
}
