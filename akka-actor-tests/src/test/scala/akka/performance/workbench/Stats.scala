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
  percentiles: TreeMap[Int, Long] = TreeMap.empty) {

  def median: Long = percentiles(50)
}

