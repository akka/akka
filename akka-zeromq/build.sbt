import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Unidoc }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.zeroMQ

libraryDependencies ++= Dependencies.zeroMQ

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-zeromq").value
