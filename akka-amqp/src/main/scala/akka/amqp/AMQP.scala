package akka.amqp

import akka.actor._
import com.typesafe.config.Config

class AMQPSettings(config: Config) extends Extension {

  import scala.collection.JavaConverters._

  val DefaultAddresses: Seq[String] = config.getStringList("akka.amqp.default.addresses").asScala.toSeq
  val DefaultUser: String = config.getString("akka.amqp.default.user")
  val DefaultPass: String = config.getString("akka.amqp.default.pass")
  val DefaultVhost: String = config.getString("akka.amqp.default.vhost")
  val DefaultAmqpHeartbeatMs: Long = config.getMilliseconds("akka.amqp.default.heartbeat")
  val DefaultMaxReconnectDelayMs: Long = config.getMilliseconds("akka.amqp.default.max-reconnect-delay")
  val DefaultChannelThreads: Int = config.getInt("akka.amqp.default.channel-threads")
}

object AMQP extends ExtensionId[AMQPSettings] with ExtensionIdProvider {

  override def lookup() = this
  override def createExtension(system: ExtendedActorSystem): AMQPSettings = new AMQPSettings(system.settings.config)
}
