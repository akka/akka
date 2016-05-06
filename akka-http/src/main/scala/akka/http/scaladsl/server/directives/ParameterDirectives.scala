/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.javadsl.server.directives.CorrespondsTo

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import akka.http.scaladsl.common._
import akka.http.impl.util._

/**
 * @groupname param Parameter directives
 * @groupprio param 150
 */
trait ParameterDirectives extends ToNameReceptacleEnhancements {
  import ParameterDirectives._

  /**
   * Extracts the request's query parameters as a `Map[String, String]`.
   *
   * @group param
   */
  def parameterMap: Directive1[Map[String, String]] = _parameterMap

  /**
   * Extracts the request's query parameters as a `Map[String, List[String]]`.
   *
   * @group param
   */
  def parameterMultiMap: Directive1[Map[String, List[String]]] = _parameterMultiMap

  /**
   * Extracts the request's query parameters as a `Seq[(String, String)]`.
   *
   * @group param
   */
  def parameterSeq: Directive1[immutable.Seq[(String, String)]] = _parameterSeq

  /**
   * Extracts a query parameter value from the request.
   * Rejects the request if the defined query parameter matcher(s) don't match.
   *
   * Due to a bug in Scala 2.10, invocations of this method sometimes fail to compile with an
   * "too many arguments for method parameter" or "type mismatch" error.
   *
   * As a workaround add an `import ParameterDirectives.ParamMagnet` or use Scala 2.11.x.
   *
   * @group param
   */
  def parameter(pdm: ParamMagnet): pdm.Out = pdm()

  /**
   * Extracts a number of query parameter values from the request.
   * Rejects the request if the defined query parameter matcher(s) don't match.
   *
   * Due to a bug in Scala 2.10, invocations of this method sometimes fail to compile with an
   * "too many arguments for method parameters" or "type mismatch" error.
   *
   * As a workaround add an `import ParameterDirectives.ParamMagnet` or use Scala 2.11.x.
   *
   * @group param
   */
  def parameters(pdm: ParamMagnet): pdm.Out = pdm()

}

object ParameterDirectives extends ParameterDirectives {
  import BasicDirectives._

  private val _parameterMap: Directive1[Map[String, String]] =
    extract(_.request.uri.query().toMap)

  private val _parameterMultiMap: Directive1[Map[String, List[String]]] =
    extract(_.request.uri.query().toMultiMap)

  private val _parameterSeq: Directive1[immutable.Seq[(String, String)]] =
    extract(_.request.uri.query().toSeq)

  sealed trait ParamMagnet {
    type Out
    def apply(): Out
  }
  object ParamMagnet {
    implicit def apply[T](value: T)(implicit pdef: ParamDef[T]): ParamMagnet { type Out = pdef.Out } =
      new ParamMagnet {
        type Out = pdef.Out
        def apply() = pdef(value)
      }
  }

  type ParamDefAux[T, U] = ParamDef[T] { type Out = U }
  sealed trait ParamDef[T] {
    type Out
    def apply(value: T): Out
  }
  object ParamDef {
    def paramDef[A, B](f: A ⇒ B): ParamDefAux[A, B] =
      new ParamDef[A] {
        type Out = B
        def apply(value: A) = f(value)
      }

    import akka.http.scaladsl.unmarshalling.{ FromStringUnmarshaller ⇒ FSU, _ }
    import BasicDirectives._
    import RouteDirectives._
    import FutureDirectives._
    type FSOU[T] = Unmarshaller[Option[String], T]

    private def extractParameter[A, B](f: A ⇒ Directive1[B]): ParamDefAux[A, Directive1[B]] = paramDef(f)
    private def handleParamResult[T](paramName: String, result: Future[T])(implicit ec: ExecutionContext): Directive1[T] =
      onComplete(result).flatMap {
        case Success(x)                               ⇒ provide(x)
        case Failure(Unmarshaller.NoContentException) ⇒ reject(MissingQueryParamRejection(paramName))
        case Failure(x)                               ⇒ reject(MalformedQueryParamRejection(paramName, x.getMessage.nullAsEmpty, Option(x.getCause)))
      }

    //////////////////// "regular" parameter extraction //////////////////////

