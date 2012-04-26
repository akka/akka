/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import java.net.InetSocketAddress

import akka.testkit._
import akka.dispatch._
import akka.actor._
import akka.remote._
import akka.util.duration._

import com.typesafe.config._

/**
 * *****************************************************
 *
 * Add these options to the sbt script:
 * java \
 * -Dcom.sun.management.jmxremote.port=9999 \
 * -Dcom.sun.management.jmxremote.ssl=false \
 * -Dcom.sun.management.jmxremote.authenticate=false \
 * -Dcom.sun.management.jmxremote.password.file=<path to file>
 *
 * Use the jmx/cmdline-jmxclient to invoke queries and operations:
 * java -jar jmx/cmdline-jmxclient-0.10.3.jar - localhost:9999 akka:type=Cluster join=akka://system0@localhost:5550
 *
 * *****************************************************
 */

/*class ClusterMBeanSpec extends ClusterSpec("akka.loglevel = DEBUG") with ImplicitSender {

  var node0: Cluster = _
  var system0: ActorSystemImpl = _

  try {
    "A cluster node" must {
      System.setProperty("com.sun.management.jmxremote.port", "9999")
      system0 = ActorSystem("system0", ConfigFactory
        .parseString("akka.remote.netty.port=5550")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote0 = system0.provider.asInstanceOf[RemoteActorRefProvider]
      node0 = Cluster(system0)

      "be able to communicate over JMX through its ClusterMBean" taggedAs LongRunningTest in {
        Thread.sleep(60.seconds.dilated.toMillis)

        // FIXME test JMX API
      }
    }
  } catch {
    case e: Exception ⇒
      e.printStackTrace
      fail(e.toString)
  }

  override def atTermination() {
    if (node0 ne null) node0.shutdown()
    if (system0 ne null) system0.shutdown()
  }
}
*/ 