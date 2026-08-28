/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.mcp

import java.time.Instant
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, ExecutorService, TimeoutException, TimeUnit}
import java.util.function.Supplier

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf.{FRONTEND_PROXY_HTTP_CLIENT_IP_HEADER, FRONTEND_REST_BIND_PORT}
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_NAMESPACE
import org.apache.kyuubi.ha.client.{DiscoveryPaths, ServiceDiscovery}
import org.apache.kyuubi.ha.client.DiscoveryClientProvider.withDiscoveryClient
import org.apache.kyuubi.server.KyuubiRestFrontendService
import org.apache.kyuubi.server.api.v1.InternalRestClient
import org.apache.kyuubi.service.authentication.InternalSecurityAccessor
import org.apache.kyuubi.util.ThreadUtils

/** Coordinates cluster-wide MCP diagnostics without recursively invoking the public MCP endpoint. */
private[mcp] class KyuubiMcpClusterDiagnostics(
    frontendService: KyuubiRestFrontendService,
    objectMapper: ObjectMapper) extends Logging {

  import KyuubiMcpClusterDiagnostics._
  import KyuubiMcpLocalDiagnostics._

  private val localDiagnostics = new KyuubiMcpLocalDiagnostics(frontendService, objectMapper)
  private val executor: ExecutorService =
    ThreadUtils.newDaemonFixedThreadPool(MAX_CONCURRENT_PEERS, "mcp-cluster-diagnostics")
  private val clients = new ConcurrentHashMap[String, InternalRestClient]()

  def listSessions(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val limit = boundedIntArgument(arguments, "limit", 100, 200)
    aggregateList(LIST_SESSIONS, arguments, principal, "sessions", "identifier", limit)
  }

  def getSession(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    requiredStringArgument(arguments, "session_id")
    aggregateLookup(GET_SESSION, arguments, principal, "session")
  }

  def listOperations(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val limit = boundedIntArgument(arguments, "limit", 100, 200)
    aggregateList(LIST_OPERATIONS, arguments, principal, "operations", "identifier", limit)
  }

  def getOperation(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    requiredStringArgument(arguments, "operation_id")
    aggregateLookup(GET_OPERATION, arguments, principal, "operation")
  }

  def readOperationLog(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    requiredStringArgument(arguments, "operation_id")
    boundedIntArgument(arguments, "max_rows", 100, 1000)
    aggregateLookup(READ_OPERATION_LOG, arguments, principal, "operationLog")
  }

  def close(): Unit = ThreadUtils.shutdown(executor)

  private def aggregateList(
      action: String,
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal,
      resultName: String,
      identifierName: String,
      limit: Int): java.util.Map[String, Object] = {
    val fanout = executeAcrossCluster(action, arguments, principal)
    val items = fanout.responses.flatMap { response =>
      Option(response.payload.get("items")) match {
        case Some(values: java.util.List[_]) =>
          values.asScala.collect {
            case value: java.util.Map[_, _] =>
              value.asInstanceOf[java.util.Map[String, Object]]
          }
        case _ => Seq.empty
      }
    }
      .groupBy(item => Option(item.get(identifierName)).map(_.toString).getOrElse(""))
      .values
      .map(_.head)
      .toSeq
      .sortBy(item => longValue(item.get("createTime")))(Ordering.Long.reverse)
      .take(limit)

    envelope(
      Map[String, Object](
        resultName -> items.asJava,
        "count" -> Int.box(items.size)),
      fanout)
  }

  private def aggregateLookup(
      action: String,
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal,
      resultName: String): java.util.Map[String, Object] = {
    val fanout = executeAcrossCluster(action, arguments, principal)
    val found = fanout.responses.iterator.flatMap { response =>
      if (java.lang.Boolean.TRUE == response.payload.get("found")) {
        Option(response.payload.get("value")) collect {
          case value: java.util.Map[_, _] =>
            response.instance -> value.asInstanceOf[java.util.Map[String, Object]]
        }
      } else {
        None
      }
    }.toSeq.headOption
    val values = Map[String, Object](
      "found" -> Boolean.box(found.nonEmpty),
      resultName -> found.map(_._2).orNull,
      "server" -> found.map(_._1).orNull)
    envelope(values, fanout)
  }

  private def executeAcrossCluster(
      action: String,
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): FanoutResult = {
    val allInstances = discoverInstances()
    val instances = allInstances.take(MAX_CLUSTER_PEERS)
    val failures = ListBuffer[PeerFailure]()
    allInstances.drop(MAX_CLUSTER_PEERS).foreach(instance =>
      failures += PeerFailure(instance, "fanout limit exceeded"))

    val localInstance = normalizeInstance(frontendService.connectionUrl)
    val remoteInstances = instances.filterNot(_ == localInstance)
    if (remoteInstances.nonEmpty && InternalSecurityAccessor.get() == null) {
      throw new IllegalStateException(
        "Cluster MCP diagnostics require kyuubi.internal.security.enabled=true")
    }

    val futures = instances.map { instance =>
      instance -> CompletableFuture.supplyAsync(
        new Supplier[PeerResponse] {
          override def get(): PeerResponse = {
            val payload = if (instance == localInstance) {
              localDiagnostics.execute(action, arguments, principal)
            } else {
              executeRemote(instance, action, arguments, principal)
            }
            PeerResponse(instance, payload)
          }
        },
        executor)
    }

    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FANOUT_DEADLINE_MS)
    val responses = ListBuffer[PeerResponse]()
    futures.foreach { case (instance, future) =>
      val remainingNanos = deadlineNanos - System.nanoTime()
      if (remainingNanos <= 0) {
        future.cancel(true)
        failures += PeerFailure(instance, "deadline exceeded")
      } else {
        try {
          responses += future.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch {
          case _: TimeoutException =>
            future.cancel(true)
            failures += PeerFailure(instance, "timeout")
          case NonFatal(e) =>
            debug(s"MCP diagnostic request to $instance failed", e)
            failures += PeerFailure(instance, "unavailable")
        }
      }
    }
    FanoutResult(allInstances.size, responses.toSeq, failures.toSeq)
  }

  private def executeRemote(
      instance: String,
      action: String,
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val body = objectMapper.writeValueAsString(Map(
      "action" -> action,
      "arguments" -> arguments.asJava).asJava)
    val response = internalClient(instance).executeMcpDiagnostic(
      principal.realUser,
      principal.clientIp,
      body)
    objectMapper.readValue(response, MAP_TYPE)
  }

  private def internalClient(instance: String): InternalRestClient =
    clients.computeIfAbsent(
      instance,
      value =>
        new InternalRestClient(
          value,
          frontendService.getConf.get(FRONTEND_PROXY_HTTP_CLIENT_IP_HEADER),
          PEER_SOCKET_TIMEOUT_MS,
          PEER_CONNECT_TIMEOUT_MS,
          securityEnabled = true,
          requestMaxAttempts = 1,
          requestAttemptWait = 0))

  private def discoverInstances(): Seq[String] = {
    val conf = frontendService.getConf
    val localInstance = normalizeInstance(frontendService.connectionUrl)
    if (ServiceDiscovery.supportServiceDiscovery(conf)) {
      val serverSpace = DiscoveryPaths.makePath(null, conf.get(HA_NAMESPACE))
      val restPort = conf.get(FRONTEND_REST_BIND_PORT)
      withDiscoveryClient(conf) { client =>
        (client.getServiceNodesInfo(serverSpace)
          .map(node => normalizeInstance(formatHostPort(node.host, restPort)))
          :+ localInstance).distinct.sorted
      }
    } else {
      Seq(localInstance)
    }
  }

  private def envelope(
      values: Map[String, Object],
      fanout: FanoutResult): java.util.Map[String, Object] = {
    val failedServers = fanout.failures.map(failure =>
      Map[String, Object](
        "server" -> failure.instance,
        "reason" -> failure.reason).asJava).asJava
    (values ++ Map[String, Object](
      "partial" -> Boolean.box(fanout.failures.nonEmpty),
      "failedServers" -> failedServers,
      "discoveredServers" -> Int.box(fanout.discoveredServers),
      "respondedServers" -> Int.box(fanout.responses.size),
      "observedAt" -> Instant.now().toString)).asJava
  }

  private def longValue(value: Object): Long = value match {
    case number: Number => number.longValue()
    case null => 0L
    case other => other.toString.toLong
  }
}

private[mcp] object KyuubiMcpClusterDiagnostics {
  private val MAX_CONCURRENT_PEERS = 16
  private val MAX_CLUSTER_PEERS = 64
  private val PEER_CONNECT_TIMEOUT_MS = 1500
  private val PEER_SOCKET_TIMEOUT_MS = 4000
  private val FANOUT_DEADLINE_MS = 5000L
  private val MAP_TYPE = new TypeReference[java.util.Map[String, Object]]() {}

  private case class PeerResponse(
      instance: String,
      payload: java.util.Map[String, Object])
  private case class PeerFailure(instance: String, reason: String)
  private case class FanoutResult(
      discoveredServers: Int,
      responses: Seq[PeerResponse],
      failures: Seq[PeerFailure])

  private def normalizeInstance(instance: String): String =
    instance.stripPrefix("http://").stripPrefix("https://").stripSuffix("/")

  private def formatHostPort(host: String, port: Int): String =
    if (host.contains(":") && !host.startsWith("[")) s"[$host]:$port" else s"$host:$port"
}
