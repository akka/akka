package sample.zeromq

import util.Random

object Util {
  def randomString(random: Random, maxMessageSize: Int) = {
    val size = random.nextInt(maxMessageSize) + 1
    random.nextString(size)
  }
}
