/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.common

import akka.NotUsed
import akka.http.javadsl.{ common, model ⇒ jm }
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Flow
import akka.util.ByteString

/**
 * Entity streaming support trait allowing rendering and receiving incoming ``Source[T, _]`` from HTTP entities.
 *
 * See [[JsonEntityStreamingSupport]] or [[CsvEntityStreamingSupport]] for default implementations.
 */
abstract class EntityStreamingSupport extends common.EntityStreamingSupport {
  /** Read-side, what content types it is able to frame and unmarshall. */
  def supported: ContentTypeRange
  /** Write-side, defines what Content-Type the Marshaller should offer and the final Content-Type of the response. */
  def contentType: ContentType

  /**
   * Read-side, decode incoming framed entity.
   * For example with an incoming JSON array, chunk it up into JSON objects contained within that array.
   */
  def framingDecoder: Flow[ByteString, ByteString, NotUsed]
  override final def getFramingDecoder = framingDecoder.asJava

  /**
   * Write-side, apply framing to outgoing entity stream.
   *
   * Most typical usage will be a variant of `Flow[ByteString].intersperse`.
   *
   * For example for rendering a JSON array one would return
   * `Flow[ByteString].intersperse(ByteString("["), ByteString(","), ByteString("]"))`
   * and for rendering a new-line separated CSV simply `Flow[ByteString].intersperse(ByteString("\n"))`.
   */
  def framingRenderer: Flow[ByteString, ByteString, NotUsed]
  override final def getFramingRenderer = framingRenderer.asJava

  /**
   * Read-side, allows changing what content types are accepted by this framing.
   *
   * EntityStreamingSupport traits MUST support re-configuring the accepted [[ContentTypeRange]].
   *
   * This is in order to support a-typical APIs which users still want to communicate with using
   * the provided support trait. Typical examples include APIs which return valid `application/json`
   * however advertise the content type as being `application/javascript` or vendor specific content types,
   * which still parse correctly as JSON, CSV or something else that a provided support trait is built for.
   *
   * NOTE: Implementations should specialize the return type to their own Type!
   */
  override def withSupported(range: jm.ContentTypeRange): EntityStreamingSupport

  /**
   * Write-side, defines what Content-Type the Marshaller should offer and the final Content-Type of the response.
   *
   * EntityStreamingSupport traits MUST support re-configuring the offered [[ContentType]].
   * This is due to the need integrating with existing systems which sometimes excpect custom Content-Types,
   * however really are just plain JSON or something else internally (perhaps with slight extensions).
   *
   * NOTE: Implementations should specialize the return type to their own Type!
   */
  override def withContentType(range: jm.ContentType): EntityStreamingSupport

  /**
   * Write-side / read-side, defines if (un)marshalling should be done in parallel.
   *
   * This may be beneficial marshalling the bottleneck in the pipeline.
   *
   * See also [[parallelism]] and [[withParallelMarshalling]].
   */
  def parallelism: Int

  /**
   * Write-side / read-side, defines if (un)marshalling of incoming stream elements should be perserved or not.
   *
   * Allowing for parallel and unordered (un)marshalling often yields higher throughput and also allows avoiding
   * head-of-line blocking if some elements are much larger than others.
   *
   * See also [[parallelism]] and [[withParallelMarshalling]].
   */
  def unordered: Boolean

  /**
   * Write-side / read-side, defines parallelism and if ordering should be preserved or not of Source element marshalling.
   *
   * Sometimes marshalling multiple elements at once (esp. when elements are not evenly sized, and ordering is not enforced)
   * may yield in higher throughput.
   *
   * NOTE: Implementations should specialize the return type to their own Type!
   */
  def withParallelMarshalling(parallelism: Int, unordered: Boolean): EntityStreamingSupport

}

/**
 * Entity streaming support, independent of used Json parsing library etc.
 */
object EntityStreamingSupport {

  /**
   * Default `application/json` entity streaming support.
   *
   * Provides framing (based on scanning the incoming dataBytes for valid JSON objects, so for example uploads using arrays or
   * new-line separated JSON objects are all parsed correctly) and rendering of Sources as JSON Arrays.
   * A different very popular style of returning streaming JSON is to separate JSON objects on a line-by-line basis,
   * you can configure the support trait to do so by calling `withFramingRendererFlow`.
   *
   * Limits the maximum JSON object length to 8KB, if you want to increase this limit provide a value explicitly.
   *
   * See also <a href="https://en.wikipedia.org/wiki/JSON_Streaming">https://en.wikipedia.org/wiki/JSON_Streaming</a>
   */
  def json(): JsonEntityStreamingSupport = json(8 * 1024)
  /**
   * Default `application/json` entity streaming support.
   *
   * Provides framing (based on scanning the incoming dataBytes for valid JSON objects, so for example uploads using arrays or
   * new-line separated JSON objects are all parsed correctly) and rendering of Sources as JSON Arrays.
   * A different very popular style of returning streaming JSON is to separate JSON objects on a line-by-line basis,
   * you can configure the support trait to do so by calling `withFramingRendererFlow`.
   *
   * See also <a href="https://en.wikipedia.org/wiki/JSON_Streaming">https://en.wikipedia.org/wiki/JSON_Streaming</a>
   */
  def json(maxObjectLength: Int): JsonEntityStreamingSupport = new JsonEntityStreamingSupport(maxObjectLength)

  /**
   * Default `text/csv(UTF-8)` entity streaming support.
   * Provides framing and rendering of `\n` separated lines and marshalling Sources into such values.
   *
   * Limits the maximum line-length to 8KB, if you want to increase this limit provide a value explicitly.
   */
  def csv(): CsvEntityStreamingSupport = csv(8 * 1024)
  /**
   * Default `text/csv(UTF-8)` entity streaming support.
   * Provides framing and rendering of `\n` separated lines and marshalling Sources into such values.
   */
  def csv(maxLineLength: Int): CsvEntityStreamingSupport = new CsvEntityStreamingSupport(maxLineLength)
}

