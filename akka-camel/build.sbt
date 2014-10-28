import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Unidoc }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.camel

libraryDependencies ++= Dependencies.camel

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-camel").value
