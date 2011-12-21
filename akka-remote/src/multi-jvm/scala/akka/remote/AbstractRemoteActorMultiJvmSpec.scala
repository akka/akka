package akka.remote

import com.typesafe.config.{Config, ConfigFactory}

trait AbstractRemoteActorMultiJvmSpec {
  def NrOfNodes: Int
  def commonConfig: Config

  def remotes: List[String] = {
    val listOpt = Option(System.getProperty("test.hosts")).map(_.split(",").toList)
    listOpt getOrElse List.fill(NrOfNodes)("localhost")
  }

  val nodeConfigs = ((1 to NrOfNodes).toList zip remotes) map {
    case (idx, host) =>
      ConfigFactory.parseString("""
        akka {
          remote.server.hostname="%s"
          remote.server.port = "999%d"
          cluster.nodename = "node%d"
        }""".format(host, idx, idx)) withFallback commonConfig
  }
}
