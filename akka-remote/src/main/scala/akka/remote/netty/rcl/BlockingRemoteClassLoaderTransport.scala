package akka.remote.netty.rcl

import akka.remote.netty.NettyRemoteTransport
import akka.rcl.impl.ThreadLocalReflectiveDynamicAccess
import org.fest.reflect.core.Reflection
import com.typesafe.config.ConfigFactory
import akka.remote.RemoteProtocol.RemoteMessageProtocol
import com.google.protobuf.ByteString
import akka.dispatch.Await
import akka.actor._
import java.net.URL
import com.google.common.io.Resources

import akka.util.Timeout
import akka.util.duration._
import akka.pattern.ask
import com.google.common.base.Charsets
import com.google.common.cache.{ LoadingCache, CacheLoader, CacheBuilder }
import util.Random
import com.google.common.collect.{ Lists, ImmutableMap, Iterables }
import org.objectweb.asm.ClassReader
import org.objectweb.asm.commons.{ EmptyVisitor, RemappingClassAdapter, Remapper }
import java.util.{ HashMap, HashSet, ArrayList }
import akka.remote.{ RemoteSettings, RemoteMessage, RemoteProtocol, RemoteActorRefProvider }

class BlockingRemoteClassLoaderTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends NettyRemoteTransport(_system, _provider) {

  val systemClassLoader = _system.dynamicAccess.classLoader

  val systemClassLoaderChain: Array[ClassLoader] = {
    val clChain = new ArrayList[ClassLoader]()
    var currentCl = systemClassLoader
    while (currentCl != null) {
      clChain.add(currentCl)
      currentCl = currentCl.getParent
    }
    Iterables.toArray(clChain, classOf[ClassLoader])
  }

  // replace dynamic access with our thread local dynamic access
  val threadLocalDynamicAccess = new ThreadLocalReflectiveDynamicAccess(systemClassLoader)
  Reflection.field("_pm").ofType(classOf[DynamicAccess]).in(_system).set(threadLocalDynamicAccess)

  // boot up isolated system to do RCL communication (updates etc.)
  val rclPort = new Random().nextInt(10000) + 20000
  val hostname = _system.settings.config.getString("akka.remote.netty.hostname")

  val rclCommunicationSystem = RclConfig.newIsolatedSystem(hostname, rclPort, systemClassLoader)
  // install the RCL actor to respond to class file requests
  val rclActor = rclCommunicationSystem.actorOf(Props {
    new RclActor(systemClassLoader)
  }, "rcl")

  val originAddressByteString = ByteString.copyFrom("akka://rcl@" + hostname + ":" + rclPort, Charsets.UTF_8.name())

  val remoteClassLoaders: LoadingCache[ByteString, RclBlockingClassLoader] = CacheBuilder.newBuilder().build(new CacheLoader[ByteString, RclBlockingClassLoader] {
    def load(address: ByteString) = {
      val originRclActor = address.toStringUtf8 + "/user/rcl"
      new RclBlockingClassLoader(systemClassLoader, rclCommunicationSystem.actorFor(originRclActor), address)
    }
  })

  // just make sure the "context" has the correct classloader set
  override def receiveMessage(remoteMessage: RemoteMessage) {
    RclMetadata.getOrigin(remoteMessage) match {
      case someOrigin: ByteString if someOrigin.equals(originAddressByteString) ⇒ super.receiveMessage(remoteMessage)
      case someOrigin: ByteString ⇒ {
        try {
          // the RCL CL cannot be null, but can point to downed system
          threadLocalDynamicAccess.setThreadLocalClassLoader(remoteClassLoaders.get(someOrigin))
          super.receiveMessage(remoteMessage)
        } finally {
          threadLocalDynamicAccess.setThreadLocalClassLoader(systemClassLoader)
        }
      }
      case _ ⇒ super.receiveMessage(remoteMessage)
    }
  }

