/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.actor.ActorSystem
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorMaterializer
import org.reactivestreams.tck.SubscriberBlackboxVerification
import org.reactivestreams.tck.SubscriberWhiteboxVerification
import org.reactivestreams.tck.TestEnvironment
import org.scalatest.testng.TestNGSuiteLike

abstract class AkkaSubscriberBlackboxVerification[T](env: TestEnvironment)
  extends SubscriberBlackboxVerification[T](env) with TestNGSuiteLike
  with AkkaSubscriberVerificationLike with ActorSystemLifecycle {

  def this(printlnDebug: Boolean) =
    this(new TestEnvironment(Timeouts.defaultTimeoutMillis, Timeouts.defaultNoSignalsTimeoutMillis, printlnDebug))

  def this() = this(false)
}

abstract class AkkaSubscriberWhiteboxVerification[T](env: TestEnvironment)
  extends SubscriberWhiteboxVerification[T](env) with TestNGSuiteLike
  with AkkaSubscriberVerificationLike {

  def this(printlnDebug: Boolean) =
    this(new TestEnvironment(Timeouts.defaultTimeoutMillis, Timeouts.defaultNoSignalsTimeoutMillis, printlnDebug))

  def this() = this(false)
}

trait AkkaSubscriberVerificationLike {
  implicit def system: ActorSystem

  implicit lazy val materializer = ActorMaterializer(ActorMaterializerSettings(system))
}
