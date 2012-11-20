/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi

import de.kalpatec.pojosr.framework.launch.{ BundleDescriptor, PojoServiceRegistryFactory, ClasspathScanner }

import scala.collection.JavaConversions.seqAsJavaList
import scala.collection.JavaConversions.collectionAsScalaIterable
import org.apache.commons.io.IOUtils.copy

import org.osgi.framework._
import java.net.URL
import java.util.jar.JarInputStream
import java.io._
import org.scalatest.{ BeforeAndAfterAll, Suite }
import java.util.{ UUID, Date, ServiceLoader, HashMap }
import scala.reflect.ClassTag
import scala.concurrent.duration._
import scala.annotation.tailrec

/**
 * Trait that provides support for building akka-osgi tests using PojoSR
 */
trait PojoSRTestSupport extends Suite with BeforeAndAfterAll {

  val MaxWaitDuration = 12800.millis
  val SleepyTime = 50.millis

  /**
   * All bundles being found on the test classpath are automatically installed and started in the PojoSR runtime.
   * Implement this to define the extra bundles that should be available for testing.
   */
  def testBundles: Seq[BundleDescriptor]

  val bufferedLoadingErrors = new ByteArrayOutputStream()

  lazy val context: BundleContext = {
    val config = new HashMap[String, AnyRef]()
    System.setProperty("org.osgi.framework.storage", "target/akka-osgi/" + UUID.randomUUID().toString)

    val bundles = new ClasspathScanner().scanForBundles()
    bundles.addAll(testBundles)
    config.put(PojoServiceRegistryFactory.BUNDLE_DESCRIPTORS, bundles)

    val oldErr = System.err
    System.setErr(new PrintStream(bufferedLoadingErrors))
    try {
      ServiceLoader.load(classOf[PojoServiceRegistryFactory]).iterator.next.newPojoServiceRegistry(config).getBundleContext
    } catch {
      case e: Throwable ⇒ oldErr.write(bufferedLoadingErrors.toByteArray); throw e
    } finally {
      System.setErr(oldErr)
    }
  }

  // Ensure bundles get stopped at the end of the test to release resources and stop threads
  override protected def afterAll() = context.getBundles.foreach(_.stop)

  /**
   * Convenience method to find a bundle by symbolic name
   */
  def bundleForName(name: String) =
    context.getBundles.find(_.getSymbolicName == name).getOrElse(fail("Unable to find bundle with symbolic name %s".format(name)))

  /**
   * Convenience method to find a service by interface.  If the service is not already available in the OSGi Service
   * Registry, this method will wait for a few seconds for the service to appear.
   */
  def serviceForType[T](implicit t: ClassTag[T]): T =
    context.getService(awaitReference(t.runtimeClass)).asInstanceOf[T]

  def awaitReference(serviceType: Class[_]): ServiceReference = awaitReference(serviceType, SleepyTime)

  def awaitReference(serviceType: Class[_], wait: FiniteDuration): ServiceReference = {

    @tailrec def poll(step: Duration, deadline: Deadline): ServiceReference = context.getServiceReference(serviceType.getName) match {
      case null ⇒
        if (deadline.isOverdue()) fail("Gave up waiting for service of type %s".format(serviceType))
        else {
          Thread.sleep((step min deadline.timeLeft max Duration.Zero).toMillis)
          poll(step, deadline)
        }
      case some ⇒ some
    }

    poll(wait, Deadline.now + MaxWaitDuration)
  }

  protected def buildTestBundles(builders: Seq[BundleDescriptorBuilder]): Seq[BundleDescriptor] = builders map (_.build)

  def filterErrors()(block: ⇒ Unit): Unit = {
    try {
      block
    } catch {
      case e: Throwable ⇒ System.err.write(bufferedLoadingErrors.toByteArray); throw e
    }
  }
}

object PojoSRTestSupport {
  /**
   * Convenience method to define additional test bundles
   */
  def bundle(name: String) = new BundleDescriptorBuilder(name)
}

/**
 * Helper class to make it easier to define test bundles
 */
class BundleDescriptorBuilder(name: String) {

  import org.ops4j.pax.tinybundles.core.TinyBundles

  val tinybundle = TinyBundles.bundle.set(Constants.BUNDLE_SYMBOLICNAME, name)

  /**
   * Add a Blueprint XML file to our test bundle
   */
  def withBlueprintFile(name: String, contents: URL): BundleDescriptorBuilder = {
    tinybundle.add("OSGI-INF/blueprint/%s".format(name), contents)
    this
  }

  /**
   * Add a Blueprint XML file to our test bundle
   */
  def withBlueprintFile(contents: URL): BundleDescriptorBuilder = {
    val filename = contents.getFile.split("/").last
    withBlueprintFile(filename, contents)
  }

  /**
   * Add a Bundle activator to our test bundle
   */
  def withActivator(activator: Class[_ <: BundleActivator]): BundleDescriptorBuilder = {
    tinybundle.set(Constants.BUNDLE_ACTIVATOR, activator.getName)
    this
  }

  /**
   * Build the actual PojoSR BundleDescriptor instance
   */
  def build: BundleDescriptor = {
    val file: File = tinybundleToJarFile(name)
    new BundleDescriptor(getClass().getClassLoader(), new URL("jar:" + file.toURI().toString() + "!/"), extractHeaders(file))
  }

  def extractHeaders(file: File): HashMap[String, String] = {
    val headers = new HashMap[String, String]()

    val jis = new JarInputStream(new FileInputStream(file))
    try {
      for (entry ← jis.getManifest().getMainAttributes().entrySet())
        headers.put(entry.getKey().toString(), entry.getValue().toString())
    } finally jis.close()

    headers
  }

  def tinybundleToJarFile(name: String): File = {
    val file = new File("target/%s-%tQ.jar".format(name, new Date()))
    val fos = new FileOutputStream(file)
    try copy(tinybundle.build(), fos) finally fos.close()

    file
  }
}

