package org.virtuslab.besom.example

import sttp.tapir.*
import sttp.tapir.server.jdkhttp.*
import sttp.tapir.files.*
import java.util.concurrent.Executors
import sttp.model.HeaderNames

@main def main(): Unit =
  // required by Cloud Run Container runtime contract
  // https://cloud.google.com/run/docs/reference/container-contract
  val host = "0.0.0.0"
  val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080)

  // handle index path only
  val indexEndpoint = endpoint.get
    .in("")
    .in(extractFromRequest(_.connectionInfo.remote))
    .in(header[Option[String]](HeaderNames.XForwardedFor))
    .out(htmlBodyUtf8)
    .handle { case (requestHost, xff) =>
      val remote = xff.orElse(requestHost).getOrElse("unknown")
      val forwarded = if xff.isDefined then "forwarded" else "not forwarded"

      scribe.info(s"Received request from $remote ($forwarded) serving index.html...")

      Right(index)
    }

  // serve resources in "static" directory under static/ path
  val staticResourcesEndpoint =
    staticResourcesGetServerEndpoint[Id]("static")(
      // use classloader used to load this application
      classOf[this.type].getClassLoader,
      "static"
    )

  scribe.info(s"Starting server on $host:$port")
  val _ = JdkHttpServer()
    // use Loom's virtual threads to dispatch requests
    .executor(Executors.newVirtualThreadPerTaskExecutor())
    .host(host)
    .port(port)
    .addEndpoint(staticResourcesEndpoint)
    .addEndpoint(indexEndpoint)
    .start()

// html template using tailwind css
val index: String =
  """
  <!DOCTYPE html>
  <html lang="en">
  <head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <link href="https://cdn.jsdelivr.net/npm/tailwindcss@2.2.19/dist/tailwind.min.css" rel="stylesheet">
    <title>Scala 3 app!</title>
  </head>
  <body>
    <div class="flex h-screen w-full justify-center items-center">
      <img src="static/scala.png" alt="scala logo"/>
    </div>
  </body>
  </html>
  """
