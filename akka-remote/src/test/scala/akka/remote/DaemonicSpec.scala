/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.testkit._
import scala.concurrent.duration._
import akka.actor.{ Address, ExtendedActorSystem, ActorSystem }
import com.typesafe.config.ConfigFactory
import java.nio.channels.ServerSocketChannel
import java.net.InetSocketAddress
import scala.collection.JavaConverters._

class DaemonicSpec extends AkkaSpec {

  def addr(sys: ActorSystem, proto: String) =
    sys.asInstanceOf[ExtendedActorSystem].provider.getExternalAddressFor(Address(s"akka.$proto", "", "", 0)).get

  def unusedPort = {
    val ss = ServerSocketChannel.open().socket()
    ss.bind(new InetSocketAddress("localhost", 0))
    val port = ss.getLocalPort
    ss.close()
    port
  }

  "Remoting configured with daemonic = on" must {

    "shut down correctly after getting connection refused" in {
      // get all threads running before actor system i started
      val origThreads: Set[Thread] = Thread.getAllStackTraces().keySet().asScala.to[Set]
      // create a separate actor system that we can check the threads for
      val daemonicSystem = ActorSystem("daemonic", ConfigFactory.parseString("""
        akka.daemonic = on
        akka.actor.provider = "akka.remote.RemoteActorRefProvider"
        akka.remote.netty.tcp.transport-class = "akka.remote.transport.netty.NettyTransport"
        akka.remote.netty.tcp.port = 0
        akka.log-dead-letters-during-shutdown = off
      """))

      val unusedAddress = addr(daemonicSystem, "tcp").copy(port = Some(unusedPort))
      val selection = daemonicSystem.actorSelection(s"${unusedAddress}/user/SomeActor")
      selection ! "whatever"
      Thread.sleep(2.seconds.dilated.toMillis)

      // get new non daemonic threads running
      val newNonDaemons: Set[Thread] = Thread.getAllStackTraces().keySet().asScala.seq.
        filter(t ⇒ !origThreads(t) && t.isDaemon == false).to[Set]

      newNonDaemons should ===(Set.empty[Thread])
      shutdown(daemonicSystem)
    }
  }
}
