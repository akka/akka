/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.testkit

import scala.reflect.ClassTag
import scala.concurrent.ExecutionContext
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import akka.util.ByteString
import akka.stream.Materializer
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.model.HttpResponse
import akka.http.impl.util._
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.server.Unmarshaller
import akka.http.javadsl.model._
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.server.Rejection
import scala.collection.JavaConverters._
import scala.annotation.varargs

/**
 * A wrapper for route results.
 *
 * To support the testkit API, a third-party testing library needs to implement this class and provide
 * implementations for the abstract assertion methods.
 */
abstract class TestRouteResult(_result: RouteResult, awaitAtMost: FiniteDuration)(implicit ec: ExecutionContext, materializer: Materializer) {
  
  private def _response = _result match {
    case RouteResult.Complete(response)   ⇒ response
    case RouteResult.Rejected(rejections) ⇒ doFail("Expected route to complete, but was instead rejected with " + rejections)
  }
  
  private def _rejections = _result match {
    case RouteResult.Complete(response) ⇒ doFail("Request was not rejected, response was " + response)
    case RouteResult.Rejected(ex)       ⇒ ex
  }  
  
  /**
   * Returns the strictified entity of the response. It will be strictified on first access.
   */
  lazy val entity: HttpEntity.Strict = _response.entity.toStrict(awaitAtMost).awaitResult(awaitAtMost)

  /**
   * Returns a copy of the underlying response with the strictified entity.
   */
  lazy val response: HttpResponse = _response.withEntity(entity)

  /**
   * Returns the response's content-type
   */
  def contentType: ContentType = _response.entity.contentType
  
  /**
   * Returns a string representation of the response's content-type
   */
  def contentTypeString: String = contentType.toString()
  
  /**
   * Returns the media-type of the the response's content-type
   */
  def mediaType: MediaType = contentType.mediaType

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
  def entity[T](unmarshaller: Unmarshaller[HttpEntity, T]): T =
    Unmarshal(response.entity)
      .to(unmarshaller.asScala, ec, materializer)
      .awaitResult(awaitAtMost)

  /**
   * Returns the entity of the response interpreted as an UTF-8 encoded string.
   */
  def entityString: String = entity.getData.utf8String

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
   * Expects the route to have been rejected, returning the list of rejections, or empty list if the route
   * was rejected with an empty rejection list.
   * Fails the test if the route completes with a response rather than having been rejected.
   */
  def rejections: java.util.List[Rejection] = _rejections.asJava
  
  /**
   * Expects the route to have been rejected with a single rejection.
   * Fails the test if the route completes with a response, or is rejected with 0 or >1 rejections.
   */
  def rejection: Rejection = {
    val r = rejections
    if (r.size == 1) r.get(0) else doFail("Expected a single rejection but got %s (%s)".format(r.size, r))
  }

  /**
   * Assert on the numeric status code.
   */
  def assertStatusCode(expected: Int): TestRouteResult =
    assertStatusCode(StatusCodes.get(expected))

  /**
   * Assert on the status code.
   */
  def assertStatusCode(expected: StatusCode): TestRouteResult =
    assertEqualsKind(expected, status, "status code")

  /**
   * Assert on the media type of the response.
   */
  def assertMediaType(expected: String): TestRouteResult =
    assertEqualsKind(expected, mediaTypeString, "media type")

  /**
   * Assert on the media type of the response.
   */
  def assertMediaType(expected: MediaType): TestRouteResult =
    assertEqualsKind(expected, mediaType, "media type")

  /**
   * Assert on the content type of the response.
   */
  def assertContentType(expected: String): TestRouteResult =
    assertEqualsKind(expected, contentTypeString, "content type")

  /**
   * Assert on the content type of the response.
   */
  def assertContentType(expected: ContentType): TestRouteResult =
    assertEqualsKind(expected, contentType, "content type")
    
  /**
   * Assert on the response entity to be a UTF8 representation of the given string.
   */
  def assertEntity(expected: String): TestRouteResult =
    assertEqualsKind(expected, entityString, "entity")

  /**
   * Assert on the response entity to equal the given bytes.
   */
  def assertEntityBytes(expected: ByteString): TestRouteResult =
    assertEqualsKind(expected, entityBytes, "entity")

  /**
   * Assert on the response entity to equal the given object after applying an [[akka.http.javadsl.server.Unmarshaller]].
   */
  def assertEntityAs[T <: AnyRef](unmarshaller: Unmarshaller[HttpEntity,T], expected: T): TestRouteResult =
    assertEqualsKind(expected, entity(unmarshaller), "entity")

  /**
   * Assert that a given header instance exists in the response.
   */
  def assertHeaderExists(expected: HttpHeader): TestRouteResult = {
    assertTrue(response.headers.exists(_ == expected), s"Header $expected was missing.")
    this
  }

  /**
   * Assert that a header of the given type exists.
   */
  def assertHeaderKindExists(name: String): TestRouteResult = {
    val lowercased = name.toRootLowerCase
    assertTrue(response.headers.exists(_.is(lowercased)), s"Expected `$name` header was missing.")
    this
  }

  /**
   * Assert that a header of the given name and value exists.
   */
  def assertHeaderExists(name: String, value: String): TestRouteResult = {
    val lowercased = name.toRootLowerCase
    val headers = response.headers.filter(_.is(lowercased))
    if (headers.isEmpty) fail(s"Expected `$name` header was missing.")
    else assertTrue(headers.exists(_.value == value),
      s"`$name` header was found but had the wrong value. Found headers: ${headers.mkString(", ")}")

    this
  }
  
  @varargs def assertRejections(expectedRejections: Rejection*): TestRouteResult = {
    if (rejections.asScala == expectedRejections.toSeq) {
      this
    } else {
      doFail(s"Expected rejections [${expectedRejections.mkString(",")}], but rejected with [${rejections.asScala.mkString(",")}] instead.") 
    }
  }

  protected def assertEqualsKind(expected: AnyRef, actual: AnyRef, kind: String): TestRouteResult = {
    assertEquals(expected, actual, s"Unexpected $kind!")
    this
  }
  protected def assertEqualsKind(expected: Int, actual: Int, kind: String): TestRouteResult = {
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