/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.serialization

import sbinary._
import sbinary.Operations._
import sbinary.DefaultProtocol._

// --- PRIMITIVES ---
case class BinaryString(val value: String) extends Serializable.SBinary[BinaryString] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]): BinaryString = BinaryString(fromByteArray[String](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigInt(val value: BigInt) extends Serializable.SBinary[BinaryBigInt] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigInt(fromByteArray[BigInt](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigDecimal(val value: BigDecimal) extends Serializable.SBinary[BinaryBigDecimal] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigDecimal(fromByteArray[BigDecimal](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryLong(val value: Long) extends Serializable.SBinary[BinaryLong] {
  def this() = this(0L)
  def fromBytes(bytes: Array[Byte]) = BinaryLong(fromByteArray[Long](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryInt(val value: Int) extends Serializable.SBinary[BinaryInt] {
  def this() = this(0)
  def fromBytes(bytes: Array[Byte]) = BinaryInt(fromByteArray[Int](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryDouble(val value: Double) extends Serializable.SBinary[BinaryDouble] {
  def this() = this(0.0D)
  def fromBytes(bytes: Array[Byte]) = BinaryDouble(fromByteArray[Double](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryFloat(val value: Float) extends Serializable.SBinary[BinaryFloat] {
  def this() = this(0.0F)
  def fromBytes(bytes: Array[Byte]) = BinaryFloat(fromByteArray[Float](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBoolean(val value: Boolean) extends Serializable.SBinary[BinaryBoolean] {
   def this() = this(true)
  def fromBytes(bytes: Array[Byte]) = BinaryBoolean(fromByteArray[Boolean](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryByte(val value: Byte) extends Serializable.SBinary[BinaryByte] {
  def this() = this(0x00)
  def fromBytes(bytes: Array[Byte]) = BinaryByte(fromByteArray[Byte](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryChar(val value: Char) extends Serializable.SBinary[BinaryChar] {
  def this() = this(' ')
  def fromBytes(bytes: Array[Byte]) = BinaryChar(fromByteArray[Char](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}

// --- ARRAYS ---
case class BinaryStringArray(val value: Array[String]) extends Serializable.SBinary[BinaryStringArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringArray(fromByteArray[Array[String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigIntArray(val value: Array[BigInt]) extends Serializable.SBinary[BinaryBigIntArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigIntArray(fromByteArray[Array[BigInt]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigDecimalArray(val value: Array[BigDecimal]) extends Serializable.SBinary[BinaryBigDecimalArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigDecimalArray(fromByteArray[Array[BigDecimal]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryIntArray(val value: Array[Int]) extends Serializable.SBinary[BinaryIntArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryIntArray(fromByteArray[Array[Int]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryLongArray(val value: Array[Long]) extends Serializable.SBinary[BinaryLongArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryLongArray(fromByteArray[Array[Long]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryDoubleArray(val value: Array[Double]) extends Serializable.SBinary[BinaryDoubleArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryDoubleArray(fromByteArray[Array[Double]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryFloatArray(val value: Array[Float]) extends Serializable.SBinary[BinaryFloatArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryFloatArray(fromByteArray[Array[Float]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBooleanArray(val value: Array[Boolean]) extends Serializable.SBinary[BinaryBooleanArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBooleanArray(fromByteArray[Array[Boolean]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryByteArray(val value: Array[Byte]) extends Serializable.SBinary[BinaryByteArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryByteArray(fromByteArray[Array[Byte]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
/*case class BinaryClassArray(val value: Array[Class[_]]) extends Serializable.SBinary[BinaryClassArray] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryClassArray(fromByteArray[Array[Class[_]]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}*/

// --- LISTS ---
case class BinaryStringList(val value: List[String]) extends Serializable.SBinary[BinaryStringList] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringList(fromByteArray[List[String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigIntList(val value: List[BigInt]) extends Serializable.SBinary[BinaryBigIntList] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigIntList(fromByteArray[List[BigInt]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigDecimalList(val value: List[BigDecimal]) extends Serializable.SBinary[BinaryBigDecimalList] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigDecimalList(fromByteArray[List[BigDecimal]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryLongList(val value: List[Long]) extends Serializable.SBinary[BinaryLongList] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryLongList(fromByteArray[List[Long]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryIntList(val value: List[Int]) extends Serializable.SBinary[BinaryIntList] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryIntList(fromByteArray[List[Int]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryDoubleList(val value: List[Double]) extends Serializable.SBinary[BinaryDoubleList] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryDoubleList(fromByteArray[List[Double]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryFloatList(val value: List[Float]) extends Serializable.SBinary[BinaryFloatList] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryFloatList(fromByteArray[List[Float]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBooleanList(val value: List[Boolean]) extends Serializable.SBinary[BinaryBooleanList] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBooleanList(fromByteArray[List[Boolean]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryClassList(val value: List[Class[_]]) extends Serializable.SBinary[BinaryClassList] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryClassList(fromByteArray[List[Class[_]]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}

// --- TUPLES ---
case class BinaryStringStringTuple(val value: Tuple2[String, String]) extends Serializable.SBinary[BinaryStringStringTuple] {
 def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringStringTuple(fromByteArray[Tuple2[String, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigIntBigIntTuple(val value: Tuple2[BigInt, BigInt]) extends Serializable.SBinary[BinaryBigIntBigIntTuple] {
 def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigIntBigIntTuple(fromByteArray[Tuple2[BigInt, BigInt]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigDecimalBigDecimalTuple(val value: Tuple2[BigDecimal, BigDecimal]) extends Serializable.SBinary[BinaryBigDecimalBigDecimalTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigDecimalBigDecimalTuple(fromByteArray[Tuple2[BigDecimal, BigDecimal]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryLongLongTuple(val value: Tuple2[Long, Long]) extends Serializable.SBinary[BinaryLongLongTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryLongLongTuple(fromByteArray[Tuple2[Long, Long]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryIntIntTuple(val value: Tuple2[Int, Int]) extends Serializable.SBinary[BinaryIntIntTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryIntIntTuple(fromByteArray[Tuple2[Int, Int]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryDoubleDoubleTuple(val value: Tuple2[Double, Double]) extends Serializable.SBinary[BinaryDoubleDoubleTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryDoubleDoubleTuple(fromByteArray[Tuple2[Double, Double]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryFloatFloatTuple(val value: Tuple2[Float, Float]) extends Serializable.SBinary[BinaryFloatFloatTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryFloatFloatTuple(fromByteArray[Tuple2[Float, Float]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBooleanBooleanTuple(val value: Tuple2[Boolean, Boolean]) extends Serializable.SBinary[BinaryBooleanBooleanTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBooleanBooleanTuple(fromByteArray[Tuple2[Boolean, Boolean]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryClassClassTuple(val value: Tuple2[Class[_], Class[_]]) extends Serializable.SBinary[BinaryClassClassTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryClassClassTuple(fromByteArray[Tuple2[Class[_], Class[_]]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryByteArrayByteArrayTuple(val value: Tuple2[Array[Byte], Array[Byte]]) extends Serializable.SBinary[BinaryByteArrayByteArrayTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryByteArrayByteArrayTuple(fromByteArray[Tuple2[Array[Byte], Array[Byte]]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigIntStringTuple(val value: Tuple2[BigInt, String]) extends Serializable.SBinary[BinaryBigIntStringTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigIntStringTuple(fromByteArray[Tuple2[BigInt, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigDecimalStringTuple(val value: Tuple2[BigDecimal, String]) extends Serializable.SBinary[BinaryBigDecimalStringTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigDecimalStringTuple(fromByteArray[Tuple2[BigDecimal, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryLongStringTuple(val value: Tuple2[Long, String]) extends Serializable.SBinary[BinaryLongStringTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryLongStringTuple(fromByteArray[Tuple2[Long, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryIntStringTuple(val value: Tuple2[Int, String]) extends Serializable.SBinary[BinaryIntStringTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryIntStringTuple(fromByteArray[Tuple2[Int, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryDoubleStringTuple(val value: Tuple2[Double, String]) extends Serializable.SBinary[BinaryDoubleStringTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryDoubleStringTuple(fromByteArray[Tuple2[Double, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryFloatStringTuple(val value: Tuple2[Float, String]) extends Serializable.SBinary[BinaryFloatStringTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryFloatStringTuple(fromByteArray[Tuple2[Float, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBooleanStringTuple(val value: Tuple2[Boolean, String]) extends Serializable.SBinary[BinaryBooleanStringTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBooleanStringTuple(fromByteArray[Tuple2[Boolean, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryClassStringTuple(val value: Tuple2[Class[_], String]) extends Serializable.SBinary[BinaryClassStringTuple] {
  import sbinary.DefaultProtocol._
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryClassStringTuple(fromByteArray[Tuple2[Class[_], String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryStringByteArrayTuple(val value: Tuple2[String, Array[Byte]]) extends Serializable.SBinary[BinaryStringByteArrayTuple] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringByteArrayTuple(fromByteArray[Tuple2[String, Array[Byte]]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}

// --- MAPS ---
case class BinaryStringStringMap(val value: Map[String, String]) extends Serializable.SBinary[BinaryStringStringMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringStringMap(fromByteArray[Map[String, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigIntStringMap(val value: Map[BigInt, String]) extends Serializable.SBinary[BinaryBigIntStringMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigIntStringMap(fromByteArray[Map[BigInt, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryBigDecimalStringMap(val value: Map[BigDecimal, String]) extends Serializable.SBinary[BinaryBigDecimalStringMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryBigDecimalStringMap(fromByteArray[Map[BigDecimal, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryLongStringMap(val value: Map[Long, String]) extends Serializable.SBinary[BinaryLongStringMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryLongStringMap(fromByteArray[Map[Long, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryIntStringMap(val value: Map[Int, String]) extends Serializable.SBinary[BinaryIntStringMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryIntStringMap(fromByteArray[Map[Int, String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryClassStringMap(val value: Map[Class[_], String]) extends Serializable.SBinary[BinaryClassStringMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryClassStringMap(fromByteArray[Map[Class[_], String]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryStringBigIntMap(val value: Map[String, BigInt]) extends Serializable.SBinary[BinaryStringBigIntMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringBigIntMap(fromByteArray[Map[String, BigInt]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryStringBigDecimalMap(val value: Map[String, BigDecimal]) extends Serializable.SBinary[BinaryStringBigDecimalMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringBigDecimalMap(fromByteArray[Map[String, BigDecimal]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryStringLongMap(val value: Map[String, Long]) extends Serializable.SBinary[BinaryStringLongMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringLongMap(fromByteArray[Map[String, Long]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryStringIntMap(val value: Map[String, Int]) extends Serializable.SBinary[BinaryStringIntMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringIntMap(fromByteArray[Map[String, Int]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryStringClassMap(val value: Map[String, Class[_]]) extends Serializable.SBinary[BinaryStringClassMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringClassMap(fromByteArray[Map[String, Class[_]]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
case class BinaryStringByteArrayMap(val value: Map[String, Array[Byte]]) extends Serializable.SBinary[BinaryStringByteArrayMap] {
  def this() = this(null)
  def fromBytes(bytes: Array[Byte]) = BinaryStringByteArrayMap(fromByteArray[Map[String, Array[Byte]]](bytes))
  def toBytes: Array[Byte] =          toByteArray(value)
}
