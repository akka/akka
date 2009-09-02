/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.util

import java.lang.reflect.{Array => JArray}
import java.lang.{Float => JFloat, Double => JDouble}

/**
 * Set of methods which allow easy implementation of <code>hashCode</code>.
 *
 * Example:
 * <pre>
 *  override def hashCode: Int = {
 *    var result = HashCode.SEED
 *    //collect the contributions of various fields
 *    result = HashCode.hash(result, fPrimitive)
 *    result = HashCode.hash(result, fObject)
 *    result = HashCode.hash(result, fArray)
 *    result
 *  }
 * </pre>
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object HashCode {
  val SEED = 23

  def hash(seed: Int, value: Boolean): Int = firstTerm(seed) + (if (value) 1 else 0)
  def hash(seed: Int, value: Char): Int = firstTerm(seed) + value.asInstanceOf[Int]
  def hash(seed: Int, value: Int): Int = firstTerm(seed) + value
  def hash(seed: Int, value: Long): Int = firstTerm(seed)  + (value ^ (value >>> 32) ).asInstanceOf[Int]
  def hash(seed: Int, value: Float): Int = hash(seed, JFloat.floatToIntBits(value))
  def hash(seed: Int, value: Double): Int = hash(seed, JDouble.doubleToLongBits(value))
  def hash(seed: Int, anyRef: AnyRef): Int = {
    var result = seed
    if (anyRef == null) result = hash(result, 0)
    else if (!isArray(anyRef)) result = hash(result, anyRef.hashCode())
    else for (id <- 0 until JArray.getLength(anyRef)) result = hash(result, JArray.get(anyRef, id)) // is an array
    result
  }

  private def firstTerm(seed: Int): Int = PRIME * seed
  private def isArray(anyRef: AnyRef): Boolean = anyRef.getClass.isArray
  private val PRIME = 37
}

