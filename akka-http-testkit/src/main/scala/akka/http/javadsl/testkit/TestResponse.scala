/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.testkit

import scala.reflect.ClassTag
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import akka.util.ByteString
import akka.stream.Materializer
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.model.HttpResponse
import akka.http.impl.util._
import akka.http.impl.server.UnmarshallerImpl
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.server.Unmarshaller
import akka.http.javadsl.model._

/**
 * A wrapper for responses.
 *
 * To support the testkit API, a third-party testing library needs to implement this class and provide
 * implementations for the abstract assertion methods.
 */
abstract class TestResponse(_response: HttpResponse, awaitAtMost: FiniteDuration)(implicit ec: ExecutionContext, materializer: Materializer) {
  /**
   * Returns the strictified entity of the response. It will be strictified on first access.
   */
  lazy val entity: HttpEntity.Strict = _response.entity.toStrict(awaitAtMost).awaitResult(awaitAtMost)

  /**
   * Returns a copy of the underlying response with the strictified entity.
   */
  lazy val response: HttpResponse = _response.withEntity(entity)

  /**
   * Returns the media-type of the response's content-type
   */
  def mediaType: MediaType = extractFromResponse(_.entity.contentType.mediaType)

  /**
   * Returns a string representation of the media-type of the response's content-type
   */
  def mediaTypeString: String = mediaType.toString

  /**
   * Returns the bytes of the response entity
   */
  def entityBytes: ByteString = entity.getData

  /**
   * Returns the entity of the response unmarshalled with the given ``Unmarshaller``.
   */
  def entityAs[T](unmarshaller: Unmarshaller[T]): T =
    Unmarshal(response)
      .to(unmarshaller.asInstanceOf[UnmarshallerImpl[T]].scalaUnmarshaller, ec, materializer)
      .awaitResult(awaitAtMost)

  /**
   * Returns the entity of the response interpreted as an UTF-8 encoded string.
   */
  def entityAsString: String = entity.getData.utf8String

  /**
   * Returns the [[akka.http.javadsl.model.StatusCode]] of the response.
   */
  def status: StatusCode = response.status.asJava

  /**
   * Returns the numeric status code of the response.
   * @return
   */
  def statusCode: Int = response.status.intValue

  /**
   * Returns the first header of the response which is of the given class.
   */
  def header[T <: HttpHeader](clazz: Class[T]): T =
    response.header(ClassTag(clazz))
      .getOrElse(doFail(s"Expected header of type ${clazz.getSimpleName} but wasn't found."))

  /**
   * Assert on the numeric status code.
   */
  def assertStatusCode(expected: Int): TestResponse =
    assertStatusCode(StatusCodes.get(expected))

  /**
   * Assert on the status code.
   */
  def assertStatusCode(expected: StatusCode): TestResponse =
    assertEqualsKind(expected, status, "status code")

  /**
   * Assert on the media type of the response.
   */
  def assertMediaType(expected: String): TestResponse =
    assertEqualsKind(expected, mediaTypeString, "media type")

  /**
   * Assert on the media type of the response.
   */
  def assertMediaType(expected: MediaType): TestResponse =
    assertEqualsKind(expected, mediaType, "media type")

  /**
   * Assert on the response entity to be a UTF8 representation of the given string.
   */
  def assertEntity(expected: String): TestResponse =
    assertEqualsKind(expected, entityAsString, "entity")

  /**
   * Assert on the response entity to equal the given bytes.
   */
  def assertEntityBytes(expected: ByteString): TestResponse =
    assertEqualsKind(expected, entityBytes, "entity")

  /**
   * Assert on the response entity to equal the given object after applying an [[akka.http.javadsl.server.Unmarshaller]].
   */
  def assertEntityAs[T <: AnyRef](unmarshaller: Unmarshaller[T], expected: T): TestResponse =
    assertEqualsKind(expected, entityAs(unmarshaller), "entity")

  /**
   * Assert that a given header instance exists in the response.
   */
  def assertHeaderExists(expected: HttpHeader): TestResponse = {
    assertTrue(response.headers.exists(_ == expected), s"Header $expected was missing.")
    this
  }

  /**
   * Assert that a header of the given type exists.
   */
  def assertHeaderKindExists(name: String): TestResponse = {
    val lowercased = name.toRootLowerCase
    assertTrue(response.headers.exists(_.is(lowercased)), s"Expected `$name` header was missing.")
    this
  }

  /**
   * Assert that a header of the given name and value exists.
   */
  def assertHeaderExists(name: String, value: String): TestResponse = {
    val lowercased = name.toRootLowerCase
    val headers = response.headers.filter(_.is(lowercased))
    if (headers.isEmpty) fail(s"Expected `$name` header was missing.")
    else assertTrue(headers.exists(_.value == value),
      s"`$name` header was found but had the wrong value. Found headers: ${headers.mkString(", ")}")

    this
  }

  private[this] def extractFromResponse[T](f: HttpResponse ⇒ T): T =
    if (response eq null) doFail("Request didn't complete with response")
    else f(response)

  protected def assertEqualsKind(expected: AnyRef, actual: AnyRef, kind: String): TestResponse = {
    assertEquals(expected, actual, s"Unexpected $kind!")
    this
  }
  protected def assertEqualsKind(expected: Int, actual: Int, kind: String): TestResponse = {
    assertEquals(expected, actual, s"Unexpected $kind!")
    this
  }

  // allows to `fail` as an expression
  private def doFail(message: String): Nothing = {
    fail(message)
    throw new IllegalStateException("Shouldn't be reached")
  }

  protected def fail(message: String): Unit
  protected def assertEquals(expected: AnyRef, actual: AnyRef, message: String): Unit
  protected def assertEquals(expected: Int, actual: Int, message: String): Unit
  protected def assertTrue(predicate: Boolean, message: String): Unit
}