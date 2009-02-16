/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.api

import com.scalablesolutions.akka.kernel.ActiveObject

import java.util.{List => JList}

import scala.actors.behavior._
import scala.reflect.BeanProperty

sealed abstract class Configuration

class RestartStrategy(@BeanProperty val scheme: FailOverScheme, @BeanProperty val maxNrOfRetries: Int, @BeanProperty val withinTimeRange: Int) extends Configuration {
  def transform = scala.actors.behavior.RestartStrategy(scheme.transform, maxNrOfRetries, withinTimeRange)
}
class LifeCycle(@BeanProperty val scope: Scope, @BeanProperty val shutdownTime: Int) extends Configuration {
  def transform = scala.actors.behavior.LifeCycle(scope.transform, shutdownTime)
}

abstract class Scope extends Configuration {
  def transform: scala.actors.behavior.Scope
}
class Permanent extends Scope {
  override def transform = scala.actors.behavior.Permanent
}
class Transient extends Scope {
  override def transform = scala.actors.behavior.Transient
}
class Temporary extends Scope {
  override def transform = scala.actors.behavior.Temporary
}

abstract class FailOverScheme extends Configuration {
  def transform: scala.actors.behavior.FailOverScheme
}
class AllForOne extends FailOverScheme {
  override def transform = scala.actors.behavior.AllForOne
}
class OneForOne extends FailOverScheme {
  override def transform = scala.actors.behavior.OneForOne
}

abstract class Server extends Configuration
class SupervisorConfig(@BeanProperty val restartStrategy: RestartStrategy, @BeanProperty val servers: JList[Server]) extends Server {
  def transform = scala.actors.behavior.SupervisorConfig(restartStrategy.transform, servers.toArray.toList.asInstanceOf[List[Component]].map(_.transform))
}
class Component(@BeanProperty val serverContainer: GenericServerContainer, @BeanProperty val lifeCycle: LifeCycle) extends Server {
  def transform = scala.actors.behavior.Worker(serverContainer, lifeCycle.transform)
}


object Configuration {
  def supervise(restartStrategy: RestartStrategy, components: JList[Component]): Supervisor = 
    ActiveObject.supervise(
      restartStrategy.transform, 
      components.toArray.toList.asInstanceOf[List[Component]].map(
        c => scala.actors.behavior.Worker(c.serverContainer, c.lifeCycle.transform)))
}



//   static class SupervisorConfig extends Server {
//     private final RestartStrategy restartStrategy;
//     private final List<Server> servers;
//     public SupervisorConfig(RestartStrategy restartStrategy, List<Server> servers) {
//       this.restartStrategy = restartStrategy;
//       this.servers = servers;
//     }
//     public RestartStrategy getRestartStrategy() {
//       return restartStrategy;
//     }
//     public List<Server> getServer() {
//       return servers;
//     }
//     public scala.actors.behavior.SupervisorConfig scalaVersion() {
//       List<scala.actors.behavior.Server> ss = new ArrayList<scala.actors.behavior.Server>();
//       for (Server s: servers) {
//         ss.add(s.scalaVersion());
//       }
//       return new scala.actors.behavior.SupervisorConfig(restartStrategy.scalaVersion(), ss);
//     }
//   }

//   static class Component extends Server {
//     private final GenericServerContainer serverContainer;
//     private final LifeCycle lifeCycle;
//     public Component(GenericServerContainer serverContainer, LifeCycle lifeCycle) {
//       this.serverContainer = serverContainer;
//       this.lifeCycle = lifeCycle;
//     }
//     public GenericServerContainer getServerContainer() {
//       return serverContainer;
//     }
//     public LifeCycle getLifeCycle() {
//       return lifeCycle;
//     }
//     public scala.actors.behavior.Server scalaVersion() {
//       return new scala.actors.behavior.Worker(serverContainer, lifeCycle.scalaVersion());
//     }
//   }

//   static class RestartStrategy extends Configuration {
//     private final FailOverScheme scheme;
//     private final int maxNrOfRetries;
//     private final int withinTimeRange;
//     public RestartStrategy(FailOverScheme scheme, int maxNrOfRetries, int withinTimeRange) {
//       this.scheme = scheme;
//       this.maxNrOfRetries = maxNrOfRetries;
//       this.withinTimeRange = withinTimeRange;
//     }
//     public FailOverScheme getFailOverScheme() {
//       return scheme;
//     }
//     public int getMaxNrOfRetries() {
//       return maxNrOfRetries;
//     }
//     public int getWithinTimeRange() {
//       return withinTimeRange;
//     }
//     public scala.actors.behavior.RestartStrategy scalaVersion() {
//       scala.actors.behavior.FailOverScheme fos;
//       switch (scheme) {
//       case AllForOne: fos = new scala.actors.behavior.AllForOne(); break;
//       case OneForOne: fos = new scala.actors.behavior.OneForOne(); break;
//       }
//       return new scala.actors.behavior.RestartStrategy(fos, maxNrOfRetries, withinTimeRange);
//     }
//   }

//   static class LifeCycle extends Configuration {
//     private final Scope scope;
//     private final int shutdownTime;
//     public LifeCycle(Scope scope, int shutdownTime) {
//       this.scope = scope;
//       this.shutdownTime = shutdownTime;
//     }
//     public Scope getScope() {
//       return scope;
//     }
//     public int getShutdownTime() {
//       return shutdownTime;
//     }
//     public scala.actors.behavior.LifeCycle scalaVersion() {
//       scala.actors.behavior.Scope s;
//       switch (scope) {
//       case Permanent: s = new scala.actors.behavior.Permanent(); break;
//       case Transient: s = new scala.actors.behavior.Transient(); break;
//       case Temporary: s = new scala.actors.behavior.Temporary(); break;
//       }
//       return new scala.actors.behavior.LifeCycle(s, shutdownTime);
//     }
//   }
// }

