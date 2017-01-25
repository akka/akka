/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.unmarshalling._
import akka.stream.Materializer
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.ExceptionMatchers._
import org.specs2.matcher.FutureMatchers._
import org.specs2.matcher.AnyMatchers._
import org.specs2.matcher.Matcher

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

trait Specs2Utils extends MarshallingTestUtils {

  def evaluateTo[T](value: T)(implicit ee: ExecutionEnv): Matcher[Future[T]] =
    beEqualTo(value).await

  def haveFailedWith(t: Throwable)(implicit ee: ExecutionEnv): Matcher[Future[_]] =
    throwA(t).await

  def unmarshalToValue[T: FromEntityUnmarshaller](value: T)(implicit ec: ExecutionContext, mat: Materializer): Matcher[HttpEntity] =
    beEqualTo(value).^^(unmarshalValue(_: HttpEntity))

  def unmarshalTo[T: FromEntityUnmarshaller](value: Try[T])(implicit ec: ExecutionContext, mat: Materializer): Matcher[HttpEntity] =
    beEqualTo(value).^^(unmarshal(_: HttpEntity))
}

trait Specs2RouteTest extends RouteTest with Specs2FrameworkInterface.Specs2 with Specs2Utils