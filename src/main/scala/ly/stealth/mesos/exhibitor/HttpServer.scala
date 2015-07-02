/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.exhibitor

import java.io._
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import org.apache.log4j.Logger
import org.eclipse.jetty.server.{Server, ServerConnector}
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.eclipse.jetty.util.thread.QueuedThreadPool
import play.api.libs.json.Json

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

object HttpServer {
  private val logger = Logger.getLogger(HttpServer.getClass)
  private var server: Server = null

  val jarMask = "mesos-exhibitor.*\\.jar"
  val exhibitorMask = "exhibitor.*\\.jar"
  val zookeeperMask = "zookeeper.*"

  private[exhibitor] var jar: File = null
  private[exhibitor] var exhibitorDist: File = null
  private[exhibitor] var zookeeperDist: File = null

  def start(resolveDeps: Boolean = true) {
    if (server != null) throw new IllegalStateException("HttpServer already started")
    if (resolveDeps) this.resolveDeps()

    val threadPool = new QueuedThreadPool(16)
    threadPool.setName("Jetty")

    server = new Server(threadPool)
    val connector = new ServerConnector(server)
    connector.setPort(Config.httpServerPort)
    connector.setIdleTimeout(60 * 1000)

    val handler = new ServletContextHandler
    handler.addServlet(new ServletHolder(new Servlet()), "/")

    server.setHandler(handler)
    server.addConnector(connector)
    server.start()

    logger.info("started on port " + connector.getPort)
  }

  def stop() {
    if (server == null) throw new IllegalStateException("HttpServer not started")

    server.stop()
    server.join()
    server = null

    logger.info("HttpServer stopped")
  }

  private def resolveDeps() {
    for (file <- new File(".").listFiles()) {
      if (file.getName.matches(jarMask)) jar = file
      if (file.getName.matches(exhibitorMask)) exhibitorDist = file
      if (file.getName.matches(zookeeperMask) && !file.isDirectory) zookeeperDist = file
    }

    if (jar == null) throw new IllegalStateException(jarMask + " not found in current dir")
    if (exhibitorDist == null) throw new IllegalStateException(exhibitorMask + " not found in in current dir")
    if (zookeeperDist == null) throw new IllegalStateException(zookeeperMask + " not found in in current dir")
  }

  class Servlet extends HttpServlet {
    override def doGet(request: HttpServletRequest, response: HttpServletResponse) {
      Try(handle(request, response)) match {
        case Success(_) =>
        case Failure(e) =>
          logger.warn("", e)
          response.sendError(500, "" + e)
          throw e
      }
    }

    def handle(request: HttpServletRequest, response: HttpServletResponse) {
      val uri = request.getRequestURI
      if (uri.startsWith("/jar/")) downloadFile(HttpServer.jar, response)
      else if (uri.startsWith("/exhibitor/")) downloadFile(HttpServer.exhibitorDist, response)
      else if (uri.startsWith("/zookeeper/")) downloadFile(HttpServer.zookeeperDist, response)
      else if (uri.startsWith("/s3credentials/")) downloadFile(new File(uri.split("/").last), response)
      else if (uri.startsWith("/api")) handleApi(request, response)
      else response.sendError(404)
    }

    def downloadFile(file: File, response: HttpServletResponse) {
      response.setContentType("application/zip")
      response.setHeader("Content-Length", "" + file.length())
      response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName + "\"")
      Util.copyAndClose(new FileInputStream(file), response.getOutputStream)
    }

    def handleApi(request: HttpServletRequest, response: HttpServletResponse) {
      response.setContentType("application/json; charset=utf-8")
      var uri: String = request.getRequestURI.substring("/api".length)
      if (uri.startsWith("/")) uri = uri.substring(1)

      if (uri == "add") handleAddServer(request, response)
      else if (uri == "start") handleStartServer(request, response)
      else if (uri == "stop") handleStopServer(request, response)
      else if (uri == "remove") handleRemoveServer(request, response)
      else if (uri == "status") handleClusterStatus(request, response)
      else if (uri == "config") handleConfigureServer(request, response)
      else response.sendError(404)
    }

