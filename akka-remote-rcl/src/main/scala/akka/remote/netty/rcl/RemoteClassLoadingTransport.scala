package akka.remote.netty.rcl

import akka.remote.RemoteSettings
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
import scala.collection._
import akka.util.NonFatal

class RemoteClassLoadingTransport(system: ExtendedActorSystem, provider: RemoteActorRefProvider) extends NettyRemoteTransport(system, provider) {

  // 1 thread should be plenty
  val nrOfRclThreads = 1;

  // the RCL stuff has to be processed by a separate thread because we might block in the classloader or the user might block
  override def createPipeline(endpoint: ⇒ ChannelHandler, withTimeout: Boolean): ChannelPipelineFactory = {
    new ChannelPipelineFactory {
      def getPipeline = PipelineFactory(PipelineFactory.defaultStack(withTimeout).dropRight(1).toSeq ++ Seq(sedaRclPriorityHandler, endpoint))
    }
  }

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
          case arp: AkkaRemoteProtocol ⇒ RclMetadata.isRclChatMsg(arp)
          case _                       ⇒ false
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
  ReflectionUtil.setFieldValue("_pm", system, threadLocalDynamicAccess)

  lazy val originAddressByteString = ByteString.copyFrom(address.toString, "utf-8")

  val remoteClassLoaders: HashMap[ByteString, RemoteClassLoader] = HashMap()

  private val remoteClassLoadersLock = new ReentrantReadWriteLock

  // this is the actor we query for RCL stuff that is process by seperated thread
  system.actorOf(Props { new RclActor(systemClassLoader) }, "Rcl-Service")

