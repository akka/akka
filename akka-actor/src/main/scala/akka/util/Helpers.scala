/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util

import java.util.Comparator
import scala.annotation.tailrec
import java.util.regex.Pattern
import com.typesafe.config.Config
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import java.util.Locale

object Helpers {

  val isWindows: Boolean = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).indexOf("win") >= 0

  def makePattern(s: String): Pattern = Pattern.compile("^\\Q" + s.replace("?", "\\E.\\Q").replace("*", "\\E.*\\Q") + "\\E$")

  def compareIdentityHash(a: AnyRef, b: AnyRef): Int = {
    /*
     * make sure that there is no overflow or underflow in comparisons, so
     * that the ordering is actually consistent and you cannot have a
     * sequence which cyclically is monotone without end.
     */
    val diff = ((System.identityHashCode(a) & 0xffffffffL) - (System.identityHashCode(b) & 0xffffffffL))
    if (diff > 0) 1 else if (diff < 0) -1 else 0
  }

  /**
   * Create a comparator which will efficiently use `System.identityHashCode`,
   * unless that happens to be the same for two non-equals objects, in which
   * case the supplied “real” comparator is used; the comparator must be
   * consistent with equals, otherwise it would not be an enhancement over
   * the identityHashCode.
   */
  def identityHashComparator[T <: AnyRef](comp: Comparator[T]): Comparator[T] = new Comparator[T] {
    def compare(a: T, b: T): Int = compareIdentityHash(a, b) match {
      case 0 if a != b ⇒ comp.compare(a, b)
      case x           ⇒ x
    }
  }

  /**
   * Converts a "currentTimeMillis"-obtained timestamp accordingly:
   * {{{
   *   "$hours%02d:$minutes%02d:$seconds%02d.$ms%03dUTC"
   * }}}
   *
   * @param timestamp a "currentTimeMillis"-obtained timestamp
   * @return the formatted timestamp
   */
  def currentTimeMillisToUTCString(timestamp: Long): String = {
    val timeOfDay = timestamp % 86400000L
    val hours = timeOfDay / 3600000L
    val minutes = timeOfDay / 60000L % 60
    val seconds = timeOfDay / 1000L % 60
    val ms = timeOfDay % 1000
    f"$hours%02d:$minutes%02d:$seconds%02d.$ms%03dUTC"
  }

  final val base64chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+~"

  @tailrec
  def base64(l: Long, sb: java.lang.StringBuilder = new java.lang.StringBuilder("$")): String = {
    sb append base64chars.charAt(l.toInt & 63)
    val next = l >>> 6
    if (next == 0) sb.toString
    else base64(next, sb)
  }

  /**
   * Implicit class providing `requiring` methods. This class is based on
   * `Predef.ensuring` in the Scala standard library. The difference is that
   * this class's methods throw `IllegalArgumentException`s rather than
   * `AssertionError`s.
   *
   * An example adapted from `Predef`'s documentation:
   * {{{
   * import akka.util.Helpers.Requiring
   *
   * def addNaturals(nats: List[Int]): Int = {
   *   require(nats forall (_ >= 0), "List contains negative numbers")
   *   nats.foldLeft(0)(_ + _)
   * } requiring(_ >= 0)
   * }}}
   *
   * @param value The value to check.
   */
  @inline final implicit class Requiring[A](val value: A) extends AnyVal {
    /**
     * Check that a condition is true. If true, return `value`, otherwise throw
     * an `IllegalArgumentException` with the given message.
     *
     * @param cond The condition to check.
     * @param msg The message to report if the condition isn't met.
     */
    @inline def requiring(cond: Boolean, msg: ⇒ Any): A = {
      require(cond, msg)
      value
    }

    /**
     * Check that a condition is true for the `value`. If true, return `value`,
     * otherwise throw an `IllegalArgumentException` with the given message.
     *
     * @param cond The function used to check the `value`.
     * @param msg The message to report if the condition isn't met.
     */
    @inline def requiring(cond: A ⇒ Boolean, msg: ⇒ Any): A = {
      require(cond(value), msg)
      value
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] final implicit class ConfigOps(val config: Config) extends AnyVal {
    def getMillisDuration(path: String): FiniteDuration = getDuration(path, TimeUnit.MILLISECONDS)

    def getNanosDuration(path: String): FiniteDuration = getDuration(path, TimeUnit.NANOSECONDS)

    private def getDuration(path: String, unit: TimeUnit): FiniteDuration =
      Duration(config.getDuration(path, unit), unit)
  }

}
