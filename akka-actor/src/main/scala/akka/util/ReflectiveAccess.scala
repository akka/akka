/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import akka.actor._
import DeploymentConfig.ReplicationScheme
import akka.dispatch.MessageInvocation
import akka.config.{ Config, ModuleNotAvailableException }
import akka.event.EventHandler
import akka.cluster.ClusterNode
import akka.remote.{ RemoteSupport, RemoteService }
import akka.routing.{ RoutedProps, Router }

import java.net.InetSocketAddress

/**
 * Helper class for reflective access to different modules in order to allow optional loading of modules.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ReflectiveAccess {

  val loader = getClass.getClassLoader
  val emptyParams: Array[Class[_]] = Array()
  val emptyArguments: Array[AnyRef] = Array()

  /**
   * Reflective access to the Cluster module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object ClusterModule {
    lazy val isEnabled = Config.isClusterEnabled //&& clusterInstance.isDefined

    lazy val clusterRefClass: Class[_] = getClassFor("akka.cluster.ClusterActorRef") match {
      case Left(e)  ⇒ throw e
      case Right(b) ⇒ b
    }

    def newClusteredActorRef(props: RoutedProps): ActorRef = {
      val params: Array[Class[_]] = Array(classOf[RoutedProps])
      val args: Array[AnyRef] = Array(props)

      createInstance(clusterRefClass, params, args) match {
        case Left(e)  ⇒ throw e
        case Right(b) ⇒ b.asInstanceOf[ActorRef]
      }
    }

    def ensureEnabled() {
      if (!isEnabled) {
        val e = new ModuleNotAvailableException(
          "Can't load the cluster module, make sure it is enabled in the config ('akka.enabled-modules = [\"cluster\"])' and that akka-cluster.jar is on the classpath")
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

    lazy val clusterDeployerInstance: Option[ActorDeployer] = getObjectFor("akka.cluster.ClusterDeployer$") match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        EventHandler.debug(this, exception.toString)
        None
    }

    lazy val transactionLogInstance: Option[TransactionLogObject] = getObjectFor("akka.cluster.TransactionLog$") match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        EventHandler.debug(this, exception.toString)
        None
    }

    lazy val node: ClusterNode = {
      ensureEnabled()
      clusterInstance.get.node
    }

    lazy val clusterDeployer: ActorDeployer = {
      ensureEnabled()
      clusterDeployerInstance.get
    }

    lazy val transactionLog: TransactionLogObject = {
      ensureEnabled()
      transactionLogInstance.get
    }

    type Cluster = {
      def node: ClusterNode
    }

    type Mailbox = {
      def enqueue(message: MessageInvocation)
      def dequeue: MessageInvocation
    }

    type TransactionLogObject = {
      def newLogFor(
        id: String,
        isAsync: Boolean,
        replicationScheme: ReplicationScheme): TransactionLog

      def logFor(
        id: String,
        isAsync: Boolean,
        replicationScheme: ReplicationScheme): TransactionLog

      def shutdown()
    }

    type TransactionLog = {
      def recordEntry(messageHandle: MessageInvocation, actorRef: LocalActorRef)
      def recordEntry(entry: Array[Byte])
      def recordSnapshot(snapshot: Array[Byte])
      def entries: Vector[Array[Byte]]
      def entriesFromLatestSnapshot: Tuple2[Array[Byte], Vector[Array[Byte]]]
      def entriesInRange(from: Long, to: Long): Vector[Array[Byte]]
      def latestSnapshotAndSubsequentEntries: (Option[Array[Byte]], Vector[Array[Byte]])
      def latestEntryId: Long
      def latestSnapshotId: Long
      def delete()
      def close()
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
        val e = new ModuleNotAvailableException(
          "Can't load the remote module, make sure it is enabled in the config ('akka.enabled-modules = [\"remote\"])' and that akka-remote.jar is on the classpath")
        EventHandler.debug(this, e.toString)
        throw e
      }
    }

    lazy val remoteInstance: Option[RemoteService] = getObjectFor("akka.remote.Remote$") match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        EventHandler.debug(this, exception.toString)
        None
    }

    lazy val remoteService: RemoteService = {
      ensureEnabled()
      remoteInstance.get
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
