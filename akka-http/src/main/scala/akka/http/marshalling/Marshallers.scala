/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.collection.immutable
import scala.concurrent.{ Future, ExecutionContext }
import scala.util.control.NonFatal
import scala.xml.NodeSeq
import akka.http.util.FastFuture
import akka.http.model._
import FastFuture._
import MediaTypes._

case class Marshallers[-A, +B](marshallers: immutable.Seq[Marshaller[A, B]]) {
  require(marshallers.nonEmpty, "marshallers must be non-empty")
  def map[C](f: B ⇒ C)(implicit ec: ExecutionContext): Marshallers[A, C] =
    Marshallers(marshallers map (_ map f))
}

object Marshallers extends SingleMarshallerMarshallers {
  def apply[A, B](m: Marshaller[A, B]): Marshallers[A, B] = apply(m :: Nil)
  def apply[A, B](first: Marshaller[A, B], more: Marshaller[A, B]*): Marshallers[A, B] = apply(first +: more.toVector)
  def apply[A, B](first: MediaType, more: MediaType*)(f: MediaType ⇒ Marshaller[A, B]): Marshallers[A, B] = {
    val vector: Vector[Marshaller[A, B]] = more.map(f)(collection.breakOut)
    Marshallers(f(first) +: vector)
  }

  implicit def nodeSeqMarshallers(implicit ec: ExecutionContext): ToEntityMarshallers[NodeSeq] =
    Marshallers(`text/xml`, `application/xml`, `text/html`, `application/xhtml+xml`)(PredefinedToEntityMarshallers.nodeSeqMarshaller)

  implicit def entity2response[T](implicit m: Marshallers[T, ResponseEntity], ec: ExecutionContext): Marshallers[T, HttpResponse] =
    m map (entity ⇒ HttpResponse(entity = entity))
}

sealed abstract class SingleMarshallerMarshallers {
  implicit def singleMarshallerMarshallers[A, B](implicit m: Marshaller[A, B]): Marshallers[A, B] = Marshallers(m)
}

sealed trait Marshaller[-A, +B] extends (A ⇒ Future[Marshalling[B]]) {

  def map[C](f: B ⇒ C)(implicit ec: ExecutionContext): Marshaller[A, C] =
    Marshaller[A, C](value ⇒ this(value).fast.map(_ map f))

  /**
   * Reuses this Marshaller's logic to produce a new Marshaller from another type `C` which overrides
   * the produced media-type with another one.
   */
  def wrap[C, D >: B](mediaType: MediaType)(f: C ⇒ A)(implicit ec: ExecutionContext, mto: MediaTypeOverrider[D]): Marshaller[C, D] =
    Marshaller { value ⇒
      import Marshalling._
      this(f(value)).fast.map {
        case WithFixedCharset(_, cs, marshal) ⇒ WithFixedCharset(mediaType, cs, () ⇒ mto(marshal(), mediaType))
        case WithOpenCharset(_, marshal)      ⇒ WithOpenCharset(mediaType, cs ⇒ mto(marshal(cs), mediaType))
        case Opaque(marshal)                  ⇒ Opaque(() ⇒ mto(marshal(), mediaType))
      }
    }

  override def compose[C](f: C ⇒ A): Marshaller[C, B] = Marshaller(super.compose(f))
}

object Marshaller
  extends GenericMarshallers
  with PredefinedToEntityMarshallers
  with PredefinedToResponseMarshallers
  with PredefinedToRequestMarshallers {

  def apply[A, B](f: A ⇒ Future[Marshalling[B]]): Marshaller[A, B] =
    new Marshaller[A, B] {
      def apply(value: A) =
        try f(value)
        catch { case NonFatal(e) ⇒ FastFuture.failed(e) }
    }

  def withFixedCharset[A, B](mediaType: MediaType, charset: HttpCharset)(marshal: A ⇒ B): Marshaller[A, B] =
    Marshaller { value ⇒ FastFuture.successful(Marshalling.WithFixedCharset(mediaType, charset, () ⇒ marshal(value))) }

  def withOpenCharset[A, B](mediaType: MediaType)(marshal: (A, HttpCharset) ⇒ B): Marshaller[A, B] =
    Marshaller { value ⇒ FastFuture.successful(Marshalling.WithOpenCharset(mediaType, charset ⇒ marshal(value, charset))) }

  def opaque[A, B](marshal: A ⇒ B): Marshaller[A, B] =
    Marshaller { value ⇒ FastFuture.successful(Marshalling.Opaque(() ⇒ marshal(value))) }
}

/**
 * Describes what a Marshaller can produce for a given value.
 */
sealed trait Marshalling[+A] {
  def map[B](f: A ⇒ B): Marshalling[B]
}

object Marshalling {
  /**
   * A Marshalling to a specific MediaType and charset.
   */
  final case class WithFixedCharset[A](mediaType: MediaType,
                                       charset: HttpCharset,
                                       marshal: () ⇒ A) extends Marshalling[A] {
    def map[B](f: A ⇒ B): WithFixedCharset[B] = copy(marshal = () ⇒ f(marshal()))
  }

  /**
   * A Marshalling to a specific MediaType and a potentially flexible charset.
   */
  final case class WithOpenCharset[A](mediaType: MediaType,
                                      marshal: HttpCharset ⇒ A) extends Marshalling[A] {
    def map[B](f: A ⇒ B): WithOpenCharset[B] = copy(marshal = cs ⇒ f(marshal(cs)))
  }

  /**
   * A Marshalling to an unknown MediaType and charset.
   * Circumvents content negotiation.
   */
  final case class Opaque[A](marshal: () ⇒ A) extends Marshalling[A] {
    def map[B](f: A ⇒ B): Opaque[B] = copy(marshal = () ⇒ f(marshal()))
  }
}
