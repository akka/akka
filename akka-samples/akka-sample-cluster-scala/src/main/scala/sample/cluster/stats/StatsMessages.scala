package sample.cluster.stats

//#messages
case class StatsJob(text: String)
case class StatsResult(meanWordLength: Double)
case class JobFailed(reason: String)
//#messages