  // just tag the message
  override def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]) = {
    val pb = super.createRemoteMessageProtocolBuilder(recipient, message, senderOption)

    message match {
      case ref: AnyRef ⇒ {
        ref.getClass.getClassLoader match {
          case rcl: RclBlockingClassLoader ⇒ RclMetadata.addOrigin(pb, rcl.originAddress)
          case cl: ClassLoader ⇒ {
            if (systemClassLoaderChain.find(_ == cl).isDefined) {
              RclMetadata.addOrigin(pb, originAddressByteString)
            }
          }
          case null ⇒ // do nothing
        }
      }
      case _ ⇒ // do nothing
    }
    pb
  }

  override def shutdown() {
    // if we are shutting down the transports lets shut down the isolated rcl system too
    rclCommunicationSystem.shutdown()
    super.shutdown()
  }
}

class RclBlockingClassLoader(parent: ClassLoader, origin: ActorRef, val originAddress: ByteString) extends ClassLoader(parent) {

  implicit val timeout = Timeout(19 seconds)

  var innerCall = false
  val bytecodeCache = new HashMap[String, Array[Byte]]

  // normally it is not possible to block in here as this will in fact block the netty dispatcher i.e. no new stuff on this channel
  // but we are using a secondary actor system just for RCL so it is safe to block
  override def findClass(fqn: String): Class[_] = {
    println("Asking for " + fqn)
    // when asking ourself if we already loaded a class we must not enter again the rcl logic
    if (innerCall) throw new ClassNotFoundException(fqn)
    // maybe we already have the class in cache?
    bytecodeCache.get(fqn) match {
      case bytecode: Array[Byte] ⇒ {
        bytecodeCache.remove(fqn)
        return defineClass(fqn, bytecode, 0, bytecode.length)
      }
      case _ ⇒ // ignore
    }
    // if not try to load the bytecode cache via rcl
    // prepare cache
    try {
      innerCall = true;
      bytecodeCache.putAll(getDirectlyReferencesClassesByteCode(fqn))
    } finally {
      innerCall = false;
    }
    // cache is prepared lets load the class
    bytecodeCache.get(fqn) match {
      case bytecode: Array[Byte] ⇒ defineClass(fqn, bytecode, 0, bytecode.length)
      case _                     ⇒ throw new ClassNotFoundException(fqn)
    }
  }

  def getDirectlyReferencesClassesByteCode(fqn: String): java.util.Map[String, Array[Byte]] = {
    // first lets get all the fqns of directly references classes
    val referencedFqns = Await.result(origin ? GiveMeReferencedClassesFor(fqn), timeout.duration) match {
      case ListOfReferencedClassesFor(fqn, referencedFqns) ⇒ referencedFqns
      case _ ⇒ throw new ClassNotFoundException(fqn)
    }

    // lets figure out if we have some of the needed classes already loaded  by us
    // or if we already have this in the code cache
    val neededClasses = referencedFqns.filter(fqn ⇒ {
      try {
        // this is why we need the innerCall to throw exception
        loadClass(fqn)
        false
      } catch {
        case _ ⇒ true
      }
    }).filter(!bytecodeCache.containsKey(_))

    // lets ask for the classes we know we need
    Await.result(origin ? GiveMeByteCodesFor(neededClasses), timeout.duration) match {
      case ByteCodesFor(map2) ⇒ map2
      case _                  ⇒ throw new ClassNotFoundException(fqn)
    }
  }

  def simpleRcl(fqn: String): Class[_] = {
    Await.result(origin ? GiveMeByteCodeFor(fqn), timeout.duration) match {
      case ByteCodeFor(fqn, bytecode) ⇒ {
        defineClass(fqn, bytecode, 0, bytecode.length)
      }
      case _ ⇒ throw new ClassNotFoundException(fqn)
    }
  }

}

object RclMetadata {

  val INPUT_FIELD = Reflection.field("input").ofType(classOf[RemoteProtocol.RemoteMessageProtocol]);

  def addOrigin(pb: RemoteMessageProtocol.Builder, origin: ByteString) {
    val metadataBuilder = pb.addMetadataBuilder()
    metadataBuilder.setKey("origin")
    metadataBuilder.setValue(origin)
  }

  def getOrigin(rm: RemoteMessage): ByteString = {
    val rmp = INPUT_FIELD.in(rm).get()
    import scala.collection.JavaConversions._
    rmp.getMetadataList.find(_.getKey.equals("origin")) match {
      case Some(e) ⇒ e.getValue
      case _       ⇒ null
    }
  }
}

object RclConfig {