    private def handleAddServer(request: HttpServletRequest, response: HttpServletResponse) {
      val id = request.getParameter("id")
      val cpus = Option(request.getParameter("cpu"))
      val mem = Option(request.getParameter("mem"))
      val constraints = Option(request.getParameter("constraints"))
      val backoff = Option(request.getParameter("configchangebackoff"))

      val server = ExhibitorServer(id)
      cpus.foreach(cpus => server.config.cpus = cpus.toDouble)
      mem.foreach(mem => server.config.mem = mem.toDouble)
      server.constraints ++= Constraint.parse(constraints.getOrElse("hostname=unique"))
      backoff.foreach(backoff => server.config.sharedConfigChangeBackoff = backoff.toLong)

      Scheduler.cluster.servers += server
      logger.info(s"Added server to cluster: $server")

      response.getWriter.println(Json.toJson(server))
    }

    private def handleStartServer(request: HttpServletRequest, response: HttpServletResponse) {
      val id = request.getParameter("id")

      Scheduler.cluster.getServer(id) match {
        case Some(s) =>
          if (s.state == ExhibitorServer.Added) {
            s.state = ExhibitorServer.Stopped
            logger.info(s"Starting server $id")
          } else logger.warn(s"Server $id already started")

          response.getWriter.println(Json.toJson(s))
        case None =>
          logger.warn(s"Received start server for unknown server id: $id")
          handleUnknownServer(id, response)
      }
    }

    private def handleStopServer(request: HttpServletRequest, response: HttpServletResponse) {
      val id = request.getParameter("id")
      Scheduler.stopServer(id) match {
        case Some(s) =>
          response.getWriter.println(Json.toJson(s))
        case None =>
          logger.warn(s"Received stop server for unknown server id: $id")
          handleUnknownServer(id, response)
      }
    }

    private def handleRemoveServer(request: HttpServletRequest, response: HttpServletResponse) {
      val id = request.getParameter("id")
      Scheduler.removeServer(id) match {
        case Some(s) =>
          logger.info("Cluster after removal: " + Scheduler.cluster.servers)
          response.getWriter.println(Json.toJson(s))
        case None =>
          logger.warn(s"Received remove server for unknown server id: $id")
          handleUnknownServer(id, response)
      }
    }

    private def handleClusterStatus(request: HttpServletRequest, response: HttpServletResponse) {
      response.getWriter.println(Json.toJson(Scheduler.cluster.servers.toList))
    }

    private val exhibitorConfigs = Set("configtype", "zkconfigconnect", "zkconfigzpath", "s3credentials",
      "s3region", "s3config", "s3configprefix")
    private val sharedConfigs = Set("zookeeper-install-directory", "zookeeper-data-directory")

    private def handleConfigureServer(request: HttpServletRequest, response: HttpServletResponse) {
      val id = request.getParameter("id")

      Scheduler.cluster.getServer(id) match {
        case Some(s) =>
          logger.info(s"Received configurations for server $id: ${request.getParameterMap.toMap.map(entry => entry._1 -> entry._2.head)}")

          request.getParameterMap.toMap.foreach {
            case (key, Array(value)) if exhibitorConfigs.contains(key) => s.config.exhibitorConfig += key -> value
            case (key, Array(value)) if sharedConfigs.contains(key) => s.config.sharedConfigOverride += key -> value
            case other => logger.debug(s"Got invalid configuration value: $other")
          }

          response.getWriter.println(Json.toJson(s))
        case None =>
          logger.warn(s"Received configure server for unknown server id: $id")
          handleUnknownServer(id, response)
      }
    }
  }

  private def handleUnknownServer(id: String, response: HttpServletResponse) {
    val unknownServer = ExhibitorServer(id)
    unknownServer.state = ExhibitorServer.Unknown
    response.getWriter.println(Json.toJson(unknownServer))
  }

}