/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.coordination

import akka.cluster.Cluster
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.{ Lease, LeaseProvider }
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future

//#lease-example
class SampleLease(settings: LeaseSettings) extends Lease(settings) {

  override def acquire(): Future[Boolean] = {
    Future.successful(true)
  }

  override def acquire(leaseLostCallback: Option[Throwable] => Unit): Future[Boolean] = {
    Future.successful(true)
  }

  override def release(): Future[Boolean] = {
    Future.successful(true)
  }

  override def checkLease(): Boolean = {
    true
  }
}
//#lease-example

object LeaseDocSpec {

  val config = ConfigFactory.parseString("""
      #lease-config
      akka.actor.provider = cluster
      docs-lease {
        lease-class = "docs.akka.coordination.SampleLease"
        heartbeat-timeout = 100s
        heartbeat-interval = 1s
        lease-operation-timeout = 1s
        # Any lease specific configuration
      }
      #lease-config
    """.stripMargin)

  def blackhole(stuff: Any*): Unit = {
    stuff.toString
    ()
  }
  def doSomethingImportant(leaseLostReason: Option[Throwable]): Unit = {
    leaseLostReason.map(_.toString)
    ()
  }
}

class LeaseDocSpec extends AkkaSpec(LeaseDocSpec.config) {
  import LeaseDocSpec._

  "A docs lease" should {
    "be loadable" in {

      //#lease-usage
      val lease = LeaseProvider(system).getLease("<name of the lease>", "docs-lease", "owner")
      val acquired: Future[Boolean] = lease.acquire()
      val stillAcquired: Boolean = lease.checkLease()
      val released: Future[Boolean] = lease.release()
      //#lease-usage

      //#lost-callback
      lease.acquire(leaseLostReason => doSomethingImportant(leaseLostReason))
      //#lost-callback

      //#cluster-owner
      val owner = Cluster(system).selfAddress.hostPort
      //#cluster-owner

      // remove compiler warnings
      blackhole(acquired, stillAcquired, released, owner)

    }
  }

}
