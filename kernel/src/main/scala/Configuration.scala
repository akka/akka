/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.api

import com.scalablesolutions.akka.kernel.{ActiveObject, ActiveObjectProxy}
import google.inject.{AbstractModule}

import java.util.{List => JList, ArrayList}

import scala.actors.behavior._
import scala.reflect.BeanProperty

// ============================================
// Java version of the configuration API

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
//class SupervisorConfig(@BeanProperty val restartStrategy: RestartStrategy, @BeanProperty val servers: JList[Server]) extends Server {
//  def transform = scala.actors.behavior.SupervisorConfig(restartStrategy.transform, servers.toArray.toList.asInstanceOf[List[Server]].map(_.transform))
//}
class Component(@BeanProperty val intf: Class[_],
                @BeanProperty val target: Class[_],
                @BeanProperty val lifeCycle: LifeCycle,
                @BeanProperty val timeout: Int) extends Server {
  def newWorker(proxy: ActiveObjectProxy) = scala.actors.behavior.Worker(proxy.server, lifeCycle.transform)
}


// ============================================

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
//object Configuration {
//  import com.google.inject.{Module, AbstractModule, CreationException, Guice, Injector, Provides, Singleton, Binder}
//  import com.google.inject.jsr250.{ResourceProviderFactory}
//
//  private val modules = new ArrayList[Module]
//
//  def addModule(module: Module) = modules.add(module)
//
//  def supervise(restartStrategy: RestartStrategy, components: Array[Component]): Supervisor = {
//    val componentList = components.toList.asInstanceOf[List[Component]]
//
//    object defaultModule extends AbstractModule {
//       protected def configure {
//         bind(classOf[ResourceProviderFactory[_]])
//         //componentList.foreach(c => bind(c.proxy.intf.asInstanceOf[Class[_]]).to(c.proxy.target.getClass.asInstanceOf[Class[_]]).in(classOf[Singleton]))
//       }
//
//      // @Provides
//         // def createJndiContext: Context = {
//         //   val answer = new JndiContext
//         //   answer.bind("foo", new AnotherBean("Foo"))
//         //   answer.bind("xyz", new AnotherBean("XYZ"))
//         //   answer
//         // }
//       }
//    modules.add(defaultModule)
//    val injector = Guice.createInjector(modules)
//
//    // swap 'target' in proxy before running supervise
//  //  componentList.foreach(c => c.proxy.target = injector.getInstance(c.proxy.targetClass))
//
//    ActiveObject.supervise(
//      restartStrategy.transform,
//      componentList.map(c => scala.actors.behavior.Worker(c.proxy.server, c.lifeCycle.transform)))
//
//  }
//}


