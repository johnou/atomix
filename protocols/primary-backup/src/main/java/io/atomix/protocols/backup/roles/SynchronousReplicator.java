/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.backup.roles;

import io.atomix.cluster.MemberId;
import io.atomix.protocols.backup.protocol.BackupOperation;
import io.atomix.protocols.backup.protocol.BackupRequest;
import io.atomix.protocols.backup.protocol.PrimaryBackupResponse.Status;
import io.atomix.protocols.backup.service.impl.PrimaryBackupServiceContext;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * Synchronous replicator.
 */
class SynchronousReplicator implements Replicator {
  private final PrimaryBackupServiceContext context;
  private final Logger log;
  private final Map<MemberId, BackupQueue> queues = new HashMap<>();
  private final Map<Long, CompletableFuture<Void>> futures = new LinkedHashMap<>();

  SynchronousReplicator(PrimaryBackupServiceContext context, Logger log) {
    this.context = context;
    this.log = log;
  }

  @Override
  public CompletableFuture<Void> replicate(BackupOperation operation) {
    if (context.backups().isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> future = new CompletableFuture<>();
    futures.put(operation.index(), future);
    for (MemberId backup : context.backups()) {
      queues.computeIfAbsent(backup, BackupQueue::new).add(operation);
    }
    return future;
  }

  @Override
  public void removePreviousOperation(MemberId memberId, long endIndex) {
    queues.computeIfPresent(memberId, (node, queue) -> {
      queue.clear(endIndex);
      return queue;
    });
  }


  /**
   * Completes futures.
   */
  private void completeFutures() {
    long commitIndex = queues.values().stream()
        .map(queue -> queue.ackedIndex)
        .reduce(Math::min)
        .orElse(0L);
    for (long i = context.getCommitIndex() + 1; i <= commitIndex; i++) {
      CompletableFuture<Void> future = futures.remove(i);
      if (future != null) {
        future.complete(null);
      }
    }
    context.setCommitIndex(commitIndex);
  }

  @Override
  public void close() {
    futures.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Not the primary")));
  }

  /**
   * Synchronous backup queue.
   */
  private final class BackupQueue {
    private final Queue<BackupOperation> operations = new LinkedList<>();
    private final MemberId memberId;
    private boolean inProgress;
    private long ackedIndex;

    BackupQueue(MemberId memberId) {
      this.memberId = memberId;
    }

    /**
     * Adds an operation to the queue.
     *
     * @param operation the operation to add
     */
    void add(BackupOperation operation) {
      operations.add(operation);
      maybeBackup();
    }

    /**
     * Sends the next batch if operations are queued and no backup is already in progress.
     */
    private void maybeBackup() {
      if (!inProgress && !operations.isEmpty()) {
        inProgress = true;
        backup();
      }
    }

    /**
     * Sends the next batch of operations to the backup.
     */
    private void backup() {
      List<BackupOperation> operations = new LinkedList<>();
      long index = 0;
      while (operations.size() < 100 && !this.operations.isEmpty()) {
        BackupOperation operation = this.operations.remove();
        operations.add(operation);
        index = operation.index();
      }

      long lastIndex = index;
      BackupRequest request = BackupRequest.request(
          context.descriptor(),
          context.memberId(),
          context.currentTerm(),
          context.getCommitIndex(),
          operations);

      log.trace("Sending {} to {}", request, memberId);
      context.protocol().backup(memberId, request).whenCompleteAsync((response, error) -> {
        if (error == null) {
          log.trace("Received {} from {}", response, memberId);
          if (response.status() == Status.OK) {
            ackedIndex = lastIndex;
            completeFutures();
          } else {
            log.trace("Replication to {} failed!", memberId);
          }
        } else {
          log.trace("Replication to {} failed! {}", memberId, error);
        }
        inProgress = false;
        maybeBackup();
      }, context.threadContext());
      operations.clear();
    }

    /**
     * Clears the queue.
     */
    void clear(long index) {
      BackupOperation op = operations.peek();
      while (op != null && op.index() <= index) {
        operations.remove();
        op = operations.peek();
      }
    }
  }
}
