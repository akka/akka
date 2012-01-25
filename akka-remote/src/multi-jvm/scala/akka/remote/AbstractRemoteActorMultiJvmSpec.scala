package akka.remote

import com.typesafe.config.{Config, ConfigFactory}

trait AbstractRemoteActorMultiJvmSpec {
  def NrOfNodes: Int
  def commonConfig: Config

  private[this] val remotes: IndexedSeq[String] = {
    val nodesOpt = Option(AkkaRemoteSpec.testNodes).map(_.split(",").toIndexedSeq)
    nodesOpt getOrElse IndexedSeq.fill(NrOfNodes)("localhost")
  }

	def akkaSpec(idx: Int) = "AkkaRemoteSpec@%s:%d".format(remotes(idx), 9991+idx)

  def akkaURIs(count: Int): String = {
    0 until count map {idx => "\"akka://" + akkaSpec(idx) + "\""} mkString ","
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
