/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.testkit

import akka.http.unmarshalling.{ Unmarshal, FromEntityUnmarshaller }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Await }

import akka.http.marshalling._
import akka.http.model.HttpEntity
import akka.stream.ActorFlowMaterializer

import scala.util.Try

trait MarshallingTestUtils {
  def marshal[T: ToEntityMarshaller](value: T)(implicit ec: ExecutionContext, mat: ActorFlowMaterializer): HttpEntity.Strict =
    Await.result(Marshal(value).to[HttpEntity].flatMap(_.toStrict(1.second)), 1.second)

  def unmarshalValue[T: FromEntityUnmarshaller](entity: HttpEntity)(implicit ec: ExecutionContext, mat: ActorFlowMaterializer): T =
    unmarshal(entity).get

  def unmarshal[T: FromEntityUnmarshaller](entity: HttpEntity)(implicit ec: ExecutionContext, mat: ActorFlowMaterializer): Try[T] = {
    val fut = Unmarshal(entity).to[T]
    Await.ready(fut, 1.second)
    fut.value.get
  }
}

