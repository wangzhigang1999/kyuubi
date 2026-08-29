<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# MCP Server

The optional Kyuubi MCP Server exposes read-only monitoring and diagnosis through one stateless
Streamable HTTP endpoint at `/mcp`. A client connects to any Kyuubi Server instance. The receiving
server discovers its peers and aggregates node-local observations, so the MCP client does not need
one endpoint per instance.

The MCP Server does not execute SQL, fetch query results, submit or cancel workloads, change
configuration, or provide data-development tools.

## Build and runtime

Build the distribution using JDK 17 or later. The `mcp` profile activates automatically:

```bash
build/mvn -pl kyuubi-assembly -am package -DskipTests
```

The build JDK, Java API level, bytecode ceiling, and runtime JDK are independent. MCP sources are
restricted to Java 17 APIs, and its classes and runtime dependencies do not exceed Java 17
bytecode, while regular Kyuubi modules retain their existing bytecode target. Kyuubi must run on
Java 17 or later when MCP is enabled. On Java 21 or later, cluster fan-out automatically uses
Kyuubi's bounded virtual-thread executor. Java 17 uses a fixed platform-thread pool. Java 21 is
useful for larger clusters and concurrent diagnosis, but is not required for protocol compatibility
or correctness.

## Production configuration

Enable MCP on every Kyuubi Server instance and use an authenticated REST frontend. For example,
LDAP authentication can use the existing Kyuubi LDAP settings:

```properties
kyuubi.frontend.mcp.enabled=true
kyuubi.authentication=LDAP
kyuubi.authentication.ldap.url=ldap://ldap.example.net
kyuubi.authentication.ldap.baseDN=dc=example,dc=net
```

`kyuubi.frontend.mcp.allowInsecureAuthentication` defaults to `false`. Do not enable it outside
an isolated development environment: REST `NONE` authentication treats every caller as an
administrator.

LDAP and other password-based providers authenticate MCP requests through HTTP Basic
`Authorization`. Terminate TLS at the Kyuubi ingress or a trusted reverse proxy, and configure the
MCP client to obtain its password from secret storage. Do not place a password or a precomputed
Basic header in chat instructions, tool arguments, or source-controlled client configuration. The
endpoint is stateless, so credentials are checked on each request.

For a multi-instance cluster, also enable Kyuubi internal security. Peer fan-out uses the existing
short-lived internal token and never forwards the caller's password:

```properties
kyuubi.internal.security.enabled=true
kyuubi.internal.security.secret.provider=zookeeper
```

All instances must share the same HA namespace and internal-security secret, and their REST ports
must be reachable from one another. If discovery or a peer fails, tools return available evidence
with `partial=true` and a bounded `failedServers` list instead of silently treating the missing
node as empty.

## Tools

|          Tool          |                 Scope                  |                                       Purpose                                        |
|------------------------|----------------------------------------|--------------------------------------------------------------------------------------|
| `get_cluster_overview` | User; administrator may select a user  | Summarize reachable servers and live session and operation states                    |
| `list_servers`         | Administrator                          | Verify every discovered Kyuubi Server is reachable                                   |
| `get_server_runtime`   | Administrator                          | Inspect a fixed projection of JVM, heap, thread, uptime, processor, and load metrics |
| `list_engines`         | User; administrator may select a user  | List live engines from Kyuubi service discovery                                      |
| `list_sessions`        | Owner; administrator may select a user | List live sessions with bounded filters                                              |
| `get_session`          | Owner or administrator                 | Find one live session on any server                                                  |
| `list_operations`      | Owner; administrator may select a user | List live operations with session and state filters                                  |
| `get_operation`        | Owner or administrator                 | Find one live operation on any server                                                |
| `read_operation_log`   | Owner or administrator                 | Read bounded, redacted lines from one live operation log                             |
| `list_server_logs`     | Administrator                          | List allowlisted server logs on all reachable nodes using opaque IDs                 |
| `read_server_log`      | Administrator                          | Read a bounded, redacted tail using an opaque ID                                     |

All tools are annotated as read-only, idempotent, non-destructive, and closed-world. List and read
parameters have schema-enforced limits. Identifiers should come from a preceding list call. Missing
and inaccessible objects intentionally return the same public error.

Every cluster response includes:

- `partial`, `failedServers`, `discoveredServers`, and `respondedServers`;
- `observedAt`, because results are point-in-time observations;
- `fanoutMode`, either `platform_pool` or `virtual_threads`.

## Server log sandbox

Server log access is disabled by default. Enable it only with canonical roots controlled by the
Kyuubi administrator:

```properties
kyuubi.frontend.mcp.server.log.directories=/var/log/kyuubi
kyuubi.frontend.mcp.server.log.extensions=.log,.out,.err
```

The public tools never accept a filesystem path. `list_server_logs` walks at most four levels
under configured roots without following symbolic links and returns opaque SHA-256-derived IDs.
`read_server_log` resolves an ID again, opens the file with `NOFOLLOW_LINKS`, reads at most 1,000
lines and 256 KiB from its tail, applies only a bounded literal filter, and redacts common
credentials. Roots and extensions are administrator allowlists; discovery counts, response bytes,
concurrency, and request duration have hard limits.

The sandbox reads Kyuubi Server files on each node. YARN and Kubernetes application-container logs
are outside this filesystem boundary and require a separate cluster-manager-aware provider with its
own authorization and resource allowlist.

## Diagnostic workflows

The server exposes tools only. It does not publish MCP resources or prompts. Clients can compose
tool calls for cluster health, operation, engine startup, and server diagnosis without adding
server-defined conversation templates or a second discovery surface.

A diagnosis must inspect `partial` and `failedServers` before claiming that a session, operation,
engine, or log is absent. Use metadata first and logs only when state and timing do not explain the
problem.

## Transport limits

The endpoint accepts stateless JSON-RPC over HTTP POST. It enforces a 64 KiB request limit, 1 MiB
response limit, 64 concurrent requests, a 15-second request deadline, and a 5-second cluster
fan-out deadline. Protocol and validation errors are JSON and do not include stack traces.
