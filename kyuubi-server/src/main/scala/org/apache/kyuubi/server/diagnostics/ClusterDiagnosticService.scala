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

package org.apache.kyuubi.server.diagnostics

import java.time.Instant
import java.util.concurrent.{Callable, ConcurrentHashMap, ExecutorCompletionService, ExecutorService, Future, TimeUnit}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.kyuubi.{KYUUBI_VERSION, Logging}
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.engine.{EngineType, ShareLevel}
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_NAMESPACE
import org.apache.kyuubi.ha.client.{DiscoveryPaths, ServiceDiscovery, ServiceNodeInfo}
import org.apache.kyuubi.ha.client.DiscoveryClientProvider.withDiscoveryClient
import org.apache.kyuubi.metrics.MetricsConstants.{DIAGNOSTICS_FANOUT_TIME, DIAGNOSTICS_PEER_FAILURE}
import org.apache.kyuubi.metrics.MetricsSystem
import org.apache.kyuubi.server.KyuubiRestFrontendService
import org.apache.kyuubi.server.api.v1.InternalRestClient
import org.apache.kyuubi.service.authentication.InternalSecurityAccessor
import org.apache.kyuubi.util.ThreadUtils

/**
 * Coordinates protocol-neutral, cluster-wide diagnostics through the authenticated internal
 * endpoint.
 */
