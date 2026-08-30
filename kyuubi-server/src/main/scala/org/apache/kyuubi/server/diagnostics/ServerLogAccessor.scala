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

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths, StandardOpenOption}
import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, Locale}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.server.KyuubiRestFrontendService

/** Reads only regular log files discovered beneath the Kyuubi Server log directory. */
private[server] class ServerLogAccessor(frontendService: KyuubiRestFrontendService)
  extends Logging {

  import DiagnosticService._
  import ServerLogAccessor._

  private def serverInstance: String = frontendService.connectionUrl

  def list(
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    requireAdministrator(principal)
    val roots = configuredRoots()
    val limit = boundedIntArgument(arguments, "limit", 100, MAX_LIST_RESULTS)
    val contains = boundedLiteralArgument(arguments, "contains")
    val files = discoverFiles(roots)
      .filter(file => contains.forall(value => file.name.toLowerCase(Locale.ROOT).contains(value)))
      .sortBy(_.lastModified)(Ordering.Long.reverse)
      .take(limit)
    Map[String, Object](
      "enabled" -> Boolean.box(roots.nonEmpty),
      "items" -> files.map(file =>
        Map[String, Object](
          "logId" -> file.id,
          "name" -> file.name,
          "server" -> serverInstance,
          "sizeBytes" -> Long.box(file.size),
          "lastModified" -> Instant.ofEpochMilli(file.lastModified).toString).asJava).asJava).asJava
  }

  def read(
      arguments: Map[String, AnyRef],
      principal: DiagnosticPrincipal): java.util.Map[String, Object] = {
    requireAdministrator(principal)
    val logId = requiredStringArgument(arguments, "log_id")
    if (!LOG_ID_PATTERN.pattern.matcher(logId).matches()) {
      throw new IllegalArgumentException(
        "log_id must be an opaque identifier from list_server_logs")
    }
    val maxLines = boundedIntArgument(arguments, "max_lines", 200, MAX_READ_LINES)
    val maxBytes = boundedIntArgument(arguments, "max_bytes", DEFAULT_READ_BYTES, MAX_READ_BYTES)
    val regex = regexArgument(arguments, "regex")
    discoverFiles(configuredRoots()).find(_.id == logId) match {
      case Some(file) =>
        val content = readTail(file.path, maxLines, maxBytes, regex)
        info(s"Server log read allowed for user ${auditValue(principal.realUser)}")
        Map[String, Object](
          "found" -> Boolean.box(true),
          "value" -> Map[String, Object](
            "logId" -> file.id,
            "name" -> file.name,
            "server" -> serverInstance,
            "lines" -> content.lines.asJava,
            "count" -> Int.box(content.lines.size),
            "truncated" -> Boolean.box(content.truncated),
            "redacted" -> Boolean.box(true),
            "maxLines" -> Int.box(maxLines),
            "maxBytes" -> Int.box(maxBytes)).asJava).asJava
      case None =>
        warn(s"Server log read denied for user ${auditValue(principal.realUser)}")
        Map[String, Object](
          "found" -> Boolean.box(false),
          "value" -> null).asJava
    }
  }

  private def discoverFiles(roots: Seq[Path]): Seq[LogFile] = roots.flatMap { root =>
    val stream = Files.walk(root, MAX_DIRECTORY_DEPTH)
    try {
      stream.iterator().asScala
        .filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        .filterNot(Files.isSymbolicLink)
        .filter(path =>
          ALLOWED_LOG_EXTENSIONS.exists(extension =>
            path.getFileName.toString.toLowerCase(Locale.ROOT).endsWith(extension)))
        .take(MAX_DISCOVERED_FILES)
        .flatMap(path => safeLogFile(root, path))
        .toVector
    } finally {
      stream.close()
    }
  }

  private def safeLogFile(root: Path, path: Path): Option[LogFile] = {
    try {
      val realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
      if (!realPath.startsWith(root) || Files.isSymbolicLink(realPath)) {
        None
      } else {
        val name = root.relativize(realPath).iterator().asScala.mkString("/")
        Some(LogFile(
          opaqueId(serverInstance, realPath),
          name,
          realPath,
          Files.size(realPath),
          Files.getLastModifiedTime(realPath, LinkOption.NOFOLLOW_LINKS).toMillis))
      }
    } catch {
      case NonFatal(e) =>
        debug(s"Skipping an unreadable server log under $KYUUBI_LOG_DIR $root", e)
        None
    }
  }

  private def readTail(
      path: Path,
      maxLines: Int,
      maxBytes: Int,
      regex: Option[java.util.regex.Pattern]): TailContent = {
    val file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    try {
      val length = file.size()
      val bytesToRead = math.min(length, maxBytes.toLong).toInt
      val start = length - bytesToRead
      file.position(start)
      val buffer = ByteBuffer.allocate(bytesToRead)
      while (buffer.hasRemaining && file.read(buffer) >= 0) {}
      val bytes = buffer.array().take(buffer.position())
      val completeBytes = if (start == 0) {
        bytes
      } else {
        val firstNewline = bytes.indexOf('\n'.toByte)
        if (firstNewline < 0) Array.emptyByteArray else bytes.drop(firstNewline + 1)
      }
      val allLines = new String(completeBytes, StandardCharsets.UTF_8)
        .split("\\r?\\n", -1)
        .toSeq
        .filter(line => regex.forall(_.matcher(line).find()))
      val lines = allLines.takeRight(maxLines).map(redact)
      TailContent(lines, start > 0 || allLines.size > maxLines)
    } finally {
      file.close()
    }
  }

  private def requireAdministrator(principal: DiagnosticPrincipal): Unit = {
    if (!principal.administrator) {
      throw new IllegalArgumentException("Server log access requires administrator permission.")
    }
  }

  private def auditValue(value: String): String = value.iterator
    .map(character => if (Character.isISOControl(character)) '?' else character)
    .take(MAX_AUDIT_VALUE_LENGTH)
    .mkString
}

