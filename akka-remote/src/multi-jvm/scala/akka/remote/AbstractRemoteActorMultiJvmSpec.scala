package akka.remote

import com.typesafe.config.{Config, ConfigFactory}

trait AbstractRemoteActorMultiJvmSpec {
  def NrOfNodes: Int
  def commonConfig: Config

  def remotes: List[String] = {
    val listOpt = Option(System.getProperty("test.hosts")).map(_.split(",").toList)
    listOpt getOrElse List.fill(NrOfNodes)("localhost")
  }

  def specString(count: Int): String = {
    val specs = for ((host, idx) <- remotes.take(count).zipWithIndex) yield
      "\"akka://AkkaRemoteSpec@%s:%d\"".format(host, 9991+idx)
    specs.mkString(",")
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
