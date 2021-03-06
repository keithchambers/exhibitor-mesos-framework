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

import java.io.{DataOutputStream, File, IOException}
import java.net.{HttpURLConnection, URL, URLClassLoader}
import java.nio.file.{Files, Paths}

import org.apache.log4j.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.io.Source
import scala.util.{Failure, Success, Try}

trait IExhibitor {
  def isStarted: Boolean

  def start(config: TaskConfig)

  def stop()

  def await()
}

class Exhibitor extends IExhibitor {
  private final val ZK_DATA_DIR = new File("./zkdata")
  private final val ZK_LOG_DIR = new File("./zklog")
  private final val ZK_LOG_INDEX_DIR = new File("./zklogindex")

  private val logger = Logger.getLogger(classOf[Exhibitor])
  @volatile var server: AnyRef = null

  private var config: TaskConfig = null
  private var sharedConfig: SharedConfig = null

  def url: String = s"http://${config.hostname}:${config.exhibitorConfig("port")}"

  def isStarted: Boolean = server != null

  def start(config: TaskConfig) {
    if (isStarted) throw new IllegalStateException("Already started")

    this.config = config

    Thread.currentThread().setContextClassLoader(Exhibitor.loader)

    server = Exhibitor.newServer(config.exhibitorConfig.toMap)

    logger.info("Starting Exhibitor Server")
    server.getClass.getMethod("start").invoke(server)

    listenForConfigChanges()
  }

  def await() {
    if (server != null)
      server.getClass.getMethod("join").invoke(server)
  }

  def stop() {
    this.synchronized {
      if (server != null) {
        val shutdownSignaledField = server.getClass.getDeclaredField("shutdownSignaled")
        shutdownSignaledField.setAccessible(true)
        val shutdownSignaled = shutdownSignaledField.get(server)
        shutdownSignaled.getClass.getMethod("set", classOf[Boolean]).invoke(shutdownSignaled, true: java.lang.Boolean)
        server.getClass.getMethod("close").invoke(server)
      }

      server = null
    }
    //TODO
    //for ( Closeable closeable : creator.getCloseables() )
    //{
    //  CloseableUtils.closeQuietly(closeable);
    //}
  }

  private def listenForConfigChanges() {
    new Thread {
      override def run() {
        while (isStarted) {
          val newConfig = ExhibitorAPI.getSystemState(url)
          if (newConfig != sharedConfig) {
            logger.debug("Shared configuration changed, applying changes")
            sharedConfig = newConfig

            applyChanges()
          }

          Thread.sleep(config.sharedConfigChangeBackoff)
        }
      }
    }.start()
  }

  private def applyChanges() {
    createSymlinkIfNotEmpty(sharedConfig.zookeeperInstallDirectory, findZookeeperDist)
    createSymlinkIfNotEmpty(sharedConfig.zookeeperDataDirectory, ZK_DATA_DIR)
    createSymlinkIfNotEmpty(sharedConfig.zookeeperLogDirectory, ZK_LOG_DIR)
    createSymlinkIfNotEmpty(sharedConfig.logIndexDirectory, ZK_LOG_INDEX_DIR)
  }

  private def createSymlinkIfNotEmpty(link: String, target: File) {
    if (link != "") {
      logger.debug(s"Creating symbolic link $link to ${Paths.get(target.toURI)}")
      target.mkdirs() //create directories if they do not exist yet
      new File(link).delete() //remove symlink if already exists
      Files.createSymbolicLink(Paths.get(link), Paths.get(target.toURI)) //create a new symlink
    }
  }

  private def findZookeeperDist: File = {
    for (file <- new File(System.getProperty("user.dir")).listFiles()) {
      if (file.getName.matches(HttpServer.zookeeperMask) && file.isDirectory) return file
    }

    throw new IllegalStateException("Directory that matches " + HttpServer.zookeeperMask + " not found in in current dir")
  }
}

object Exhibitor {
  private val logger = Logger.getLogger(classOf[Exhibitor])
  private lazy val loader = initLoader

  private def initLoader: ClassLoader = {
    new File(".").listFiles().find(file => file.getName.matches(HttpServer.exhibitorMask)) match {
      case None => throw new IllegalStateException("Exhibitor standalone jar not found")
      case Some(exhibitorDist) => URLClassLoader.newInstance(Array(exhibitorDist.toURI.toURL), getClass.getClassLoader)
    }
  }