private[server] object ServerLogAccessor {
  private val MAX_DIRECTORY_DEPTH = 4
  private val MAX_DISCOVERED_FILES = 2000
  private val MAX_LIST_RESULTS = 200
  private val MAX_READ_LINES = 1000
  private val DEFAULT_READ_BYTES = 64 * 1024
  private val MAX_READ_BYTES = 256 * 1024
  private val MAX_LITERAL_LENGTH = 128
  private val MAX_AUDIT_VALUE_LENGTH = 128
  private val KYUUBI_LOG_DIR = "KYUUBI_LOG_DIR"
  private val ALLOWED_LOG_EXTENSIONS = Set(".log", ".out", ".err")
  private val LOG_ID_PATTERN = "^[A-Za-z0-9_-]{43}$".r
  private val SECRET_ASSIGNMENT =
    ("(?i)([\\\"']?(?:password|passwd|pwd|token|secret|authorization|access[_-]?key|" +
      "private[_-]?key)[\\\"']?\\s*[:=]\\s*)" +
      "(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;}]+)").r
  private val AUTHORIZATION = "(?i)\\b(Basic|Bearer)\\s+[A-Za-z0-9._~+/-]+=*".r
  private val URI_CREDENTIALS = "(://)[^\\s/:@]+:[^\\s/@]+@".r
  private val ACCESS_KEY = "\\bAKIA[0-9A-Z]{16}\\b".r

  private case class LogFile(id: String, name: String, path: Path, size: Long, lastModified: Long)
  private case class TailContent(lines: Seq[String], truncated: Boolean)

  private def configuredRoots(): Seq[Path] = Option(System.getenv(KYUUBI_LOG_DIR))
    .map(_.trim)
    .filter(_.nonEmpty)
    .toSeq
    .map { configured =>
      val path = Paths.get(configured).toAbsolutePath.normalize()
      if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(
          s"$KYUUBI_LOG_DIR must identify a non-symbolic-link directory")
      }
      path.toRealPath(LinkOption.NOFOLLOW_LINKS)
    }

  private def opaqueId(instance: String, path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest((instance + "\u0000" + path.toString).getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding().encodeToString(digest)
  }

  private def boundedLiteralArgument(
      arguments: Map[String, AnyRef],
      name: String): Option[String] = {
    val value = DiagnosticService.stringArgument(arguments, name)
    if (value.exists(_.length > MAX_LITERAL_LENGTH)) {
      throw new IllegalArgumentException(s"$name must not exceed $MAX_LITERAL_LENGTH characters")
    }
    value.map(_.toLowerCase(Locale.ROOT))
  }

  private[server] def redact(value: String): String = {
    val authorization = AUTHORIZATION.replaceAllIn(
      value,
      matched =>
        matched.group(1) + " [REDACTED]")
    val uri = URI_CREDENTIALS.replaceAllIn(
      authorization,
      matched =>
        matched.group(1) + "[REDACTED]@")
    val assignments = SECRET_ASSIGNMENT.replaceAllIn(
      uri,
      matched =>
        matched.group(1) + "[REDACTED]")
    ACCESS_KEY.replaceAllIn(assignments, "[REDACTED]")
  }
}
