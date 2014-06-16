/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi

import scala.collection.immutable
import scala.reflect.ClassTag

import akka.http.model
import java.{ util ⇒ ju, lang ⇒ jl }
import akka.japi
import java.net.InetAddress

/** INTERNAL API */
trait J2SMapping[J] {
  type S
  def toScala(javaObject: J): S
}
/** INTERNAL API */
object J2SMapping {
  implicit def fromJavaMapping[J](implicit mapping: JavaMapping[J, _]): J2SMapping[J] { type S = mapping.S } = mapping

  implicit def seqMapping[J](implicit mapping: J2SMapping[J]): J2SMapping[Seq[J]] { type S = immutable.Seq[mapping.S] } =
    new J2SMapping[Seq[J]] {
      type S = immutable.Seq[mapping.S]
      def toScala(javaObject: Seq[J]): S = javaObject.map(mapping.toScala(_)).toList
    }
}
/** INTERNAL API */
trait S2JMapping[S] {
  type J
  def toJava(scalaObject: S): J
}
/** INTERNAL API */
object S2JMapping {
  implicit def fromJavaMapping[S](implicit mapping: JavaMapping[_, S]): S2JMapping[S] { type J = mapping.J } = mapping
}

/** INTERNAL API */
trait JavaMapping[_J, _S] extends J2SMapping[_J] with S2JMapping[_S] {
  type J = _J
  type S = _S
}
/** INTERNAL API */
object JavaMapping {
  trait AsScala[S] {
    def asScala: S
  }
  trait AsJava[J] {
    def asJava: J
  }

  object Implicits {
    import scala.language.implicitConversions

    implicit def convertToScala[J](j: J)(implicit mapping: J2SMapping[J]): mapping.S = mapping.toScala(j)
    implicit def convertSeqToScala[J](j: Seq[J])(implicit mapping: J2SMapping[J]): immutable.Seq[mapping.S] =
      j.map(mapping.toScala(_)).toList

    implicit def AddAsScala[J](javaObject: J)(implicit mapping: J2SMapping[J]): AsScala[mapping.S] = new AsScala[mapping.S] {
      def asScala = convertToScala(javaObject)
    }
    implicit def AddAsJava[S](scalaObject: S)(implicit mapping: S2JMapping[S]): AsJava[mapping.J] = new AsJava[mapping.J] {
      def asJava = mapping.toJava(scalaObject)
    }
  }

  implicit def iterableMapping[_J, _S](implicit mapping: JavaMapping[_J, _S]): JavaMapping[jl.Iterable[_J], immutable.Seq[_S]] =
    new JavaMapping[jl.Iterable[_J], immutable.Seq[_S]] {
      import collection.JavaConverters._

      def toJava(scalaObject: immutable.Seq[_S]): jl.Iterable[_J] = scalaObject.map(mapping.toJava(_)).asJavaCollection
      def toScala(javaObject: jl.Iterable[_J]): immutable.Seq[_S] =
        Implicits.convertSeqToScala(iterableAsScalaIterableConverter(javaObject).asScala.toSeq)
    }
  implicit def map[K, V]: JavaMapping[ju.Map[K, V], immutable.Map[K, V]] =
    new JavaMapping[ju.Map[K, V], immutable.Map[K, V]] {
      import scala.collection.JavaConverters._
      def toScala(javaObject: ju.Map[K, V]): immutable.Map[K, V] = javaObject.asScala.toMap
      def toJava(scalaObject: immutable.Map[K, V]): ju.Map[K, V] = scalaObject.asJava
    }
  implicit def option[_J, _S](implicit mapping: JavaMapping[_J, _S]): JavaMapping[akka.japi.Option[_J], Option[_S]] =
    new JavaMapping[akka.japi.Option[_J], Option[_S]] {
      def toScala(javaObject: japi.Option[_J]): Option[_S] = javaObject.asScala.map(mapping.toScala(_))
      def toJava(scalaObject: Option[_S]): japi.Option[_J] = japi.Option.fromScalaOption(scalaObject.map(mapping.toJava(_)))
    }

  implicit object StringIdentity extends Identity[String]

  implicit object LongMapping extends JavaMapping[jl.Long, Long] {
    def toScala(javaObject: jl.Long): Long = javaObject
    def toJava(scalaObject: Long): jl.Long = scalaObject
  }
  implicit object InetAddressIdentity extends Identity[InetAddress]

