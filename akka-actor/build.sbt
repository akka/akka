import akka.{ AkkaBuild, Formatting, OSGi, Unidoc, Dependencies }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.actor

libraryDependencies ++= Dependencies.actor

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-actor").value
