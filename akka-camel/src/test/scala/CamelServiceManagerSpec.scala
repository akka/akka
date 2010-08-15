package se.scalablesolutions.akka.camel

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.scalatest.matchers.MustMatchers

import se.scalablesolutions.akka.actor.ActorRegistry

/**
 * @author Martin Krasser
 */
class CamelServiceManagerSpec extends WordSpec with BeforeAndAfterAll with MustMatchers {

  override def afterAll = ActorRegistry.shutdownAll

  "A CamelServiceManager" when {
    "the startCamelService method been has been called" must {
      "have registered the started CamelService instance" in {
        val service = CamelServiceManager.startCamelService
        CamelServiceManager.service must be theSameInstanceAs (service)
      }
    }
    "the stopCamelService method been has been called" must {
      "have unregistered the current CamelService instance" in {
        val service = CamelServiceManager.stopCamelService
        intercept[IllegalStateException] { CamelServiceManager.service }
      }
    }
  }

  "A CamelServiceManager" when {
    val service = CamelServiceFactory.createCamelService
    "a CamelService instance has been started externally" must {
      "have registered the started CamelService instance" in {
        service.start
        CamelServiceManager.service must be theSameInstanceAs (service)
      }
    }
    "the current CamelService instance has been stopped externally" must {
      "have unregistered the current CamelService instance" in {
        service.stop
        intercept[IllegalStateException] { CamelServiceManager.service }
      }
    }
  }

  "A CamelServiceManager" when {
    "a CamelService has been started" must {
      "not allow further CamelService instances to be started" in {
        CamelServiceManager.startCamelService
        intercept[IllegalStateException] { CamelServiceManager.startCamelService }
      }
    }
    "a CamelService has been stopped" must {
      "only allow the current CamelService instance to be stopped" in {
        intercept[IllegalStateException] { CamelServiceFactory.createCamelService.stop }
      }
      "ensure that the current CamelService instance has been actually started" in {
        CamelServiceManager.stopCamelService
        intercept[IllegalStateException] { CamelServiceManager.stopCamelService }
      }
    }
  }
}