package akka.remote.actmote

import com.typesafe.config.Config
import concurrent.util.Duration

private[akka] class ActorManagedRemotingSettings(config: Config) {

  import config._
  val Connector: String = getString("connector")
  val UsePassiveConnections: Boolean = getBoolean("use-passive-connections")
  val StartupTimeout: Duration = Duration.fromNanos(getNanoseconds("startup-timeout"))
  val ShutdownTimeout: Duration = Duration.fromNanos(getNanoseconds("shutdown-timeout"))
  val PreConnectBufferSize: Int = getInt("preconnect-buffer-size")

}
