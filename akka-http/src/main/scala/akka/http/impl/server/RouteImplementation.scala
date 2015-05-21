/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import scala.language.implicitConversions
import scala.annotation.tailrec
import scala.collection.immutable
import akka.http.javadsl.model.ContentType
import akka.http.scaladsl.server.directives.{ UserCredentials, ContentTypeResolver }
import akka.http.scaladsl.server.directives.FileAndResourceDirectives.DirectoryRenderer
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.CustomHeader
import akka.http.scaladsl.server.{ Route ⇒ ScalaRoute, Directive0, Directives }
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.scaladsl.server
import akka.http.javadsl.server._
import RouteStructure._

/**
 * INTERNAL API
 */
private[http] trait ExtractionMap extends CustomHeader {
  def get[T](key: RequestVal[T]): Option[T]
  def set[T](key: RequestVal[T], value: T): ExtractionMap
}
/**
 * INTERNAL API
 */
private[http] object ExtractionMap {
  implicit def apply(map: Map[RequestVal[_], Any]): ExtractionMap =
    new ExtractionMap {
      def get[T](key: RequestVal[T]): Option[T] =
        map.get(key).asInstanceOf[Option[T]]

      def set[T](key: RequestVal[T], value: T): ExtractionMap =
        ExtractionMap(map.updated(key, value))

      // CustomHeader methods
      override def suppressRendering: Boolean = true
      def name(): String = "ExtractedValues"
      def value(): String = "<empty>"
    }
}

/**
 * INTERNAL API
 */
private[http] object RouteImplementation extends Directives with server.RouteConcatenation {
  def apply(route: Route): ScalaRoute = route match {
    case RouteAlternatives(children) ⇒
      val converted = children.map(RouteImplementation.apply)
      converted.reduce(_ ~ _)
    case RawPathPrefix(elements, children) ⇒
      val inner = apply(RouteAlternatives(children))

      def one[T](matcher: PathMatcher[T]): Directive0 =
        rawPathPrefix(matcher.asInstanceOf[PathMatcherImpl[T]].matcher) flatMap { value ⇒
          addExtraction(matcher, value)
        }
      elements.map(one(_)).reduce(_ & _).apply(inner)

    case GetFromResource(path, contentType, classLoader) ⇒
      getFromResource(path, contentType.asScala, classLoader)
    case GetFromResourceDirectory(path, classLoader, resolver) ⇒
      getFromResourceDirectory(path, classLoader)(scalaResolver(resolver))
    case GetFromFile(file, contentType) ⇒
      getFromFile(file, contentType.asScala)
    case GetFromDirectory(directory, true, resolver) ⇒
      extractExecutionContext { implicit ec ⇒
        getFromBrowseableDirectory(directory.getPath)(DirectoryRenderer.defaultDirectoryRenderer, scalaResolver(resolver))
      }
    case FileAndResourceRouteWithDefaultResolver(constructor) ⇒
      RouteImplementation(constructor(new directives.ContentTypeResolver {
        def resolve(fileName: String): ContentType = ContentTypeResolver.Default(fileName)
      }))

    case MethodFilter(m, children) ⇒
      val inner = apply(RouteAlternatives(children))
      method(m.asScala).apply(inner)

    case Extract(extractions, children) ⇒
      val inner = apply(RouteAlternatives(children))
      extractRequestContext.flatMap { ctx ⇒
        extractions.map { e ⇒
          e.directive.flatMap(addExtraction(e.asInstanceOf[RequestVal[Any]], _))
        }.reduce(_ & _)
      }.apply(inner)

    case BasicAuthentication(authenticator, children) ⇒
      val inner = apply(RouteAlternatives(children))
      authenticateBasicAsync(authenticator.realm, { creds ⇒
        val javaCreds =
          creds match {
            case UserCredentials.Missing ⇒
              new BasicUserCredentials {
                def available: Boolean = false
                def userName: String = throw new IllegalStateException("Credentials missing")
                def verifySecret(secret: String): Boolean = throw new IllegalStateException("Credentials missing")
              }
            case p @ UserCredentials.Provided(name) ⇒
              new BasicUserCredentials {
                def available: Boolean = true
                def userName: String = name
                def verifySecret(secret: String): Boolean = p.verifySecret(secret)
              }
          }

        authenticator.authenticate(javaCreds)
      }).flatMap { user ⇒
        addExtraction(authenticator.asInstanceOf[RequestVal[Any]], user)
      }.apply(inner)

    case EncodeResponse(coders, children) ⇒
      val scalaCoders = coders.map(_._underlyingScalaCoder())
      encodeResponseWith(scalaCoders.head, scalaCoders.tail: _*).apply(apply(RouteAlternatives(children)))

    case Conditional(eTag, lastModified, children) ⇒
      conditional(eTag.asScala, lastModified.asScala).apply(apply(RouteAlternatives(children)))

    case o: OpaqueRoute ⇒
      (ctx ⇒ o.handle(new RequestContextImpl(ctx)).asInstanceOf[RouteResultImpl].underlying)

    case p: Product ⇒ extractExecutionContext { implicit ec ⇒ complete(500, s"Not implemented: ${p.productPrefix}") }
  }

  def addExtraction[T](key: RequestVal[T], value: T): Directive0 = {
    @tailrec def addToExtractionMap(headers: immutable.Seq[HttpHeader], prefix: Vector[HttpHeader] = Vector.empty): immutable.Seq[HttpHeader] =
      headers match {
        case (m: ExtractionMap) +: rest ⇒ m.set(key, value) +: (prefix ++ rest)
        case other +: rest              ⇒ addToExtractionMap(rest, prefix :+ other)
        case Nil                        ⇒ ExtractionMap(Map(key -> value)) +: prefix
      }
    mapRequest(_.mapHeaders(addToExtractionMap(_)))
  }

  private def scalaResolver(resolver: directives.ContentTypeResolver): ContentTypeResolver =
    ContentTypeResolver(f ⇒ resolver.resolve(f).asScala)
}
