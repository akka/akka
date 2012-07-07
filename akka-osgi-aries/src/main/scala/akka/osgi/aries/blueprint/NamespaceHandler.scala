package akka.osgi.aries.blueprint

import org.osgi.framework.BundleContext
import org.osgi.service.blueprint.container.ComponentDefinitionException
import org.osgi.service.blueprint.reflect.{ BeanMetadata, ComponentMetadata }
import org.apache.aries.blueprint.ParserContext
import org.apache.aries.blueprint.mutable.MutableBeanMetadata
import org.apache.aries.blueprint.reflect.{ ValueMetadataImpl, RefMetadataImpl, BeanArgumentImpl }
import org.w3c.dom.{ Element, Node }
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import scala.annotation.tailrec
import java.net.URL

object NamespaceHandler {
  private val ID_ATTRIBUTE = "id"
  private val NAME_ATTRIBUTE = "name"

  private val BUNDLE_CONTEXT_REFID = "blueprintBundleContext"

  private val ACTORSYSTEM_ELEMENT_NAME = "actor-system"
  private val CONFIG_ELEMENT_NAME = "config"

  private val DESTROY_METHOD_NAME = "destroy"
  private val FACTORY_METHOD_NAME = "create"
}

/**
 * Aries Blueprint namespace handler implementation.  This namespace handler will allow users of Apache Aries' Blueprint
 * implementation to define their Akka [[akka.actor.ActorSystem]] using a syntax like this:
 *
 * {{{
 * <?xml version="1.0" encoding="UTF-8"?>
 * <blueprint xmlns="http://www.osgi.org/xmlns/blueprint/v1.0.0"
 *            xmlns:akka="http://akka.io/xmlns/blueprint/v1.0.0">
 *
 *   <akka:actor-system name="config">
 *      <akka:config>
 *        some.config {
 *          key=value
 *        }
 *      </akka:config>
 *   </akka:actor-system>
 *
 * </blueprint>
 * }}}
 *
 * Users of other IoC frameworks in an OSGi environment should use [[akka.osgi.OsgiActorSystemFactory]] instead.
 */
class NamespaceHandler extends org.apache.aries.blueprint.NamespaceHandler {

  import NamespaceHandler._

  protected val idCounter = new AtomicInteger(0)

  override def getSchemaLocation(namespace: String): URL = getClass().getResource("akka.xsd")

  override def getManagedClasses = java.util.Collections.singleton(classOf[BlueprintActorSystemFactory])

  override def parse(element: Element, context: ParserContext) =
    if (element.getLocalName == ACTORSYSTEM_ELEMENT_NAME) parseActorSystem(element, context)
    else throw new ComponentDefinitionException("Unexpected element for Akka namespace: %s".format(element))

  override def decorate(node: Node, component: ComponentMetadata, context: ParserContext) =
    throw new ComponentDefinitionException("Bad xml syntax: node decoration is not supported")

  /*
   * Parse <akka:actor-system/>
   */
  def parseActorSystem(element: Element, context: ParserContext) = {
    val factory = createFactoryBean(context, element.getAttribute(NAME_ATTRIBUTE))

    val nodelist = element.getChildNodes
    (0 until nodelist.getLength) collect {
      case idx if nodelist.item(idx).getNodeType == Node.ELEMENT_NODE ⇒ nodelist.item(idx).asInstanceOf[Element]
    } foreach {
      case child if child.getLocalName == CONFIG_ELEMENT_NAME ⇒ parseConfig(child, context, factory)
      case child ⇒ throw new ComponentDefinitionException("Unexpected child element %s found in %s".format(child, element))
    }

    createActorSystemBean(context, element, factory)
  }

  /*
   * Parse <akka:config/>
   */
  protected def parseConfig(node: Element, context: ParserContext, factory: MutableBeanMetadata) =
    factory.addProperty(CONFIG_ELEMENT_NAME, new ValueMetadataImpl(node.getTextContent))

  @tailrec protected final def findAvailableId(context: ParserContext): String =
    ".akka-" + idCounter.incrementAndGet() match {
      case id if context.getComponentDefinitionRegistry.containsComponentDefinition(id) ⇒ findAvailableId(context)
      case available ⇒ available
    }

  /*
   * Create the bean definition for the ActorSystem
   */
  protected def createActorSystemBean(context: ParserContext, element: Element, factory: MutableBeanMetadata): MutableBeanMetadata = {
    val system = context.createMetadata(classOf[MutableBeanMetadata])
    val id = if (element.hasAttribute(ID_ATTRIBUTE)) element.getAttribute(ID_ATTRIBUTE) else findAvailableId(context)

    system.setId(id)
    system.setFactoryComponent(factory)

    system.setFactoryMethod(FACTORY_METHOD_NAME)
    system.setRuntimeClass(classOf[ActorSystem])
    system
  }

  /*
   * Create the bean definition for the BlueprintActorSystemFactory
   */
  protected def createFactoryBean(context: ParserContext, name: String): MutableBeanMetadata = {
    val factory = context.createMetadata(classOf[MutableBeanMetadata])
    factory.setId(findAvailableId(context))
    factory.setScope(BeanMetadata.SCOPE_SINGLETON)
    factory.setProcessor(true)
    factory.setRuntimeClass(classOf[BlueprintActorSystemFactory])

    factory.setDestroyMethod(DESTROY_METHOD_NAME)

    factory.addArgument(new BeanArgumentImpl(new RefMetadataImpl(BUNDLE_CONTEXT_REFID), classOf[BundleContext].getName, -1))
    factory.addArgument(new BeanArgumentImpl(new ValueMetadataImpl(name), classOf[String].getName, -1))
    factory.setProcessor(true)
    context.getComponentDefinitionRegistry.registerComponentDefinition(factory)
    factory
  }
}