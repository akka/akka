/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import java.net.InetAddress
import java.util.Optional
import java.{ util ⇒ ju, lang ⇒ jl }
import akka.japi.Pair
import akka.stream.{ Graph, FlowShape, javadsl, scaladsl }

import scala.collection.immutable
import scala.compat.java8.OptionConverters
import scala.reflect.ClassTag
import akka.NotUsed
import akka.http.impl.model.{ JavaQuery, JavaUri }
import akka.http.javadsl.{ model ⇒ jm, HttpConnectionContext, ConnectionContext, HttpsConnectionContext }
import akka.http.scaladsl.{ model ⇒ sm }
import akka.http.javadsl.{ settings ⇒ js }

import akka.http.impl.util.JavaMapping.Implicits._

import scala.util.Try

/** INTERNAL API */
private[http] trait J2SMapping[J] {
  type S
  def toScala(javaObject: J): S
}

/** INTERNAL API */
private[http] object J2SMapping {
  implicit def fromJavaMapping[J](implicit mapping: JavaMapping[J, _]): J2SMapping[J] { type S = mapping.S } = mapping

  implicit def fromJavaSeqMapping[J](implicit mapping: J2SMapping[J]): J2SMapping[Seq[J]] { type S = immutable.Seq[mapping.S] } =
    new J2SMapping[Seq[J]] {
      type S = immutable.Seq[mapping.S]
      def toScala(javaObject: Seq[J]): S = javaObject.map(mapping.toScala(_)).toList
    }
}

/** INTERNAL API */
private[http] trait S2JMapping[S] {
  type J
  def toJava(scalaObject: S): J
}

/** INTERNAL API */
private[http] object S2JMapping {
  implicit def fromScalaMapping[S](implicit mapping: JavaMapping[_, S]): S2JMapping[S] { type J = mapping.J } = mapping
}

/** INTERNAL API */
private[http] trait JavaMapping[_J, _S] extends J2SMapping[_J] with S2JMapping[_S] {
  type J = _J
  type S = _S
}

