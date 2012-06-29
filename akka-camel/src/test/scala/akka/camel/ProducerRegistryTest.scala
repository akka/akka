/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import language.postfixOps

import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec
import akka.camel.TestSupport.SharedCamelSystem
import scala.concurrent.util.duration._
import akka.actor.{ ActorRef, Props }

class ProducerRegistryTest extends WordSpec with MustMatchers with SharedCamelSystem {

  "A ProducerRegistry" must {
    def newEmptyActor: ActorRef = system.actorOf(Props.empty)
    def registerProcessorFor(actorRef: ActorRef) = camel.registerProducer(actorRef, "mock:mock")._2

    "register a started SendProcessor for the producer, which is stopped when the actor is stopped" in {
      val actorRef = newEmptyActor
      val processor = registerProcessorFor(actorRef)
      camel.awaitActivation(actorRef, 1 second)
      processor.isStarted must be(true)
      system.stop(actorRef)
      camel.awaitDeactivation(actorRef, 1 second)
      (processor.isStopping || processor.isStopped) must be(true)
    }
    "remove and stop the SendProcessor if the actorRef is registered" in {
      val actorRef = newEmptyActor
      val processor = registerProcessorFor(actorRef)
      camel.unregisterProducer(actorRef)
      (processor.isStopping || processor.isStopped) must be(true)
    }
    "remove and stop only the SendProcessor for the actorRef that is registered" in {
      val actorRef1 = newEmptyActor
      val actorRef2 = newEmptyActor
      val processor = registerProcessorFor(actorRef2)
      // this generates a warning on the activationTracker.
      camel.unregisterProducer(actorRef1)
      (!processor.isStopped && !processor.isStopping) must be(true)

      camel.unregisterProducer(actorRef2)
      (processor.isStopping || processor.isStopped) must be(true)
    }
  }

}