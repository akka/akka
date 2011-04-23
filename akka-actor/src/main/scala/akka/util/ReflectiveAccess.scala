/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import akka.dispatch.{Future, CompletableFuture, MessageInvocation}
import akka.config.{Config, ModuleNotAvailableException}

import java.net.InetSocketAddress
import akka.remoteinterface.RemoteSupport
import akka.actor._
import akka.event.EventHandler

/**
 * Helper class for reflective access to different modules in order to allow optional loading of modules.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ReflectiveAccess {

  val loader = getClass.getClassLoader

  def isRemotingEnabled   = Remote.isEnabled
  lazy val isTypedActorEnabled = TypedActorModule.isEnabled

  def ensureRemotingEnabled   = Remote.ensureEnabled
  def ensureTypedActorEnabled = TypedActorModule.ensureEnabled

  /**
   * Reflective access to the RemoteClient module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Remote {
    val TRANSPORT = Config.config.getString("akka.remote.layer", "akka.remote.netty.NettyRemoteSupport")

    private[akka] val configDefaultAddress =
      new InetSocketAddress(Config.config.getString("akka.remote.server.hostname", "localhost"),
                            Config.config.getInt("akka.remote.server.port", 2552))

    lazy val isEnabled = remoteSupportClass.isDefined

    def ensureEnabled = if (!isEnabled) {
      val e = new ModuleNotAvailableException(
        "Can't load the remoting module, make sure that akka-remote.jar is on the classpath")
      EventHandler.debug(this, e.toString)
      throw e
    }
    val remoteSupportClass: Option[Class[_ <: RemoteSupport]] = getClassFor(TRANSPORT)

    protected[akka] val defaultRemoteSupport: Option[() => RemoteSupport] =
      remoteSupportClass map { remoteClass =>
        () => createInstance[RemoteSupport](
          remoteClass,
          Array[Class[_]](),
          Array[AnyRef]()
        ) getOrElse {
          val e = new ModuleNotAvailableException(
            "Can't instantiate [%s] - make sure that akka-remote.jar is on the classpath".format(remoteClass.getName))
          EventHandler.debug(this, e.toString)
          throw e
        }
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

  object AkkaCloudModule {

    type Mailbox = {
      def enqueue(message: MessageInvocation)
      def dequeue: MessageInvocation
    }

    type Serializer = {
      def toBinary(obj: AnyRef): Array[Byte]
      def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef
    }

    lazy val isEnabled = clusterObjectInstance.isDefined

    val clusterObjectInstance: Option[AnyRef] =
      getObjectFor("akka.cloud.cluster.Cluster$")

    val serializerClass: Option[Class[_]] =
      getClassFor("akka.serialization.Serializer")

    def ensureEnabled = if (!isEnabled) throw new ModuleNotAvailableException(
      "Feature is only available in Akka Cloud")
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
    case e: Exception =>
      EventHandler.debug(this, e.toString)
      None
  }

  def createInstance[T](fqn: String,
                        params: Array[Class[_]],
                        args: Array[AnyRef],
                        classloader: ClassLoader = loader): Option[T] = try {
    assert(params ne null)
    assert(args ne null)
    getClassFor(fqn) match {
      case Some(clazz) =>
        val ctor = clazz.getDeclaredConstructor(params: _*)
        ctor.setAccessible(true)
        Some(ctor.newInstance(args: _*).asInstanceOf[T])
      case None => None
    }
  } catch {
    case e: Exception =>
      EventHandler.debug(this, e.toString)
      None
  }

  def getObjectFor[T](fqn: String, classloader: ClassLoader = loader): Option[T] = try {//Obtains a reference to $MODULE$
    getClassFor(fqn) match {
      case Some(clazz) =>
        val instance = clazz.getDeclaredField("MODULE$")
        instance.setAccessible(true)
        Option(instance.get(null).asInstanceOf[T])
      case None => None
    }
  } catch {
    case e: ExceptionInInitializerError =>
      EventHandler.debug(this, e.toString)
      throw e
  }

  def getClassFor[T](fqn: String, classloader: ClassLoader = loader): Option[Class[T]] = {
    assert(fqn ne null)

    // First, use the specified CL
    val first = try {
      Option(classloader.loadClass(fqn).asInstanceOf[Class[T]])
    } catch {
      case c: ClassNotFoundException => None
    }

    if (first.isDefined) first
    else {
      // Second option is to use the ContextClassLoader
      val second = try {
        Option(Thread.currentThread.getContextClassLoader.loadClass(fqn).asInstanceOf[Class[T]])
      } catch {
        case c: ClassNotFoundException => None
      }

      if (second.isDefined) second
      else {
        val third = try {
           // Don't try to use "loader" if we got the default "classloader" parameter
           if (classloader ne loader) Option(loader.loadClass(fqn).asInstanceOf[Class[T]])
          else None
        } catch {
          case c: ClassNotFoundException => None
        }

        if (third.isDefined) third
        else {
          // Last option is Class.forName
          try {
            Option(Class.forName(fqn).asInstanceOf[Class[T]])
          } catch {
            case c: ClassNotFoundException => None
          }
        }
      }
    }
  }
}