  def newServer(props: Map[String, String]): AnyRef = {
    val params = props.flatMap { case (key, value) =>
      Array(s"--$key", value)
    }.toArray
    logger.info(s"Exhibitor params: ${params.mkString(" ")}")

    val exhibitorCreatorClass = loader.loadClass("com.netflix.exhibitor.standalone.ExhibitorCreator")
    val securityArgumentsClass = loader.loadClass("com.netflix.exhibitor.standalone.SecurityArguments")
    val exhibitorMainClass = loader.loadClass("com.netflix.exhibitor.application.ExhibitorMain")
    val backupProviderClass = loader.loadClass("com.netflix.exhibitor.core.backup.BackupProvider")
    val configProviderClass = loader.loadClass("com.netflix.exhibitor.core.config.ConfigProvider")
    val builderClass = loader.loadClass("com.netflix.exhibitor.core.ExhibitorArguments$Builder")
    val securityHandlerClass = loader.loadClass("org.mortbay.jetty.security.SecurityHandler")

    val exhibitorCreator = exhibitorCreatorClass.getConstructor(classOf[Array[String]]).newInstance(params).asInstanceOf[AnyRef]

    val securityFile = exhibitorCreatorClass.getMethod("getSecurityFile").invoke(exhibitorCreator)
    val realmSpec = exhibitorCreatorClass.getMethod("getRealmSpec").invoke(exhibitorCreator)
    val remoteAuthSpec = exhibitorCreatorClass.getMethod("getRemoteAuthSpec").invoke(exhibitorCreator)
    val securityArguments = securityArgumentsClass.getConstructor(classOf[String], classOf[String], classOf[String]).newInstance(securityFile, realmSpec, remoteAuthSpec).asInstanceOf[AnyRef]

    val backupProvider = exhibitorCreatorClass.getMethod("getBackupProvider").invoke(exhibitorCreator)
    val configProvider = exhibitorCreatorClass.getMethod("getConfigProvider").invoke(exhibitorCreator)
    val builder = exhibitorCreatorClass.getMethod("getBuilder").invoke(exhibitorCreator)
    val httpPort = exhibitorCreatorClass.getMethod("getHttpPort").invoke(exhibitorCreator)
    val securityHandler = exhibitorCreatorClass.getMethod("getSecurityHandler").invoke(exhibitorCreator)
    val exhibitorMain = exhibitorMainClass.getConstructor(backupProviderClass, configProviderClass, builderClass, Integer.TYPE, securityHandlerClass, securityArgumentsClass)
      .newInstance(backupProvider, configProvider, builder, httpPort, securityHandler, securityArguments).asInstanceOf[AnyRef]

    exhibitorMain
  }
}

case class Result(succeeded: Boolean, message: String)

object Result {
  implicit val reader = Json.reads[Result]
}

// have to use this to overcome 22 fields limitation
case class Ports(client: Int, connect: Int, election: Int)

