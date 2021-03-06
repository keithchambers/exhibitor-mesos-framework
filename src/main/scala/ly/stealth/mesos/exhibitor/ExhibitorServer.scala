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

import java.util.UUID

import com.google.protobuf.ByteString
import ly.stealth.mesos.exhibitor.Util.Range
import org.apache.mesos.Protos
import org.apache.mesos.Protos._
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsValue, Json, Writes, _}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.duration.Duration

case class TaskConfig(exhibitorConfig: mutable.Map[String, String], sharedConfigOverride: mutable.Map[String, String], id: String, var hostname: String = "", var sharedConfigChangeBackoff: Long = 10000, var cpus: Double = 0.2, var mem: Double = 256, var ports: List[Range] = Nil)

object TaskConfig {
  implicit val reader = (
    (__ \ 'exhibitorConfig).read[Map[String, String]].map(m => mutable.Map(m.toSeq: _*)) and
      (__ \ 'sharedConfigOverride).read[Map[String, String]].map(m => mutable.Map(m.toSeq: _*)) and
      (__ \ 'id).read[String] and
      (__ \ 'hostname).read[String] and
      (__ \ 'sharedConfigChangeBackoff).read[Long] and
      (__ \ 'cpu).read[Double] and
      (__ \ 'mem).read[Double] and
      (__ \ 'ports).read[String].map(Range.parseRanges))(TaskConfig.apply _)

  implicit val writer = new Writes[TaskConfig] {
    def writes(tc: TaskConfig): JsValue = {
      Json.obj(
        "exhibitorConfig" -> tc.exhibitorConfig.toMap[String, String],
        "sharedConfigOverride" -> tc.sharedConfigOverride.toMap[String, String],
        "id" -> tc.id,
        "hostname" -> tc.hostname,
        "cpu" -> tc.cpus,
        "mem" -> tc.mem,
        "sharedConfigChangeBackoff" -> tc.sharedConfigChangeBackoff,
        "ports" -> tc.ports.mkString(",")
      )
    }
  }
}

case class ExhibitorServer(id: String) {
  private[exhibitor] var task: ExhibitorServer.Task = null

  val config = TaskConfig(new mutable.HashMap[String, String](), new mutable.HashMap[String, String](), id)

  private[exhibitor] val constraints: mutable.Map[String, List[Constraint]] = new mutable.HashMap[String, List[Constraint]]
  private[exhibitor] var state: ExhibitorServer.State = ExhibitorServer.Added

  def createTask(offer: Offer): TaskInfo = {
    val port = getPort(offer).getOrElse(throw new IllegalStateException("No suitable port"))

    val name = s"exhibitor-${this.id}"
    val id = ExhibitorServer.nextTaskId(this.id)
    this.config.exhibitorConfig.put("port", port.toString)
    this.config.hostname = offer.getHostname
    val taskId = TaskID.newBuilder().setValue(id).build
    TaskInfo.newBuilder().setName(name).setTaskId(taskId).setSlaveId(offer.getSlaveId)
      .setExecutor(newExecutor(this.id))
      .setData(ByteString.copyFromUtf8(Json.stringify(Json.toJson(this.config))))
      .addResources(Protos.Resource.newBuilder().setName("cpus").setType(Protos.Value.Type.SCALAR).setScalar(Protos.Value.Scalar.newBuilder().setValue(this.config.cpus)))
      .addResources(Protos.Resource.newBuilder().setName("mem").setType(Protos.Value.Type.SCALAR).setScalar(Protos.Value.Scalar.newBuilder().setValue(this.config.mem)))
      .addResources(Protos.Resource.newBuilder().setName("ports").setType(Protos.Value.Type.RANGES).setRanges(
      Protos.Value.Ranges.newBuilder().addRange(Protos.Value.Range.newBuilder().setBegin(port).setEnd(port))
    )).build
  }

  def matches(offer: Offer, otherAttributes: String => List[String] = _ => Nil): Option[String] = {
    val offerResources = offer.getResourcesList.toList.map(res => res.getName -> res).toMap

    if (getPort(offer).isEmpty) return Some("no suitable port")

    offerResources.get("cpus") match {
      case Some(cpusResource) => if (cpusResource.getScalar.getValue < config.cpus) return Some(s"cpus ${cpusResource.getScalar.getValue} < ${config.cpus}")
      case None => return Some("no cpus")
    }

    offerResources.get("mem") match {
      case Some(memResource) => if (memResource.getScalar.getValue < config.mem) return Some(s"mem ${memResource.getScalar.getValue} < ${config.mem}")
      case None => return Some("no mem")
    }

    val offerAttributes = offer.getAttributesList.toList.foldLeft(Map("hostname" -> offer.getHostname)) { case (attributes, attribute) =>
      if (attribute.hasText) attributes.updated(attribute.getName, attribute.getText.getValue)
      else attributes
    }

    for ((name, constraints) <- constraints) {
      for (constraint <- constraints) {
        offerAttributes.get(name) match {
          case Some(attribute) => if (!constraint.matches(attribute, otherAttributes(name))) return Some(s"$name doesn't match $constraint")
          case None => return Some(s"no $name")
        }
      }
    }

    None
  }

  def waitFor(state: ExhibitorServer.State, timeout: Duration): Boolean = {
    var t = timeout.toMillis
    while (t > 0 && this.state != state) {
      val delay = Math.min(100, t)
      Thread.sleep(delay)
      t -= delay
    }

    this.state == state
  }

  def isReconciling: Boolean = this.state == ExhibitorServer.Reconciling

  private[exhibitor] def newExecutor(id: String): ExecutorInfo = {
    val java = "$(find jdk* -maxdepth 0 -type d)" // find non-recursively a directory starting with "jdk"
    val cmd = s"export PATH=$$MESOS_DIRECTORY/$java/bin:$$PATH && java -cp ${HttpServer.jar.getName}${if (Config.debug) " -Ddebug" else ""} ly.stealth.mesos.exhibitor.Executor"

    val commandBuilder = CommandInfo.newBuilder()
    commandBuilder
      .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.api}/exhibitor/" + HttpServer.exhibitorDist.getName))
      .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.api}/zookeeper/" + HttpServer.zookeeperDist.getName).setExtract(true))
      .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.api}/jdk/" + HttpServer.jdkDist.getName).setExtract(true))
      .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.api}/jar/" + HttpServer.jar.getName))
      .setValue(cmd)

    this.config.exhibitorConfig.get("s3credentials").foreach { creds =>
      commandBuilder
        .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.api}/s3credentials/" + creds))
    }

    this.config.exhibitorConfig.get("defaultconfig").foreach { config =>
      commandBuilder
        .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.api}/defaultconfig/" + config))
    }

    ExecutorInfo.newBuilder()
      .setExecutorId(ExecutorID.newBuilder().setValue(id))
      .setCommand(commandBuilder)
      .setName(s"exhibitor-$id")
      .build
  }

  private[exhibitor] def getPort(offer: Offer): Option[Long] = {
    val ports = Util.getRangeResources(offer, "ports").map(r => Range(r.getBegin.toInt, r.getEnd.toInt))

    if (config.ports == Nil) ports.headOption.map(_.start)
    else ports.flatMap(range => config.ports.flatMap(range.overlap)).headOption.map(_.start)
  }

  def url: String = s"http://${config.hostname}:${config.exhibitorConfig("port")}"
}

object ExhibitorServer {

  case class Task(id: String, slaveId: String, executorId: String, attributes: Map[String, String])

  object Task {
    implicit val writer = Json.writes[Task]
    implicit val reader = Json.reads[Task]
  }

  def nextTaskId(serverId: String): String = s"exhibitor-$serverId-${UUID.randomUUID()}"

  def idFromTaskId(taskId: String): String = {
    taskId.split("-", 3) match {
      case Array(_, id, _) => id
      case _ => throw new IllegalArgumentException(taskId)
    }
  }

  sealed trait State

  case object Added extends State

  case object Stopped extends State

  case object Staging extends State

  case object Running extends State

  case object Reconciling extends State

  implicit val writer = new Writes[ExhibitorServer] {
    def writes(es: ExhibitorServer): JsValue = {
      Json.obj(
        "id" -> es.id,
        "state" -> es.state.toString,
        "task" -> Option(es.task),
        "constraints" -> Util.formatConstraints(es.constraints),
        "config" -> es.config
      )
    }
  }

  implicit val reader = (
    (__ \ 'id).read[String] and
      (__ \ 'state).read[String] and
      (__ \ 'task).readNullable[Task] and
      (__ \ 'constraints).read[String].map(Constraint.parse) and
      (__ \ 'config).read[TaskConfig])((id, state, task, constraints, config) => {
    val server = ExhibitorServer(id)
    state match {
      case "Added" => server.state = Added
      case "Stopped" => server.state = Stopped
      case "Staging" => server.state = Staging
      case "Running" => server.state = Running
      case "Reconciling" => server.state = Reconciling
    }
    server.task = task.orNull
    constraints.foreach(server.constraints += _)
    config.exhibitorConfig.foreach(server.config.exhibitorConfig += _)
    config.sharedConfigOverride.foreach(server.config.sharedConfigOverride += _)
    server.config.cpus = config.cpus
    server.config.mem = config.mem
    server.config.sharedConfigChangeBackoff = config.sharedConfigChangeBackoff
    server.config.hostname = config.hostname
    server.config.ports = config.ports
    server
  })
}


/**
 * @param server Exhibitor-on-mesos server instance
 * @param exhibitorClusterView a holder for Exhibitor's /status endpoint response - the view of the Exhibitor cluster
 * status from the particular node
 */
case class ExhibitorOnMesosServerStatus(server: ExhibitorServer, exhibitorClusterView: Option[Seq[ExhibitorServerStatus]])
object ExhibitorOnMesosServerStatus{

  implicit val writer = new Writes[ExhibitorOnMesosServerStatus] {
    def writes(emss: ExhibitorOnMesosServerStatus): JsValue = {
      Json.obj(
        "server" -> emss.server,
        "exhibitorClusterView" -> emss.exhibitorClusterView
      )
    }
  }

  implicit val reader = (
    (__ \ 'server).read[ExhibitorServer] and
      (__ \ 'exhibitorClusterView).read[Option[Seq[ExhibitorServerStatus]]])(ExhibitorOnMesosServerStatus.apply _)
}

case class ClusterStatus(serverStatuses: Seq[ExhibitorOnMesosServerStatus]){
  val servers = serverStatuses.map(_.server)
}

object ClusterStatus{

  implicit val writer = new Writes[ClusterStatus] {
    def writes(cs: ClusterStatus): JsValue = {
      Json.obj(
        "serverStatuses" -> cs.serverStatuses
      )
    }
  }

  implicit val reader = (__ \ 'serverStatuses).read[Seq[ExhibitorOnMesosServerStatus]].map{ l => ClusterStatus(l) }
}
