/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import akka.dispatch.{Future, CompletableFuture, MessageInvocation}
import akka.config.{Config, ModuleNotAvailableException}
import akka.AkkaException

import java.net.InetSocketAddress
import akka.remoteinterface.RemoteSupport
import akka.actor._

/**
 * Helper class for reflective access to different modules in order to allow optional loading of modules.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ReflectiveAccess extends Logging {

  val loader = getClass.getClassLoader

  lazy val isRemotingEnabled   = Remote.isEnabled
  lazy val isTypedActorEnabled = TypedActorModule.isEnabled

  def ensureRemotingEnabled   = Remote.ensureEnabled
  def ensureTypedActorEnabled = TypedActorModule.ensureEnabled

  /**
   * Reflective access to the RemoteClient module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Remote {
    val TRANSPORT = Config.config.getString("akka.remote.transport","akka.remote.NettyRemoteSupport")
    val HOSTNAME  = Config.config.getString("akka.remote.server.hostname", "localhost")
    val PORT      = Config.config.getInt("akka.remote.server.port", 2552)

    lazy val isEnabled = remoteSupportClass.isDefined

    def ensureEnabled = if (!isEnabled) throw new ModuleNotAvailableException(
      "Can't load the remoting module, make sure that akka-remote.jar is on the classpath")

    //TODO: REVISIT: Make class configurable
    val remoteSupportClass: Option[Class[_ <: RemoteSupport]] = getClassFor(TRANSPORT)

    protected[akka] val defaultRemoteSupport: Option[() => RemoteSupport] = remoteSupportClass map {
      remoteClass => () => createInstance[RemoteSupport](remoteClass,Array[Class[_]](),Array[AnyRef]()).
                           getOrElse(throw new ModuleNotAvailableException("Can't instantiate "+
                                                        remoteClass.getName+
                                                        ", make sure that akka-remote.jar is on the classpath"))
    }
  }

  /**
   * Reflective access to the TypedActors module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object TypedActorModule {

    type TypedActorObject = {
      def isJoinPoint(message: Any): Boolean
      def isJoinPointAndOneWay(message: Any): Boolean
      def actorFor(proxy: AnyRef): Option[ActorRef]
      def proxyFor(actorRef: ActorRef): Option[AnyRef]
      def stop(anyRef: AnyRef) : Unit
    }

    lazy val isEnabled = typedActorObjectInstance.isDefined

    def ensureEnabled = if (!isTypedActorEnabled) throw new ModuleNotAvailableException(
      "Can't load the typed actor module, make sure that akka-typed-actor.jar is on the classpath")

    val typedActorObjectInstance: Option[TypedActorObject] =
      getObjectFor("akka.actor.TypedActor$")

    def resolveFutureIfMessageIsJoinPoint(message: Any, future: Future[_]): Boolean = {
      ensureEnabled
      if (typedActorObjectInstance.get.isJoinPointAndOneWay(message)) {
        future.asInstanceOf[CompletableFuture[Option[_]]].completeWithResult(None)
      }
      typedActorObjectInstance.get.isJoinPoint(message)
    }
  }

  val noParams = Array[Class[_]]()
  val noArgs   = Array[AnyRef]()

  def createInstance[T](clazz: Class[_],
                        params: Array[Class[_]],
                        args: Array[AnyRef]): Option[T] = try {
    assert(clazz ne null)
    assert(params ne null)
    assert(args ne null)
    val ctor = clazz.getDeclaredConstructor(params: _*)
    ctor.setAccessible(true)
    Some(ctor.newInstance(args: _*).asInstanceOf[T])
  } catch {
    case e =>
      log.slf4j.warn("Could not instantiate class [{}] due to [{}]", clazz.getName, e.getCause)
      e.printStackTrace
      None
  }

  def createInstance[T](fqn: String,
                        params: Array[Class[_]],
                        args: Array[AnyRef],
                        classloader: ClassLoader = loader): Option[T] = try {
    assert(fqn ne null)
    assert(params ne null)
    assert(args ne null)
    val clazz = classloader.loadClass(fqn)
    val ctor = clazz.getDeclaredConstructor(params: _*)
    ctor.setAccessible(true)
    Some(ctor.newInstance(args: _*).asInstanceOf[T])
  } catch {
    case e =>
      log.slf4j.warn("Could not instantiate class [{}] due to [{}]", fqn, e.getCause)
      None
  }

  def getObjectFor[T](fqn: String, classloader: ClassLoader = loader): Option[T] = try {//Obtains a reference to $MODULE$
    assert(fqn ne null)
    val clazz = classloader.loadClass(fqn)
    val instance = clazz.getDeclaredField("MODULE$")
    instance.setAccessible(true)
    Option(instance.get(null).asInstanceOf[T])
  } catch {
    case e: ClassNotFoundException =>
      log.slf4j.debug("Could not get object [{}] due to [{}]", fqn, e)
      None
  }

  def getClassFor[T](fqn: String, classloader: ClassLoader = loader): Option[Class[T]] = try {
    assert(fqn ne null)
    Some(classloader.loadClass(fqn).asInstanceOf[Class[T]])
  } catch {
    case e => None
  }
}