object Ports {
  implicit val reader = (
    (__ \ 'clientPort).read[Int] and
      (__ \ 'connectPort).read[Int] and
      (__ \ 'electionPort).read[Int])(Ports.apply _)
}

case class SharedConfig(logIndexDirectory: String, zookeeperInstallDirectory: String, zookeeperDataDirectory: String,
                        zookeeperLogDirectory: String, serversSpec: String, backupExtra: String, zooCfgExtra: Map[String, String],
                        javaEnvironment: String, log4jProperties: String, ports: Ports, checkMs: Long, cleanupPeriodMs: Long, cleanupMaxFiles: Int,
                        backupMaxStoreMs: Long, backupPeriodMs: Long, autoManageInstances: Int, autoManageInstancesSettlingPeriodMs: Long,
                        observerThreshold: Int, autoManageInstancesFixedEnsembleSize: Int, autoManageInstancesApplyAllAtOnce: Int)

object SharedConfig {
  implicit val reader = (
    (__ \ 'logIndexDirectory).read[String] and
      (__ \ 'zookeeperInstallDirectory).read[String] and
      (__ \ 'zookeeperDataDirectory).read[String] and
      (__ \ 'zookeeperLogDirectory).read[String] and
      (__ \ 'serversSpec).read[String] and
      (__ \ 'backupExtra).read[String] and
      (__ \ 'zooCfgExtra).read[Map[String, String]] and
      (__ \ 'javaEnvironment).read[String] and
      (__ \ 'log4jProperties).read[String] and
      Ports.reader and
      (__ \ 'checkMs).read[Long] and
      (__ \ 'cleanupPeriodMs).read[Long] and
      (__ \ 'cleanupMaxFiles).read[Int] and
      (__ \ 'backupMaxStoreMs).read[Long] and
      (__ \ 'backupPeriodMs).read[Long] and
      (__ \ 'autoManageInstances).read[Int] and
      (__ \ 'autoManageInstancesSettlingPeriodMs).read[Long] and
      (__ \ 'observerThreshold).read[Int] and
      (__ \ 'autoManageInstancesFixedEnsembleSize).read[Int] and
      (__ \ 'autoManageInstancesApplyAllAtOnce).read[Int])(SharedConfig.apply _)

  // Exhibitor for some reason requires the values passed back to be strings, so have to define custom writer for it.
  implicit val writer = new Writes[SharedConfig] {
    def writes(sc: SharedConfig): JsValue = {
      Json.obj(
        "logIndexDirectory" -> sc.logIndexDirectory,
        "zookeeperInstallDirectory" -> sc.zookeeperInstallDirectory,
        "zookeeperDataDirectory" -> sc.zookeeperDataDirectory,
        "zookeeperLogDirectory" -> sc.zookeeperLogDirectory,
        "serversSpec" -> sc.serversSpec,
        "backupExtra" -> sc.backupExtra,
        "zooCfgExtra" -> sc.zooCfgExtra,
        "javaEnvironment" -> sc.javaEnvironment,
        "log4jProperties" -> sc.log4jProperties,
        "clientPort" -> sc.ports.client.toString,
        "connectPort" -> sc.ports.connect.toString,
        "electionPort" -> sc.ports.election.toString,
        "checkMs" -> sc.checkMs.toString,
        "cleanupPeriodMs" -> sc.cleanupPeriodMs.toString,
        "cleanupMaxFiles" -> sc.cleanupMaxFiles.toString,
        "backupMaxStoreMs" -> sc.backupMaxStoreMs.toString,
        "backupPeriodMs" -> sc.backupPeriodMs.toString,
        "autoManageInstances" -> sc.autoManageInstances.toString,
        "autoManageInstancesSettlingPeriodMs" -> sc.autoManageInstancesSettlingPeriodMs.toString,
        "observerThreshold" -> sc.observerThreshold.toString,
        "autoManageInstancesFixedEnsembleSize" -> sc.autoManageInstancesFixedEnsembleSize.toString,
        "autoManageInstancesApplyAllAtOnce" -> sc.autoManageInstancesApplyAllAtOnce.toString
      )
    }
  }
}

case class ExhibitorServerStatus(hostname: String, isLeader: Boolean, description: String, code: Int)

object ExhibitorServerStatus {
  implicit val reader = (
    (__ \ 'hostname).read[String] and
      (__ \ 'isLeader).read[Boolean] and
      (__ \ 'description).read[String] and
      (__ \ 'code).read[Int])(ExhibitorServerStatus.apply _)

  implicit val writer = new Writes[ExhibitorServerStatus] {
    def writes(ess: ExhibitorServerStatus): JsValue = {
      Json.obj(
        "hostname" -> ess.hostname,
        "isLeader" -> ess.isLeader,
        "description" -> ess.description,
        "code" -> ess.code)
    }
  }
}

object ExhibitorAPI {
  private val logger = Logger.getLogger(ExhibitorAPI.getClass)

  private val getSystemStateURL = "exhibitor/v1/config/get-state"
  private val setConfigURL = "exhibitor/v1/config/set"
  private val getStatus = "exhibitor/v1/cluster/status"

  def getSystemState(baseUrl: String): SharedConfig = {
    val url = s"$baseUrl/$getSystemStateURL"
    val connection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    try {
      readResponse(connection, response => {
        (Json.parse(response) \ "config").validate[SharedConfig] match {
          case JsSuccess(config, _) => config
          case JsError(error) => throw new IllegalStateException(error.toString())
        }
      })
    } finally {
      connection.disconnect()
    }
  }

  def setConfig(config: SharedConfig, baseUrl: String) {
    logger.debug(s"Trying to save shared config: $config")

    val url = s"$baseUrl/$setConfigURL"
    val connection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    try {
      connection.setRequestMethod("POST")
      connection.setRequestProperty("Content-Type", "application/json")

      connection.setUseCaches(false)
      connection.setDoInput(true)
      connection.setDoOutput(true)

      val out = new DataOutputStream(connection.getOutputStream)
      out.writeBytes(Json.prettyPrint(Json.toJson(config)))
      out.flush()
      out.close()

      readResponse(connection, response => {
        Json.parse(response).validate[Result] match {
          case JsSuccess(result, _) => if (!result.succeeded) throw new IllegalStateException(result.message)
          case JsError(error) => throw new IllegalStateException(error.toString())
        }
      })
    } finally {
      connection.disconnect()
    }
  }

  def   getClusterStatus(baseUrl: String): Seq[ExhibitorServerStatus] = {
    val url = s"$baseUrl/$getStatus"
    val connection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    try {
      readResponse(connection, response => {
        Json.parse(response).validate[Seq[ExhibitorServerStatus]] match {
          case JsSuccess(serverStatuses, _) => serverStatuses
          case JsError(error) => throw new IllegalStateException(error.toString())
        }
      })
    } finally {
      connection.disconnect()
    }
  }

  private def readResponse[A](connection: HttpURLConnection, reader: String => A): A = {
    Try(Source.fromInputStream(connection.getInputStream).getLines().mkString) match {
      case Success(response) => reader(response)
      case Failure(e) =>
        if (connection.getResponseCode != 200) throw new IOException(connection.getResponseCode + " - " + connection.getResponseMessage)
        else throw e
    }
  }
}
