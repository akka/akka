/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
// FIXME: new TCK needed
//import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment, IdentityProcessorVerification }
import akka.actor.{ ActorSystem, Props }
import akka.stream.impl.ActorProcessor
import akka.stream.impl.TransformProcessorImpl
import akka.stream.impl.Ast
import akka.testkit.{ TestEvent, EventFilter }
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import java.util.concurrent.atomic.AtomicInteger

class IdentityProcessorTest(_system: ActorSystem, /*env: TestEnvironment,*/ publisherShutdownTimeout: Long) {

  // FIXME: new TCK needed
  // Original code available in 82734877d080577cf538c2a47d60c117e078ac1c
}
