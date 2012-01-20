package akka.remote

import com.typesafe.config.{Config, ConfigFactory}

trait AbstractRemoteActorMultiJvmSpec {
  def NrOfNodes: Int
  def commonConfig: Config

  def remotes: Seq[String] = {
    val arrayOpt = Option(AkkaRemoteSpec.testNodes).map(_ split ",")
    (arrayOpt getOrElse Array.fill(NrOfNodes)("localhost")).toSeq
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
          remote.netty.hostname="%s"
          remote.netty.port = "%d"
        }""".format(host, 9990+idx, idx)) withFallback commonConfig
  }
}
