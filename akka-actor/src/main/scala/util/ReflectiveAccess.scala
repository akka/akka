/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import se.scalablesolutions.akka.actor.{ActorRef, IllegalActorStateException, ActorType, Uuid}
import se.scalablesolutions.akka.dispatch.{Future, CompletableFuture, MessageInvocation}
import se.scalablesolutions.akka.config.{Config, ModuleNotAvailableException}
import se.scalablesolutions.akka.stm.Transaction
import se.scalablesolutions.akka.AkkaException

import java.net.InetSocketAddress

/**
 * Helper class for reflective access to different modules in order to allow optional loading of modules.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ReflectiveAccess extends Logging {

  val loader = getClass.getClassLoader

  lazy val isRemotingEnabled   = RemoteClientModule.isRemotingEnabled
  lazy val isTypedActorEnabled = TypedActorModule.isTypedActorEnabled
  lazy val isJtaEnabled        = JtaModule.isJtaEnabled
  lazy val isEnterpriseEnabled = EnterpriseModule.isEnterpriseEnabled

  def ensureRemotingEnabled   = RemoteClientModule.ensureRemotingEnabled
  def ensureTypedActorEnabled = TypedActorModule.ensureTypedActorEnabled
  def ensureJtaEnabled        = JtaModule.ensureJtaEnabled
  def ensureEnterpriseEnabled = EnterpriseModule.ensureEnterpriseEnabled

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
      def register(hostname: String, port: Int, uuid: Uuid): Unit
      def unregister(hostname: String, port: Int, uuid: Uuid): Unit
      def clientFor(address: InetSocketAddress): RemoteClient
      def clientFor(hostname: String, port: Int, loader: Option[ClassLoader]): RemoteClient
    }

    lazy val isRemotingEnabled = remoteClientObjectInstance.isDefined

    def ensureRemotingEnabled = if (!isRemotingEnabled) throw new ModuleNotAvailableException(
      "Can't load the remoting module, make sure that akka-remote.jar is on the classpath")

    val remoteClientObjectInstance: Option[RemoteClientObject] =
      getObjectFor("se.scalablesolutions.akka.remote.RemoteClient$")

    def register(address: InetSocketAddress, uuid: Uuid) = {
      ensureRemotingEnabled
      remoteClientObjectInstance.get.register(address.getHostName, address.getPort, uuid)
    }

    def unregister(address: InetSocketAddress, uuid: Uuid) = {
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
      def registerActor(address: InetSocketAddress, uuid: Uuid, actor: ActorRef): Unit
      def registerTypedActor(address: InetSocketAddress, name: String, typedActor: AnyRef): Unit
    }

    type RemoteNodeObject = {
      def unregister(actorRef: ActorRef): Unit
    }

    val remoteServerObjectInstance: Option[RemoteServerObject] =
      getObjectFor("se.scalablesolutions.akka.remote.RemoteServer$")

    val remoteNodeObjectInstance: Option[RemoteNodeObject] =
      getObjectFor("se.scalablesolutions.akka.remote.RemoteNode$")

    def registerActor(address: InetSocketAddress, uuid: Uuid, actorRef: ActorRef) = {
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
      def actorFor(proxy: AnyRef): Option[ActorRef]
      def proxyFor(actorRef: ActorRef): Option[AnyRef]
      def stop(anyRef: AnyRef) : Unit
    }

    lazy val isTypedActorEnabled = typedActorObjectInstance.isDefined

    def ensureTypedActorEnabled = if (!isTypedActorEnabled) throw new ModuleNotAvailableException(
      "Can't load the typed actor module, make sure that akka-typed-actor.jar is on the classpath")

    val typedActorObjectInstance: Option[TypedActorObject] =
      getObjectFor("se.scalablesolutions.akka.actor.TypedActor$")

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
      getObjectFor("se.scalablesolutions.akka.actor.TransactionContainer$")

    def createTransactionContainer: TransactionContainer = {
      ensureJtaEnabled
      transactionContainerObjectInstance.get.apply.asInstanceOf[TransactionContainer]
    }
  }

  object EnterpriseModule {

    type Mailbox = {
      def enqueue(message: MessageInvocation)
      def dequeue: MessageInvocation
    }
    
    type Serializer = {
      def toBinary(obj: AnyRef): Array[Byte]
      def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef      
    }

    lazy val isEnterpriseEnabled = clusterObjectInstance.isDefined

    val clusterObjectInstance: Option[AnyRef] =
      getObjectFor("se.scalablesolutions.akka.cluster.Cluster$")

    val serializerClass: Option[Class[_]] = 
      getClassFor("se.scalablesolutions.akka.serialization.Serializer")

    def ensureEnterpriseEnabled = if (!isEnterpriseEnabled) throw new ModuleNotAvailableException(
      "Feature is only available in Akka Enterprise")

    def createFileBasedMailbox(actorRef: ActorRef): Mailbox = createMailbox("se.scalablesolutions.akka.cluster.FileBasedMailbox", actorRef)

    def createZooKeeperBasedMailbox(actorRef: ActorRef): Mailbox = createMailbox("se.scalablesolutions.akka.cluster.ZooKeeperBasedMailbox", actorRef)

    def createBeanstalkBasedMailbox(actorRef: ActorRef): Mailbox = createMailbox("se.scalablesolutions.akka.cluster.BeanstalkBasedMailbox", actorRef)

    def createRedisBasedMailbox(actorRef: ActorRef): Mailbox = createMailbox("se.scalablesolutions.akka.cluster.RedisBasedMailbox", actorRef)

    private def createMailbox(mailboxClassname: String, actorRef: ActorRef): Mailbox = {
      ensureEnterpriseEnabled
      createInstance(
        mailboxClassname,
        Array(classOf[ActorRef]),
        Array(actorRef).asInstanceOf[Array[AnyRef]],
        loader)
        .getOrElse(throw new IllegalActorStateException("Could not create durable mailbox [" + mailboxClassname + "] for actor [" + actorRef + "]"))
        .asInstanceOf[Mailbox]
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
    case e: Exception =>
      log.debug(e, "Could not instantiate class [%s] due to [%s]", clazz.getName, e.getMessage)
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
    case e: Exception =>
      log.debug(e, "Could not instantiate class [%s] due to [%s]", fqn, e.getMessage)
      None
  }

  def getObjectFor[T](fqn: String, classloader: ClassLoader = loader): Option[T] = try {//Obtains a reference to $MODULE$
    assert(fqn ne null)
    val clazz = classloader.loadClass(fqn)
    val instance = clazz.getDeclaredField("MODULE$")
    instance.setAccessible(true)
    Option(instance.get(null).asInstanceOf[T])
  } catch {
    case e: Exception =>
      log.debug(e, "Could not get object [%s] due to [%s]", fqn, e.getMessage)
      None
  }

  def getClassFor[T](fqn: String, classloader: ClassLoader = loader): Option[Class[T]] = try {
    assert(fqn ne null)
    Some(classloader.loadClass(fqn).asInstanceOf[Class[T]])
  } catch {
    case e: Exception => None
  }
}
