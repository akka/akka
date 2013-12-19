package akka.http.model

import language.implicitConversions

trait MessagePredicate extends (HttpMessage ⇒ Boolean) { self ⇒
  def &&(that: MessagePredicate): MessagePredicate = new MessagePredicate {
    def apply(msg: HttpMessage) = self(msg) && that(msg)
  }
  def ||(that: MessagePredicate) = new MessagePredicate {
    def apply(msg: HttpMessage) = self(msg) || that(msg)
  }
  def unary_! = new MessagePredicate {
    def apply(msg: HttpMessage) = !self(msg)
  }
}

object MessagePredicate {
  implicit def apply(f: HttpMessage ⇒ Boolean): MessagePredicate =
    new MessagePredicate {
      def apply(msg: HttpMessage) = f(msg)
    }

  def isRequest = apply(_.isRequest)
  def isResponse = apply(_.isResponse)
  def minEntitySize(minSize: Int) = apply(_.entity.data.longLength >= minSize)
  def responseStatus(f: StatusCode ⇒ Boolean) = apply {
    case x: HttpResponse ⇒ f(x.status)
    case _: HttpRequest  ⇒ false
  }
  def isCompressible: MessagePredicate = apply {
    _.entity match {
      case HttpEntity.NonEmpty(contentType, _) ⇒ contentType.mediaType.compressible
      case _                                   ⇒ false
    }
  }
}
