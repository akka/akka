package akka.http.javadsl.server.directives

import scala.annotation.ClassfileAnnotation

/**
 * Used to hint at consistency spec implementations that a given JavaDSL method corresponds
 * to a method of given name in ScalaDSL.
 *
 * E.g. a Java method paramsList could be hinted using <code>@CorrespondsTo("paramsSeq")</code>.
 */
class CorrespondsTo(val target: String) extends ClassfileAnnotation