  class Identity[T] extends JavaMapping[T, T] {
    def toScala(javaObject: T): T = javaObject
    def toJava(scalaObject: T): T = scalaObject
  }
  class Inherited[J <: AnyRef, S <: J](implicit classTag: ClassTag[S]) extends JavaMapping[J, S] {
    def toJava(scalaObject: S): J = scalaObject
    def toScala(javaObject: J): S = cast[S](javaObject)
  }

  implicit object DateTime extends Inherited[DateTime, akka.http.util.DateTime]

  implicit object ContentType extends Inherited[ContentType, model.ContentType]
  implicit object Host extends Inherited[Host, model.Uri.Host]
  implicit object HttpCharset extends Inherited[HttpCharset, model.HttpCharset]
  implicit object HttpCharsetRange extends Inherited[HttpCharsetRange, model.HttpCharsetRange]
  implicit object HttpEntity extends Inherited[HttpEntity, model.HttpEntity]
  implicit object HttpEntityRegular extends Inherited[HttpEntityRegular, model.HttpEntity.Regular]
  implicit object HttpHeader extends Inherited[HttpHeader, model.HttpHeader]
  implicit object HttpMethod extends Inherited[HttpMethod, model.HttpMethod]
  implicit object HttpProtocol extends Inherited[HttpProtocol, model.HttpProtocol]
  implicit object HttpRequest extends Inherited[HttpRequest, model.HttpRequest]
  implicit object HttpResponse extends Inherited[HttpResponse, model.HttpResponse]
  implicit object MediaRange extends Inherited[MediaRange, model.MediaRange]
  implicit object MediaType extends Inherited[MediaType, model.MediaType]
  implicit object StatusCode extends Inherited[StatusCode, model.StatusCode]

  implicit object ContentRange extends Inherited[ContentRange, model.ContentRange]
  implicit object RemoteAddress extends Inherited[RemoteAddress, model.RemoteAddress]
  implicit object TransferEncoding extends Inherited[TransferEncoding, model.TransferEncoding]

  implicit object ByteRange extends Inherited[headers.ByteRange, model.headers.ByteRange]
  implicit object CacheDirective extends Inherited[headers.CacheDirective, model.headers.CacheDirective]
  implicit object ContentDispositionType extends Inherited[headers.ContentDispositionType, model.headers.ContentDispositionType]
  implicit object EntityTag extends Inherited[headers.EntityTag, model.headers.EntityTag]
  implicit object EntityTagRange extends Inherited[headers.EntityTagRange, model.headers.EntityTagRange]
  implicit object HttpChallenge extends Inherited[headers.HttpChallenge, model.headers.HttpChallenge]
  implicit object HttpCookie extends Inherited[headers.HttpCookie, model.headers.HttpCookie]
  implicit object HttpCredentials extends Inherited[headers.HttpCredentials, model.headers.HttpCredentials]
  implicit object HttpEncoding extends Inherited[headers.HttpEncoding, model.headers.HttpEncoding]
  implicit object HttpEncodingRange extends Inherited[headers.HttpEncodingRange, model.headers.HttpEncodingRange]
  implicit object HttpOrigin extends Inherited[headers.HttpOrigin, model.headers.HttpOrigin]
  implicit object HttpOriginRange extends Inherited[headers.HttpOriginRange, model.headers.HttpOriginRange]
  implicit object Language extends Inherited[headers.Language, model.headers.Language]
  implicit object LanguageRange extends Inherited[headers.LanguageRange, model.headers.LanguageRange]
  implicit object LinkParam extends Inherited[headers.LinkParam, model.headers.LinkParam]
  implicit object LinkValue extends Inherited[headers.LinkValue, model.headers.LinkValue]
  implicit object ProductVersion extends Inherited[headers.ProductVersion, model.headers.ProductVersion]
  implicit object RangeUnit extends Inherited[headers.RangeUnit, model.headers.RangeUnit]

  implicit object Uri extends JavaMapping[Uri, model.Uri] {
    def toScala(javaObject: Uri): Uri.S = cast[JavaUri](javaObject).uri
    def toJava(scalaObject: model.Uri): Uri.J = Accessors.Uri(scalaObject)
  }

  private def cast[T](obj: AnyRef)(implicit classTag: ClassTag[T]): T =
    try classTag.runtimeClass.cast(obj).asInstanceOf[T]
    catch {
      case exp: ClassCastException ⇒
        throw new IllegalArgumentException(s"Illegal custom subclass of $classTag. " +
          s"Please use only the provided factories in akka.http.model.japi.Http")
    }
}
