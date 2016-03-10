import akka.{ AkkaBuild, Formatting, OSGi, Dependencies }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.testkit
Dependencies.testkit // to fix scaladoc generation

initialCommands += "import akka.testkit._"
