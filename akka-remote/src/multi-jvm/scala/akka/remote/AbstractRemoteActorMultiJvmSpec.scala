package akka.remote

import com.typesafe.config.{Config, ConfigFactory}

trait AbstractRemoteActorMultiJvmSpec {
  def NrOfNodes: Int
  def commonConfig: Config

  private[this] val remotes: Array[String] = {
    val arrayOpt = Option(AkkaRemoteSpec.testNodes).map(_ split ",")
    (arrayOpt getOrElse Array.fill(NrOfNodes)("localhost")).toArray
  }

	def akkaSpec(idx: Int) = "AkkaRemoteSpec@%s:%d".format(remotes(idx), 9991+idx)

  def akkaURIs(count: Int): String = {
    val specs = for (idx <- 0 until count) yield "\"akka://" + akkaSpec(idx) + "\""
    specs.mkString(",")
  }

  val nodeConfigs = ((1 to NrOfNodes).toList zip remotes) map {
    case (idx, host) =>
      ConfigFactory.parseString("""
        akka {
          remote.server.hostname="%s"
          remote.server.port = "%d"
        }""".format(host, 9990+idx, idx)) withFallback commonConfig
  }
}
