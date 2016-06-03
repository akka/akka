/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.unmarshalling

import scala.collection.immutable
import akka.http.scaladsl.util.FastFuture

trait PredefinedFromStringUnmarshallers {

  implicit val byteFromStringUnmarshaller: Unmarshaller[String, Byte] =
    numberUnmarshaller(_.toByte, "8-bit signed integer")

  implicit val shortFromStringUnmarshaller: Unmarshaller[String, Short] =
    numberUnmarshaller(_.toShort, "16-bit signed integer")

  implicit val intFromStringUnmarshaller: Unmarshaller[String, Int] =
    numberUnmarshaller(_.toInt, "32-bit signed integer")

  implicit val longFromStringUnmarshaller: Unmarshaller[String, Long] =
    numberUnmarshaller(_.toLong, "64-bit signed integer")

  implicit val floatFromStringUnmarshaller: Unmarshaller[String, Float] =
    numberUnmarshaller(_.toFloat, "32-bit floating point")

  implicit val doubleFromStringUnmarshaller: Unmarshaller[String, Double] =
    numberUnmarshaller(_.toDouble, "64-bit floating point")

  implicit val booleanFromStringUnmarshaller: Unmarshaller[String, Boolean] =
    Unmarshaller.strict[String, Boolean] { string ⇒
      string.toLowerCase match {
        case "true" | "yes" | "on" | "1"  ⇒ true
        case "false" | "no" | "off" | "0" ⇒ false
        case ""                           ⇒ throw Unmarshaller.NoContentException
        case x                            ⇒ throw new IllegalArgumentException(s"'$x' is not a valid Boolean value")
      }
    }

  implicit def CsvSeq[T](implicit unmarshaller: Unmarshaller[String, T]): Unmarshaller[String, immutable.Seq[T]] =
    Unmarshaller.strict[String, immutable.Seq[String]] { string ⇒
      string.split(",").toList
    } flatMap { implicit ec ⇒ implicit mat ⇒ strings ⇒
      FastFuture.sequence(strings.map(unmarshaller(_)))
    }

  val HexByte: Unmarshaller[String, Byte] =
    numberUnmarshaller(java.lang.Byte.parseByte(_, 16), "8-bit hexadecimal integer")

  val HexShort: Unmarshaller[String, Short] =
    numberUnmarshaller(java.lang.Short.parseShort(_, 16), "16-bit hexadecimal integer")

  val HexInt: Unmarshaller[String, Int] =
    numberUnmarshaller(java.lang.Integer.parseInt(_, 16), "32-bit hexadecimal integer")

  val HexLong: Unmarshaller[String, Long] =
    numberUnmarshaller(java.lang.Long.parseLong(_, 16), "64-bit hexadecimal integer")

  private def numberUnmarshaller[T](f: String ⇒ T, target: String): Unmarshaller[String, T] =
    Unmarshaller.strict[String, T] { string ⇒
      try f(string)
      catch numberFormatError(string, target)
    }

  private def numberFormatError(value: String, target: String): PartialFunction[Throwable, Nothing] = {
    case e: NumberFormatException ⇒
      throw if (value.isEmpty) Unmarshaller.NoContentException else new IllegalArgumentException(s"'$value' is not a valid $target value", e)
  }
}

object PredefinedFromStringUnmarshallers extends PredefinedFromStringUnmarshallers
