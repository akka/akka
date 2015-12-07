/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.unmarshalling

import scala.collection.immutable

trait PredefinedFromStringUnmarshallers {

  implicit val byteFromStringUnmarshaller: Unmarshaller[String, Byte] =
    Unmarshaller.strict(byteFromString)

  implicit val shortFromStringUnmarshaller: Unmarshaller[String, Short] =
    Unmarshaller.strict(shortFromString)

  implicit val intFromStringUnmarshaller: Unmarshaller[String, Int] =
    Unmarshaller.strict(intFromString)

  implicit val longFromStringUnmarshaller: Unmarshaller[String, Long] =
    Unmarshaller.strict(longFromString)

  val HexByte: Unmarshaller[String, Byte] =
    Unmarshaller.strict[String, Byte] { string ⇒
      try java.lang.Byte.parseByte(string, 16)
      catch numberFormatError(string, "8-bit hexadecimal integer")
    }

  val HexShort: Unmarshaller[String, Short] =
    Unmarshaller.strict[String, Short] { string ⇒
      try java.lang.Short.parseShort(string, 16)
      catch numberFormatError(string, "16-bit hexadecimal integer")
    }

  val HexInt: Unmarshaller[String, Int] =
    Unmarshaller.strict[String, Int] { string ⇒
      try java.lang.Integer.parseInt(string, 16)
      catch numberFormatError(string, "32-bit hexadecimal integer")
    }

  val HexLong: Unmarshaller[String, Long] =
    Unmarshaller.strict[String, Long] { string ⇒
      try java.lang.Long.parseLong(string, 16)
      catch numberFormatError(string, "64-bit hexadecimal integer")
    }

  implicit val floatFromStringUnmarshaller: Unmarshaller[String, Float] =
    Unmarshaller.strict[String, Float] { string ⇒
      try string.toFloat
      catch numberFormatError(string, "32-bit floating point")
    }

  implicit val doubleFromStringUnmarshaller: Unmarshaller[String, Double] =
    Unmarshaller.strict[String, Double] { string ⇒
      try string.toDouble
      catch numberFormatError(string, "64-bit floating point")
    }

  implicit val booleanFromStringUnmarshaller: Unmarshaller[String, Boolean] =
    Unmarshaller.strict[String, Boolean] { string ⇒
      string.toLowerCase match {
        case "true" | "yes" | "on"  ⇒ true
        case "false" | "no" | "off" ⇒ false
        case ""                     ⇒ throw Unmarshaller.NoContentException
        case x                      ⇒ throw new IllegalArgumentException(s"'$x' is not a valid Boolean value")
      }
    }

  val CsvStringSeq: Unmarshaller[String, immutable.Seq[String]] =
    Unmarshaller.strict[String, immutable.Seq[String]] { string ⇒
      string.split(",").toList
    }

  val CsvByteSeq: Unmarshaller[String, immutable.Seq[Byte]] =
    CsvStringSeq.map(_.map(byteFromString))

  val CsvShortSeq: Unmarshaller[String, immutable.Seq[Short]] =
    CsvStringSeq.map(_.map(shortFromString))

  val CsvIntSeq: Unmarshaller[String, immutable.Seq[Int]] =
    CsvStringSeq.map(_.map(intFromString))

  val CsvLongSeq: Unmarshaller[String, immutable.Seq[Long]] =
    CsvStringSeq.map(_.map(longFromString))

  private def byteFromString(string: String) =
    try string.toByte
    catch numberFormatError(string, "8-bit signed integer")

  private def shortFromString(string: String) =
    try string.toShort
    catch numberFormatError(string, "16-bit signed integer")

  private def intFromString(string: String) =
    try string.toInt
    catch numberFormatError(string, "32-bit signed integer")

  private def longFromString(string: String) =
    try string.toLong
    catch numberFormatError(string, "64-bit signed integer")

  private def numberFormatError(value: String, target: String): PartialFunction[Throwable, Nothing] = {
    case e: NumberFormatException ⇒
      throw if (value.isEmpty) Unmarshaller.NoContentException else new IllegalArgumentException(s"'$value' is not a valid $target value", e)
  }
}

object PredefinedFromStringUnmarshallers extends PredefinedFromStringUnmarshallers
