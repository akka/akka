/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.concurrent.duration._
import scala.collection.{ immutable ⇒ im }
import scala.util.Random

import akka.actor.ExtendedActorSystem
import akka.testkit.{ AkkaSpec, LongRunningTest }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.DoNotDiscover

object JoinConfigCompatCheckerRollingUpdateSpec {

  val baseConfig = ConfigFactory.parseString(
    s"""
      akka.loglevel = WARNING
      akka.log-dead-letters = off
      akka.log-dead-letters-during-shutdown = off
      akka.remote.log-remote-lifecycle-events = off
      akka.cluster.jmx.enabled = off
    """)
    .withFallback(JoinConfigCompatCheckerSpec.baseConfig)

  val v1Config: Config = baseConfig.withFallback(JoinConfigCompatCheckerSpec.configWithChecker)

  val v2Config: Config = ConfigFactory.parseString(
    """
      akka.cluster.v2-configuration = "v2"
      akka.cluster.configuration-compatibility-check.checkers {
        rolling-upgrade-test = "akka.cluster.JoinConfigCompatRollingUpdateChecker"
      }
    """).withFallback(v1Config)

  def unenforced(config: Config): Config =
    ConfigFactory
      .parseString("""akka.cluster.configuration-compatibility-check.enforce-on-join = off""")
      .withFallback(config)
}

class JoinConfigCompatCheckerDiffSpec extends AkkaSpec {

  "JoinConfigCompatChecker" must {

    val sys = system.asInstanceOf[ExtendedActorSystem]
    val defaults = ConfigFactory.load().withOnlyPath("akka.cluster")
      .withFallback(ConfigFactory.parseString("""akka.version = "2.5.9"""")) // should have from load

    val v1Config = JoinConfigCompatCheckerRollingUpdateSpec.v1Config.withFallback(defaults)
    val v2Config = JoinConfigCompatCheckerRollingUpdateSpec.v2Config.withFallback(defaults)

    "diff ValidNel if the joining config has new, additional properties and has all required keys" in {
      val composite = JoinConfigCompatChecker.load(sys, new ClusterSettings(v1Config, system.name))
      val result = JoinConfigCompatChecker.diff(composite.requiredKeys, v2Config, v1Config)
      result should ===(ValidNel)
    }

    "diff Valid if the joining config has no new properties and has all required keys" in {
      val composite = JoinConfigCompatChecker.load(sys, new ClusterSettings(v1Config, system.name))
      val result = JoinConfigCompatChecker.diff(composite.requiredKeys, v1Config, v1Config)
      result should ===(Valid)
    }

    "be Valid in full check during a rolling upgrade if the joining config has new, additional properties and is otherwise compatible" in {
      val composite = JoinConfigCompatChecker.load(sys, new ClusterSettings(v1Config, system.name))
      val result = JoinConfigCompatChecker.fullMatch(composite.requiredKeys, v2Config, v1Config)
      result should ===(Valid)
    }

    "be Valid in full check during a rolling upgrade if the joining config no new properties and is otherwise compatible" in {
      val composite = JoinConfigCompatChecker.load(sys, new ClusterSettings(v1Config, system.name))
      val result = JoinConfigCompatChecker.fullMatch(composite.requiredKeys, v1Config, v1Config)
      result should ===(Valid)
    }
  }
}

@DoNotDiscover // temporary - wip Vector(akka.cluster.v2-configuration is missing)
class JoinConfigCompatCheckerRollingUpdateSpec
  extends AkkaSpec(JoinConfigCompatCheckerRollingUpdateSpec.baseConfig)
  with ClusterTestKit {

  import JoinConfigCompatCheckerRollingUpdateSpec._

  private val timeout = 20.seconds

  def upgradeCluster(size: Int, enforced: Boolean): Unit = {
    val util = new ClusterTestUtil(system.name)

    val config = (version: Config) ⇒
      if (enforced) version else unenforced(version)

    try {
      val nodes = for (_ ← 0 until size) yield {
        val system = util.newActorSystem(config(v1Config))
        util.joinCluster(system)
        system
      }
      awaitCond(nodes.forall(util.isMemberUp), timeout * size)

      val rolling = Random.shuffle(nodes)

      for (restarting ← rolling.tail) {
        val restarted = util.quitAndRestart(restarting, config(v2Config), timeout)
        util.joinCluster(restarted)
        awaitCond(util.isMemberUp(restarted), timeout * 2)
      }
      awaitCond(Cluster(rolling.head).readView.members.size == nodes.size, timeout * 2)

    } finally util.shutdownAll()
  }

  "A Node" must {
    "be allowed to re-join a cluster if it has a new, additional configuration the others do not have" taggedAs LongRunningTest in {
      upgradeCluster(size = 3, enforced = true)
    }
    "be allowed to re-join a cluster if it has a new, additional configuration the others do not have and configured to NOT enforce it" taggedAs LongRunningTest in {
      upgradeCluster(size = 3, enforced = false)
    }
  }
}

class JoinConfigCompatRollingUpdateChecker extends JoinConfigCompatChecker {
  override def requiredKeys: im.Seq[String] = im.Seq("akka.cluster.v2-configuration")
  override def check(toValidate: Config, actualConfig: Config): ConfigValidation =
    JoinConfigCompatChecker.fullMatch(requiredKeys, toValidate, actualConfig)
}

