package akka.remote.actmote

import com.typesafe.config.Config

private[akka] class ActorManagedRemotingSettings(config: Config) {

  import config._
  val Connector: String = getString("connector")
  val UsePassiveConnections: Boolean = getBoolean("use-passive-connections")

}
