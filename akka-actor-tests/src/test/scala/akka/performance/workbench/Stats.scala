package akka.performance.workbench

import scala.collection.immutable.TreeMap

@SerialVersionUID(1L)
case class Stats(
  name: String,
  load: Int,
  timestamp: Long = System.currentTimeMillis,
  durationNanos: Long = 0L,
  n: Long = 0L,
  min: Long = 0L,
  max: Long = 0L,
  mean: Double = 0.0,
  tps: Double = 0.0,
  percentiles: TreeMap[Int, Long] = Stats.emptyPercentiles) {

  def median: Long = percentiles(50)
}

object Stats {
  val emptyPercentiles = TreeMap[Int, Long](
    5 -> 0L,
    25 -> 0L,
    50 -> 0L,
    75 -> 0L,
    95 -> 0L)
}

