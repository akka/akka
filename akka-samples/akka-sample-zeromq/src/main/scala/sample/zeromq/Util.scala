package sample.zeromq

import akka.jsr166y.ThreadLocalRandom

object Util {
  val generator = new ThreadLocalRandom()

  def randomString(random: Random, maxMessageSize: Int) = {
    val size = random.nextInt(maxMessageSize) + 1
    val bytes = Array[Byte](size)
    generator.nextBytes(bytes)
    new String(bytes, "UTF-8")
  }
}
