package akka.osgi.blueprint.aries

import org.apache.aries.blueprint.ParserContext
import org.osgi.service.blueprint.container.ComponentDefinitionException
import org.apache.aries.blueprint.mutable.MutableBeanMetadata

import collection.JavaConversions.setAsJavaSet
import org.osgi.framework.BundleContext
import org.apache.aries.blueprint.reflect.{ ValueMetadataImpl, RefMetadataImpl, BeanArgumentImpl }
import org.w3c.dom.{ NodeList, Element, Node }
import org.osgi.service.blueprint.reflect.{ BeanMetadata, ComponentMetadata }
import akka.actor.{ ActorRef, ActorSystem }
import akka.osgi.blueprint.{ BlueprintActorSystem, BlueprintActorSystemFactory }

/**
 * Aries Blueprint namespace handler implementation
 */
class NamespaceHandler extends org.apache.aries.blueprint.NamespaceHandler {

  val CLASS_ATTRIBUTE = "class";
  val ID_ATTRIBUTE = "id";
  val NAME_ATTRIBUTE = "name";

  var idCounter = 1

  def getSchemaLocation(namespace: String) = getClass().getResource("akka.xsd")

  def getManagedClasses = setAsJavaSet(Set(classOf[BlueprintActorSystemFactory]))

  def parse(element: Element, context: ParserContext) = {
    val factory = context.createMetadata(classOf[MutableBeanMetadata])
    factory.setId(getId(context, element))
    factory.setScope(BeanMetadata.SCOPE_SINGLETON)
    factory.setProcessor(true)
    factory.setClassName(classOf[BlueprintActorSystemFactory].getName)
    factory.setDestroyMethod("destroy")
    factory.addArgument(new BeanArgumentImpl(new RefMetadataImpl("blueprintBundleContext"), classOf[BundleContext].getName, -1))

    val system = context.createMetadata(classOf[MutableBeanMetadata])
    system.setId(getId(context, element))
    system.setFactoryComponent(factory)
    system.setFactoryMethod("create")
    system.setRuntimeClass(classOf[ActorSystem])
    if (element.hasAttribute(NAME_ATTRIBUTE)) {
      system.addArgument(new BeanArgumentImpl(new ValueMetadataImpl(element.getAttribute(NAME_ATTRIBUTE)), classOf[String].getName, -1))
    }

    val actorsystem = context.createMetadata(classOf[MutableBeanMetadata])
    actorsystem.setId(getId(context, element))
    actorsystem.setClassName(classOf[BlueprintActorSystem].getName)
    actorsystem.addArgument(new BeanArgumentImpl(new RefMetadataImpl("blueprintBundleContext"), classOf[BundleContext].getName, -1))
    actorsystem.addArgument(new BeanArgumentImpl(system, classOf[ActorSystem].getName, -1))
    context.getComponentDefinitionRegistry.registerComponentDefinition(actorsystem)

    val nodelist = element.getChildNodes
    var i = 0
    while (i < nodelist.getLength) {
      val node = nodelist.item(i)
      node.getLocalName match {
        case "actor" if node.isInstanceOf[Element] ⇒ parseActor(node.asInstanceOf[Element], context, actorsystem)
        case _                                     ⇒
      }
      i += 1
    }
    factory
  }

  def parseActor(node: Element, context: ParserContext, actorsystem: MutableBeanMetadata) = {
    val actor = context.createMetadata(classOf[MutableBeanMetadata])
    actor.setFactoryComponent(actorsystem)
    if (node.hasAttribute(CLASS_ATTRIBUTE)) {
      actor.addArgument(new BeanArgumentImpl(new ValueMetadataImpl(node.getAttribute(CLASS_ATTRIBUTE)), classOf[String].getName, -1))
    }
    actor.setId(getId(context, node))
    actor.setFactoryMethod("createActor")
    //    actor.setRuntimeClass(classOf[ActorRef])
    context.getComponentDefinitionRegistry.registerComponentDefinition(actor)
  }

  def decorate(node: Node, component: ComponentMetadata, context: ParserContext) =
    throw new ComponentDefinitionException("Bad xml syntax: node decoration is not supported");

  def getId(context: ParserContext, element: Element) = {
    if (element.hasAttribute(ID_ATTRIBUTE)) {
      element.getAttribute(ID_ATTRIBUTE);
    } else {
      generateId(context);
    }
  }

  def generateId(context: ParserContext): String = {
    var id = "";
    do {
      idCounter += 1
      id = ".akka-" + idCounter;
    } while (context.getComponentDefinitionRegistry().containsComponentDefinition(id));
    id;
  }

}