  def newIsolatedSystem(hostname: String, port: Int, classLoader: ClassLoader) = {

    val config = ConfigFactory.parseString("""
      akka {
         actor {
            provider = "akka.remote.RemoteActorRefProvider"
         }
         remote {
           transport = "akka.remote.netty.NettyRemoteTransport"
           untrusted-mode = off
           remote-daemon-ack-timeout = 30s
           log-received-messages = on
           log-sent-messages = on
           netty {
             backoff-timeout = 0ms
             secure-cookie = ""
             require-cookie = off
             use-passive-connections = on
             use-dispatcher-for-io = ""
             hostname = "%s"
             port = %s
             outbound-local-address = "auto"
             message-frame-size = 1 MiB
             connection-timeout = 120s
             backlog = 4096
             execution-pool-keepalive = 60s
             execution-pool-size = 4
             max-channel-memory-size = 0b
             max-total-memory-size = 0b
             reconnect-delay = 5s
             read-timeout = 0s
             write-timeout = 10s
             all-timeout = 0s
             reconnection-time-window = 600s
           }
         }
      }""" format (hostname, port))
    ActorSystem("rcl", config, classLoader)
  }
}

class RclActor(val urlishClassLoader: ClassLoader) extends Actor {

  def receive = {
    case GiveMeByteCodeFor(fqn) ⇒ getBytecode(fqn) match {
      case bytecode: Array[Byte] ⇒ sender ! ByteCodeFor(fqn, bytecode)
      case _                     ⇒ sender ! ByteCodeNotAvailable(fqn)
    }

    case GiveMeByteCodesFor(fqns) ⇒ {
      val builder = ImmutableMap.builder[String, Array[Byte]]()
      val missing = Lists.newArrayList[String];
      for (fqn ← fqns) {
        getBytecode(fqn) match {
          case bytecode: Array[Byte] ⇒ builder.put(fqn, bytecode)
          case _                     ⇒ missing.add(fqn)
        }
      }

      missing.isEmpty match {
        case true ⇒ sender ! ByteCodesFor(builder.build())
        case _    ⇒ sender ! ByteCodesNotAvailable(Iterables.toArray(missing, classOf[String]))
      }
    }

    case GiveMeReferencedClassesFor(fqn) ⇒ {
      getBytecode(fqn) match {
        case bytecode: Array[Byte] ⇒ sender ! ListOfReferencedClassesFor(fqn, Iterables.toArray(getReferences(bytecode), classOf[String]))
        case _                     ⇒ sender ! ByteCodeNotAvailable(fqn)
      }
    }

    case _ ⇒ // drop it
  }

  def getBytecode(fqn: String): Array[Byte] = {
    val resourceName = fqn.replaceAll("\\.", "/") + ".class"
    urlishClassLoader.getResource(resourceName) match {
      case url: URL ⇒ Resources.toByteArray(url)
      case _        ⇒ null
    }
  }

  def getReferences(bytecode: Array[Byte]): java.util.Set[String] = {
    val classNames = new HashSet[String]();

    val classReader = new ClassReader(bytecode);
    val emptyVisitor = new EmptyVisitor();

    val remapper = new ClassNameRecordingRemapper(classNames);
    classReader.accept(new RemappingClassAdapter(emptyVisitor, remapper), 0);
    classNames
  }

  class ClassNameRecordingRemapper(classNames: java.util.Set[String]) extends Remapper {

    override def mapType(resource: String) = {
      val fqn = resource.replaceAll("/", ".")
      if (fqn != null && !fqn.startsWith("java.") && !fqn.startsWith("scala.")) {
        classNames.add(fqn)
      }
      resource
    }
  }

}

// RCL Questions
case class GiveMeByteCodeFor(fqn: String)

case class GiveMeByteCodesFor(fqns: Array[String])

case class GiveMeReferencedClassesFor(fqn: String)

// RCL Answers
case class ByteCodeFor(fqn: String, bytecode: Array[Byte])

case class ByteCodesFor(bytecode: ImmutableMap[String, Array[Byte]])

case class ByteCodeNotAvailable(fqn: String)

case class ByteCodesNotAvailable(fqns: Array[String])

case class ListOfReferencedClassesFor(fqn: String, references: Array[String])
