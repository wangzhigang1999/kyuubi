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
package org.apache.kyuubi.engine.dataagent.operation

import java.util.concurrent.CopyOnWriteArrayList

import org.apache.kyuubi.operation.FetchIterator

/**
 * A thread-safe [[FetchIterator]] that supports concurrent appending and reading.
 * The producer thread calls [[append]] to add items incrementally while the consumer
 * thread fetches results via the standard FetchIterator interface.
 */
class IncrementalFetchIterator[A] extends FetchIterator[A] {

  private val buffer = new CopyOnWriteArrayList[A]()

  @volatile private var fetchStart: Long = 0
  @volatile private var position: Long = 0

  /**
   * Append an item to the buffer. Thread-safe — can be called from the producer thread
   * while the consumer thread is reading.
   */
  def append(item: A): Unit = {
    buffer.add(item)
  }

  override def fetchNext(): Unit = {
    fetchStart = position
  }

  override def fetchAbsolute(pos: Long): Unit = {
    position = (pos max 0) min buffer.size()
    fetchStart = position
  }

  override def getFetchStart: Long = fetchStart

  override def getPosition: Long = position

  override def hasNext: Boolean = position < buffer.size()

  override def next(): A = {
    val idx = position.toInt
    position += 1
    buffer.get(idx)
  }
}
