/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import akka.http.impl.util.JavaMapping
import akka.http.javadsl.server.values.{ PathMatcher, BasicCredentials, OAuth2Credentials }
import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.scaladsl.server.util.TupleOps.Join
import scala.language.implicitConversions
import scala.annotation.tailrec
import scala.collection.immutable
import akka.http.javadsl.model.ContentType
import akka.http.scaladsl.server.directives.{ Credentials, ContentTypeResolver }
import akka.http.scaladsl.server.directives.FileAndResourceDirectives.DirectoryRenderer
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.{ HttpCookie, CustomHeader }
import akka.http.scaladsl.server.{ Route ⇒ ScalaRoute, Directive ⇒ ScalaDirective, PathMatcher ⇒ ScalaPathMatcher, PathMatcher1, Directive0, Directive1, Directives }
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.scaladsl.server
import akka.http.javadsl.server._
import RouteStructure._

import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import akka.dispatch.ExecutionContexts.sameThreadExecutionContext

/**
 * INTERNAL API
 */
private[http] trait ExtractionMap extends CustomHeader {
  def get[T](key: RequestVal[T]): Option[T]
  def set[T](key: RequestVal[T], value: T): ExtractionMap
  def addAll(values: Map[RequestVal[_], Any]): ExtractionMap
}
/**
 * INTERNAL API
 */
private[http] object ExtractionMap {
  val Empty = ExtractionMap(Map.empty)
  implicit def apply(map: Map[RequestVal[_], Any]): ExtractionMap =
    new ExtractionMap {
      def get[T](key: RequestVal[T]): Option[T] =
        map.get(key).asInstanceOf[Option[T]]

      def set[T](key: RequestVal[T], value: T): ExtractionMap =
        ExtractionMap(map.updated(key, value))

      def addAll(values: Map[RequestVal[_], Any]): ExtractionMap =
        ExtractionMap(map ++ values)

      def renderInRequests = false
      def renderInResponses = false
      def name(): String = "ExtractedValues"
      def value(): String = "<empty>"
    }
}

/**
 * INTERNAL API
 */
