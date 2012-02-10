package akka;

import akka.testkit.AkkaSpec
import akka.actor.ActorKilledException

class AkkaExceptionSpec extends AkkaSpec {

  "AkkaException" must {
    "have a AkkaException(String msg) constructor to be serialization friendly" in {
      // This is required to make Akka Exceptions be friends with serialization/deserialization.

      //if the call to this method completes, we know what there is at least a single constructor which has
      //the expected argument type.
      verify(classOf[AkkaException])

      //lets also try it for the exception that triggered this bug to be discovered.
      verify(classOf[ActorKilledException])
    }

    "include system address in toString" in {
      new AkkaException("test", system)
    }
  }

  /**
   * Verifies that the AkkaException has at least a single argument constructor of type String.
   */
  def verify(clazz: java.lang.Class[_]) {
    clazz.getConstructor(Array(classOf[String]): _*)
  }
}
