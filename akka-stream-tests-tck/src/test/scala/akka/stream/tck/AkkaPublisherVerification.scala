/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import scala.collection.immutable
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorMaterializer
import akka.stream.testkit.TestPublisher
import org.reactivestreams.Publisher
import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment }
import org.scalatest.testng.TestNGSuiteLike

abstract class AkkaPublisherVerification[T](val env: TestEnvironment, publisherShutdownTimeout: Long)
  extends PublisherVerification[T](env, publisherShutdownTimeout)
  with TestNGSuiteLike with ActorSystemLifecycle {

  def this(printlnDebug: Boolean) =
    this(new TestEnvironment(Timeouts.defaultTimeoutMillis, printlnDebug), Timeouts.publisherShutdownTimeoutMillis)

  def this() = this(false)

  implicit lazy val materializer = ActorMaterializer(
    ActorMaterializerSettings(system).withInputBuffer(initialSize = 512, maxSize = 512))(system)

  override def createFailedPublisher(): Publisher[T] =
    TestPublisher.error(new Exception("Unable to serve subscribers right now!"))

  def iterable(elements: Long): immutable.Iterable[Int] =
    if (elements > Int.MaxValue)
      new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
    else
      0 until elements.toInt
}
