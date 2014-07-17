package akka.http.routing
package directives

import akka.http.model._
import java.lang.IllegalStateException
import akka.shapeless._
import ops.hlist._
import akka.http.unmarshalling.{ FromStringOptionDeserializer ⇒ FSOD, Deserializer }

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

trait AnyParamDefMagnet2[T] {
  type Out
  def apply(value: T): Out
}

object AnyParamDefMagnet2 {
  import FieldDefMagnet2.FieldDefMagnetAux
  import ParamDefMagnet2.ParamDefMagnetAux

  implicit def forHList[T, L <: HList](implicit hla: Generic.Aux[T, L], f: LeftFolder[L, Directive0, MapReduce.type]) =
    new AnyParamDefMagnet2[T] {
      type Out = f.Out
      def apply(value: T) = {
        hla.to(value).foldLeft(BasicDirectives.noop)(MapReduce)
      }
    }

  object MapReduce extends Poly2 {
    implicit def from[T, LA <: HList, LB <: HList, Out <: HList](implicit fdma: FieldDefMagnetAux[T, Directive[LB]],
                                                                 pdma: ParamDefMagnetAux[T, Directive[LB]],
                                                                 ev: Prepend.Aux[LA, LB, Out]) = {
      // see https://groups.google.com/forum/?fromgroups=#!topic/spray-user/HGEEdVajpUw
      def fdmaWrapper(t: T): Directive[LB] = fdma(t).hflatMap {
        case None :: HNil ⇒ pdma(t)
        case x            ⇒ BasicDirectives.hprovide(x)
      }

      at[Directive[LA], T] { (a, t) ⇒ a & (fdmaWrapper(t) | pdma(t)) }
    }
  }
}

trait FieldDefMagnet2[T] {
  type Out
  def apply(value: T): Out
}

trait LowLevelFieldDefMagnet2 {
  type FieldDefMagnetAux[A, B] = FieldDefMagnet2[A] { type Out = B }
  def FieldDefMagnetAux[A, B](f: A ⇒ B) = new FieldDefMagnet2[A] { type Out = B; def apply(value: A) = f(value) }

  /************ HList/tuple support ******************/

  implicit def forHList[T, L <: HList](implicit hla: Generic.Aux[T, L], f: LeftFolder[L, Directive0, MapReduce.type]) =
    FieldDefMagnetAux[T, f.Out](t ⇒ hla.to(t).foldLeft(BasicDirectives.noop)(MapReduce))

  object MapReduce extends Poly2 {
    implicit def from[T, LA <: HList, LB <: HList, Out <: HList](implicit fdma: FieldDefMagnetAux[T, Directive[LB]], ev: Prepend.Aux[LA, LB, Out]) =
      at[Directive[LA], T] { (a, t) ⇒ a & fdma(t) }
  }
}

object FieldDefMagnet2 extends ToNameReceptaclePimps with LowLevelFieldDefMagnet2 {
  import akka.http.unmarshalling.{ FromRequestUnmarshaller ⇒ UM, FormFieldConverter ⇒ FFC, FromBodyPartOptionUnmarshaller ⇒ FBPOU, _ }
  import BasicDirectives._
  import RouteDirectives._

  /************ "regular" field extraction ******************/

  def extractField[A, B](f: A ⇒ Directive1[B]) = FieldDefMagnetAux[A, Directive1[B]](f)

  private def filter[A, B](nr: NameReceptacle[A])(implicit ev1: UM[HttpForm], ev2: FFC[B]): Directive1[B] = FIXME
  /*extract(_.request.as[HttpForm].right.flatMap(_.field(nr.name).as[B])).flatMap {
      case Right(value)                       ⇒ provide(value)
      case Left(ContentExpected)              ⇒ reject(MissingFormFieldRejection(nr.name))
      case Left(MalformedContent(msg, cause)) ⇒ reject(MalformedFormFieldRejection(nr.name, msg, cause))
      case Left(UnsupportedContentType(msg))  ⇒ reject(UnsupportedRequestContentTypeRejection(msg))
    }*/
  implicit def forString(implicit ev1: UM[HttpForm], ev2: FFC[String]) =
    extractField[String, String](string ⇒ filter(string))
  implicit def forSymbol(implicit ev1: UM[HttpForm], ev2: FFC[String]) =
    extractField[Symbol, String](symbol ⇒ filter(symbol))
  implicit def forNDesR[T](implicit ev1: UM[HttpForm], ev2: FBPOU[T] = null) =
    extractField[NameDeserializerReceptacle[T], T] { ndr ⇒
      FIXME //filter(NameReceptacle[T](ndr.name))(ev1, FFC.fromFSOD(ndr.deserializer))
    }
  implicit def forNDefR[T](implicit ev1: UM[HttpForm], ev2: FFC[T]) =
    extractField[NameDefaultReceptacle[T], T] { ndr ⇒
      filter(NameReceptacle[T](ndr.name))(ev1, ev2.withDefault(ndr.default))
    }
  implicit def forNDesDefR[T](implicit ev1: UM[HttpForm], ev2: FBPOU[T] = null) =
    extractField[NameDeserializerDefaultReceptacle[T], T] { ndr ⇒
      FIXME
      //filter(NameReceptacle[T](ndr.name))(ev1, FFC.fromFSOD(ndr.deserializer.withDefaultValue(ndr.default)))
    }
  implicit def forNR[T](implicit ev1: UM[HttpForm], ev2: FFC[T]) =
    extractField[NameReceptacle[T], T](nr ⇒ filter(nr))

