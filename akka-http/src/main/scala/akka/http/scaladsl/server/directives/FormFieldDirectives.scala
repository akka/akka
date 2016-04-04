/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.impl.util._
import akka.http.scaladsl.common._
import akka.http.scaladsl.server.directives.RouteDirectives._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.util.FastFuture._

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{ Failure, Success }

/**
 * @groupname form Form field directives
 * @groupprio form 90
 */
trait FormFieldDirectives extends ToNameReceptacleEnhancements {
  import FormFieldDirectives._

  /**
   * Extracts HTTP form fields from the request as a ``Map[String, String]``.
   *
   * @group form
   */
  def formFieldMap: Directive1[Map[String, String]] = _formFieldMap

  /**
   * Extracts HTTP form fields from the request as a ``Map[String, List[String]]``.
   *
   * @group form
   */
  def formFieldMultiMap: Directive1[Map[String, List[String]]] = _formFieldMultiMap

  /**
   * Extracts HTTP form fields from the request as a ``Seq[(String, String)]``.
   *
   * @group form
   */
  def formFieldSeq: Directive1[immutable.Seq[(String, String)]] = _formFieldSeq

  /**
   * Extracts an HTTP form field from the request.
   * Rejects the request if the defined form field matcher(s) don't match.
   *
   * @group form
   */
  def formField(pdm: FieldMagnet): pdm.Out = pdm()

  /**
   * Extracts a number of HTTP form field from the request.
   * Rejects the request if the defined form field matcher(s) don't match.
   *
   * @group form
   */
  def formFields(pdm: FieldMagnet): pdm.Out = pdm()

}

object FormFieldDirectives extends FormFieldDirectives {

  private val _formFieldSeq: Directive1[immutable.Seq[(String, String)]] = {
    import BasicDirectives._
    import FutureDirectives._
    import akka.http.scaladsl.unmarshalling._

    extract { ctx ⇒
      import ctx.{ executionContext, materializer }
      Unmarshal(ctx.request.entity).to[StrictForm].fast.flatMap { form ⇒
        val fields = form.fields.collect {
          case (name, field) if name.nonEmpty ⇒
            Unmarshal(field).to[String].map(fieldString ⇒ (name, fieldString))
        }
        Future.sequence(fields)
      }
    }.flatMap { sequenceF ⇒
      onComplete(sequenceF).flatMap {
        case Success(x)                                  ⇒ provide(x)
        case Failure(x: UnsupportedContentTypeException) ⇒ reject(UnsupportedRequestContentTypeRejection(x.supported))
        case Failure(_)                                  ⇒ reject // TODO Use correct rejections
      }
    }
  }

  private val _formFieldMultiMap: Directive1[Map[String, List[String]]] = {
    @tailrec def append(
      map: Map[String, List[String]],
      fields: immutable.Seq[(String, String)]): Map[String, List[String]] = {
      if (fields.isEmpty) {
        map
      } else {
        val (key, value) = fields.head
        append(map.updated(key, value :: map.getOrElse(key, Nil)), fields.tail)
      }
    }

    _formFieldSeq.map {
      case seq ⇒
        append(Map.empty, seq)
    }
  }

  private val _formFieldMap: Directive1[Map[String, String]] = _formFieldSeq.map(_.toMap)

  sealed trait FieldMagnet {
    type Out
    def apply(): Out
  }
  object FieldMagnet {
    implicit def apply[T](value: T)(implicit fdef: FieldDef[T]): FieldMagnet { type Out = fdef.Out } =
      new FieldMagnet {
        type Out = fdef.Out
        def apply() = fdef(value)
      }
  }

  type FieldDefAux[A, B] = FieldDef[A] { type Out = B }
  sealed trait FieldDef[T] {
    type Out
    def apply(value: T): Out
  }
  object FieldDef {
    def fieldDef[A, B](f: A ⇒ B): FieldDefAux[A, B] =
      new FieldDef[A] {
        type Out = B
        def apply(value: A) = f(value)
      }

    import BasicDirectives._
    import FutureDirectives._
    import RouteDirectives._
    import akka.http.scaladsl.unmarshalling.{ FromStrictFormFieldUnmarshaller ⇒ FSFFU, _ }
    type SFU = FromEntityUnmarshaller[StrictForm]
    type FSFFOU[T] = Unmarshaller[Option[StrictForm.Field], T]

    private def extractField[A, B](f: A ⇒ Directive1[B]): FieldDefAux[A, Directive1[B]] = fieldDef(f)
    private def handleFieldResult[T](fieldName: String, result: Future[T]): Directive1[T] = onComplete(result).flatMap {
      case Success(x)                                  ⇒ provide(x)
      case Failure(Unmarshaller.NoContentException)    ⇒ reject(MissingFormFieldRejection(fieldName))
      case Failure(x: UnsupportedContentTypeException) ⇒ reject(UnsupportedRequestContentTypeRejection(x.supported))
      case Failure(x)                                  ⇒ reject(MalformedFormFieldRejection(fieldName, x.getMessage.nullAsEmpty, Option(x.getCause)))
    }

    //////////////////// "regular" formField extraction ////////////////////

