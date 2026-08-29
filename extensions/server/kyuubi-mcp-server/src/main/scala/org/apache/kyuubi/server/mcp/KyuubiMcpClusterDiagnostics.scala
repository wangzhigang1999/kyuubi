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

/**
 * Coordinates cluster-wide MCP diagnostics without recursively invoking the public MCP endpoint.
 */
private[mcp] class KyuubiMcpClusterDiagnostics(
    frontendService: KyuubiRestFrontendService,
    objectMapper: ObjectMapper) extends Logging {

  import KyuubiMcpClusterDiagnostics._
  import KyuubiMcpLocalDiagnostics._

  private val localDiagnostics = new KyuubiMcpLocalDiagnostics(frontendService, objectMapper)
  private val (executor, fanoutMode) = newFanoutExecutor()
  private val clients = new ConcurrentHashMap[String, InternalRestClient]()

  def clusterOverview(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val fanout = executeAcrossCluster(CLUSTER_OVERVIEW, arguments, principal)
    val serverSummaries = fanout.responses.map(_.payload)
    val sessionCount = serverSummaries.map(intValue(_, "sessionCount")).sum
    val operationCount = serverSummaries.map(intValue(_, "operationCount")).sum
    val sessionTypes = sumCounters(serverSummaries, "sessionTypes")
    val operationStates = sumCounters(serverSummaries, "operationStates")
    envelope(
      Map[String, Object](
        "sessionCount" -> Int.box(sessionCount),
        "sessionTypes" -> sessionTypes.asJava,
        "operationCount" -> Int.box(operationCount),
        "operationStates" -> operationStates.asJava,
        "servers" -> serverSummaries.asJava),
      fanout)
  }

  def listSessions(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val limit = boundedIntArgument(arguments, "limit", 100, 200)
    aggregateList(LIST_SESSIONS, arguments, principal, "sessions", "identifier", limit)
  }

  def listServers(
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val fanout = executeAcrossCluster(GET_SERVER_RUNTIME, Map.empty, principal)
    val servers = fanout.responses.map(response =>
      Map[String, Object](
        "instance" -> response.instance,
        "status" -> "Running").asJava)
    envelope(
      Map[String, Object](
        "servers" -> servers.asJava,
        "count" -> Int.box(servers.size)),
      fanout)
  }

  def serverRuntime(
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val fanout = executeAcrossCluster(GET_SERVER_RUNTIME, Map.empty, principal)
    envelope(
      Map[String, Object](
        "serverRuntimes" -> fanout.responses.map(_.payload).asJava,
        "count" -> Int.box(fanout.responses.size)),
      fanout)
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
    boundedIntArgument(arguments, "max_bytes", 64 * 1024, 256 * 1024)
    aggregateLookup(READ_OPERATION_LOG, arguments, principal, "operationLog")
  }

  def listServerLogs(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    val limit = boundedIntArgument(arguments, "limit", 100, 200)
    val fanout = executeAcrossCluster(LIST_SERVER_LOGS, arguments, principal)
    val items = fanout.responses.flatMap(response => listValues(response.payload, "items"))
      .sortBy(item => Option(item.get("lastModified")).map(_.toString).getOrElse(""))
      .reverse
      .take(limit)
    val enabledServers = fanout.responses.count(response =>
      java.lang.Boolean.TRUE == response.payload.get("enabled"))
    envelope(
      Map[String, Object](
        "serverLogs" -> items.asJava,
        "count" -> Int.box(items.size),
        "enabledServers" -> Int.box(enabledServers)),
      fanout)
  }

  def readServerLog(
      arguments: Map[String, AnyRef],
      principal: KyuubiMcpPrincipal): java.util.Map[String, Object] = {
    requiredStringArgument(arguments, "log_id")
    boundedIntArgument(arguments, "max_lines", 200, 1000)
    boundedIntArgument(arguments, "max_bytes", 64 * 1024, 256 * 1024)
    aggregateLookup(READ_SERVER_LOG, arguments, principal, "serverLog")
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
    val items = fanout.responses.flatMap(response => listValues(response.payload, "items"))
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
    val discovery = discoverInstances()
    val allInstances = discovery.instances
    val instances = allInstances.take(MAX_CLUSTER_PEERS)
    val failures = ListBuffer[PeerFailure]() ++ discovery.failures
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

  private def discoverInstances(): DiscoveryResult = {
    val conf = frontendService.getConf
    val localInstance = normalizeInstance(frontendService.connectionUrl)
    if (ServiceDiscovery.supportServiceDiscovery(conf)) {
      val serverSpace = DiscoveryPaths.makePath(null, conf.get(HA_NAMESPACE))
      val restPort = conf.get(FRONTEND_REST_BIND_PORT)
      try {
        val instances = withDiscoveryClient(conf) { client =>
          (client.getServiceNodesInfo(serverSpace)
            .map(node => normalizeInstance(formatHostPort(node.host, restPort)))
            :+ localInstance).distinct.sorted
        }
        DiscoveryResult(instances, Seq.empty)
      } catch {
        case NonFatal(e) =>
          debug("MCP service discovery failed; continuing with the local server", e)
          DiscoveryResult(
            Seq(localInstance),
            Seq(PeerFailure("service-discovery", "unavailable")))
      }
    } else {
      DiscoveryResult(Seq(localInstance), Seq.empty)
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
      "fanoutMode" -> fanoutMode,
      "observedAt" -> Instant.now().toString)).asJava
  }

  private def longValue(value: Object): Long = value match {
    case number: Number => number.longValue()
    case null => 0L
    case other => other.toString.toLong
  }

  private def listValues(
      value: java.util.Map[String, Object],
      name: String): Seq[java.util.Map[String, Object]] = Option(value.get(name)) match {
    case Some(values: java.util.List[_]) => values.asScala.collect {
        case item: java.util.Map[_, _] =>
          item.asInstanceOf[java.util.Map[String, Object]]
      }
    case _ => Seq.empty
  }

  private def intValue(value: java.util.Map[String, Object], name: String): Int =
    Option(value.get(name)).collect { case number: Number => number.intValue() }.getOrElse(0)

  private def sumCounters(
      values: Seq[java.util.Map[String, Object]],
      name: String): Map[String, Integer] = {
    values.flatMap(value =>
      Option(value.get(name)) match {
        case Some(counters: java.util.Map[_, _]) => counters.asScala.toSeq.collect {
            case (key: String, count: Number) => key -> count.intValue()
          }
        case _ => Seq.empty
      }).groupBy(_._1).map { case (key, counters) =>
      key -> Int.box(counters.map(_._2).sum)
    }
  }

  private def newFanoutExecutor(): (ExecutorService, String) = {
    if (Runtime.version().feature() >= 21) {
      info("MCP cluster diagnostics will use bounded Java virtual threads")
      ThreadUtils.newBoundedVirtualThreadPerTaskExecutor(
        MAX_CLUSTER_PEERS,
        "mcp-cluster-diagnostics") -> "virtual_threads"
    } else {
      info("MCP cluster diagnostics will use a bounded platform thread pool")
      ThreadUtils.newDaemonFixedThreadPool(
        MAX_CONCURRENT_PEERS,
        "mcp-cluster-diagnostics") -> "platform_pool"
    }
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
  private case class DiscoveryResult(
      instances: Seq[String],
      failures: Seq[PeerFailure])

  private def normalizeInstance(instance: String): String =
    instance.stripPrefix("http://").stripPrefix("https://").stripSuffix("/")

  private def formatHostPort(host: String, port: Int): String =
    if (host.contains(":") && !host.startsWith("[")) s"[$host]:$port" else s"$host:$port"
}
