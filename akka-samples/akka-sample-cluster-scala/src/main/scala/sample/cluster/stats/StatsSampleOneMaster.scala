package sample.cluster.stats

import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.contrib.pattern.ClusterSingletonManager
import akka.contrib.pattern.ClusterSingletonProxy

object StatsSampleOneMaster {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      startup(Seq("2551", "2552", "0"))
      StatsSampleOneMasterClient.main(Array.empty)
    } else {
      startup(args)
    }
  }

  def startup(ports: Seq[String]): Unit = {
    ports foreach { port =>
      // Override the configuration of the port when specified as program argument
      val config =
        ConfigFactory.parseString(s"akka.remote.netty.tcp.port=" + port).withFallback(
          ConfigFactory.parseString("akka.cluster.roles = [compute]")).
          withFallback(ConfigFactory.load("stats2"))

      val system = ActorSystem("ClusterSystem", config)

      //#create-singleton-manager
      system.actorOf(ClusterSingletonManager.props(
        singletonProps = Props[StatsService], singletonName = "statsService",
        terminationMessage = PoisonPill, role = Some("compute")),
        name = "singleton")
      //#create-singleton-manager

      //#singleton-proxy
      system.actorOf(ClusterSingletonProxy.props(singletonPath = "/user/singleton/statsService",
        role = Some("compute")), name = "statsServiceProxy")
      //#singleton-proxy
    }
  }
}

object StatsSampleOneMasterClient {
  def main(args: Array[String]): Unit = {
    // note that client is not a compute node, role not defined
    val system = ActorSystem("ClusterSystem")
    system.actorOf(Props(classOf[StatsSampleClient], "/user/statsServiceProxy"), "client")
  }
}

