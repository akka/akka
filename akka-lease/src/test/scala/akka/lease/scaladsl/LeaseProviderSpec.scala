/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.lease.scaladsl

import scala.concurrent.Future
import akka.actor.ExtendedActorSystem
import akka.lease.LeaseSettings
import akka.testkit.{AkkaSpec, EventFilter}
import com.typesafe.config.{ConfigException, ConfigFactory}

object LeaseProviderSpec {
  class LeaseA(settings: LeaseSettings) extends Lease(settings) {
    override def acquire(): Future[Boolean] = Future.successful(false)
    override def release(): Future[Boolean] = Future.successful(false)
    override def checkLease(): Boolean = false
    override def acquire(callback: Option[Throwable] => Unit): Future[Boolean] = Future.successful(false)
  }

  class LeaseB(settings: LeaseSettings, system: ExtendedActorSystem) extends Lease(settings) {
    override def acquire(): Future[Boolean] = Future.successful(false)
    override def release(): Future[Boolean] = Future.successful(false)
    override def checkLease(): Boolean = false
    override def acquire(callback: Option[Throwable] => Unit): Future[Boolean] = Future.successful(false)
  }

  val config = ConfigFactory.parseString(
    s"""
  lease-a {
    lease-class = "${classOf[LeaseProviderSpec.LeaseA].getName}"
    key1 = value1
    heartbeat-timeout = 120s
    heartbeat-interval = 1s
    lease-operation-timeout = 2s
  }

  lease-b {
    lease-class = "${classOf[LeaseProviderSpec.LeaseB].getName}"
    key2 = value2
    heartbeat-timeout = 120s
    heartbeat-interval = 1s
    lease-operation-timeout = 2s
  }

  lease-missing {
  }
  lease-unknown {
    lease-class = "foo.wrong.ClassName"
    heartbeat-timeout = 120s
    heartbeat-interval = 1s
    lease-operation-timeout = 2s
  }

  """
  )

}

class LeaseProviderSpec extends AkkaSpec(LeaseProviderSpec.config) {
  import LeaseProviderSpec._

  "LeaseProvider" must {

    "load lease implementation" in {
      val leaseA = LeaseProvider(system).getLease("a", "lease-a", "owner1")
      leaseA.getClass should ===(classOf[LeaseA])
      leaseA.settings.leaseName should ===("a")
      leaseA.settings.ownerName should ===("owner1")
      leaseA.settings.leaseConfig.getString("key1") should ===("value1")

      val leaseB = LeaseProvider(system).getLease("b", "lease-b", "owner2")
      leaseB.getClass should ===(classOf[LeaseB])
      leaseB.settings.leaseName should ===("b")
      leaseB.settings.ownerName should ===("owner2")
      leaseB.settings.leaseConfig.getString("key2") should ===("value2")
    }

    "return same instance for same leaseName, configPath and owner" in {
      val leaseA1 = LeaseProvider(system).getLease("a2", "lease-a", "owner1")
      val leaseA2 = LeaseProvider(system).getLease("a2", "lease-a", "owner1")
      leaseA1 shouldBe theSameInstanceAs(leaseA2)
    }

    "return different instance for different leaseName" in {
      val leaseA1 = LeaseProvider(system).getLease("a3", "lease-a", "owner1")
      val leaseA2 = LeaseProvider(system).getLease("a3b", "lease-a", "owner1")
      leaseA1 should not be theSameInstanceAs(leaseA2)
    }

    "return different instance for different ownerName" in {
      val leaseA1 = LeaseProvider(system).getLease("a4", "lease-a", "owner1")
      val leaseA2 = LeaseProvider(system).getLease("a4", "lease-a", "owner2")
      leaseA1 should not be theSameInstanceAs(leaseA2)
    }

    "throw if missing lease-class config" in {
      intercept[ConfigException] {
        LeaseProvider(system).getLease("x", "lease-missing", "owner1")
      }
    }

    "throw if unknown lease-class config" in {
      intercept[ClassNotFoundException] {
        EventFilter[ClassNotFoundException](occurrences = 1).intercept {
          LeaseProvider(system).getLease("x", "lease-unknown", "owner1")
        }
      }
    }
  }

}
