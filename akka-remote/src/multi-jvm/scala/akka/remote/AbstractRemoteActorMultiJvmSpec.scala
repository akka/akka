package akka.remote

import com.typesafe.config.{Config, ConfigFactory}

trait AbstractRemoteActorMultiJvmSpec {
  def NrOfNodes: Int
  def commonConfig: Config
  def PortRangeStart = 1990

  private[this] val remotes: IndexedSeq[String] = {
    val nodesOpt = Option(AkkaRemoteSpec.testNodes).map(_.split(",").toIndexedSeq)
    nodesOpt getOrElse IndexedSeq.fill(NrOfNodes)("localhost")
  }

  val nodeConfigs = ((1 to NrOfNodes).toList zip remotes) map {
    case (idx, host) =>
      ConfigFactory.parseString("""
        akka {
          remote.netty.hostname="%s"
          remote.netty.port = "%d"
        }""".format(host, PortRangeStart + idx, idx)) withFallback commonConfig
  }

  def akkaSpec(idx: Int) = "AkkaRemoteSpec@%s:%d".format(remotes(idx), PortRangeStart + 1 + idx)
  def akkaURIs(count: Int): String = 0 until count map {idx => "\"akka://" + akkaSpec(idx) + "\""} mkString ","
}
