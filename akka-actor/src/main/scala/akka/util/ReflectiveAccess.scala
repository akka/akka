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
import akka.actor.DeploymentConfig.Deploy
import akka.serialization.Format

/**
 * Helper class for reflective access to different modules in order to allow optional loading of modules.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ReflectiveAccess {

  val loader = getClass.getClassLoader

  lazy val isRemotingEnabled: Boolean   = RemoteModule.isEnabled
  lazy val isTypedActorEnabled: Boolean = TypedActorModule.isEnabled
  lazy val isClusterEnabled: Boolean    = ClusterModule.isEnabled

  def ensureClusterEnabled()     { ClusterModule.ensureEnabled() }
  def ensureRemotingEnabled()    { RemoteModule.ensureEnabled() }
  def ensureTypedActorEnabled()  { TypedActorModule.ensureEnabled() }

  /**
   * Reflective access to the Cluster module.
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object ClusterModule {
    import java.net.InetAddress
    import com.eaio.uuid.UUID
    import akka.cluster.NodeAddress
    import Config.{config, TIME_UNIT}

    val name                        = config.getString("akka.cluster.name",                                   "default")
    val zooKeeperServers            = config.getString("akka.cluster.zookeeper-server-addresses",             "localhost:2181")
    val remoteServerPort            = config.getInt("akka.cluster.remote-server-port",                        2552)
    val sessionTimeout              = Duration(config.getInt("akka.cluster.session-timeout",                  60), TIME_UNIT).toMillis.toInt
    val connectionTimeout           = Duration(config.getInt("akka.cluster.connection-timeout",               60), TIME_UNIT).toMillis.toInt
    val maxTimeToWaitUntilConnected = Duration(config.getInt("akka.cluster.max-time-to-wait-until-connected", 30), TIME_UNIT).toMillis.toInt
    val shouldCompressData          = config.getBool("akka.cluster.use-compression",                          false)

    // FIXME allow setting hostname from command line or node local property file
    val hostname = InetAddress.getLocalHost.getHostName

    // FIXME allow setting nodeName from command line or node local property file
    val nodeName = new UUID().toString

    val nodeAddress = NodeAddress(name, nodeName, hostname, remoteServerPort)

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
      case Right(value)    => Some(value)
      case Left(exception) =>
        EventHandler.debug(this, exception.toString)
        None
    }

    lazy val clusterDeployerInstance: Option[ClusterDeployer] = getObjectFor("akka.cluster.ClusterDeployer$") match {
      case Right(value)    => Some(value)
      case Left(exception) =>
        EventHandler.debug(this, exception.toString)
        None
    }

    lazy val serializerClass: Option[Class[_]] = getClassFor("akka.serialization.Serializer") match {
      case Right(value)    => Some(value)
      case Left(exception) =>
        EventHandler.debug(this, exception.toString)
        None
    }

    lazy val node: ClusterNode = {
      ensureEnabled()
      clusterInstance.get.newNode(nodeAddress, zooKeeperServers)
    }

    lazy val clusterDeployer: ClusterDeployer = {
      ensureEnabled()
      clusterDeployerInstance.get
    }

    type ClusterNode = {
      def start()
      def shutdown()

      def store[T <: Actor]
        (address: String, actorClass: Class[T], replicas: Int, serializeMailbox: Boolean)
        (implicit format: Format[T])

      def store[T <: Actor]
        (address: String, actorRef: ActorRef, replicas: Int, serializeMailbox: Boolean)
        (implicit format: Format[T])

      def remove(address: String)
      def use(address: String): Array[ActorRef]
      def ref(address: String, router: RouterType): ActorRef
      def isClustered(address: String): Boolean
      def nrOfActors: Int
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
      def newNode(nodeAddress: NodeAddress, zkServerAddresses: String): ClusterNode
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

    val remoteServerHostname = Config.config.getString("akka.remote.server.hostname", "localhost")
    val remoteServerPort     = Config.config.getInt("akka.remote.server.port", 2552)

    private[akka] val configDefaultAddress = new InetSocketAddress(remoteServerHostname, remoteServerPort)

    lazy val isEnabled = remoteSupportClass.isDefined

    def ensureEnabled() = {
      if (!isEnabled) {
      val e = new ModuleNotAvailableException("Can't load the remoting module, make sure that akka-remote.jar is on the classpath")
      EventHandler.debug(this, e.toString)
      throw e
      }
    }

    val remoteSupportClass = getClassFor[RemoteSupport](TRANSPORT) match {
      case Right(value)    => Some(value)
      case Left(exception) =>
        EventHandler.debug(this, exception.toString)
        None
    }

    protected[akka] val defaultRemoteSupport: Option[() => RemoteSupport] =
      remoteSupportClass map { remoteClass =>
        () => createInstance[RemoteSupport](
          remoteClass,
          Array[Class[_]](),
          Array[AnyRef]()
        ) match {
          case Right(value)    => value
          case Left(exception) =>
          val e = new ModuleNotAvailableException(
            "Can't instantiate [%s] - make sure that akka-remote.jar is on the classpath".format(remoteClass.getName), exception)
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
      def stop(anyRef: AnyRef)
    }

    lazy val isEnabled = typedActorObjectInstance.isDefined

    def ensureEnabled() {
      if (!isTypedActorEnabled) throw new ModuleNotAvailableException(
        "Can't load the typed actor module, make sure that akka-typed-actor.jar is on the classpath")
    }

    val typedActorObjectInstance: Option[TypedActorObject] =
      getObjectFor[TypedActorObject]("akka.actor.TypedActor$") match {
        case Right(value) => Some(value)
        case Left(exception)=>
          EventHandler.debug(this, exception.toString)
          None
      }

    def resolveFutureIfMessageIsJoinPoint(message: Any, future: Future[_]): Boolean = {
      ensureEnabled()
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
                        args: Array[AnyRef]): Either[Exception,T] = try {
    assert(clazz ne null)
    assert(params ne null)
    assert(args ne null)
    val ctor = clazz.getDeclaredConstructor(params: _*)
    ctor.setAccessible(true)
    Right(ctor.newInstance(args: _*).asInstanceOf[T])
  } catch {
    case e: java.lang.reflect.InvocationTargetException =>
      EventHandler.debug(this, e.getCause.toString)
      Left(e)
    case e: Exception =>
      EventHandler.debug(this, e.toString)
      Left(e)
  }

  def createInstance[T](fqn: String,
                        params: Array[Class[_]],
                        args: Array[AnyRef],
                        classloader: ClassLoader = loader): Either[Exception,T] = try {
    assert(params ne null)
    assert(args ne null)
    getClassFor(fqn) match {
      case Right(value) =>
        val ctor = value.getDeclaredConstructor(params: _*)
        ctor.setAccessible(true)
        Right(ctor.newInstance(args: _*).asInstanceOf[T])
      case Left(exception) => Left(exception) //We could just cast this to Either[Exception, T] but it's ugly
    }
  } catch {
    case e: Exception =>
      Left(e)
  }

  //Obtains a reference to fqn.MODULE$
  def getObjectFor[T](fqn: String, classloader: ClassLoader = loader): Either[Exception,T] = try {
    getClassFor(fqn) match {
      case Right(value) =>
        val instance = value.getDeclaredField("MODULE$")
        instance.setAccessible(true)
        val obj = instance.get(null)
        if (obj eq null) Left(new NullPointerException) else Right(obj.asInstanceOf[T])
      case Left(exception) => Left(exception) //We could just cast this to Either[Exception, T] but it's ugly
    }
  } catch {
    case e: Exception =>
      Left(e)
  }

  def getClassFor[T](fqn: String, classloader: ClassLoader = loader): Either[Exception, Class[T]] = try {
    assert(fqn ne null)

    // First, use the specified CL
    val first = try {
      Right(classloader.loadClass(fqn).asInstanceOf[Class[T]])
    } catch {
      case c: ClassNotFoundException => Left(c)
    }

    if (first.isRight) first
    else {
      // Second option is to use the ContextClassLoader
      val second = try {
        Right(Thread.currentThread.getContextClassLoader.loadClass(fqn).asInstanceOf[Class[T]])
      } catch {
        case c: ClassNotFoundException => Left(c)
      }

      if (second.isRight) second
      else {
        val third = try {
           if (classloader ne loader) Right(loader.loadClass(fqn).asInstanceOf[Class[T]]) else Left(null) //Horrid
        } catch {
          case c: ClassNotFoundException => Left(c)
        }

        if (third.isRight) third
        else {
          try {
            Right(Class.forName(fqn).asInstanceOf[Class[T]]) // Last option is Class.forName
          } catch {
            case c: ClassNotFoundException => Left(c)
          }
        }
      }
    }
  } catch {
    case e: Exception => Left(e)
  }
}
