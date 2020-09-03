/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import java.util.Arrays

object Version {
  val Zero: Version = Version("0.0.0")

  def apply(version: String): Version =
    new Version(version)
}

/**
 * Comparable version information.
 *
 * The typical convention is to use
 * 3 digit version numbers `major.minor.patch`, but 1 or two digits are
 * also supported. It may also have a qualifier at the end, such as "1.2-rc1".
 */
final class Version(val version: String) extends Comparable[Version] {
  private val (numbers: Array[Int], rest: String) = {
    val numbers = new Array[Int](3)
    val segments: Array[String] = version.split("[.+-]")
    var segmentPos = 0
    var numbersPos = 0
    while (numbersPos < 3) {
      if (segmentPos < segments.length) try {
        numbers(numbersPos) = segments(segmentPos).toInt
        segmentPos += 1
      } catch {
        case _: NumberFormatException =>
          // This means that we have a trailing part on the version string and
          // less than 3 numbers, so we assume that this is a "newer" version
          numbers(numbersPos) = Integer.MAX_VALUE
      }
      numbersPos += 1
    }

    val rest: String =
      if (segmentPos >= segments.length) ""
      else String.join("-", Arrays.asList(Arrays.copyOfRange(segments, segmentPos, segments.length): _*))

    (numbers, rest)
  }

  override def compareTo(other: Version): Int = {
    var diff = 0
    diff = numbers(0) - other.numbers(0)
    if (diff == 0) {
      diff = numbers(1) - other.numbers(1)
      if (diff == 0) {
        diff = numbers(2) - other.numbers(2)
        if (diff == 0) {
          diff = rest.compareTo(other.rest)
        }
      }
    }
    diff
  }

  override def equals(o: Any): Boolean = o match {
    case v: Version => compareTo(v) == 0
    case _          => false
  }

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, numbers(0))
    result = HashCode.hash(result, numbers(1))
    result = HashCode.hash(result, numbers(2))
    result = HashCode.hash(result, rest)
    result
  }

  override def toString: String = version
}
