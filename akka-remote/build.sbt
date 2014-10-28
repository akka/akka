import akka.{AkkaBuild, Dependencies, Formatting, Unidoc, OSGi}
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.remote

libraryDependencies ++= Dependencies.remote

parallelExecution in Test := false

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-remote").value
