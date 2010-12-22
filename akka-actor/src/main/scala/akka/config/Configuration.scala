/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.config

/*
import akka.kernel.{TypedActor, TypedActorProxy}
import com.google.inject.{AbstractModule}
import java.util.{List => JList, ArrayList}
import scala.reflect.BeanProperty

// ============================================
// Java version of the configuration API



sealed abstract class Configuration

class RestartStrategy(@BeanProperty val scheme: FailOverScheme, @BeanProperty val maxNrOfRetries: Int, @BeanProperty val withinTimeRange: Int) extends Configuration {
  def transform = akka.kernel.RestartStrategy(scheme.transform, maxNrOfRetries, withinTimeRange)
}
class LifeCycle(@BeanProperty val scope: Scope, @BeanProperty val shutdownTime: Int) extends Configuration {
  def transform = akka.kernel.LifeCycle(scope.transform, shutdownTime)
}

abstract class Scope extends Configuration {
  def transform: akka.kernel.Scope
}
class Permanent extends Scope {
  override def transform = akka.kernel.Permanent
}
class Transient extends Scope {
  override def transform = akka.kernel.Transient
}
class Temporary extends Scope {
  override def transform = akka.kernel.Temporary
}

abstract class FailOverScheme extends Configuration {
  def transform: akka.kernel.FailOverScheme
}
class AllForOne extends FailOverScheme {
  override def transform = akka.kernel.AllForOne
}
class OneForOne extends FailOverScheme {
  override def transform = akka.kernel.OneForOne
}

abstract class Server extends Configuration
//class kernelConfig(@BeanProperty val restartStrategy: RestartStrategy, @BeanProperty val servers: JList[Server]) extends Server {
//  def transform = akka.kernel.kernelConfig(restartStrategy.transform, servers.toArray.toList.asInstanceOf[List[Server]].map(_.transform))
//}
class Component(@BeanProperty val intf: Class[_],
                @BeanProperty val target: Class[_],
                @BeanProperty val lifeCycle: LifeCycle,
                @BeanProperty val timeout: Int) extends Server {
  def newWorker(proxy: TypedActorProxy) = akka.kernel.Supervise(proxy.server, lifeCycle.transform)
}
*/
