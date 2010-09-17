/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import se.scalablesolutions.akka.actor.{ActorRef, IllegalActorStateException, ActorType}
import se.scalablesolutions.akka.dispatch.{Future, CompletableFuture}
import se.scalablesolutions.akka.config.{Config, ModuleNotAvailableException}

import java.net.InetSocketAddress
import se.scalablesolutions.akka.stm.Transaction
import se.scalablesolutions.akka.AkkaException

/**
 * Helper class for reflective access to different modules in order to allow optional loading of modules.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ReflectiveAccess {

  val loader = getClass.getClassLoader

  lazy val isRemotingEnabled   = RemoteClientModule.isRemotingEnabled
  lazy val isTypedActorEnabled = TypedActorModule.isTypedActorEnabled
  lazy val isJtaEnabled        = JtaModule.isJtaEnabled

  def ensureRemotingEnabled   = RemoteClientModule.ensureRemotingEnabled
  def ensureTypedActorEnabled = TypedActorModule.ensureTypedActorEnabled
  def ensureJtaEnabled        = JtaModule.ensureJtaEnabled

  /**
   * Reflective access to the RemoteClient module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object RemoteClientModule {

    type RemoteClient = {
      def send[T](
        message: Any,
        senderOption: Option[ActorRef],
        senderFuture: Option[CompletableFuture[_]],
        remoteAddress: InetSocketAddress,
        timeout: Long,
        isOneWay: Boolean,
        actorRef: ActorRef,
        typedActorInfo: Option[Tuple2[String, String]],
        actorType: ActorType): Option[CompletableFuture[T]]
      def registerSupervisorForActor(actorRef: ActorRef)
    }

    type RemoteClientObject = {
      def register(hostname: String, port: Int, uuid: String): Unit
      def unregister(hostname: String, port: Int, uuid: String): Unit
      def clientFor(address: InetSocketAddress): RemoteClient
      def clientFor(hostname: String, port: Int, loader: Option[ClassLoader]): RemoteClient
    }

    lazy val isRemotingEnabled = remoteClientObjectInstance.isDefined

    def ensureRemotingEnabled = if (!isRemotingEnabled) throw new ModuleNotAvailableException(
      "Can't load the remoting module, make sure that akka-remote.jar is on the classpath")

    val remoteClientObjectInstance: Option[RemoteClientObject] =
      getObject("se.scalablesolutions.akka.remote.RemoteClient$")

    def register(address: InetSocketAddress, uuid: String) = {
      ensureRemotingEnabled
      remoteClientObjectInstance.get.register(address.getHostName, address.getPort, uuid)
    }

    def unregister(address: InetSocketAddress, uuid: String) = {
      ensureRemotingEnabled
      remoteClientObjectInstance.get.unregister(address.getHostName, address.getPort, uuid)
    }

    def registerSupervisorForActor(remoteAddress: InetSocketAddress, actorRef: ActorRef) = {
      ensureRemotingEnabled
      val remoteClient = remoteClientObjectInstance.get.clientFor(remoteAddress)
      remoteClient.registerSupervisorForActor(actorRef)
    }

    def clientFor(hostname: String, port: Int, loader: Option[ClassLoader]): RemoteClient = {
      ensureRemotingEnabled
      remoteClientObjectInstance.get.clientFor(hostname, port, loader)
    }

    def send[T](
      message: Any,
      senderOption: Option[ActorRef],
      senderFuture: Option[CompletableFuture[_]],
      remoteAddress: InetSocketAddress,
      timeout: Long,
      isOneWay: Boolean,
      actorRef: ActorRef,
      typedActorInfo: Option[Tuple2[String, String]],
      actorType: ActorType): Option[CompletableFuture[T]] = {
      ensureRemotingEnabled
      clientFor(remoteAddress.getHostName, remoteAddress.getPort, None).send[T](
        message, senderOption, senderFuture, remoteAddress, timeout, isOneWay, actorRef, typedActorInfo, actorType)
    }
  }

  /**
   * Reflective access to the RemoteServer module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object RemoteServerModule {
    val HOSTNAME = Config.config.getString("akka.remote.server.hostname", "localhost")
    val PORT     = Config.config.getInt("akka.remote.server.port", 9999)

    type RemoteServerObject = {
      def registerActor(address: InetSocketAddress, uuid: String, actor: ActorRef): Unit
      def registerTypedActor(address: InetSocketAddress, name: String, typedActor: AnyRef): Unit
    }

    type RemoteNodeObject = {
      def unregister(actorRef: ActorRef): Unit
    }

    val remoteServerObjectInstance: Option[RemoteServerObject] =
      getObject("se.scalablesolutions.akka.remote.RemoteServer$")

    val remoteNodeObjectInstance: Option[RemoteNodeObject] =
      getObject("se.scalablesolutions.akka.remote.RemoteNode$")

    def registerActor(address: InetSocketAddress, uuid: String, actorRef: ActorRef) = {
      ensureRemotingEnabled
      remoteServerObjectInstance.get.registerActor(address, uuid, actorRef)
    }

    def registerTypedActor(address: InetSocketAddress, implementationClassName: String, proxy: AnyRef) = {
      ensureRemotingEnabled
      remoteServerObjectInstance.get.registerTypedActor(address, implementationClassName, proxy)
    }

    def unregister(actorRef: ActorRef) = {
      ensureRemotingEnabled
      remoteNodeObjectInstance.get.unregister(actorRef)
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
    }

    lazy val isTypedActorEnabled = typedActorObjectInstance.isDefined

    def ensureTypedActorEnabled = if (!isTypedActorEnabled) throw new ModuleNotAvailableException(
      "Can't load the typed actor module, make sure that akka-typed-actor.jar is on the classpath")

    val typedActorObjectInstance: Option[TypedActorObject] =
      getObject("se.scalablesolutions.akka.actor.TypedActor$")

    def resolveFutureIfMessageIsJoinPoint(message: Any, future: Future[_]): Boolean = {
      ensureTypedActorEnabled
      if (typedActorObjectInstance.get.isJoinPointAndOneWay(message)) {
        future.asInstanceOf[CompletableFuture[Option[_]]].completeWithResult(None)
      }
      typedActorObjectInstance.get.isJoinPoint(message)
    }
  }

  object JtaModule {

    type TransactionContainerObject = {
      def apply(): TransactionContainer
    }

    type TransactionContainer = {
      def beginWithStmSynchronization(transaction: Transaction): Unit
      def commit: Unit
      def rollback: Unit
    }

    lazy val isJtaEnabled = transactionContainerObjectInstance.isDefined

    def ensureJtaEnabled = if (!isJtaEnabled) throw new ModuleNotAvailableException(
      "Can't load the typed actor module, make sure that akka-jta.jar is on the classpath")

    val transactionContainerObjectInstance: Option[TransactionContainerObject] =
      getObject("se.scalablesolutions.akka.actor.TransactionContainer$")

    def createTransactionContainer: TransactionContainer = {
      ensureJtaEnabled
      transactionContainerObjectInstance.get.apply.asInstanceOf[TransactionContainer]
    }
  }

  val noParams = Array[Class[_]]()
  val noArgs   = Array[AnyRef]()

  def createInstance[T](clazz: Class[_],
                        params: Array[Class[_]],
                        args: Array[AnyRef]): Option[T] = try {
    val ctor = clazz.getDeclaredConstructor(params: _*)
    ctor.setAccessible(true)
    Some(ctor.newInstance(args: _*).asInstanceOf[T])
  } catch {
    case e: Exception => None
  }

  def createInstance[T](fqn: String,
                        params: Array[Class[_]],
                        args: Array[AnyRef],
                        classloader: ClassLoader = loader): Option[T] = try {
    val clazz = classloader.loadClass(fqn)
    val ctor = clazz.getDeclaredConstructor(params: _*)
    ctor.setAccessible(true)
    Some(ctor.newInstance(args: _*).asInstanceOf[T])
  } catch {
    case e: Exception => None
  }

  def getObject[T](fqn: String, classloader: ClassLoader = loader): Option[T] = try {//Obtains a reference to $MODULE$
    val clazz = classloader.loadClass(fqn)
    val instance = clazz.getDeclaredField("MODULE$")
    instance.setAccessible(true)
    Option(instance.get(null).asInstanceOf[T])
  } catch {
    case e: Exception => None
  }
}
