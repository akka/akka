import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Unidoc }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.slf4j

libraryDependencies ++= Dependencies.slf4j

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-slf4j").value
