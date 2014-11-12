/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }
import akka.http.common._
import akka.http.util._

trait ParameterDirectives extends ToNameReceptacleEnhancements {
  import ParameterDirectives._

  /**
   * Extracts the requests query parameters as a Map[String, String].
   */
  def parameterMap: Directive1[Map[String, String]] = _parameterMap

  /**
   * Extracts the requests query parameters as a Map[String, List[String]].
   */
  def parameterMultiMap: Directive1[Map[String, List[String]]] = _parameterMultiMap

  /**
   * Extracts the requests query parameters as a Seq[(String, String)].
   */
  def parameterSeq: Directive1[immutable.Seq[(String, String)]] = _parameterSeq

  /**
   * Rejects the request if the defined query parameter matcher(s) don't match.
   * Otherwise the parameter value(s) are extracted and passed to the inner route.
   */
  def parameter(pdm: ParamMagnet): pdm.Out = pdm()

  /**
   * Rejects the request if the defined query parameter matcher(s) don't match.
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

  sealed trait ParamMagnet {
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

  sealed trait ParamDef[T] {
    type Out
    def apply(value: T): Out
  }
  object ParamDef {
    def paramDef[A, B](f: A ⇒ B) =
      new ParamDef[A] {
        type Out = B
        def apply(value: A) = f(value)
      }

    import akka.http.unmarshalling.{ FromStringUnmarshaller ⇒ FSU, _ }
    import BasicDirectives._
    import RouteDirectives._
    import FutureDirectives._
    type FSOU[T] = Unmarshaller[Option[String], T]

    //////////////////// "regular" parameter extraction //////////////////////

    private def extractParameter[A, B](f: A ⇒ Directive1[B]) = paramDef(f)
    private def filter[T](paramName: String, fsou: FSOU[T]): Directive1[T] =
      extractUri flatMap { uri ⇒
        onComplete(fsou(uri.query get paramName)) flatMap {
          case Success(x)                               ⇒ provide(x)
          case Failure(Unmarshaller.NoContentException) ⇒ reject(MissingQueryParamRejection(paramName))
          case Failure(x)                               ⇒ reject(MalformedQueryParamRejection(paramName, x.getMessage.nullAsEmpty, Option(x.getCause)))
        }
      }
    implicit def forString(implicit fsu: FSU[String]) =
      extractParameter[String, String] { string ⇒ filter(string, fsu) }
    implicit def forSymbol(implicit fsu: FSU[String]) =
      extractParameter[Symbol, String] { symbol ⇒ filter(symbol.name, fsu) }
    implicit def forNR[T](implicit fsu: FSU[T]) =
      extractParameter[NameReceptacle[T], T] { nr ⇒ filter(nr.name, fsu) }
    implicit def forNUR[T] =
      extractParameter[NameUnmarshallerReceptacle[T], T] { nr ⇒ filter(nr.name, nr.um) }
    implicit def forNOR[T](implicit fsou: FSOU[T], ec: ExecutionContext) =
      extractParameter[NameOptionReceptacle[T], Option[T]] { nr ⇒ filter[Option[T]](nr.name, fsou) }
    implicit def forNDR[T](implicit fsou: FSOU[T], ec: ExecutionContext) =
      extractParameter[NameDefaultReceptacle[T], T] { nr ⇒ filter[T](nr.name, fsou withDefaultValue nr.default) }
    implicit def forNOUR[T](implicit ec: ExecutionContext) =
      extractParameter[NameOptionUnmarshallerReceptacle[T], Option[T]] { nr ⇒ filter(nr.name, nr.um: FSOU[T]) }
    implicit def forNDUR[T](implicit ec: ExecutionContext) =
      extractParameter[NameDefaultUnmarshallerReceptacle[T], T] { nr ⇒ filter[T](nr.name, (nr.um: FSOU[T]) withDefaultValue nr.default) }

    //////////////////// required parameter support ////////////////////

    private def requiredFilter[T](paramName: String, fsou: FSOU[T], requiredValue: Any): Directive0 =
      extractUri flatMap { uri ⇒
        onComplete(fsou(uri.query get paramName)) flatMap {
          case Success(value) if value == requiredValue ⇒ pass
          case _                                        ⇒ reject
        }
      }
    implicit def forRVR[T](implicit fsu: FSU[T]) =
      paramDef[RequiredValueReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, fsu, rvr.requiredValue) }
    implicit def forRVDR[T] =
      paramDef[RequiredValueUnmarshallerReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, rvr.um, rvr.requiredValue) }

    //////////////////// tuple support ////////////////////

    import akka.http.server.util.TupleOps._
    import akka.http.server.util.BinaryPolyFunc

    implicit def forTuple[T](implicit fold: FoldLeft[Directive0, T, ConvertParamDefAndConcatenate.type]) =
      paramDef[T, fold.Out](fold(BasicDirectives.pass, _))

    object ConvertParamDefAndConcatenate extends BinaryPolyFunc {
      implicit def from[P, TA, TB](implicit pdef: ParamDef[P] { type Out = Directive[TB] }, ev: Join[TA, TB]) =
        at[Directive[TA], P] { (a, t) ⇒ a & pdef(t) }
    }
  }
}