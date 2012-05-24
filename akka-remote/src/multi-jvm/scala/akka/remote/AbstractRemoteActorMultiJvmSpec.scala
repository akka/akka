package akka.remote

import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.Address

trait AbstractRemoteActorMultiJvmSpec {
  def NrOfNodes: Int
  def commonConfig: Config

  def PortRangeStart = 1990
  def NodeRange = 1 to NrOfNodes

  private[this] val remotes: IndexedSeq[String] = {
    val nodesOpt = Option(AkkaRemoteSpec.testNodes).map(_.split(",").toIndexedSeq)
    nodesOpt getOrElse IndexedSeq.fill(NrOfNodes)("localhost")
  }

  val nodeConfigs = (NodeRange.toList zip remotes) map {
    case (port, host) =>
      ConfigFactory.parseString("""
        akka {
          remote.netty.hostname="%s"
          remote.netty.port = "%d"
        }""".format(host, PortRangeStart + port, port)) withFallback commonConfig
  }

  def akkaSpec(port: Int) = "AkkaRemoteSpec@%s:%d".format(remotes(port), PortRangeStart + 1 + port)
  def akkaURIs(count: Int): String = 0 until count map {idx => "\"akka://" + akkaSpec(idx) + "\""} mkString ","
}