    private def fieldOfForm[T](fieldName: String, fu: Unmarshaller[Option[StrictForm.Field], T])(implicit sfu: SFU): RequestContext ⇒ Future[T] = { ctx ⇒
      import ctx.{ executionContext, materializer }
      sfu(ctx.request.entity).fast.flatMap(form ⇒ fu(form field fieldName))
    }
    private def filter[T](fieldName: String, fu: FSFFOU[T])(implicit sfu: SFU): Directive1[T] =
      extract(fieldOfForm(fieldName, fu)).flatMap(r ⇒ handleFieldResult(fieldName, r))
    implicit def forString(implicit sfu: SFU, fu: FSFFU[String]): FieldDefAux[String, Directive1[String]] =
      extractField[String, String] { fieldName ⇒ filter(fieldName, fu) }
    implicit def forSymbol(implicit sfu: SFU, fu: FSFFU[String]): FieldDefAux[Symbol, Directive1[String]] =
      extractField[Symbol, String] { symbol ⇒ filter(symbol.name, fu) }
    implicit def forNR[T](implicit sfu: SFU, fu: FSFFU[T]): FieldDefAux[NameReceptacle[T], Directive1[T]] =
      extractField[NameReceptacle[T], T] { nr ⇒ filter(nr.name, fu) }
    implicit def forNUR[T](implicit sfu: SFU): FieldDefAux[NameUnmarshallerReceptacle[T], Directive1[T]] =
      extractField[NameUnmarshallerReceptacle[T], T] { nr ⇒ filter(nr.name, StrictForm.Field.unmarshallerFromFSU(nr.um)) }
    implicit def forNOR[T](implicit sfu: SFU, fu: FSFFOU[T]): FieldDefAux[NameOptionReceptacle[T], Directive1[Option[T]]] =
      extractField[NameOptionReceptacle[T], Option[T]] { nr ⇒ filter[Option[T]](nr.name, fu) }
    implicit def forNDR[T](implicit sfu: SFU, fu: FSFFOU[T]): FieldDefAux[NameDefaultReceptacle[T], Directive1[T]] =
      extractField[NameDefaultReceptacle[T], T] { nr ⇒ filter(nr.name, fu withDefaultValue nr.default) }
    implicit def forNOUR[T](implicit sfu: SFU): FieldDefAux[NameOptionUnmarshallerReceptacle[T], Directive1[Option[T]]] =
      extractField[NameOptionUnmarshallerReceptacle[T], Option[T]] { nr ⇒ filter[Option[T]](nr.name, StrictForm.Field.unmarshallerFromFSU(nr.um): FSFFOU[T]) }
    implicit def forNDUR[T](implicit sfu: SFU): FieldDefAux[NameDefaultUnmarshallerReceptacle[T], Directive1[T]] =
      extractField[NameDefaultUnmarshallerReceptacle[T], T] { nr ⇒ filter(nr.name, (StrictForm.Field.unmarshallerFromFSU(nr.um): FSFFOU[T]) withDefaultValue nr.default) }

    //////////////////// required formField support ////////////////////

    private def requiredFilter[T](fieldName: String, fu: Unmarshaller[Option[StrictForm.Field], T],
                                  requiredValue: Any)(implicit sfu: SFU): Directive0 =
      extract(fieldOfForm(fieldName, fu)).flatMap {
        onComplete(_).flatMap {
          case Success(value) if value == requiredValue ⇒ pass
          case _                                        ⇒ reject
        }
      }
    implicit def forRVR[T](implicit sfu: SFU, fu: FSFFU[T]): FieldDefAux[RequiredValueReceptacle[T], Directive0] =
      fieldDef[RequiredValueReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, fu, rvr.requiredValue) }
    implicit def forRVDR[T](implicit sfu: SFU): FieldDefAux[RequiredValueUnmarshallerReceptacle[T], Directive0] =
      fieldDef[RequiredValueUnmarshallerReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, StrictForm.Field.unmarshallerFromFSU(rvr.um), rvr.requiredValue) }

    //////////////////// repeated formField support ////////////////////

    private def repeatedFilter[T](fieldName: String, fu: FSFFU[T])(implicit sfu: SFU): Directive1[Iterable[T]] =
      extract { ctx ⇒
        import ctx.{ executionContext, materializer }
        sfu(ctx.request.entity).fast.flatMap(form ⇒ Future.sequence(form.fields.collect { case (`fieldName`, value) ⇒ fu(value) }))
      }.flatMap { result ⇒
        handleFieldResult(fieldName, result)
      }
    implicit def forRepVR[T](implicit sfu: SFU, fu: FSFFU[T]): FieldDefAux[RepeatedValueReceptacle[T], Directive1[Iterable[T]]] =
      extractField[RepeatedValueReceptacle[T], Iterable[T]] { rvr ⇒ repeatedFilter(rvr.name, fu) }
    implicit def forRepVDR[T](implicit sfu: SFU): FieldDefAux[RepeatedValueUnmarshallerReceptacle[T], Directive1[Iterable[T]]] =
      extractField[RepeatedValueUnmarshallerReceptacle[T], Iterable[T]] { rvr ⇒ repeatedFilter(rvr.name, StrictForm.Field.unmarshallerFromFSU(rvr.um)) }

    //////////////////// tuple support ////////////////////

    import akka.http.scaladsl.server.util.BinaryPolyFunc
    import akka.http.scaladsl.server.util.TupleOps._

    implicit def forTuple[T](implicit fold: FoldLeft[Directive0, T, ConvertFieldDefAndConcatenate.type]): FieldDefAux[T, fold.Out] =
      fieldDef[T, fold.Out](fold(pass, _))

    object ConvertFieldDefAndConcatenate extends BinaryPolyFunc {
      implicit def from[P, TA, TB](implicit fdef: FieldDefAux[P, Directive[TB]], ev: Join[TA, TB]): BinaryPolyFunc.Case[Directive[TA], P, ConvertFieldDefAndConcatenate.type] { type Out = Directive[ev.Out] } =
        at[Directive[TA], P] { (a, t) ⇒ a & fdef(t) }
    }
  }
}
