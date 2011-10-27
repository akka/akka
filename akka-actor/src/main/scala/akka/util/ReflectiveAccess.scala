/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import akka.dispatch.Envelope
import akka.config.ModuleNotAvailableException
import akka.actor._
import DeploymentConfig.ReplicationScheme
import akka.config.ModuleNotAvailableException
import akka.event.Logging.Debug
import akka.cluster.ClusterNode
import akka.remote.{ RemoteSupport, RemoteService }
import akka.routing.{ RoutedProps, Router }
import java.net.InetSocketAddress
import akka.AkkaApplication

object ReflectiveAccess {

  val loader = getClass.getClassLoader
  val emptyParams: Array[Class[_]] = Array()
  val emptyArguments: Array[AnyRef] = Array()

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
    case e: Exception ⇒
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

/**
 * Helper class for reflective access to different modules in order to allow optional loading of modules.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ReflectiveAccess(val app: AkkaApplication) {

  import ReflectiveAccess._

  def providerClass: Class[_] = {
    getClassFor(app.AkkaConfig.ProviderClass) match {
      case Left(e)  ⇒ throw e
      case Right(b) ⇒ b
    }
  }

  def createProvider: ActorRefProvider = {
    val params: Array[Class[_]] = Array(classOf[AkkaApplication])
    val args: Array[AnyRef] = Array(app)

    createInstance[ActorRefProvider](providerClass, params, args) match {
      case Right(p) ⇒ p
      case Left(e)  ⇒ throw e
    }
  }

  /**
   * Reflective access to the Cluster module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object ClusterModule {
    lazy val isEnabled = app.AkkaConfig.ClusterEnabled //&& clusterInstance.isDefined

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
        app.mainbus.publish(Debug(this, e.toString))
        throw e
      }
    }

    lazy val clusterInstance: Option[Cluster] = getObjectFor("akka.cluster.Cluster$") match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        app.mainbus.publish(Debug(this, exception.toString))
        None
    }

    lazy val clusterDeployerInstance: Option[ActorDeployer] = getObjectFor("akka.cluster.ClusterDeployer$") match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        app.mainbus.publish(Debug(this, exception.toString))
        None
    }

    lazy val transactionLogInstance: Option[TransactionLogObject] = getObjectFor("akka.cluster.TransactionLog$") match {
      case Right(value) ⇒ Some(value)
      case Left(exception) ⇒
        app.mainbus.publish(Debug(this, exception.toString))
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
      def enqueue(message: Envelope)
      def dequeue: Envelope
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
      def recordEntry(messageHandle: Envelope, actorRef: LocalActorRef)
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
}