/** INTERNAL API */
private[http] object JavaMapping {
  trait AsScala[S] {
    def asScala: S
  }
  trait AsJava[J] {
    def asJava: J
  }

  def toJava[J, S](s: S)(implicit mapping: JavaMapping[J, S]): J = mapping.toJava(s)
  def toScala[J, S](j: J)(implicit mapping: JavaMapping[J, S]): S = mapping.toScala(j)

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

  /** This trivial mapping isn't enabled by default to prevent it from conflicting with the `Inherited` ones */
  def identity[T]: JavaMapping[T, T] = _identityMapping.asInstanceOf[JavaMapping[T, T]]
  private final val _identityMapping = new JavaMapping[Any, Any] {
    def toJava(scalaObject: Any): Any = scalaObject
    def toScala(javaObject: Any): Any = javaObject
  }

  implicit def iterableMapping[_J, _S](implicit mapping: JavaMapping[_J, _S]): JavaMapping[jl.Iterable[_J], immutable.Seq[_S]] =
    new JavaMapping[jl.Iterable[_J], immutable.Seq[_S]] {
      import collection.JavaConverters._

      def toJava(scalaObject: immutable.Seq[_S]): jl.Iterable[_J] = scalaObject.map(mapping.toJava).asJavaCollection
      def toScala(javaObject: jl.Iterable[_J]): immutable.Seq[_S] =
        Implicits.convertSeqToScala(iterableAsScalaIterableConverter(javaObject).asScala.toSeq)
    }
  implicit def map[K, V]: JavaMapping[ju.Map[K, V], immutable.Map[K, V]] =
    new JavaMapping[ju.Map[K, V], immutable.Map[K, V]] {
      import scala.collection.JavaConverters._
      def toScala(javaObject: ju.Map[K, V]): immutable.Map[K, V] = javaObject.asScala.toMap
      def toJava(scalaObject: immutable.Map[K, V]): ju.Map[K, V] = scalaObject.asJava
    }
  implicit def option[_J, _S](implicit mapping: JavaMapping[_J, _S]): JavaMapping[Optional[_J], Option[_S]] =
    new JavaMapping[Optional[_J], Option[_S]] {
      def toScala(javaObject: Optional[_J]): Option[_S] = OptionConverters.toScala(javaObject).map(mapping.toScala)
      def toJava(scalaObject: Option[_S]): Optional[_J] = OptionConverters.toJava(scalaObject.map(mapping.toJava))
    }

  implicit def flowMapping[JIn, SIn, JOut, SOut, M](implicit inMapping: JavaMapping[JIn, SIn], outMapping: JavaMapping[JOut, SOut]): JavaMapping[javadsl.Flow[JIn, JOut, M], scaladsl.Flow[SIn, SOut, M]] =
    new JavaMapping[javadsl.Flow[JIn, JOut, M], scaladsl.Flow[SIn, SOut, M]] {
      def toScala(javaObject: javadsl.Flow[JIn, JOut, M]): S =
        scaladsl.Flow[SIn].map(inMapping.toJava).viaMat(javaObject)(scaladsl.Keep.right).map(outMapping.toScala)
      def toJava(scalaObject: scaladsl.Flow[SIn, SOut, M]): J =
        javadsl.Flow.fromGraph {
          scaladsl.Flow[JIn].map(inMapping.toScala).viaMat(scalaObject)(scaladsl.Keep.right).map(outMapping.toJava)
        }
    }

  implicit def graphFlowMapping[JIn, SIn, JOut, SOut, M](implicit inMapping: JavaMapping[JIn, SIn], outMapping: JavaMapping[JOut, SOut]): JavaMapping[Graph[FlowShape[JIn, JOut], M], Graph[FlowShape[SIn, SOut], M]] =
    new JavaMapping[Graph[FlowShape[JIn, JOut], M], Graph[FlowShape[SIn, SOut], M]] {
      def toScala(javaObject: Graph[FlowShape[JIn, JOut], M]): S =
        scaladsl.Flow[SIn].map(inMapping.toJava).viaMat(javaObject)(scaladsl.Keep.right).map(outMapping.toScala)
      def toJava(scalaObject: Graph[FlowShape[SIn, SOut], M]): J =
        javadsl.Flow.fromGraph {
          scaladsl.Flow[JIn].map(inMapping.toScala).viaMat(scalaObject)(scaladsl.Keep.right).map(outMapping.toJava)
        }
    }

  def scalaToJavaAdapterFlow[J, S](implicit mapping: JavaMapping[J, S]): scaladsl.Flow[S, J, NotUsed] =
    scaladsl.Flow[S].map(mapping.toJava)
  def javaToScalaAdapterFlow[J, S](implicit mapping: JavaMapping[J, S]): scaladsl.Flow[J, S, NotUsed] =
    scaladsl.Flow[J].map(mapping.toScala)
  def adapterBidiFlow[JIn, SIn, SOut, JOut](implicit inMapping: JavaMapping[JIn, SIn], outMapping: JavaMapping[JOut, SOut]): scaladsl.BidiFlow[JIn, SIn, SOut, JOut, NotUsed] =
    scaladsl.BidiFlow.fromFlowsMat(javaToScalaAdapterFlow(inMapping), scalaToJavaAdapterFlow(outMapping))(scaladsl.Keep.none)

  implicit def pairMapping[J1, J2, S1, S2](implicit _1Mapping: JavaMapping[J1, S1], _2Mapping: JavaMapping[J2, S2]): JavaMapping[Pair[J1, J2], (S1, S2)] =
    new JavaMapping[Pair[J1, J2], (S1, S2)] {
      def toJava(scalaObject: (S1, S2)): J = Pair(_1Mapping.toJava(scalaObject._1), _2Mapping.toJava(scalaObject._2))
      def toScala(javaObject: Pair[J1, J2]): (S1, S2) = (_1Mapping.toScala(javaObject.first), _2Mapping.toScala(javaObject.second))
    }
  implicit def tryMapping[_J, _S](implicit mapping: JavaMapping[_J, _S]): JavaMapping[Try[_J], Try[_S]] =
    new JavaMapping[Try[_J], Try[_S]] {
      def toScala(javaObject: Try[_J]): S = javaObject.map(mapping.toScala)
      def toJava(scalaObject: Try[_S]): J = scalaObject.map(mapping.toJava)
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

  implicit object ConnectionContext extends Inherited[ConnectionContext, akka.http.scaladsl.ConnectionContext]
  implicit object HttpConnectionContext extends Inherited[HttpConnectionContext, akka.http.scaladsl.HttpConnectionContext]
  implicit object HttpsConnectionContext extends Inherited[HttpsConnectionContext, akka.http.scaladsl.HttpsConnectionContext]

  implicit object ClientConnectionSettings extends Inherited[js.ClientConnectionSettings, akka.http.scaladsl.settings.ClientConnectionSettings]
  implicit object ConnectionPoolSettings extends Inherited[js.ConnectionPoolSettings, akka.http.scaladsl.settings.ConnectionPoolSettings]
  implicit object ParserSettings extends Inherited[js.ParserSettings, akka.http.scaladsl.settings.ParserSettings]
  implicit object CookieParsingMode extends Inherited[js.ParserSettings.CookieParsingMode, akka.http.scaladsl.settings.ParserSettings.CookieParsingMode]
  implicit object ErrorLoggingVerbosity extends Inherited[js.ParserSettings.ErrorLoggingVerbosity, akka.http.scaladsl.settings.ParserSettings.ErrorLoggingVerbosity]
  implicit object ServerSettings extends Inherited[js.ServerSettings, akka.http.scaladsl.settings.ServerSettings]
  implicit object ServerSettingsT extends Inherited[js.ServerSettings.Timeouts, akka.http.scaladsl.settings.ServerSettings.Timeouts]

  implicit object DateTime extends Inherited[jm.DateTime, akka.http.scaladsl.model.DateTime]

  implicit object ContentType extends Inherited[jm.ContentType, sm.ContentType]
  implicit object ContentTypeBinary extends Inherited[jm.ContentType.Binary, sm.ContentType.Binary]
  implicit object ContentTypeNonBinary extends Inherited[jm.ContentType.NonBinary, sm.ContentType.NonBinary]
  implicit object ContentTypeWithFixedCharset extends Inherited[jm.ContentType.WithFixedCharset, sm.ContentType.WithFixedCharset]
  implicit object ContentTypeWithCharset extends Inherited[jm.ContentType.WithCharset, sm.ContentType.WithCharset]
  implicit object ContentTypeRange extends Inherited[jm.ContentTypeRange, sm.ContentTypeRange]
  implicit object Host extends Inherited[jm.Host, sm.Uri.Host]
  implicit object HttpCharset extends Inherited[jm.HttpCharset, sm.HttpCharset]
  implicit object HttpCharsetRange extends Inherited[jm.HttpCharsetRange, sm.HttpCharsetRange]
  implicit object HttpEntity extends Inherited[jm.HttpEntity, sm.HttpEntity]
  implicit object RequestEntity extends Inherited[jm.RequestEntity, sm.RequestEntity]
  implicit object ResponseEntity extends Inherited[jm.ResponseEntity, sm.ResponseEntity]
  implicit object HttpHeader extends Inherited[jm.HttpHeader, sm.HttpHeader]
  implicit object HttpMethod extends Inherited[jm.HttpMethod, sm.HttpMethod]
  implicit object HttpProtocol extends Inherited[jm.HttpProtocol, sm.HttpProtocol]
  implicit object HttpRequest extends Inherited[jm.HttpRequest, sm.HttpRequest]
  implicit object HttpResponse extends Inherited[jm.HttpResponse, sm.HttpResponse]
  implicit object MediaRange extends Inherited[jm.MediaRange, sm.MediaRange]
  implicit object MediaType extends Inherited[jm.MediaType, sm.MediaType]
  implicit object MediaTypeBinary extends Inherited[jm.MediaType.Binary, sm.MediaType.Binary]
  implicit object MediaTypeNonBinary extends Inherited[jm.MediaType.NonBinary, sm.MediaType.NonBinary]
  implicit object MediaTypeFixedCharset extends Inherited[jm.MediaType.WithFixedCharset, sm.MediaType.WithFixedCharset]
  implicit object MediaTypeOpenCharset extends Inherited[jm.MediaType.WithOpenCharset, sm.MediaType.WithOpenCharset]
  implicit object StatusCode extends Inherited[jm.StatusCode, sm.StatusCode]

  implicit object ContentRange extends Inherited[jm.ContentRange, sm.ContentRange]
  implicit object RemoteAddress extends Inherited[jm.RemoteAddress, sm.RemoteAddress]
  implicit object TransferEncoding extends Inherited[jm.TransferEncoding, sm.TransferEncoding]

  implicit object HostHeader extends Inherited[jm.headers.Host, sm.headers.Host]
  implicit object Server extends Inherited[jm.headers.Server, sm.headers.Server]
  implicit object ByteRange extends Inherited[jm.headers.ByteRange, sm.headers.ByteRange]
  implicit object CacheDirective extends Inherited[jm.headers.CacheDirective, sm.headers.CacheDirective]
  implicit object UserAgent extends Inherited[jm.headers.UserAgent, sm.headers.`User-Agent`]
  implicit object ContentDispositionType extends Inherited[jm.headers.ContentDispositionType, sm.headers.ContentDispositionType]
  implicit object EntityTag extends Inherited[jm.headers.EntityTag, sm.headers.EntityTag]
  implicit object EntityTagRange extends Inherited[jm.headers.EntityTagRange, sm.headers.EntityTagRange]
  implicit object HttpChallenge extends Inherited[jm.headers.HttpChallenge, sm.headers.HttpChallenge]
  implicit object HttpCookie extends Inherited[jm.headers.HttpCookie, sm.headers.HttpCookie]
  implicit object HttpCookiePair extends Inherited[jm.headers.HttpCookiePair, sm.headers.HttpCookiePair]
  implicit object HttpCredentials extends Inherited[jm.headers.HttpCredentials, sm.headers.HttpCredentials]
  implicit object HttpEncoding extends Inherited[jm.headers.HttpEncoding, sm.headers.HttpEncoding]
  implicit object HttpEncodingRange extends Inherited[jm.headers.HttpEncodingRange, sm.headers.HttpEncodingRange]
  implicit object HttpOrigin extends Inherited[jm.headers.HttpOrigin, sm.headers.HttpOrigin]
  implicit object HttpOriginRange extends Inherited[jm.headers.HttpOriginRange, sm.headers.HttpOriginRange]
  implicit object Language extends Inherited[jm.headers.Language, sm.headers.Language]
  implicit object LanguageRange extends Inherited[jm.headers.LanguageRange, sm.headers.LanguageRange]
  implicit object LinkParam extends Inherited[jm.headers.LinkParam, sm.headers.LinkParam]
  implicit object LinkValue extends Inherited[jm.headers.LinkValue, sm.headers.LinkValue]
  implicit object ProductVersion extends Inherited[jm.headers.ProductVersion, sm.headers.ProductVersion]
  implicit object RangeUnit extends Inherited[jm.headers.RangeUnit, sm.headers.RangeUnit]

  implicit object WsMessage extends JavaMapping[jm.ws.Message, sm.ws.Message] {
    def toScala(javaObject: J): WsMessage.S = javaObject.asScala
    def toJava(scalaObject: S): WsMessage.J = jm.ws.Message.adapt(scalaObject)
  }

  implicit object Uri extends JavaMapping[jm.Uri, sm.Uri] {
    def toScala(javaObject: J): Uri.S = cast[JavaUri](javaObject).uri
    def toJava(scalaObject: S): Uri.J = JavaUri(scalaObject)
  }
  implicit object UriParsingMode extends Inherited[jm.Uri.ParsingMode, akka.http.scaladsl.model.Uri.ParsingMode]

  implicit object Query extends JavaMapping[jm.Query, sm.Uri.Query] {
    def toScala(javaObject: J): Query.S = cast[JavaQuery](javaObject).query
    def toJava(scalaObject: S): Query.J = JavaQuery(scalaObject)
  }

  private def cast[T](obj: AnyRef)(implicit classTag: ClassTag[T]): T =
    try classTag.runtimeClass.cast(obj).asInstanceOf[T]
    catch {
      case exp: ClassCastException ⇒
        throw new IllegalArgumentException(s"Illegal custom subclass of $classTag. " +
          s"Please use only the provided factories in akka.http.javadsl.model.Http")
    }
}
