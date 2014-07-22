/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import akka.stream.scaladsl.Flow
import akka.actor.ActorSystem
import akka.stream.testkit.AkkaSpec

class ActorPublisherTest(_system: ActorSystem, /*env: TestEnvironment,*/ publisherShutdownTimeout: Long) {
  // FIXME: Needs new TCK version
  // Original code available in 82734877d080577cf538c2a47d60c117e078ac1c
}