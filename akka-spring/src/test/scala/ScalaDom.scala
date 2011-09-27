package akka.spring
/**
 * from http://stackoverflow.com/questions/2002685/any-conversion-from-scalas-xml-to-w3c-dom
 */

object ScalaDom {
  import scala.xml._
  import org.w3c.dom.{ Document ⇒ JDocument, Node ⇒ JNode }
  import javax.xml.parsers.DocumentBuilderFactory

  def dom(n: Node): JDocument = {

    val doc = DocumentBuilderFactory
      .newInstance
      .newDocumentBuilder
      .getDOMImplementation
      .createDocument(null, null, null)

    def build(node: Node, parent: JNode) {
      val jnode: JNode = node match {
        case e: Elem ⇒ {
          val jn = doc.createElement(e.label)
          e.attributes foreach { a ⇒ jn.setAttribute(a.key, a.value.mkString) }
          jn
        }
        case a: Atom[_]    ⇒ doc.createTextNode(a.text)
        case c: Comment    ⇒ doc.createComment(c.commentText)
        case er: EntityRef ⇒ doc.createEntityReference(er.entityName)
        case pi: ProcInstr ⇒ doc.createProcessingInstruction(pi.target, pi.proctext)
      }
      parent.appendChild(jnode)
      node.child.map { build(_, jnode) }
    }

    build(n, doc)
    doc

  }
}

