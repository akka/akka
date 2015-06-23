/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.testkit

import scala.reflect.ClassTag
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import akka.util.ByteString
import akka.stream.ActorMaterializer
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.model.HttpResponse
import akka.http.impl.util._
import akka.http.impl.server.UnmarshallerImpl
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.server.Unmarshaller
import akka.http.javadsl.model._

/**
 * A wrapper for responses
 */
abstract class TestResponse(_response: HttpResponse, awaitAtMost: FiniteDuration)(implicit ec: ExecutionContext, materializer: ActorMaterializer) {
  lazy val entity: HttpEntityStrict =
    _response.entity.toStrict(awaitAtMost).awaitResult(awaitAtMost)
  lazy val response: HttpResponse = _response.withEntity(entity)

  // FIXME: add header getters / assertions

  def mediaType: MediaType = extractFromResponse(_.entity.contentType.mediaType)
  def mediaTypeString: String = mediaType.toString
  def entityBytes: ByteString = entity.data()
  def entityAs[T](unmarshaller: Unmarshaller[T]): T =
    Unmarshal(response)
      .to(unmarshaller.asInstanceOf[UnmarshallerImpl[T]].scalaUnmarshaller(ec, materializer), ec)
      .awaitResult(awaitAtMost)
  def entityAsString: String = entity.data().utf8String
  def status: StatusCode = response.status.asJava
  def statusCode: Int = response.status.intValue
  def header[T <: HttpHeader](clazz: Class[T]): T =
    response.header(ClassTag(clazz))
      .getOrElse(fail(s"Expected header of type ${clazz.getSimpleName} but wasn't found."))

  def assertStatusCode(expected: Int): TestResponse =
    assertStatusCode(StatusCodes.get(expected))
  def assertStatusCode(expected: StatusCode): TestResponse =
    assertEqualsKind(expected, status, "status code")
  def assertMediaType(expected: String): TestResponse =
    assertEqualsKind(expected, mediaTypeString, "media type")
  def assertMediaType(expected: MediaType): TestResponse =
    assertEqualsKind(expected, mediaType, "media type")
  def assertEntity(expected: String): TestResponse =
    assertEqualsKind(expected, entityAsString, "entity")
  def assertEntityBytes(expected: ByteString): TestResponse =
    assertEqualsKind(expected, entityBytes, "entity")
  def assertEntityAs[T <: AnyRef](unmarshaller: Unmarshaller[T], expected: T): TestResponse =
    assertEqualsKind(expected, entityAs(unmarshaller), "entity")
  def assertHeaderExists(expected: HttpHeader): TestResponse = {
    assertTrue(response.headers.exists(_ == expected), s"Header $expected was missing.")
    this
  }
  def assertHeaderKindExists(name: String): TestResponse = {
    val lowercased = name.toRootLowerCase
    assertTrue(response.headers.exists(_.is(lowercased)), s"Expected `$name` header was missing.")
    this
  }
  def assertHeaderExists(name: String, value: String): TestResponse = {
    val lowercased = name.toRootLowerCase
    val headers = response.headers.filter(_.is(lowercased))
    if (headers.isEmpty) fail(s"Expected `$name` header was missing.")
    else assertTrue(headers.exists(_.value == value),
      s"`$name` header was found but had the wrong value. Found headers: ${headers.mkString(", ")}")

    this
  }

  private[this] def extractFromResponse[T](f: HttpResponse ⇒ T): T =
    if (response eq null) fail("Request didn't complete with response")
    else f(response)

  protected def assertEqualsKind(expected: AnyRef, actual: AnyRef, kind: String): TestResponse = {
    assertEquals(expected, actual, s"Unexpected $kind!")
    this
  }
  protected def assertEqualsKind(expected: Int, actual: Int, kind: String): TestResponse = {
    assertEquals(expected, actual, s"Unexpected $kind!")
    this
  }

  protected def fail(message: String): Nothing
  protected def assertEquals(expected: AnyRef, actual: AnyRef, message: String): Unit
  protected def assertEquals(expected: Int, actual: Int, message: String): Unit
  protected def assertTrue(predicate: Boolean, message: String): Unit
}