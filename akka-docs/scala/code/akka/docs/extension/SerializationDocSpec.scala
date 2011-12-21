/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.extension

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

class SerializationDocSpec extends WordSpec with MustMatchers {

  "demonstrate how to use Serialization" in {
    """
     serializers {
      # java = "akka.serialization.JavaSerializer"
      # proto = "akka.serialization.ProtobufSerializer"

      default = "akka.serialization.JavaSerializer"
    }

    # serialization-bindings {
    #   java = ["akka.serialization.SerializeSpec$Address",
    #           "akka.serialization.MyJavaSerializableActor",
    #           "akka.serialization.MyStatelessActorWithMessagesInMailbox",
    #           "akka.serialization.MyActorWithProtobufMessagesInMailbox"]
    #   proto = ["com.google.protobuf.Message",
    #            "akka.actor.ProtobufProtocol$MyMessage"]
    # }
    """
  }

}