  // on received just make sure the "context" has the correct classloader set
  override def receiveMessage(remoteMessage: RemoteMessage) {
    RclMetadata.getOrigin(remoteMessage) match {
      case `originAddressByteString` ⇒ {
        threadLocalDynamicAccess.dynamicVariable.withValue(systemClassLoader) {
          super.receiveMessage(remoteMessage)
        }
      }
      case someOrigin: ByteString ⇒ {
        val rcl = getClassLoaderForOrigin(someOrigin)

        threadLocalDynamicAccess.dynamicVariable.withValue(rcl) {
          super.receiveMessage(remoteMessage)
        }
      }
      case _ ⇒ {
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
                  val cl = new RemoteClassLoader(systemClassLoader, system.actorFor(someOrigin.toStringUtf8 + "/user/Rcl-Service"), someOrigin, log, new RemoteSettings(system.settings.config, system.name))
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

  // just tag the message with the origin and in case of RCL messages with RCL tag
  override def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]) = {
    val pb = super.createRemoteMessageProtocolBuilder(recipient, message, senderOption)

    message match {
      case rcl: RclChatMsg ⇒ RclMetadata.addRclChatTag(pb)
      case ref: AnyRef ⇒ {
        val name = ref.getClass.getName
        if (!name.startsWith("java.") && !name.startsWith("scala.")) {
          ref.getClass.getClassLoader match {
            case rcl: RemoteClassLoader ⇒ {
              RclMetadata.addOrigin(pb, rcl.originAddress)
            }
            case cl: ClassLoader ⇒ systemClassLoaderChain(cl) match {
              case true ⇒ RclMetadata.addOrigin(pb, originAddressByteString)
              case _    ⇒ log.warning("Remote Class Loading does not support sending messages loaded outside Actor System's classloader.\n{}", message)
            }
            case null ⇒ // null ClassLoader e.g. java.lang.String this is fine
          }
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

class RemoteClassLoader(parent: ClassLoader, origin: ActorRef, val originAddress: ByteString, log: LoggingAdapter, settings: RemoteSettings) extends ClassLoader(parent) {

  implicit val timeout = Timeout(settings.RemoteSystemDaemonAckTimeout)

  val preloadedClasses = new mutable.HashMap[String, Array[Byte]]()

  var innerCall = false

  // normally it is not possible to block in here as this will in fact block the netty dispatcher i.e. no new stuff on this channel
  // but we are using a special thread just for this so this is safe
  override def findClass(fqn: String): Class[_] = {
    if (innerCall) throw new ClassNotFoundException(fqn)

    log.debug("ClassLoader#findClass({}) from {}.", fqn, origin.path.address)

    preloadedClasses remove (fqn) orNull match {
      case null ⇒ doRcl(fqn)
      case bytecode: Array[Byte] ⇒ {
        log.debug("Bytecode for {} found in preloaded cache from {}.", fqn, origin.path.address)
        defineClass(fqn, bytecode, 0, bytecode.length)
      }
    }
  }

  def doRcl(fqn: String): Class[_] = {
    try {
      log.debug("Initiated RCL for {} from {}.", fqn, origin.path.address)
      val referencedClasses = Await.result(origin ? GiveMeReferencedClassesOf(fqn), timeout.duration) match {
        case r: ReferencedClassesOf ⇒ r.refs
        case _ ⇒ {
          // if we can't get references we are sure we cannot get the bytecode or is corrupt
          log.warning("Failed to find referenced classes for {}.")
          throw new ClassNotFoundException(fqn)
        }
      }
      log.debug("Got list of referenced classes for {} from {}.\n{}", fqn, origin.path.address, referencedClasses deepToString)
      val withoutAlreadyAvailable = referencedClasses filter (!isAlreadyLoaded(_)) filter (_ != fqn)
      log.debug("Requesting only the following bytecodes from {}.\n{}", origin.path.address, withoutAlreadyAvailable deepToString)

      val bytecodes = Await.result(origin ? GiveMeByteCodesFor(fqn, withoutAlreadyAvailable), timeout.duration).asInstanceOf[ByteCodesFor]

      log.debug("Got bytecodes from {}.\n{}", origin.path.address, bytecodes.entries deepToString)

      bytecodes.entries foreach (_ match {
        case ByteCodeFor(fqn, bytecode) ⇒ preloadedClasses += fqn -> bytecode
        case _                          ⇒ log.error("Bug in RCL code. ByteCodesFor contained invalid entries.")
      })
      val bytecode = bytecodes.first.bytecode
      defineClass(fqn, bytecode, 0, bytecode.length)
    } catch {
      case e: Exception ⇒ {
        log.debug("Failed to get requested bytecode for class {} from {}.\n{}", fqn, origin.path.address, e)
        throw new ClassNotFoundException(fqn)
      }
    }
  }

  def isAlreadyLoaded(fqn: String): Boolean = {
    try {
      innerCall = true
      loadClass(fqn)
      return true
    } catch {
      case NonFatal(_) ⇒ false
    } finally {
      innerCall = false
    }
  }
}

object RclMetadata {

  def isRclChatMsg(arp: AkkaRemoteProtocol): Boolean = {
    if (arp.hasInstruction) false
    import scala.collection.JavaConversions._
    arp.getMessage.getMetadataList.collectFirst({
      case entry if entry.getKey == "rclChatMsg" ⇒ true
    }).getOrElse(false)
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
    rmp.getMetadataList.collectFirst({
      case entry if entry.getKey == "origin" ⇒ entry.getValue
    }).orNull
  }

}

class RclActor(val cl: ClassLoader) extends Actor {

  val log = Logging(context.system, this)

  def receive = {
    case GiveMeByteCodeFor(fqn) ⇒ {
      try {
        log.debug("Recieved bytecode request for {} from {}.", fqn, sender.path.address)
        sender ! ByteCodeFor(fqn, getBytecode(fqn))
      } catch {
        case NonFatal(_) ⇒
          log.warning("Cannot find bytecode for {}.", fqn)
          sender ! ByteCodeNotAvailable(fqn)

      }
    }

    case GiveMeByteCodesFor(fqn, fqns) ⇒ {
      try {
        log.debug("Recieved bytecodes request for {} from {}.", fqns, sender.path.address)
        sender ! ByteCodesFor(ByteCodeFor(fqn, getBytecode(fqn)), fqns.map((fqn) ⇒ ByteCodeFor(fqn, getBytecode(fqn))) toArray)
      } catch {
        case NonFatal(_) ⇒
          log.warning("Cannot find bytecode for {}.", fqns)
          sender ! ByteCodeNotAvailable("")

      }
    }

    case GiveMeReferencedClassesOf(fqn) ⇒ {
      log.debug("Recieved request for all references of {} from {}.", fqn, sender.path.address)
      try {
        // this should always unless we don't have the class or is corrupt
        // but we consider if is corrupt that the bytecode is not available
        sender ! ReferencedClassesOf(fqn, ByteCodeInspector.findReferencedClassesFor(cl.loadClass(fqn)) toArray)
      } catch {
        case NonFatal(_) ⇒
          log.warning("Cannot find referenced classes of of {}.", fqn)

          sender ! ByteCodeNotAvailable(fqn)
      }

    }

    case _ ⇒ log.warning("RCL actor received unknown message. Might indicate a bug present.")
  }

  def getBytecode(fqn: String): Array[Byte] = {
    val resourceName = fqn.replaceAll("\\.", "/") + ".class"
    cl.getResource(resourceName) match {
      case url: URL ⇒ IOUtil.toByteArray(url)
      case _        ⇒ throw new ClassNotFoundException(fqn)
    }
  }

}

sealed trait RclChatMsg

// RCL Questions
case class GiveMeByteCodeFor(fqn: String) extends RclChatMsg
case class GiveMeByteCodesFor(fqn: String, fqns: Array[String]) extends RclChatMsg
case class GiveMeReferencedClassesOf(fqn: String) extends RclChatMsg

// RCL Answers
case class ByteCodeFor(fqn: String, bytecode: Array[Byte]) extends RclChatMsg
case class ByteCodesFor(first: ByteCodeFor, entries: Array[ByteCodeFor]) extends RclChatMsg
case class ReferencedClassesOf(fromFqn: String, refs: Array[String]) extends RclChatMsg

case class ByteCodeNotAvailable(fqn: String) extends RclChatMsg