private[http] object RouteImplementation extends Directives with server.RouteConcatenation {
  def apply(route: Route): ScalaRoute = {
    def directiveFor(route: DirectiveRoute): Directive0 = route match {
      case RouteAlternatives()                      ⇒ ScalaDirective.Empty
      case RawPathPrefix(elements)                  ⇒ pathMatcherDirective[String](elements, rawPathPrefix)
      case RawPathPrefixTest(elements)              ⇒ pathMatcherDirective[String](elements, rawPathPrefixTest)
      case PathSuffix(elements)                     ⇒ pathMatcherDirective[String](elements, pathSuffix)
      case PathSuffixTest(elements)                 ⇒ pathMatcherDirective[String](elements, pathSuffixTest)
      case RedirectToTrailingSlashIfMissing(code)   ⇒ redirectToTrailingSlashIfMissing(code.asScala.asInstanceOf[Redirection])
      case RedirectToNoTrailingSlashIfPresent(code) ⇒ redirectToNoTrailingSlashIfPresent(code.asScala.asInstanceOf[Redirection])

      case MethodFilter(m)                          ⇒ method(m.asScala)
      case Extract(extractions) ⇒
        extractRequestContext.flatMap { ctx ⇒
          extractions.map { e ⇒
            e.directive.flatMap(addExtraction(e.asInstanceOf[RequestVal[Any]], _))
          }.reduce(_ & _)
        }

      case BasicAuthentication(authenticator) ⇒
        authenticateBasicAsync(authenticator.realm, { creds ⇒
          val javaCreds =
            creds match {
              case Credentials.Missing ⇒
                new BasicCredentials {
                  def available: Boolean = false
                  def identifier: String = throw new IllegalStateException("Credentials missing")
                  def verify(secret: String): Boolean = throw new IllegalStateException("Credentials missing")
                }
              case p @ Credentials.Provided(name) ⇒
                new BasicCredentials {
                  def available: Boolean = true
                  def identifier: String = name
                  def verify(secret: String): Boolean = p.verify(secret)
                }
            }

          authenticator.authenticate(javaCreds).toScala.map(_.asScala)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
        }).flatMap { user ⇒
          addExtraction(authenticator.asInstanceOf[RequestVal[Any]], user)
        }

      case OAuth2Authentication(authenticator) ⇒
        authenticateOAuth2Async(authenticator.realm, { creds ⇒
          val javaCreds =
            creds match {
              case Credentials.Missing ⇒
                new OAuth2Credentials {
                  def available: Boolean = false
                  def identifier: String = throw new IllegalStateException("Credentials missing")
                  def verify(secret: String): Boolean = throw new IllegalStateException("Credentials missing")
                }
              case p @ Credentials.Provided(name) ⇒
                new OAuth2Credentials {
                  def available: Boolean = true
                  def identifier: String = name
                  def verify(secret: String): Boolean = p.verify(secret)
                }
            }

          authenticator.authenticate(javaCreds).toScala.map(_.asScala)(sameThreadExecutionContext)
        }).flatMap { user ⇒
          addExtraction(authenticator.asInstanceOf[RequestVal[Any]], user)
        }

      case EncodeResponse(coders) ⇒
        val scalaCoders = coders.map(_._underlyingScalaCoder())
        encodeResponseWith(scalaCoders.head, scalaCoders.tail: _*)

      case DecodeRequest(coders)           ⇒ decodeRequestWith(coders.map(_._underlyingScalaCoder()): _*)
      case Conditional(eTag, lastModified) ⇒ conditional(eTag.map(_.asScala), lastModified.map(_.asScala))
      case h: HostFilter                   ⇒ host(h.filter _)
      case SchemeFilter(schemeName)        ⇒ scheme(schemeName)

      case HandleExceptions(handler) ⇒
        val pf: akka.http.scaladsl.server.ExceptionHandler = akka.http.scaladsl.server.ExceptionHandler {
          case e: RuntimeException ⇒ apply(handler.handle(e))
        }
        handleExceptions(pf)

      case HandleRejections(handler)        ⇒ handleRejections(new RejectionHandlerWrapper(handler))
      case Validated(isValid, errorMsg)     ⇒ validate(isValid, errorMsg)
      case RangeSupport()                   ⇒ withRangeSupport
      case SetCookie(cookie)                ⇒ setCookie(cookie.asScala)
      case DeleteCookie(name, domain, path) ⇒ deleteCookie(HttpCookie(name, domain = domain, path = path, value = "deleted"))
    }

    route match {
      case route: DirectiveRoute                                 ⇒ directiveFor(route).apply(fromAlternatives(route.children))
      case GetFromResource(path, contentType, classLoader)       ⇒ getFromResource(path, contentType.asScala, classLoader)
      case GetFromResourceDirectory(path, classLoader, resolver) ⇒ getFromResourceDirectory(path, classLoader)(scalaResolver(resolver))
      case GetFromFile(file, contentType)                        ⇒ getFromFile(file, contentType.asScala)
      case GetFromDirectory(directory, true, resolver) ⇒
        extractExecutionContext { implicit ec ⇒
          getFromBrowseableDirectory(directory.getPath)(DirectoryRenderer.defaultDirectoryRenderer, scalaResolver(resolver))
        }
      case FileAndResourceRouteWithDefaultResolver(constructor) ⇒
        RouteImplementation(constructor(new directives.ContentTypeResolver {
          def resolve(fileName: String): ContentType = ContentTypeResolver.Default(fileName)
        }))

      case HandleWebSocketMessages(handler) ⇒ handleWebSocketMessages(JavaMapping.toScala(handler))
      case Redirect(uri, code)              ⇒ redirect(uri.asScala, code.asScala.asInstanceOf[Redirection]) // guarded by require in Redirect

      case dyn: DynamicDirectiveRoute1[t1Type] ⇒
        def runToRoute(t1: t1Type): ScalaRoute =
          apply(dyn.createDirective(t1).route(dyn.innerRoute, dyn.moreInnerRoutes: _*))

        requestValToDirective(dyn.value1)(runToRoute)

      case dyn: DynamicDirectiveRoute2[t1Type, t2Type] ⇒
        def runToRoute(t1: t1Type, t2: t2Type): ScalaRoute =
          apply(dyn.createDirective(t1, t2).route(dyn.innerRoute, dyn.moreInnerRoutes: _*))

        (requestValToDirective(dyn.value1) & requestValToDirective(dyn.value2))(runToRoute)

      case o: OpaqueRoute ⇒ (ctx ⇒ o.handle(new RequestContextImpl(ctx)).asInstanceOf[RouteResultImpl].underlying)
      case p: Product     ⇒ extractExecutionContext { implicit ec ⇒ complete((500, s"Not implemented: ${p.productPrefix}")) }
    }
  }
  def pathMatcherDirective[T](matchers: immutable.Seq[PathMatcher[_]],
                              directive: PathMatcher1[T] ⇒ Directive1[T] // this type is too specific and only a placeholder for a proper polymorphic function
                              ): Directive0 = {
    // Concatenating PathMatchers is a bit complicated as we don't want to build up a tuple
    // but something which we can later split all the separate values and add them to the
    // ExtractionMap.
    //
    // This is achieved by providing a specialized `Join` instance to use with PathMatcher
    // which provides the desired behavior.

    type ValMap = Tuple1[Map[RequestVal[_], Any]]
    object AddToMapJoin extends Join[ValMap, ValMap] {
      type Out = ValMap
      def apply(prefix: ValMap, suffix: ValMap): AddToMapJoin.Out =
        Tuple1(prefix._1 ++ suffix._1)
    }
    def toScala(matcher: PathMatcher[_]): ScalaPathMatcher[ValMap] =
      matcher.asInstanceOf[PathMatcherImpl[_]].matcher.transform(_.map(v ⇒ Tuple1(Map(matcher -> v._1))))
    def addExtractions(valMap: T): Directive0 = transformExtractionMap(_.addAll(valMap.asInstanceOf[Map[RequestVal[_], Any]]))
    val reduced: ScalaPathMatcher[ValMap] = matchers.map(toScala).reduce(_.~(_)(AddToMapJoin))
    directive(reduced.asInstanceOf[PathMatcher1[T]]).flatMap(addExtractions)
  }

  def fromAlternatives(alternatives: Seq[Route]): ScalaRoute =
    alternatives.map(RouteImplementation.apply).reduce(_ ~ _)

  def addExtraction[T](key: RequestVal[T], value: T): Directive0 =
    transformExtractionMap(_.set(key, value))

  def transformExtractionMap(f: ExtractionMap ⇒ ExtractionMap): Directive0 = {
    @tailrec def updateExtractionMap(headers: immutable.Seq[HttpHeader], prefix: Vector[HttpHeader] = Vector.empty): immutable.Seq[HttpHeader] =
      headers match {
        case (m: ExtractionMap) +: rest ⇒ f(m) +: (prefix ++ rest)
        case other +: rest              ⇒ updateExtractionMap(rest, prefix :+ other)
        case Nil                        ⇒ f(ExtractionMap.Empty) +: prefix
      }
    mapRequest(_.mapHeaders(updateExtractionMap(_)))
  }

  private def scalaResolver(resolver: directives.ContentTypeResolver): ContentTypeResolver =
    ContentTypeResolver(f ⇒ resolver.resolve(f).asScala)

  def requestValToDirective[T](value: RequestVal[T]): Directive1[T] =
    value match {
      case s: StandaloneExtractionImpl[_] ⇒ s.directive
      case v: RequestVal[_]               ⇒ extract(ctx ⇒ v.get(new RequestContextImpl(ctx)))
    }
}