    private def filter[T](paramName: String, fsou: FSOU[T]): Directive1[T] =
      extractRequestContext flatMap { ctx ⇒
        import ctx.executionContext
        import ctx.materializer
        handleParamResult(paramName, fsou(ctx.request.uri.query().get(paramName)))
      }
    implicit def forString(implicit fsu: FSU[String]): ParamDefAux[String, Directive1[String]] =
      extractParameter[String, String] { string ⇒ filter(string, fsu) }
    implicit def forSymbol(implicit fsu: FSU[String]): ParamDefAux[Symbol, Directive1[String]] =
      extractParameter[Symbol, String] { symbol ⇒ filter(symbol.name, fsu) }
    implicit def forNR[T](implicit fsu: FSU[T]): ParamDefAux[NameReceptacle[T], Directive1[T]] =
      extractParameter[NameReceptacle[T], T] { nr ⇒ filter(nr.name, fsu) }
    implicit def forNUR[T]: ParamDefAux[NameUnmarshallerReceptacle[T], Directive1[T]] =
      extractParameter[NameUnmarshallerReceptacle[T], T] { nr ⇒ filter(nr.name, nr.um) }
    implicit def forNOR[T](implicit fsou: FSOU[T]): ParamDefAux[NameOptionReceptacle[T], Directive1[Option[T]]] =
      extractParameter[NameOptionReceptacle[T], Option[T]] { nr ⇒ filter[Option[T]](nr.name, fsou) }
    implicit def forNDR[T](implicit fsou: FSOU[T]): ParamDefAux[NameDefaultReceptacle[T], Directive1[T]] =
      extractParameter[NameDefaultReceptacle[T], T] { nr ⇒ filter[T](nr.name, fsou withDefaultValue nr.default) }
    implicit def forNOUR[T]: ParamDefAux[NameOptionUnmarshallerReceptacle[T], Directive1[Option[T]]] =
      extractParameter[NameOptionUnmarshallerReceptacle[T], Option[T]] { nr ⇒ filter(nr.name, nr.um: FSOU[T]) }
    implicit def forNDUR[T]: ParamDefAux[NameDefaultUnmarshallerReceptacle[T], Directive1[T]] =
      extractParameter[NameDefaultUnmarshallerReceptacle[T], T] { nr ⇒ filter[T](nr.name, (nr.um: FSOU[T]) withDefaultValue nr.default) }

    //////////////////// required parameter support ////////////////////

    private def requiredFilter[T](paramName: String, fsou: FSOU[T], requiredValue: Any): Directive0 =
      extractRequestContext flatMap { ctx ⇒
        import ctx.executionContext
        import ctx.materializer
        onComplete(fsou(ctx.request.uri.query().get(paramName))) flatMap {
          case Success(value) if value == requiredValue ⇒ pass
          case _                                        ⇒ reject
        }
      }
    implicit def forRVR[T](implicit fsu: FSU[T]): ParamDefAux[RequiredValueReceptacle[T], Directive0] =
      paramDef[RequiredValueReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, fsu, rvr.requiredValue) }
    implicit def forRVDR[T]: ParamDefAux[RequiredValueUnmarshallerReceptacle[T], Directive0] =
      paramDef[RequiredValueUnmarshallerReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, rvr.um, rvr.requiredValue) }

    //////////////////// repeated parameter support ////////////////////

    private def repeatedFilter[T](paramName: String, fsu: FSU[T]): Directive1[Iterable[T]] =
      extractRequestContext flatMap { ctx ⇒
        import ctx.executionContext
        import ctx.materializer
        handleParamResult(paramName, Future.sequence(ctx.request.uri.query().getAll(paramName).map(fsu.apply)))
      }
    implicit def forRepVR[T](implicit fsu: FSU[T]): ParamDefAux[RepeatedValueReceptacle[T], Directive1[Iterable[T]]] =
      extractParameter[RepeatedValueReceptacle[T], Iterable[T]] { rvr ⇒ repeatedFilter(rvr.name, fsu) }
    implicit def forRepVDR[T]: ParamDefAux[RepeatedValueUnmarshallerReceptacle[T], Directive1[Iterable[T]]] =
      extractParameter[RepeatedValueUnmarshallerReceptacle[T], Iterable[T]] { rvr ⇒ repeatedFilter(rvr.name, rvr.um) }

    //////////////////// tuple support ////////////////////

    import akka.http.scaladsl.server.util.TupleOps._
    import akka.http.scaladsl.server.util.BinaryPolyFunc

    implicit def forTuple[T](implicit fold: FoldLeft[Directive0, T, ConvertParamDefAndConcatenate.type]): ParamDefAux[T, fold.Out] =
      paramDef[T, fold.Out](fold(BasicDirectives.pass, _))

    object ConvertParamDefAndConcatenate extends BinaryPolyFunc {
      implicit def from[P, TA, TB](implicit pdef: ParamDef[P] { type Out = Directive[TB] }, ev: Join[TA, TB]): BinaryPolyFunc.Case[Directive[TA], P, ConvertParamDefAndConcatenate.type] { type Out = Directive[ev.Out] } =
        at[Directive[TA], P] { (a, t) ⇒ a & pdef(t) }
    }
  }
}
