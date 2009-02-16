/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.api

import com.scalablesolutions.akka.kernel.{ActiveObject, ActiveObjectProxy}

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
class Component(@BeanProperty val proxy: ActiveObjectProxy, @BeanProperty val lifeCycle: LifeCycle) extends Server {
  def transform = scala.actors.behavior.Worker(proxy.server, lifeCycle.transform)
}


object Configuration {
  import com.google.inject.{AbstractModule, CreationException, Guice, Injector, Provides, Singleton}
  import com.google.inject.spi.CloseFailedException
  import com.google.inject.jsr250.{ResourceProviderFactory}
  import javax.annotation.Resource
  import javax.naming.Context

  def supervise(restartStrategy: RestartStrategy, components: JList[Component]): Supervisor = {
    val componentList = components.toArray.toList.asInstanceOf[List[Component]]

    val injector = Guice.createInjector(new AbstractModule {
      protected def configure = {
        bind(classOf[ResourceProviderFactory[_]])
        componentList.foreach(c => bind(c.getClass).in(classOf[Singleton]))
      }

      // @Provides
      // def createJndiContext: Context = {
      //   val answer = new JndiContext
      //   answer.bind("foo", new AnotherBean("Foo"))
      //   answer.bind("xyz", new AnotherBean("XYZ"))
      //   answer
      // }
    })

    val injectedComponents = componentList.map(c => injector.getInstance(c.getClass))

    // TODO: swap 'target' in proxy before running supervise
    
    ActiveObject.supervise(
      restartStrategy.transform, 
      componentList.map(c => scala.actors.behavior.Worker(c.proxy.server, c.lifeCycle.transform)))

  }
}