  /************ required formField support ******************/

  private def requiredFilter[A](paramName: String, requiredValue: A)(implicit ev1: UM[HttpForm], ffc: FFC[A]): Directive1[A] =
    FIXME
  /*filter(NameReceptacle[A](paramName))
      .require(_ == requiredValue, MalformedFormFieldRejection(paramName, s"Form field '$paramName' had wrong value."))*/
  implicit def forRVR[T](implicit ev1: UM[HttpForm], ev2: FFC[T]) =
    FieldDefMagnetAux[RequiredValueReceptacle[T], Directive0] { rvr ⇒
      FIXME //requiredFilter(rvr.name, rvr.requiredValue)
    }
  implicit def forRVDR[T](implicit ev1: UM[HttpForm], ev2: FBPOU[T] = null) =
    FieldDefMagnetAux[RequiredValueDeserializerReceptacle[T], Directive0] { rvr ⇒
      FIXME //requiredFilter(rvr.name, rvr.requiredValue)(ev1, FFC.fromFSOD(rvr.deserializer))
    }
}

trait ParamDefMagnet2[T] {
  type Out
  def apply(value: T): Out
}

trait LowLevelParamDefMagnet2 {
  type ParamDefMagnetAux[A, B] = ParamDefMagnet2[A] { type Out = B }
  def ParamDefMagnetAux[A, B](f: A ⇒ B) = new ParamDefMagnet2[A] { type Out = B; def apply(value: A) = f(value) }

  /************ HList/tuple support ******************/
  implicit def forHList[T, L <: HList](implicit hla: Generic.Aux[T, L], f: LeftFolder[L, Directive0, MapReduce.type]) =
    ParamDefMagnetAux[T, f.Out](t ⇒ hla.to(t).foldLeft(BasicDirectives.noop)(MapReduce))

  object MapReduce extends Poly2 {
    implicit def from[T, LA <: HList, LB <: HList, Out <: HList](implicit pdma: ParamDefMagnetAux[T, Directive[LB]], ev: Prepend.Aux[LA, LB, Out]) =
      at[Directive[LA], T] { (a, t) ⇒ a & pdma(t) }
  }
}

object ParamDefMagnet2 extends LowLevelParamDefMagnet2 {
  import akka.http.unmarshalling.{ FromStringOptionDeserializer ⇒ FSOD, _ }
  import BasicDirectives._
  import RouteDirectives._

  /************ "regular" parameter extraction ******************/

  private def extractParameter[A, B](f: A ⇒ Directive1[B]) = ParamDefMagnetAux[A, Directive1[B]](f)
  private def filter[T](paramName: String, fsod: FSOD[T])(implicit ec: ExecutionContext): Directive1[T] =
    extract(ctx ⇒ fsod(ctx.request.uri.query.get(paramName))).afterCompletion.flatMap {
      case Success(x)                              ⇒ provide(x)
      case Failure(ContentExpected)                ⇒ reject(MissingQueryParamRejection(paramName))
      case Failure(MalformedContent(error, cause)) ⇒ reject(MalformedQueryParamRejection(paramName, error, cause))
      case Failure(x: UnsupportedContentType)      ⇒ throw new IllegalStateException(x.toString)
    }
  implicit def forString(implicit fsod: FSOD[String], ec: ExecutionContext) = extractParameter[String, String] { string ⇒
    filter(string, fsod)
  }
  implicit def forSymbol(implicit fsod: FSOD[String], ec: ExecutionContext) = extractParameter[Symbol, String] { symbol ⇒
    filter(symbol.name, fsod)
  }
  implicit def forNDesR[T](implicit ec: ExecutionContext) = extractParameter[NameDeserializerReceptacle[T], T] { nr ⇒
    filter(nr.name, nr.deserializer)
  }
  implicit def forNDefR[T](implicit fsod: FSOD[T], ec: ExecutionContext) = extractParameter[NameDefaultReceptacle[T], T] { nr ⇒
    filter(nr.name, fsod.withDefaultValue(nr.default))
  }
  implicit def forNDesDefR[T](implicit ec: ExecutionContext) = extractParameter[NameDeserializerDefaultReceptacle[T], T] { nr ⇒
    filter(nr.name, nr.deserializer.withDefaultValue(nr.default))
  }
  implicit def forNR[T](implicit fsod: FSOD[T], ec: ExecutionContext) = extractParameter[NameReceptacle[T], T] { nr ⇒
    filter(nr.name, fsod)
  }

  /************ required parameter support ******************/

  private def requiredFilter(paramName: String, fsod: FSOD[_], requiredValue: Any)(implicit ec: ExecutionContext): Directive0 =
    extract(ctx ⇒ fsod(ctx.request.uri.query.get(paramName))).afterCompletion.flatMap {
      case Success(value) if value == requiredValue ⇒ pass
      case _                                        ⇒ reject
    }
  implicit def forRVR[T](implicit fsod: FSOD[T], ec: ExecutionContext) = ParamDefMagnetAux[RequiredValueReceptacle[T], Directive0] { rvr ⇒
    requiredFilter(rvr.name, fsod, rvr.requiredValue)
  }
  implicit def forRVDR[T](implicit ec: ExecutionContext) = ParamDefMagnetAux[RequiredValueDeserializerReceptacle[T], Directive0] { rvr ⇒
    requiredFilter(rvr.name, rvr.deserializer, rvr.requiredValue)
  }
}
