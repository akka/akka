/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.control.NonFatal
import akka.http.util.FastFuture
import akka.http.model._
import FastFuture._

sealed abstract class Marshaller[-A, +B] extends (A ⇒ Future[List[Marshalling[B]]]) {

  def map[C](f: B ⇒ C)(implicit ec: ExecutionContext): Marshaller[A, C] =
    Marshaller[A, C](value ⇒ this(value).fast map (_ map (_ map f)))

  /**
   * Reuses this Marshaller's logic to produce a new Marshaller from another type `C` which overrides
   * the produced [[ContentType]] with another one.
   * Depending on whether the given [[ContentType]] has a defined charset or not and whether the underlying
   * marshaller marshals with a fixed charset it can happen, that the wrapping becomes illegal.
   * For example, a marshaller producing content encoded with UTF-16 cannot be wrapped with a [[ContentType]]
   * that has a defined charset of UTF-8, since akka-http will never recode entities.
   * If the wrapping is illegal the [[Future]] produced by the resulting marshaller will contain a [[RuntimeException]].
   */
  def wrap[C, D >: B](contentType: ContentType)(f: C ⇒ A)(implicit ec: ExecutionContext, mto: MediaTypeOverrider[D]): Marshaller[C, D] =
    Marshaller { value ⇒
      import Marshalling._
      this(f(value)).fast map {
        _ map {
          case WithFixedCharset(_, cs, marshal) if contentType.definedCharset.isEmpty || contentType.charset == cs ⇒
            WithFixedCharset(contentType.mediaType, cs, () ⇒ mto(marshal(), contentType.mediaType))
          case WithOpenCharset(_, marshal) if contentType.definedCharset.isEmpty ⇒
            WithOpenCharset(contentType.mediaType, cs ⇒ mto(marshal(cs), contentType.mediaType))
          case WithOpenCharset(_, marshal) ⇒
            WithFixedCharset(contentType.mediaType, contentType.charset, () ⇒ mto(marshal(contentType.charset), contentType.mediaType))
          case Opaque(marshal) if contentType.definedCharset.isEmpty ⇒ Opaque(() ⇒ mto(marshal(), contentType.mediaType))
          case x ⇒ sys.error(s"Illegal marshaller wrapping. Marshalling `$x` cannot be wrapped with ContentType `$contentType`")
        }
      }
    }

  override def compose[C](f: C ⇒ A): Marshaller[C, B] = Marshaller(super.compose(f))
}

object Marshaller
  extends GenericMarshallers
  with PredefinedToEntityMarshallers
  with PredefinedToResponseMarshallers
  with PredefinedToRequestMarshallers {

  /**
   * Creates a [[Marshaller]] from the given function.
   */
  def apply[A, B](f: A ⇒ Future[List[Marshalling[B]]]): Marshaller[A, B] =
    new Marshaller[A, B] {
      def apply(value: A) =
        try f(value)
        catch { case NonFatal(e) ⇒ FastFuture.failed(e) }
    }

  /**
   * Helper for creating a [[Marshaller]] using the given function.
   */
  def strict[A, B](f: A ⇒ Marshalling[B]): Marshaller[A, B] =
    Marshaller { a ⇒ FastFuture.successful(f(a) :: Nil) }

  /**
   * Helper for creating a "super-marshaller" from a number of "sub-marshallers".
   * Content-negotiation determines, which "sub-marshallers" eventually gets to do the job.
   */
  def oneOf[A, B](marshallers: Marshaller[A, B]*)(implicit ec: ExecutionContext): Marshaller[A, B] =
    Marshaller { a ⇒ FastFuture.sequence(marshallers.map(_(a))).fast.map(_.flatten.toList) }

  /**
   * Helper for creating a "super-marshaller" from a number of values and a function producing "sub-marshallers"
   * from these values. Content-negotiation determines, which "sub-marshallers" eventually gets to do the job.
   */
  def oneOf[T, A, B](values: T*)(f: T ⇒ Marshaller[A, B])(implicit ec: ExecutionContext): Marshaller[A, B] =
    oneOf(values map f: _*)

  /**
   * Helper for creating a synchronous [[Marshaller]] to content with a fixed charset from the given function.
   */
  def withFixedCharset[A, B](mediaType: MediaType, charset: HttpCharset)(marshal: A ⇒ B): Marshaller[A, B] =
    strict { value ⇒ Marshalling.WithFixedCharset(mediaType, charset, () ⇒ marshal(value)) }

  /**
   * Helper for creating a synchronous [[Marshaller]] to content with a negotiable charset from the given function.
   */
  def withOpenCharset[A, B](mediaType: MediaType)(marshal: (A, HttpCharset) ⇒ B): Marshaller[A, B] =
    strict { value ⇒ Marshalling.WithOpenCharset(mediaType, charset ⇒ marshal(value, charset)) }

  /**
   * Helper for creating a synchronous [[Marshaller]] to non-negotiable content from the given function.
   */
  def opaque[A, B](marshal: A ⇒ B): Marshaller[A, B] =
    strict { value ⇒ Marshalling.Opaque(() ⇒ marshal(value)) }
}

/**
 * Describes one possible option for marshalling a given value.
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
