package akka.remote.testconductor

import akka.actor.ExtensionKey
import akka.actor.Extension
import akka.actor.ExtendedActorSystem
import akka.remote.RemoteActorRefProvider
import akka.actor.ActorContext
import akka.util.{ Duration, Timeout }
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.ActorRef
import java.util.concurrent.ConcurrentHashMap
import akka.actor.Address

object TestConductor extends ExtensionKey[TestConductorExt] {

  def apply()(implicit ctx: ActorContext): TestConductorExt = apply(ctx.system)

}

class TestConductorExt(val system: ExtendedActorSystem) extends Extension with Conductor with Player {

  object Settings {
    val config = system.settings.config

    implicit val BarrierTimeout = Timeout(Duration(config.getMilliseconds("akka.testconductor.barrier-timeout"), MILLISECONDS))
    implicit val QueryTimeout = Timeout(Duration(config.getMilliseconds("akka.testconductor.query-timeout"), MILLISECONDS))

    val name = config.getString("akka.testconductor.name")
    val host = config.getString("akka.testconductor.host")
    val port = config.getInt("akka.testconductor.port")
  }

  val transport = system.provider.asInstanceOf[RemoteActorRefProvider].transport
  val address = transport.address

  val failureInjectors = new ConcurrentHashMap[Address, FailureInjector]

}