private[server] class ClusterDiagnosticService(
    frontendService: KyuubiRestFrontendService,
    objectMapper: ObjectMapper) extends Logging {

  import ClusterDiagnosticService._
  import DiagnosticService._

  private val localDiagnostics = new DiagnosticService(frontendService)
  private val executor = newFanoutExecutor()
  private val clients = new ConcurrentHashMap[String, InternalRestClient]()

  def clusterOverview(
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
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
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    val limit = boundedIntArgument(arguments, "limit", 100, 200)
    aggregateList(
      LIST_SESSIONS,
      arguments,
      principal,
      "sessions",
      "sessionId",
      "createdAt",
      limit)
  }

  def listEngines(
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    val limit = boundedIntArgument(arguments, "limit", 100, MAX_ENGINE_RESULTS)
    val requestedUser = stringArgument(arguments, "user")
    if (requestedUser.exists(_ != principal.realUser) && !principal.administrator) {
      throw new IllegalArgumentException("The requested user is not accessible.")
    }
    val clonedConf = frontendService.getConf.clone
    if (!ServiceDiscovery.supportServiceDiscovery(clonedConf)) {
      return engineResult(
        discoveryEnabled = false,
        Seq.empty[AnyRef].asJava)
    }
    val engineTypes = stringArgument(arguments, "engine_type").map(Seq(_)).getOrElse(ENGINE_TYPES)
    val shareLevels = stringArgument(arguments, "share_level").map(Seq(_)).getOrElse(SHARE_LEVELS)
    val requestedNamespace = stringArgument(arguments, "subdomain")
    val visibleUser = requestedUser.orElse {
      if (principal.administrator) None else Some(principal.realUser)
    }
    lazy val visibleGroup = visibleUser.map(user =>
      frontendService.sessionManager.groupProvider.primaryGroup(
        user,
        frontendService.getConf.getAll.asJava))
    val registrations = ListBuffer[EngineRegistration]()
    val failures = ListBuffer[PeerFailure]()
    var truncated = false
    var inspectedNamespaces = 0
    try {
      withDiscoveryClient(clonedConf) { client =>
        val roots = for {
          engineType <- engineTypes
          shareLevel <- shareLevels
        } yield {
          val name =
            s"${clonedConf.get(HA_NAMESPACE)}_${KYUUBI_VERSION}_${shareLevel}_${engineType}"
          (DiscoveryPaths.makePath(null, name), engineType, shareLevel)
        }
        val rootIterator = roots.iterator
        while (rootIterator.hasNext && registrations.size <= limit && !truncated) {
          val (root, engineType, shareLevel) = rootIterator.next()
          if (!client.pathNonExists(root)) {
            val owners = client.getChildren(root).sorted.filter { owner =>
              shareLevel match {
                case share if share == ShareLevel.GROUP.toString =>
                  visibleGroup.forall(_ == owner)
                case share
                    if share == ShareLevel.USER.toString ||
                      share == ShareLevel.CONNECTION.toString =>
                  visibleUser.forall(_ == owner)
                case _ => true
              }
            }
            val ownerIterator = owners.iterator
            while (ownerIterator.hasNext && registrations.size <= limit && !truncated) {
              val owner = ownerIterator.next()
              val ownerPath = DiscoveryPaths.makePath(root, owner)
              val namespaces = requestedNamespace match {
                case Some(value) =>
                  val path = DiscoveryPaths.makePath(ownerPath, value)
                  if (client.pathNonExists(path)) Seq.empty else Seq(value)
                case None => client.getChildren(ownerPath).sorted
              }
              val namespaceIterator = namespaces.iterator
              while (namespaceIterator.hasNext && registrations.size <= limit && !truncated) {
                if (inspectedNamespaces >= MAX_ENGINE_NAMESPACES) {
                  truncated = true
                } else {
                  val namespace = namespaceIterator.next()
                  inspectedNamespaces += 1
                  val path = DiscoveryPaths.makePath(ownerPath, namespace)
                  val remaining = limit + 1 - registrations.size
                  registrations ++= client.getServiceNodesInfoOrThrow(path, Some(remaining)).map(
                    EngineRegistration(owner, engineType, shareLevel, namespace, _))
                }
              }
              if (namespaceIterator.hasNext) truncated = true
            }
            if (ownerIterator.hasNext) truncated = true
          }
        }
        if (rootIterator.hasNext) truncated = true
      }
    } catch {
      case NonFatal(e) =>
        debug("Engine service discovery failed", e)
        failures += PeerFailure("service-discovery", "unavailable")
    }
    val engines = registrations.take(limit).map(registration =>
      Map[String, Object](
        "owner" -> registration.owner,
        "engineType" -> registration.engineType,
        "shareLevel" -> registration.shareLevel,
        "namespace" -> registration.namespace,
        "address" -> registration.node.instance,
        "version" -> registration.node.version.getOrElse(KYUUBI_VERSION)).asJava).asJava
    engineResult(
      discoveryEnabled = true,
      engines,
      truncated,
      failures.toSeq)
  }

  def listServers(
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
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
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    val fanout = executeAcrossCluster(GET_SERVER_RUNTIME, arguments, principal)
    envelope(
      Map[String, Object](
        "serverRuntimes" -> fanout.responses.map(_.payload).asJava,
        "count" -> Int.box(fanout.responses.size)),
      fanout)
  }

  def getSession(
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    requiredStringArgument(arguments, "session_id")
    aggregateLookup(GET_SESSION, arguments, principal, "session")
  }

  def listOperations(
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    operationTimeWindow(arguments)
    val limit = boundedIntArgument(arguments, "limit", 100, 200)
    aggregateList(
      LIST_OPERATIONS,
      arguments,
      principal,
      "operations",
      "operationId",
      "createdAt",
      limit)
  }

  def getOperation(
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    requiredStringArgument(arguments, "operation_id")
    aggregateLookup(GET_OPERATION, arguments, principal, "operation")
  }

  def readOperationLog(
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    requiredStringArgument(arguments, "operation_id")
    boundedIntArgument(arguments, "max_rows", 100, 1000)
    boundedIntArgument(arguments, "max_bytes", 64 * 1024, 256 * 1024)
    regexArgument(arguments, "regex")
    aggregateLookup(READ_OPERATION_LOG, arguments, principal, "operationLog")
  }

  def listServerLogs(
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
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
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    requiredStringArgument(arguments, "log_id")
    boundedIntArgument(arguments, "max_lines", 200, 1000)
    boundedIntArgument(arguments, "max_bytes", 64 * 1024, 256 * 1024)
    regexArgument(arguments, "regex")
    aggregateLookup(READ_SERVER_LOG, arguments, principal, "serverLog")
  }

  def close(): Unit = {
    ThreadUtils.shutdown(executor)
    clients.values().asScala.foreach(_.close())
    clients.clear()
  }

  private def aggregateList(
      action: String,
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal,
      resultName: String,
      identifierName: String,
      createdAtName: String,
      limit: Int): java.util.Map[String, Object] = {
    val fanout = executeAcrossCluster(action, arguments, principal)
    val items = fanout.responses.flatMap { response =>
      listValues(response.payload, "items").map { item =>
        val attributed = new java.util.HashMap[String, Object](item)
        attributed.put("server", response.instance)
        attributed
      }
    }
      .groupBy(item => Option(item.get(identifierName)).map(_.toString).getOrElse(""))
      .values
      .map(_.head)
      .toSeq
      .sortBy(item => timestampValue(item.get(createdAtName)))(Ordering.Long.reverse)
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
      principal: DiagnosticPrincipal,
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
      principal: DiagnosticPrincipal): FanoutResult = MetricsSystem.timerTracing(
    DIAGNOSTICS_FANOUT_TIME) {
    val discovery = discoverInstances()
    val allInstances = discovery.instances
    val localInstance = normalizeInstance(frontendService.connectionUrl)
    val discoveredInstances = localInstance +: allInstances.filterNot(_ == localInstance)
    val requestedServer = stringArgument(arguments, TARGET_SERVER_ARGUMENT)
      .map(normalizeInstance)
    val instances = requestedServer match {
      case Some(server) if discoveredInstances.contains(server) => Seq(server)
      case Some(_) =>
        throw new IllegalArgumentException(
          s"$TARGET_SERVER_ARGUMENT must match an address returned by list_servers")
      case None => discoveredInstances.take(MAX_CLUSTER_PEERS)
    }
    val diagnosticArguments = arguments - TARGET_SERVER_ARGUMENT
    val failures = ListBuffer[PeerFailure]() ++ discovery.failures
    val omittedServers = discoveredInstances.size - instances.size
    if (requestedServer.isEmpty && omittedServers > 0) {
      failures += PeerFailure(
        "cluster-fanout",
        s"$omittedServers servers omitted by the $MAX_CLUSTER_PEERS-server fanout limit")
    }

    val remoteInstances = instances.filterNot(_ == localInstance)
    if (remoteInstances.nonEmpty && InternalSecurityAccessor.get() == null) {
      throw new IllegalStateException(
        "Cluster diagnostics require kyuubi.internal.security.enabled=true")
    }

    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FANOUT_DEADLINE_MS)
    val responses = ListBuffer[PeerResponse]()
    val completion = new ExecutorCompletionService[PeerAttempt](executor)
    val pending = remoteInstances.map { instance =>
      val future = completion.submit(new Callable[PeerAttempt] {
        override def call(): PeerAttempt = {
          try {
            PeerAttempt(
              instance,
              Some(PeerResponse(
                instance,
                executeRemote(instance, action, diagnosticArguments, principal))),
              None)
          } catch {
            case NonFatal(e) =>
              debug(s"Diagnostic request to $instance failed", e)
              PeerAttempt(instance, None, Some(PeerFailure(instance, peerFailureReason(e))))
          }
        }
      })
      future -> instance
    }.toMap

    if (instances.contains(localInstance)) {
      try {
        responses += PeerResponse(
          localInstance,
          localDiagnostics.execute(action, diagnosticArguments, principal))
      } catch {
        case NonFatal(e) =>
          error("Local diagnostic request failed", e)
          failures += PeerFailure(localInstance, "local_failure")
      }
    }

    val outstanding = scala.collection.mutable.Map[Future[PeerAttempt], String]() ++ pending
    while (outstanding.nonEmpty) {
      val remainingNanos = deadlineNanos - System.nanoTime()
      val completed = if (remainingNanos > 0) {
        completion.poll(remainingNanos, TimeUnit.NANOSECONDS)
      } else {
        completion.poll()
      }
      if (completed == null) {
        outstanding.foreach { case (future, instance) =>
          future.cancel(true)
          failures += PeerFailure(instance, "fanout_deadline")
        }
        outstanding.clear()
      } else {
        val instance = outstanding.remove(completed).getOrElse("unknown")
        try {
          val attempt = completed.get()
          attempt.response.foreach(responses += _)
          attempt.failure.foreach(failures += _)
        } catch {
          case NonFatal(e) =>
            debug(s"Diagnostic request to $instance failed", e)
            failures += PeerFailure(instance, peerFailureReason(e))
        }
      }
    }
    val result = FanoutResult(
      requestedServer.fold(discoveredInstances.size)(_ => 1),
      responses.toSeq,
      failures.toSeq)
    if (discovery.failures.isEmpty) {
      pruneClients(allInstances.toSet)
    }
    if (result.failures.nonEmpty) {
      MetricsSystem.tracing(_.markMeter(DIAGNOSTICS_PEER_FAILURE, result.failures.size))
    }
    result
  }

  private def executeRemote(
      instance: String,
      action: String,
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    val body = objectMapper.writeValueAsString(Map(
      "action" -> action,
      "arguments" -> arguments.asJava).asJava)
    val response = internalClient(instance).executeDiagnostic(
      principal.realUser,
      principal.clientIp,
      body)
    objectMapper.readValue(response, MAP_TYPE)
  }

  private def peerFailureReason(error: Throwable): String = {
    val causes = Iterator.iterate(error)(_.getCause).takeWhile(_ != null).toSeq
    if (causes.exists(cause =>
        cause.isInstanceOf[java.net.SocketTimeoutException] ||
          cause.getClass.getSimpleName == "ConnectTimeoutException")) {
      "peer_timeout"
    } else {
      "peer_unavailable"
    }
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

  private def pruneClients(activeInstances: Set[String]): Unit = {
    clients.asScala.foreach { case (instance, client) =>
      if (!activeInstances.contains(instance) && clients.remove(instance, client)) {
        client.close()
      }
    }
  }

  private def discoverInstances(): DiscoveryResult = {
    val conf = frontendService.getConf
    val localInstance = normalizeInstance(frontendService.connectionUrl)
    if (ServiceDiscovery.supportServiceDiscovery(conf)) {
      val serverSpace = DiscoveryPaths.makePath(null, conf.get(HA_NAMESPACE))
      val restPort = conf.get(FRONTEND_REST_BIND_PORT)
      try {
        val instances = withDiscoveryClient(conf) { client =>
          val nodes = if (client.pathNonExists(serverSpace)) {
            Seq.empty
          } else {
            client.getServiceNodesInfoOrThrow(serverSpace)
          }
          (nodes
            .map(node => normalizeInstance(formatHostPort(node.host, restPort)))
            :+ localInstance).distinct.sorted
        }
        DiscoveryResult(instances, Seq.empty)
      } catch {
        case NonFatal(e) =>
          debug("Diagnostic service discovery failed; continuing with the local server", e)
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
      "observedAt" -> Instant.now().toString)).asJava
  }

  private def timestampValue(value: Object): Long = value match {
    case number: Number => number.longValue()
    case null => 0L
    case other =>
      try {
        Instant.parse(other.toString).toEpochMilli
      } catch {
        case NonFatal(_) => 0L
      }
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

  private def engineResult(
      discoveryEnabled: Boolean,
      engines: java.util.List[_],
      truncated: Boolean = false,
      failures: Seq[PeerFailure] = Seq.empty): java.util.Map[String, Object] = {
    val failedServers = failures.map(failure =>
      Map[String, Object](
        "server" -> failure.instance,
        "reason" -> failure.reason).asJava).asJava
    Map[String, Object](
      "discoveryEnabled" -> Boolean.box(discoveryEnabled),
      "engines" -> engines,
      "count" -> Int.box(engines.size()),
      "truncated" -> Boolean.box(truncated),
      "partial" -> Boolean.box(truncated || failures.nonEmpty),
      "failedServers" -> failedServers,
      "observedAt" -> Instant.now().toString).asJava
  }

  private def newFanoutExecutor(): ExecutorService = {
    val javaVersion = System.getProperty("java.specification.version").split("\\.").last.toInt
    if (javaVersion >= 21) {
      info("Cluster diagnostics will use bounded Java virtual threads")
      ThreadUtils.newBoundedVirtualThreadPerTaskExecutor(
        MAX_CLUSTER_PEERS,
        "cluster-diagnostics")
    } else {
      info("Cluster diagnostics will use a bounded platform thread pool")
      ThreadUtils.newDaemonFixedThreadPool(
        MAX_CONCURRENT_PEERS,
        "cluster-diagnostics")
    }
  }
}

private[server] object ClusterDiagnosticService {
  private val MAX_CONCURRENT_PEERS = 16
  private val ENGINE_TYPES = EngineType.values.toSeq.map(_.toString)
  private val SHARE_LEVELS = ShareLevel.values.toSeq.map(_.toString)
  private val MAX_CLUSTER_PEERS = 64
  private val MAX_ENGINE_NAMESPACES = 200
  private val MAX_ENGINE_RESULTS = 200
  private val PEER_CONNECT_TIMEOUT_MS = 1500
  private val PEER_SOCKET_TIMEOUT_MS = 4000
  private val FANOUT_DEADLINE_MS = 5000L
  private val TARGET_SERVER_ARGUMENT = "server"
  private val MAP_TYPE = new TypeReference[java.util.Map[String, Object]]() {}

  private case class PeerResponse(
      instance: String,
      payload: java.util.Map[String, Object])
  private case class PeerAttempt(
      instance: String,
      response: Option[PeerResponse],
      failure: Option[PeerFailure])
  private case class PeerFailure(instance: String, reason: String)
  private case class EngineRegistration(
      owner: String,
      engineType: String,
      shareLevel: String,
      namespace: String,
      node: ServiceNodeInfo)
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
