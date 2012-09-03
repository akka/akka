package akka.remote.actmote

import com.typesafe.config.Config
import concurrent.util.Duration

private[akka] class ActorManagedRemotingSettings(config: Config) {

  import config._
  val Connector: String = getString("akka.remote.managed.connector")
  val UsePassiveConnections: Boolean = getBoolean("akka.remote.managed.use-passive-connections")
  val StartupTimeout: Duration = Duration.fromNanos(getNanoseconds("akka.remote.managed.startup-timeout"))
  val ShutdownTimeout: Duration = Duration.fromNanos(getNanoseconds("akka.remote.managed.shutdown-timeout"))
  val PreConnectBufferSize: Int = getInt("akka.remote.managed.preconnect-buffer-size") //TODO: rename
  val RetryLatchClosedFor: Duration = Duration.fromNanos(getNanoseconds("akka.remote.managed.retry-latch-closed-for"))
  val FlowControlBackoff: Duration = Duration.fromNanos(getNanoseconds("akka.remote.managed.flow-control-backoff"))

}
