/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.SortedSet
import akka.actor.Props
import akka.actor.Actor

class SunnyWeatherMultiJvmSpec(artery: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  val common =
    """
    akka {
      actor.provider = akka.cluster.ClusterActorRefProvider
      loggers = ["akka.testkit.TestEventListener"]
      loglevel = INFO
      remote.log-remote-lifecycle-events = off
      cluster.failure-detector.monitored-by-nr-of-members = 3
    }
    """

  val arteryConfig =
    """
    akka.remote.artery {
      enabled = on
    }
    """

  // Note that this test uses default configuration,
  // not MultiNodeClusterSpec.clusterConfig
  commonConfig(
    if (artery) ConfigFactory.parseString(arteryConfig).withFallback(ConfigFactory.parseString(common))
    else ConfigFactory.parseString(common))

}

class SunnyWeatherRemotingMultiJvmNode1 extends SunnyWeatherRemotingSpec
class SunnyWeatherRemotingMultiJvmNode2 extends SunnyWeatherRemotingSpec
class SunnyWeatherRemotingMultiJvmNode3 extends SunnyWeatherRemotingSpec
class SunnyWeatherRemotingMultiJvmNode4 extends SunnyWeatherRemotingSpec
class SunnyWeatherRemotingMultiJvmNode5 extends SunnyWeatherRemotingSpec

class SunnyWeatherArteryMultiJvmNode1 extends SunnyWeatherArterySpec
class SunnyWeatherArteryMultiJvmNode2 extends SunnyWeatherArterySpec
class SunnyWeatherArteryMultiJvmNode3 extends SunnyWeatherArterySpec
class SunnyWeatherArteryMultiJvmNode4 extends SunnyWeatherArterySpec
class SunnyWeatherArteryMultiJvmNode5 extends SunnyWeatherArterySpec

abstract class SunnyWeatherRemotingSpec extends SunnyWeatherSpec(new SunnyWeatherMultiJvmSpec(artery = false))
abstract class SunnyWeatherArterySpec extends SunnyWeatherSpec(new SunnyWeatherMultiJvmSpec(artery = true))

abstract class SunnyWeatherSpec(multiNodeConfig: SunnyWeatherMultiJvmSpec)
  extends MultiNodeSpec(multiNodeConfig)
  with MultiNodeClusterSpec {

  import multiNodeConfig._

  import ClusterEvent._

  "A normal cluster" must {
    "be healthy" taggedAs LongRunningTest in {

      // start some
      awaitClusterUp(first, second, third)
      runOn(first, second, third) {
        log.debug("3 joined")
      }

      // add a few more
      awaitClusterUp(roles: _*)
      log.debug("5 joined")

      val unexpected = new AtomicReference[SortedSet[Member]](SortedSet.empty)
      cluster.subscribe(system.actorOf(Props(new Actor {
        def receive = {
          case event: MemberEvent ⇒
            // we don't expected any changes to the cluster
            unexpected.set(unexpected.get + event.member)
          case _: CurrentClusterState ⇒ // ignore
        }
      })), classOf[MemberEvent])

      for (n ← 1 to 30) {
        enterBarrier("period-" + n)
        unexpected.get should ===(SortedSet.empty)
        awaitMembersUp(roles.size)
        assertLeaderIn(roles)
        if (n % 5 == 0) log.debug("Passed period [{}]", n)
        Thread.sleep(1000)
      }

      enterBarrier("after")
    }
  }
}
