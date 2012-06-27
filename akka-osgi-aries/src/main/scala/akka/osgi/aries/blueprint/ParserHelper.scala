package akka.osgi.aries.blueprint

import org.w3c.dom.{ Node, Element }

/**
 * Helper class to deal with the W3C DOM types
 */
object ParserHelper {

  def childElements(element: Element): Seq[Element] =
    children(element).filter(_.getNodeType == Node.ELEMENT_NODE).asInstanceOf[Seq[Element]]

  private[this] def children(element: Element): Seq[Node] = {
    val nodelist = element.getChildNodes
    for (index ← 0 until nodelist.getLength) yield nodelist.item(index)
  }
}
