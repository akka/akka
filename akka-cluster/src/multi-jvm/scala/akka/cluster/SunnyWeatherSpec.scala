/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.SortedSet

object SunnyWeatherMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(ConfigFactory.parseString("""
    akka.cluster.nr-of-deputy-nodes = 0
    akka.loglevel = INFO
    """))
}

class SunnyWeatherMultiJvmNode1 extends SunnyWeatherSpec with AccrualFailureDetectorStrategy
class SunnyWeatherMultiJvmNode2 extends SunnyWeatherSpec with AccrualFailureDetectorStrategy
class SunnyWeatherMultiJvmNode3 extends SunnyWeatherSpec with AccrualFailureDetectorStrategy
class SunnyWeatherMultiJvmNode4 extends SunnyWeatherSpec with AccrualFailureDetectorStrategy
class SunnyWeatherMultiJvmNode5 extends SunnyWeatherSpec with AccrualFailureDetectorStrategy

abstract class SunnyWeatherSpec
  extends MultiNodeSpec(SunnyWeatherMultiJvmSpec)
  with MultiNodeClusterSpec {

  import SunnyWeatherMultiJvmSpec._

  "A normal cluster" must {
    "be healthy" taggedAs LongRunningTest in {

      // start some
      awaitClusterUp(first, second, third)
      runOn(first, second, third) {
        log.info("3 joined")
      }

      // add a few more
      awaitClusterUp(roles: _*)
      log.info("5 joined")

      val unexpected = new AtomicReference[SortedSet[Member]]
      cluster.registerListener(new MembershipChangeListener {
        def notify(members: SortedSet[Member]) {
          // we don't expected any changes to the cluster
          unexpected.set(members)
        }
      })

      for (n ← 1 to 30) {
        testConductor.enter("period-" + n)
        unexpected.get must be(null)
        awaitUpConvergence(roles.size)
        assertLeaderIn(roles)
        if (n % 5 == 0) log.info("Passed period [{}]", n)
        1.seconds.sleep
      }

      testConductor.enter("after")
    }
  }
}
