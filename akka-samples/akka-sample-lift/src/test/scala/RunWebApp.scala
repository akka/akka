import org.eclipse.jetty.webapp.WebAppContext
import org.eclipse.jetty.server.Server

object RunWebApp extends Application {
  val server = new Server(8080)
  val context = new WebAppContext()
  context.setServer(server)
  context.setContextPath("/")
  context.setWar("src/main/webapp")

  server.setHandler(context)

  try {
    println(">>> STARTING EMBEDDED JETTY SERVER, PRESS ANY KEY TO STOP")
    server.start()
    while (System.in.available() == 0) {
      Thread.sleep(5000)
    }
    server.stop()
    server.join()
  } catch {
    case exc : Exception => {
      exc.printStackTrace()
      System.exit(100)
    }
  }
}
