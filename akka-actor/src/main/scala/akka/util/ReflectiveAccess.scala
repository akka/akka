/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import akka.actor.{ActorRef, IllegalActorStateException, ActorType, Uuid}
import akka.dispatch.{Future, CompletableFuture, MessageInvocation}
import akka.config.{Config, ModuleNotAvailableException}
import akka.AkkaException

import java.net.InetSocketAddress

/**
 * Helper class for reflective access to different modules in order to allow optional loading of modules.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ReflectiveAccess extends Logging {

  val loader = getClass.getClassLoader

  lazy val isRemotingEnabled   = RemoteClientModule.isEnabled
  lazy val isTypedActorEnabled = TypedActorModule.isEnabled

  def ensureRemotingEnabled   = RemoteClientModule.ensureEnabled
  def ensureTypedActorEnabled = TypedActorModule.ensureEnabled

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

    lazy val isEnabled = remoteClientObjectInstance.isDefined

    def ensureEnabled = if (!isEnabled) throw new ModuleNotAvailableException(
      "Can't load the remoting module, make sure that akka-remote.jar is on the classpath")

    val remoteClientObjectInstance: Option[RemoteClientObject] =
      getObjectFor("akka.remote.RemoteClient$")

    def register(address: InetSocketAddress, uuid: Uuid) = {
      ensureEnabled
      remoteClientObjectInstance.get.register(address.getHostName, address.getPort, uuid)
    }

    def unregister(address: InetSocketAddress, uuid: Uuid) = {
      ensureEnabled
      remoteClientObjectInstance.get.unregister(address.getHostName, address.getPort, uuid)
    }

    def registerSupervisorForActor(remoteAddress: InetSocketAddress, actorRef: ActorRef) = {
      ensureEnabled
      val remoteClient = remoteClientObjectInstance.get.clientFor(remoteAddress)
      remoteClient.registerSupervisorForActor(actorRef)
    }

    def clientFor(hostname: String, port: Int, loader: Option[ClassLoader]): RemoteClient = {
      ensureEnabled
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
      actorType: ActorType,
      loader: Option[ClassLoader] = None): Option[CompletableFuture[T]] = {
      ensureEnabled
      clientFor(remoteAddress.getHostName, remoteAddress.getPort, loader).send[T](
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
    val PORT     = Config.config.getInt("akka.remote.server.port", 2552)

    type RemoteServerObject = {
      def registerActor(address: InetSocketAddress, actor: ActorRef): Unit
      def registerTypedActor(address: InetSocketAddress, name: String, typedActor: AnyRef): Unit
    }

    type RemoteNodeObject = {
      def unregister(actorRef: ActorRef): Unit
    }

    val remoteServerObjectInstance: Option[RemoteServerObject] =
      getObjectFor("akka.remote.RemoteServer$")

    val remoteNodeObjectInstance: Option[RemoteNodeObject] =
      getObjectFor("akka.remote.RemoteNode$")

    def registerActor(address: InetSocketAddress, actorRef: ActorRef) = {
      RemoteClientModule.ensureEnabled
      remoteServerObjectInstance.get.registerActor(address, actorRef)
    }

    def registerTypedActor(address: InetSocketAddress, implementationClassName: String, proxy: AnyRef) = {
      RemoteClientModule.ensureEnabled
      remoteServerObjectInstance.get.registerTypedActor(address, implementationClassName, proxy)
    }

    def unregister(actorRef: ActorRef) = {
      RemoteClientModule.ensureEnabled
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

    def createFileBasedMailbox(actorRef: ActorRef): Mailbox = createMailbox("akka.cloud.cluster.FileBasedMailbox", actorRef)

    def createZooKeeperBasedMailbox(actorRef: ActorRef): Mailbox = createMailbox("akka.cloud.cluster.ZooKeeperBasedMailbox", actorRef)

    def createBeanstalkBasedMailbox(actorRef: ActorRef): Mailbox = createMailbox("akka.cloud.cluster.BeanstalkBasedMailbox", actorRef)

    def createRedisBasedMailbox(actorRef: ActorRef): Mailbox = createMailbox("akka.cloud.cluster.RedisBasedMailbox", actorRef)

    private def createMailbox(mailboxClassname: String, actorRef: ActorRef): Mailbox = {
      ensureEnabled
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
    case e =>
      log.slf4j.warn("Could not instantiate class [{}] due to [{}]", clazz.getName, e.getCause)
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
    case e: ClassNotFoundException => {
      log.slf4j.debug("Could not get object [{}] due to [{}]", fqn, e)
      None
    }
    case ei: ExceptionInInitializerError => {
      log.error("Exception in initializer for object [%s]".format(fqn))
      log.error(ei.getCause, "Cause was:")
      throw ei
    }
  }

  def getClassFor[T](fqn: String, classloader: ClassLoader = loader): Option[Class[T]] = try {
    assert(fqn ne null)
    Some(classloader.loadClass(fqn).asInstanceOf[Class[T]])
  } catch {
    case e => None
  }
}
