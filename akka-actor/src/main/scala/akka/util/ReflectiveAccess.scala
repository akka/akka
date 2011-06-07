/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import akka.dispatch.{ Future, Promise, MessageInvocation }
import akka.config.{ Config, ModuleNotAvailableException }
import akka.remoteinterface.RemoteSupport
import akka.actor._
import DeploymentConfig.Deploy
import akka.event.EventHandler
import akka.serialization.Format
import akka.cluster.ClusterNode

import java.net.InetSocketAddress

/**
 * Helper class for reflective access to different modules in order to allow optional loading of modules.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ReflectiveAccess {

  val loader = getClass.getClassLoader

  /**
   * Reflective access to the Cluster module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object ClusterModule {
    lazy val isEnabled = clusterInstance.isDefined

    def ensureEnabled() {
      if (!isEnabled) {
        val e = new ModuleNotAvailableException(
          "Can't load the cluster module, make sure that akka-cluster.jar is on the classpath")
        EventHandler.debug(this, e.toString)
        throw e
      }
    }

    lazy val clusterInstance: Option[Cluster] = getObjectFor("akka.cluster.Cluster$") match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        EventHandler.debug(this, exception.toString)
        None
    }

    lazy val clusterDeployerInstance: Option[ClusterDeployer] = getObjectFor("akka.cluster.ClusterDeployer$") match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        EventHandler.debug(this, exception.toString)
        None
    }

    lazy val serializerClass: Option[Class[_]] = getClassFor("akka.serialization.Serializer") match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        EventHandler.debug(this, exception.toString)
        None
    }

    lazy val node: ClusterNode = {
      ensureEnabled()
      clusterInstance.get.node
    }

    lazy val clusterDeployer: ClusterDeployer = {
      ensureEnabled()
      clusterDeployerInstance.get
    }

    type ClusterDeployer = {
      def init(deployments: List[Deploy])
      def shutdown()
      def deploy(deployment: Deploy)
      def undeploy(deployment: Deploy)
      def undeployAll()
      def lookupDeploymentFor(address: String): Option[Deploy]
    }

    type Cluster = {
      def node: ClusterNode
    }

    type Mailbox = {
      def enqueue(message: MessageInvocation)
      def dequeue: MessageInvocation
    }

    type Serializer = {
      def toBinary(obj: AnyRef): Array[Byte]
      def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef
    }
  }

  /**
   * Reflective access to the RemoteClient module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object RemoteModule {
    val TRANSPORT = Config.config.getString("akka.remote.layer", "akka.remote.netty.NettyRemoteSupport")

    val configDefaultAddress = new InetSocketAddress(Config.hostname, Config.remoteServerPort)

    lazy val isEnabled = remoteSupportClass.isDefined

    def ensureEnabled() = {
      if (!isEnabled) {
        val e = new ModuleNotAvailableException("Can't load the remoting module, make sure that akka-remote.jar is on the classpath")
        EventHandler.debug(this, e.toString)
        throw e
      }
    }

    val remoteSupportClass = getClassFor[RemoteSupport](TRANSPORT) match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        EventHandler.debug(this, exception.toString)
        None
    }

    protected[akka] val defaultRemoteSupport: Option[() ⇒ RemoteSupport] =
      remoteSupportClass map { remoteClass ⇒
        () ⇒ createInstance[RemoteSupport](
          remoteClass,
          Array[Class[_]](),
          Array[AnyRef]()) match {
            case Right(value) ⇒ value
            case Left(exception) ⇒
              val e = new ModuleNotAvailableException(
                "Can't instantiate [%s] - make sure that akka-remote.jar is on the classpath".format(remoteClass.getName), exception)
              EventHandler.debug(this, e.toString)
              throw e
          }
      }
  }

  val noParams = Array[Class[_]]()
  val noArgs = Array[AnyRef]()

  def createInstance[T](clazz: Class[_],
                        params: Array[Class[_]],
                        args: Array[AnyRef]): Either[Exception, T] = try {
    assert(clazz ne null)
    assert(params ne null)
    assert(args ne null)
    val ctor = clazz.getDeclaredConstructor(params: _*)
    ctor.setAccessible(true)
    Right(ctor.newInstance(args: _*).asInstanceOf[T])
  } catch {
    case e: java.lang.reflect.InvocationTargetException ⇒
      EventHandler.debug(this, e.getCause.toString)
      Left(e)
    case e: Exception ⇒
      EventHandler.debug(this, e.toString)
      Left(e)
  }

  def createInstance[T](fqn: String,
                        params: Array[Class[_]],
                        args: Array[AnyRef],
                        classloader: ClassLoader = loader): Either[Exception, T] = try {
    assert(params ne null)
    assert(args ne null)
    getClassFor(fqn, classloader) match {
      case Right(value) ⇒
        val ctor = value.getDeclaredConstructor(params: _*)
        ctor.setAccessible(true)
        Right(ctor.newInstance(args: _*).asInstanceOf[T])
      case Left(exception) ⇒ Left(exception) //We could just cast this to Either[Exception, T] but it's ugly
    }
  } catch {
    case e: Exception ⇒
      Left(e)
  }

  //Obtains a reference to fqn.MODULE$
  def getObjectFor[T](fqn: String, classloader: ClassLoader = loader): Either[Exception, T] = try {
    getClassFor(fqn, classloader) match {
      case Right(value) ⇒
        val instance = value.getDeclaredField("MODULE$")
        instance.setAccessible(true)
        val obj = instance.get(null)
        if (obj eq null) Left(new NullPointerException) else Right(obj.asInstanceOf[T])
      case Left(exception) ⇒ Left(exception) //We could just cast this to Either[Exception, T] but it's ugly
    }
  } catch {
    case e: Exception ⇒
      Left(e)
  }

  def getClassFor[T](fqn: String, classloader: ClassLoader = loader): Either[Exception, Class[T]] = try {
    assert(fqn ne null)

    // First, use the specified CL
    val first = try {
      Right(classloader.loadClass(fqn).asInstanceOf[Class[T]])
    } catch {
      case c: ClassNotFoundException ⇒ Left(c)
    }

    if (first.isRight) first
    else {
      // Second option is to use the ContextClassLoader
      val second = try {
        Right(Thread.currentThread.getContextClassLoader.loadClass(fqn).asInstanceOf[Class[T]])
      } catch {
        case c: ClassNotFoundException ⇒ Left(c)
      }

      if (second.isRight) second
      else {
        val third = try {
          if (classloader ne loader) Right(loader.loadClass(fqn).asInstanceOf[Class[T]]) else Left(null) //Horrid
        } catch {
          case c: ClassNotFoundException ⇒ Left(c)
        }

        if (third.isRight) third
        else {
          try {
            Right(Class.forName(fqn).asInstanceOf[Class[T]]) // Last option is Class.forName
          } catch {
            case c: ClassNotFoundException ⇒ Left(c)
          }
        }
      }
    }
  } catch {
    case e: Exception ⇒ Left(e)
  }
}
