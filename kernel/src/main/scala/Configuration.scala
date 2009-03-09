/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel.configuration

import com.scalablesolutions.akka.kernel.{ActiveObject, ActiveObjectProxy}
import google.inject.{AbstractModule}
import java.util.{List => JList, ArrayList}
import scala.reflect.BeanProperty

// ============================================
// Java version of the configuration API

sealed abstract class Configuration

class RestartStrategy(@BeanProperty val scheme: FailOverScheme, @BeanProperty val maxNrOfRetries: Int, @BeanProperty val withinTimeRange: Int) extends Configuration {
  def transform = com.scalablesolutions.akka.supervisor.RestartStrategy(scheme.transform, maxNrOfRetries, withinTimeRange)
}
class LifeCycle(@BeanProperty val scope: Scope, @BeanProperty val shutdownTime: Int) extends Configuration {
  def transform = com.scalablesolutions.akka.supervisor.LifeCycle(scope.transform, shutdownTime)
}

abstract class Scope extends Configuration {
  def transform: com.scalablesolutions.akka.supervisor.Scope
}
class Permanent extends Scope {
  override def transform = com.scalablesolutions.akka.supervisor.Permanent
}
class Transient extends Scope {
  override def transform = com.scalablesolutions.akka.supervisor.Transient
}
class Temporary extends Scope {
  override def transform = com.scalablesolutions.akka.supervisor.Temporary
}

abstract class FailOverScheme extends Configuration {
  def transform: com.scalablesolutions.akka.supervisor.FailOverScheme
}
class AllForOne extends FailOverScheme {
  override def transform = com.scalablesolutions.akka.supervisor.AllForOne
}
class OneForOne extends FailOverScheme {
  override def transform = com.scalablesolutions.akka.supervisor.OneForOne
}

abstract class Server extends Configuration
//class SupervisorConfig(@BeanProperty val restartStrategy: RestartStrategy, @BeanProperty val servers: JList[Server]) extends Server {
//  def transform = com.scalablesolutions.akka.supervisor.SupervisorConfig(restartStrategy.transform, servers.toArray.toList.asInstanceOf[List[Server]].map(_.transform))
//}
class Component(@BeanProperty val intf: Class[_],
                 @BeanProperty val target: Class[_],
                 @BeanProperty val lifeCycle: LifeCycle,
                 @BeanProperty val timeout: Int) extends Server {
  def newWorker(proxy: ActiveObjectProxy) = com.scalablesolutions.akka.supervisor.Worker(proxy.server, lifeCycle.transform)
}
