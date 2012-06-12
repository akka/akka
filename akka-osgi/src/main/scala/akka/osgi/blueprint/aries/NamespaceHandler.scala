package akka.osgi.blueprint.aries

import org.apache.aries.blueprint.ParserContext
import org.osgi.service.blueprint.container.ComponentDefinitionException
import org.apache.aries.blueprint.mutable.MutableBeanMetadata

import collection.JavaConversions.setAsJavaSet
import org.osgi.framework.BundleContext
import org.apache.aries.blueprint.reflect.{ ValueMetadataImpl, RefMetadataImpl, BeanArgumentImpl }
import org.w3c.dom.{ NodeList, Element, Node }
import org.osgi.service.blueprint.reflect.{ BeanMetadata, ComponentMetadata }
import akka.actor.{ ActorSystem }
import akka.osgi.blueprint.{ BlueprintActorSystemFactory }
import java.util.concurrent.atomic.AtomicInteger

import ParserHelper.childElements

/**
 * Aries Blueprint namespace handler implementation
 */
class NamespaceHandler extends org.apache.aries.blueprint.NamespaceHandler {

  import NamespaceHandler._

  val idCounter = new AtomicInteger(0)

  def getSchemaLocation(namespace: String) = getClass().getResource("akka.xsd")

  def getManagedClasses = setAsJavaSet(Set(classOf[BlueprintActorSystemFactory]))

  def parse(element: Element, context: ParserContext) = element.getLocalName match {
    case ACTORSYSTEM_ELEMENT_NAME ⇒ parseActorSystem(element, context)
    case _                        ⇒ throw new ComponentDefinitionException("Unexpected element for Akka namespace: %s".format(element))
  }

  def decorate(node: Node, component: ComponentMetadata, context: ParserContext) =
    throw new ComponentDefinitionException("Bad xml syntax: node decoration is not supported");

  /*
   * Parse <akka:actor-system/>
   */
  def parseActorSystem(element: Element, context: ParserContext) = {
    val factory = createFactoryBean(context, element.getAttribute(NAME_ATTRIBUTE))

    for (child ← childElements(element)) {
      child.getLocalName match {
        case CONFIG_ELEMENT_NAME ⇒ parseConfig(child, context, factory)
        case _                   ⇒ throw new ComponentDefinitionException("Unexpected child element %s found in %s".format(child, element))
      }
    }

    createActorSystemBean(context, element, factory)
  }

  /*
   * Parse <akka:config/>
   */
  def parseConfig(node: Element, context: ParserContext, factory: MutableBeanMetadata) = {
    factory.addProperty("config", new ValueMetadataImpl(node.getTextContent))
  }

  /*
   * Create the bean definition for the ActorSystem
   */
  def createActorSystemBean(context: ParserContext, element: Element, factory: MutableBeanMetadata): MutableBeanMetadata = {
    val system = context.createMetadata(classOf[MutableBeanMetadata])
    system.setId(getId(context, element))
    system.setFactoryComponent(factory)

    system.setFactoryMethod(FACTORY_METHOD_NAME)
    system.setRuntimeClass(classOf[ActorSystem])
    system
  }

  /*
   * Create the bean definition for the BlueprintActorSystemFactory
   */
  def createFactoryBean(context: ParserContext, name: String): MutableBeanMetadata = {
    val factory = context.createMetadata(classOf[MutableBeanMetadata])
    factory.setId(findAvailableId(context))
    factory.setScope(BeanMetadata.SCOPE_SINGLETON)
    factory.setProcessor(true)
    factory.setClassName(classOf[BlueprintActorSystemFactory].getName)

    factory.setDestroyMethod(DESTROY_METHOD_NAME)

    factory.addArgument(new BeanArgumentImpl(new RefMetadataImpl(BUNDLE_CONTEXT_REFID), classOf[BundleContext].getName, -1))
    factory.addArgument(new BeanArgumentImpl(new ValueMetadataImpl(name), classOf[String].getName, -1))
    factory.setProcessor(true)
    context.getComponentDefinitionRegistry.registerComponentDefinition(factory)
    factory
  }

  /*
   * Get the assigned id or generate a suitable id
   */
  def getId(context: ParserContext, element: Element) = {
    if (element.hasAttribute(ID_ATTRIBUTE)) {
      element.getAttribute(ID_ATTRIBUTE);
    } else {
      findAvailableId(context);
    }
  }

  /*
   * Find the next available component id
   */
  def findAvailableId(context: ParserContext): String = {
    val id = ".akka-" + idCounter.incrementAndGet()
    if (context.getComponentDefinitionRegistry.containsComponentDefinition(id)) {
      // id already exists, let's try the next one
      findAvailableId(context)
    } else id
  }
}

object NamespaceHandler {

  private val ID_ATTRIBUTE = "id";
  private val NAME_ATTRIBUTE = "name";

  private val BUNDLE_CONTEXT_REFID = "blueprintBundleContext"

  private val ACTORSYSTEM_ELEMENT_NAME = "actor-system"
  private val CONFIG_ELEMENT_NAME = "config"

  private val DESTROY_METHOD_NAME = "destroy"
  private val FACTORY_METHOD_NAME = "create"

}
