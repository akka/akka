/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config

import kernel.actor.Actor
import reflect.BeanProperty

/**
 * Configuration classes - not to be used as messages.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ScalaConfig {
  sealed abstract class ConfigElement

  abstract class Server extends ConfigElement
  abstract class FailOverScheme extends ConfigElement
  abstract class Scope extends ConfigElement

  case class SupervisorConfig(restartStrategy: RestartStrategy, worker: List[Server]) extends Server
  case class Worker(actor: Actor, lifeCycle: LifeCycle) extends Server

  case class RestartStrategy(scheme: FailOverScheme, maxNrOfRetries: Int, withinTimeRange: Int) extends ConfigElement

  case object AllForOne extends FailOverScheme
  case object OneForOne extends FailOverScheme

  case class LifeCycle(scope: Scope, shutdownTime: Int) extends ConfigElement
  case object Permanent extends Scope
  case object Transient extends Scope
  case object Temporary extends Scope

  class Component(_intf: Class[_],
                       val target: Class[_],
                       val lifeCycle: LifeCycle,
                       val timeout: Int) extends Server {
    val intf: Option[Class[_]] = if (_intf == null) None else Some(_intf)
  }
  object Component {
    def apply(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Int) =
      new Component(intf, target, lifeCycle, timeout)
    def apply(target: Class[_], lifeCycle: LifeCycle, timeout: Int) =
      new Component(null, target, lifeCycle, timeout)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object JavaConfig {
  sealed abstract class ConfigElement

  class RestartStrategy(
      @BeanProperty val scheme: FailOverScheme,
      @BeanProperty val maxNrOfRetries: Int,
      @BeanProperty val withinTimeRange: Int) extends ConfigElement {
    def transform = se.scalablesolutions.akka.kernel.config.ScalaConfig.RestartStrategy(
      scheme.transform, maxNrOfRetries, withinTimeRange)
  }
  class LifeCycle(@BeanProperty val scope: Scope, @BeanProperty val shutdownTime: Int) extends ConfigElement {
    def transform = se.scalablesolutions.akka.kernel.config.ScalaConfig.LifeCycle(scope.transform, shutdownTime)
  }

  abstract class Scope extends ConfigElement {
    def transform: se.scalablesolutions.akka.kernel.config.ScalaConfig.Scope
  }
  class Permanent extends Scope {
    override def transform = se.scalablesolutions.akka.kernel.config.ScalaConfig.Permanent
  }
  class Transient extends Scope {
    override def transform = se.scalablesolutions.akka.kernel.config.ScalaConfig.Transient
  }
  class Temporary extends Scope {
    override def transform = se.scalablesolutions.akka.kernel.config.ScalaConfig.Temporary
  }

  abstract class FailOverScheme extends ConfigElement {
    def transform: se.scalablesolutions.akka.kernel.config.ScalaConfig.FailOverScheme
  }
  class AllForOne extends FailOverScheme {
    override def transform = se.scalablesolutions.akka.kernel.config.ScalaConfig.AllForOne
  }
  class OneForOne extends FailOverScheme {
    override def transform = se.scalablesolutions.akka.kernel.config.ScalaConfig.OneForOne
  }

  abstract class Server extends ConfigElement
  class Component(@BeanProperty val intf: Class[_],
                  @BeanProperty val target: Class[_],
                  @BeanProperty val lifeCycle: LifeCycle,
                  @BeanProperty val timeout: Int) extends Server {
    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Int) =
      this(null, target, lifeCycle, timeout)
    def transform = se.scalablesolutions.akka.kernel.config.ScalaConfig.Component(
      intf, target, lifeCycle.transform, timeout)
    def newWorker(actor: Actor) =
      se.scalablesolutions.akka.kernel.config.ScalaConfig.Worker(actor, lifeCycle.transform)
  }
  
}