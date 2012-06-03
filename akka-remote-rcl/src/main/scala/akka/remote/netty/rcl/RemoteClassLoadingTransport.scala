package akka.remote.netty.rcl

import akka.remote.netty.NettyRemoteTransport
import org.jboss.netty.channel._
import org.jboss.netty.handler.execution._
import akka.remote.{ RemoteMessage, RemoteActorRefProvider }
import com.google.protobuf.ByteString
import akka.remote.RemoteProtocol.{ RemoteMessageProtocol, AkkaRemoteProtocol }
import collection.immutable.HashSet
import collection.mutable.HashMap
import akka.dispatch.Await
import java.net.URL
import akka.actor.{ Props, Actor, ActorRef, ExtendedActorSystem }
import java.util.concurrent.locks.ReentrantReadWriteLock
import akka.event.{ LoggingAdapter, Logging }

class RemoteClassLoadingTransport(system: ExtendedActorSystem, provider: RemoteActorRefProvider) extends NettyRemoteTransport(system, provider) {

  // 1 thread should be plenty
  val nrOfRclThreads = 1;

  // the RCL stuff has to be processed by a separate thread because we might block in the classloader or the user might block
  override def createPipeline(endpoint: ⇒ ChannelHandler, withTimeout: Boolean): ChannelPipelineFactory = {
    new ChannelPipelineFactory {
      def getPipeline = PipelineFactory(PipelineFactory.defaultStack(withTimeout).dropRight(1).toSeq ++ Seq(sedaRclPriorityHandler, endpoint))
    }
  }

  log.debug("Using {} thread(s) for handling RCL communicaton.", nrOfRclThreads)

  lazy val rclExecutor = new OrderedMemoryAwareThreadPoolExecutor(
    nrOfRclThreads,
    settings.MaxChannelMemorySize,
    settings.MaxTotalMemorySize,
    settings.ExecutionPoolKeepalive.length,
    settings.ExecutionPoolKeepalive.unit,
    system.threadFactory)

  lazy val superExecutor = new OrderedMemoryAwareThreadPoolExecutor(
    settings.ExecutionPoolSize,
    settings.MaxChannelMemorySize,
    settings.MaxTotalMemorySize,
    settings.ExecutionPoolKeepalive.length,
    settings.ExecutionPoolKeepalive.unit,
    system.threadFactory)

  lazy val sedaRclPriorityHandler = new ExecutionHandler(new ChainedExecutor(new ChannelEventRunnableFilter {
    def filter(event: ChannelEventRunnable) = {
      event.getEvent match {
        case me: MessageEvent ⇒ me.getMessage match {
          case arp: AkkaRemoteProtocol ⇒ {

            RclMetadata.isRclChatMsg(arp) match {
              case true ⇒ {
                log.debug("Received RCL chat message. Using special handler for it.\n{}", arp)
                true
              }
              case false ⇒ false
            }
          }
          case _ ⇒ false
        }
        case _ ⇒ false
      }
    }
  }, rclExecutor, superExecutor))

  val systemClassLoader = system.dynamicAccess.classLoader

  val systemClassLoaderChain: HashSet[ClassLoader] = {
    var clChain = new HashSet[ClassLoader]()
    var currentCl = systemClassLoader
    while (currentCl != null) {
      clChain += currentCl
      currentCl = currentCl.getParent
    }
    clChain
  }

  // replace dynamic access with our thread local dynamic access
  val threadLocalDynamicAccess = new ThreadLocalReflectiveDynamicAccess(systemClassLoader)
  ReflectionUtil.setField("_pm", system, threadLocalDynamicAccess)

  log.debug("Replaced ActorSystem's Dynamic Access Class Loader with Remote Class Loading's.")

  lazy val originAddressByteString = ByteString.copyFrom(address.toString, "utf-8")

  val remoteClassLoaders: HashMap[ByteString, RemoteClassLoader] = HashMap()

  private val remoteClassLoadersLock = new ReentrantReadWriteLock

  system.actorOf(Props {
    new RclActor(systemClassLoader)
  }, "Rcl-Service")

  // just make sure the "context" has the correct classloader set
  override def receiveMessage(remoteMessage: RemoteMessage) {
    log.debug("Received message")
    RclMetadata.getOrigin(remoteMessage) match {
      case `originAddressByteString` ⇒ {
        log.debug("Received message that we are the source of the class. Calling super.\n")
        threadLocalDynamicAccess.dynamicVariable.withValue(systemClassLoader) {
          super.receiveMessage(remoteMessage)
        }
      }
      case someOrigin: ByteString ⇒ {
        log.debug("Received message that requires RCL classloader with id {}.\n{}", someOrigin.toStringUtf8)
        val rcl = getClassLoaderForOrigin(someOrigin)

        threadLocalDynamicAccess.dynamicVariable.withValue(rcl) {
          super.receiveMessage(remoteMessage)
        }
      }
      case _ ⇒ {
        log.debug("Received untagged message. Calling super.\n{}", remoteMessage.input)
        threadLocalDynamicAccess.dynamicVariable.withValue(systemClassLoader) {
          super.receiveMessage(remoteMessage)
        }
      }
    }
  }

