/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }
import akka.http.util._

trait ParameterDirectives extends ToNameReceptacleEnhancements {

  /**
   * Extracts the requests query parameters as a Map[String, String].
   */
  def parameterMap: Directive1[Map[String, String]] = ParameterDirectives._parameterMap

  /**
   * Extracts the requests query parameters as a Map[String, List[String]].
   */
  def parameterMultiMap: Directive1[Map[String, List[String]]] = ParameterDirectives._parameterMultiMap

  /**
   * Extracts the requests query parameters as a Seq[(String, String)].
   */
  def parameterSeq: Directive1[immutable.Seq[(String, String)]] = ParameterDirectives._parameterSeq

  /**
   * Rejects the request if the query parameter matcher(s) defined by the definition(s) don't match.
   * Otherwise the parameter value(s) are extracted and passed to the inner route.
   */
  def parameter(pdm: ParamMagnet): pdm.Out = pdm()

  /**
   * Rejects the request if the query parameter matcher(s) defined by the definition(s) don't match.
   * Otherwise the parameter value(s) are extracted and passed to the inner route.
   */
  def parameters(pdm: ParamMagnet): pdm.Out = pdm()

}

object ParameterDirectives extends ParameterDirectives {
  import BasicDirectives._

  private val _parameterMap: Directive1[Map[String, String]] =
    extract(_.request.uri.query.toMap)

  private val _parameterMultiMap: Directive1[Map[String, List[String]]] =
    extract(_.request.uri.query.toMultiMap)

  private val _parameterSeq: Directive1[immutable.Seq[(String, String)]] =
    extract(_.request.uri.query.toSeq)
}

trait ParamMagnet {
  type Out
  def apply(): Out
}
object ParamMagnet {
  implicit def apply[T](value: T)(implicit pdef: ParamDef[T]) =
    new ParamMagnet {
      type Out = pdef.Out
      def apply() = pdef(value)
    }
}

trait ParamDef[T] {
  type Out
  def apply(value: T): Out
}

object ParamDef {
  def paramDef[A, B](f: A ⇒ B) =
    new ParamDef[A] {
      type Out = B
      def apply(value: A) = f(value)
    }

  import akka.http.unmarshalling.{ FromStringOptionUnmarshaller ⇒ FSOU, _ }
  import BasicDirectives._
  import RouteDirectives._
  import FutureDirectives._
  import UnmarshallingError._

  /************ "regular" parameter extraction ******************/

  private def extractParameter[A, B](f: A ⇒ Directive1[B]) = paramDef(f)
  private def filter[T](paramName: String, fsou: ExecutionContext ⇒ FSOU[T]): Directive1[T] =
    extract(ctx ⇒ fsou(ctx.executionContext)(ctx.request.uri.query get paramName)).flatMap {
      onComplete(_).flatMap {
        case Success(x)               ⇒ provide(x)
        case Failure(ContentExpected) ⇒ reject(MissingQueryParamRejection(paramName))
        case Failure(x)               ⇒ reject(MalformedQueryParamRejection(paramName, x.getMessage.nullAsEmpty, Option(x.getCause)))
      }
    }
  implicit def forString(implicit fsou: FSOU[String]) =
    extractParameter[String, String] { string ⇒ filter(string, _ ⇒ fsou) }
  implicit def forSymbol(implicit fsou: FSOU[String]) =
    extractParameter[Symbol, String] { symbol ⇒ filter(symbol.name, _ ⇒ fsou) }
  implicit def forNUmR[T] =
    extractParameter[NameUnmarshallerReceptacle[T], T] { nr ⇒ filter(nr.name, nr.um) }
  implicit def forNDefR[T](implicit fsou: FSOU[T]) =
    extractParameter[NameDefaultReceptacle[T], T] { nr ⇒ filter(nr.name, ec ⇒ fsou.withDefaultValue(nr.default)(ec)) }
  implicit def forNUmDefR[T] =
    extractParameter[NameUnmarshallerDefaultReceptacle[T], T] { nr ⇒ filter(nr.name, ec ⇒ nr.um(ec).withDefaultValue(nr.default)(ec)) }
  implicit def forNR[T](implicit fsou: FSOU[T]) =
    extractParameter[NameReceptacle[T], T] { nr ⇒ filter(nr.name, _ ⇒ fsou) }

  /************ required parameter support ******************/

  private def requiredFilter(paramName: String, fsou: ExecutionContext ⇒ FSOU[_], requiredValue: Any): Directive0 =
    extract(ctx ⇒ fsou(ctx.executionContext)(ctx.request.uri.query.get(paramName))).flatMap { deserialisationFuture ⇒
      onComplete(deserialisationFuture).flatMap {
        case Success(value) if value == requiredValue ⇒ pass
        case _                                        ⇒ reject
      }
    }
  implicit def forRVR[T](implicit fsou: FSOU[T]) =
    paramDef[RequiredValueReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, _ ⇒ fsou, rvr.requiredValue) }
  implicit def forRVDR[T] =
    paramDef[RequiredValueUnmarshallerReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, rvr.um, rvr.requiredValue) }

  /************ tuple support ******************/

  import akka.http.server.util.TupleOps._
  import akka.http.server.util.BinaryPolyFunc

  implicit def forTuple[T](implicit fold: FoldLeft[Directive0, T, ConvertParamDefAndConcatenate.type]) =
    paramDef[T, fold.Out](fold(BasicDirectives.pass, _))

  object ConvertParamDefAndConcatenate extends BinaryPolyFunc {
    implicit def from[P, TA, TB](implicit pdef: ParamDef[P] { type Out = Directive[TB] }, ev: Join[TA, TB]) =
      at[Directive[TA], P] { (a, t) ⇒ a & pdef(t) }
  }
}
