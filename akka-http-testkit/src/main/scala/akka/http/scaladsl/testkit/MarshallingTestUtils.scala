/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Await }
import akka.http.scaladsl.unmarshalling.{ Unmarshal, FromEntityUnmarshaller }
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.HttpEntity
import akka.stream.Materializer

import scala.util.Try

trait MarshallingTestUtils {
  def marshal[T: ToEntityMarshaller](value: T)(implicit ec: ExecutionContext, mat: Materializer): HttpEntity.Strict =
    Await.result(Marshal(value).to[HttpEntity].flatMap(_.toStrict(1.second)), 1.second)

  def unmarshalValue[T: FromEntityUnmarshaller](entity: HttpEntity)(implicit ec: ExecutionContext, mat: Materializer): T =
    unmarshal(entity).get

  def unmarshal[T: FromEntityUnmarshaller](entity: HttpEntity)(implicit ec: ExecutionContext, mat: Materializer): Try[T] = {
    val fut = Unmarshal(entity).to[T]
    Await.ready(fut, 1.second)
    fut.value.get
  }
}

