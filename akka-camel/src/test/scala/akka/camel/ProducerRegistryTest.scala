/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec
import akka.camel.TestSupport.SharedCamelSystem
import akka.actor.Props
import akka.util.duration._

class ProducerRegistryTest extends WordSpec with MustMatchers with SharedCamelSystem {
  "A ProducerRegistry" must {
    "register a started SendProcessor for the producer, which is stopped when the actor is stopped" in {
      val actorRef = system.actorOf(Props(behavior = ctx ⇒ {
        case _ ⇒ {}
      }))
      val (endpoint, processor) = camel.registerProducer(actorRef, "mock:mock")
      camel.awaitActivation(actorRef, 1 second)
      processor.isStarted must be(true)
      endpoint.getCamelContext must equal(camel.context)
      system.stop(actorRef)
      camel.awaitDeactivation(actorRef, 1 second)
      if (!processor.isStopping) {
        processor.isStopped must be(true)
      }
    }
    "remove and stop the SendProcessor if the actorRef is registered" in {
      val actorRef = system.actorOf(Props(behavior = ctx ⇒ {
        case _ ⇒ {}
      }))
      val (_, processor) = camel.registerProducer(actorRef, "mock:mock")
      camel.remove(actorRef)
      if (!processor.isStopping) {
        processor.isStopped must be(true)
      }
    }
    "remove and stop only the SendProcessor for the actorRef that is registered" in {
      val actorRef1 = system.actorOf(Props(behavior = ctx ⇒ {
        case _ ⇒ {}
      }))
      val actorRef2 = system.actorOf(Props(behavior = ctx ⇒ {
        case _ ⇒ {}
      }))
      val (_, processor) = camel.registerProducer(actorRef2, "mock:mock")
      // this generates a warning on the activationTracker.
      camel.remove(actorRef1)
      processor.isStopped must be(false)
      processor.isStopping must be(false)

      camel.remove(actorRef2)
      if (!processor.isStopping) {
        processor.isStopped must be(true)
      }
    }
  }

}