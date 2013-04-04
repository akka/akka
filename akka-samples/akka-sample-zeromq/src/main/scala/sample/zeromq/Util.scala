package sample.zeromq

import scala.concurrent.forkjoin.ThreadLocalRandom

/**
 * Some helper methods for the sample code
 */
object Util {

  /**
   * Generates a random, printable char
   */
  def randomPrintableChar(): Char = {
    val low = 33
    val high = 127
    (ThreadLocalRandom.current.nextInt(high - low) + low).toChar
  }

  /**
   * Generates a random, printable string of specified maximum length
   */
  def randomString(maxMessageSize: Int) = {
    val size = ThreadLocalRandom.current.nextInt(maxMessageSize) + 1
    (for (i ← 0 until size) yield randomPrintableChar()).mkString
  }
}
