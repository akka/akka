/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.event.Logging

import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.AkkaSpec
import org.reactivestreams.Publisher
import org.reactivestreams.tck.SubscriberBlackboxVerification
import org.reactivestreams.tck.SubscriberWhiteboxVerification
import org.reactivestreams.tck.TestEnvironment
import org.scalatest.testng.TestNGSuiteLike
import org.testng.annotations.AfterClass

abstract class AkkaSubscriberBlackboxVerification[T](env: TestEnvironment)
  extends SubscriberBlackboxVerification[T](env) with TestNGSuiteLike
  with AkkaSubscriberVerificationLike with ActorSystemLifecycle {

  def this(printlnDebug: Boolean) =
    this(new TestEnvironment(Timeouts.defaultTimeoutMillis, printlnDebug))

  def this() = this(false)
}

abstract class AkkaSubscriberWhiteboxVerification[T](env: TestEnvironment)
  extends SubscriberWhiteboxVerification[T](env) with TestNGSuiteLike
  with AkkaSubscriberVerificationLike {

  def this(printlnDebug: Boolean) =
    this(new TestEnvironment(Timeouts.defaultTimeoutMillis, printlnDebug))

  def this() = this(false)
}

trait AkkaSubscriberVerificationLike {
  implicit def system: ActorSystem

  implicit lazy val materializer = ActorMaterializer(ActorMaterializerSettings(system))
}