  def getClassLoaderForOrigin(someOrigin: ByteString): RemoteClassLoader = {
    remoteClassLoadersLock.readLock.lock
    try {
      remoteClassLoaders.get(someOrigin) match {
        case Some(cl) ⇒ cl
        case None ⇒
          remoteClassLoadersLock.readLock.unlock
          remoteClassLoadersLock.writeLock.lock //Lock upgrade, not supported natively
          try {
            try {
              remoteClassLoaders.get(someOrigin) match {
                //Recheck for addition, race between upgrades
                case Some(cl) ⇒ cl //If already populated by other writer
                case None ⇒ //Populate map
                  log.debug("Creating new Remote Class Loader with Origin {}.", someOrigin.toStringUtf8)
                  val cl = new RemoteClassLoader(systemClassLoader, system.actorFor(someOrigin.toStringUtf8 + "/user/Rcl-Service"), someOrigin, log)
                  remoteClassLoaders += someOrigin -> cl
                  cl
              }
            } finally {
              remoteClassLoadersLock.readLock.lock
            } //downgrade
          } finally {
            remoteClassLoadersLock.writeLock.unlock
          }
      }
    } finally {
      remoteClassLoadersLock.readLock().unlock()
    }
  }

  // just tag the message
  override def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]) = {
    val pb = super.createRemoteMessageProtocolBuilder(recipient, message, senderOption)

    message match {
      case rcl: RclChatMsg ⇒ {
        log.debug("Tagging message as RCL chat protocol msg.\n{}", message)
        RclMetadata.addRclChatTag(pb)
      }
      case ref: AnyRef ⇒ {
        ref.getClass.getClassLoader match {
          case rcl: RemoteClassLoader ⇒ {
            log.debug("Tagging message loaded in RCL with remote  id {}.\n{}", rcl.originAddress.toStringUtf8, message)
            RclMetadata.addOrigin(pb, rcl.originAddress)
          }
          case cl: ClassLoader ⇒ {
            if (systemClassLoaderChain.find(_ == cl).isDefined) {
              log.debug("Tagging message loaded in our CL with id {}.\n{}", originAddressByteString.toStringUtf8, message)
              RclMetadata.addOrigin(pb, originAddressByteString)
            } else {
              log.warning("Sending message {} from unknown classloader", message)
            }
          }
          case null ⇒ // null ClassLoader e.g. java.lang.String this is fine
        }
      }
      case _ ⇒ // not AnyRef even less of a problem
    }
    pb
  }
}

import akka.util.Timeout
import akka.util.duration._
import akka.pattern.ask

class RemoteClassLoader(parent: ClassLoader, origin: ActorRef, val originAddress: ByteString, log: LoggingAdapter) extends ClassLoader(parent) {

  implicit val timeout = Timeout(19 seconds)

  // normally it is not possible to block in here as this will in fact block the netty dispatcher i.e. no new stuff on this channel
  // but we are using a special thread just for this so this is safe
  override def findClass(fqn: String): Class[_] = {
    log.debug("Requesting bytecode for class {} from {}.", fqn, origin.path.address)
    try {
      Await.result(origin ? GiveMeByteCodeFor(fqn), timeout.duration) match {
        case ByteCodeFor(fqn, bytecode) ⇒ {
          log.debug("Received bytecode for class {} from {}.", fqn, origin.path.address)
          defineClass(fqn, bytecode, 0, bytecode.length)
        }
        case _ ⇒ throw new ClassNotFoundException(fqn)
      }
    } catch {
      case e: Exception ⇒ {
        log.debug("Failed to get requested bytecode for class {} from {}.\n{}", fqn, origin.path.address, e)
        throw new ClassNotFoundException(fqn)
      }
    }
  }

}

object RclMetadata {

  def isRclChatMsg(arp: AkkaRemoteProtocol): Boolean = {
    if (arp.hasInstruction) false
    import scala.collection.JavaConversions._
    arp.getMessage.getMetadataList.find(_.getKey.equals("rclChatMsg")) match {
      case Some(e) ⇒ {
        println("Found " + e)
        true
      }
      case _ ⇒ false
    }
  }

  def addRclChatTag(pb: RemoteMessageProtocol.Builder) {
    val metadataBuilder = pb.addMetadataBuilder()
    metadataBuilder.setKey("rclChatMsg")
    metadataBuilder.setValue(ByteString.EMPTY)
  }

  def addOrigin(pb: RemoteMessageProtocol.Builder, origin: ByteString) {
    val metadataBuilder = pb.addMetadataBuilder()
    metadataBuilder.setKey("origin")
    metadataBuilder.setValue(origin)
  }

  def getOrigin(rm: RemoteMessage): ByteString = {
    val rmp: RemoteMessageProtocol = rm.input
    import scala.collection.JavaConversions._
    rmp.getMetadataList.find(_.getKey.equals("origin")) match {
      case Some(e) ⇒ e.getValue
      case _       ⇒ null
    }
  }

}

class RclActor(val cl: ClassLoader) extends Actor {

  val log = Logging(context.system, this)

  def receive = {
    case GiveMeByteCodeFor(fqn) ⇒ {
      log.debug("Recived bytecode request for {} from {}.", fqn, sender.path.address)
      getBytecode(fqn) match {
        case bytecode: Array[Byte] ⇒ sender ! ByteCodeFor(fqn, bytecode)
        case _                     ⇒ sender ! ByteCodeNotAvailable(fqn)
      }
    }

    case _ ⇒ // drop it
  }

  def getBytecode(fqn: String): Array[Byte] = {

    val resourceName = fqn.replaceAll("\\.", "/") + ".class"
    cl.getResource(resourceName) match {
      case url: URL ⇒ IOUtil.toByteArray(url)
      case _        ⇒ null
    }
  }

}

sealed trait RclChatMsg

// RCL Questions
case class GiveMeByteCodeFor(fqn: String) extends RclChatMsg

// RCL Answers
case class ByteCodeFor(fqn: String, bytecode: Array[Byte]) extends RclChatMsg

case class ByteCodeNotAvailable(fqn: String) extends RclChatMsg
