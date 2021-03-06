/**
 * Copyright 2010 - 2016 JetBrains s.r.o.
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
package jetbrains.exodus.entitystore;

import jetbrains.exodus.backup.Backupable;
import jetbrains.exodus.bindings.ComparableBinding;
import jetbrains.exodus.core.execution.MultiThreadDelegatingJobProcessor;
import jetbrains.exodus.env.Environment;
import jetbrains.exodus.management.Statistics;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings({"JavaDoc"})
public interface PersistentEntityStore extends EntityStore, Backupable {

    @NotNull
    Environment getEnvironment();

    @NotNull
    PersistentEntityStoreConfig getConfig();

    void clear();

    void executeInTransaction(@NotNull StoreTransactionalExecutable executable);

    void executeInReadonlyTransaction(@NotNull StoreTransactionalExecutable executable);

    /**
     * Compute a computable in transaction.
     *
     * @param computable
     * @param <T>
     * @return computed result regardless of was transaction aborted or not
     */
    <T> T computeInTransaction(@NotNull StoreTransactionalComputable<T> computable);

    <T> T computeInReadonlyTransaction(@NotNull StoreTransactionalComputable<T> computable);

    @NotNull
    BlobVault getBlobVault();

    void registerCustomPropertyType(@NotNull final StoreTransaction txn,
                                    @NotNull final Class<? extends Comparable> clazz, @NotNull final ComparableBinding binding);

    Entity getEntity(@NotNull EntityId id);

    int getEntityTypeId(@NotNull final String entityType);

    @NotNull
    String getEntityType(int entityTypeId);

    void renameEntityType(@NotNull String oldEntityTypeName, @NotNull String newEntityTypeName);

    long getUsableSpace();

    @NotNull
    MultiThreadDelegatingJobProcessor getAsyncProcessor();

    @NotNull
    Statistics getStatistics();
}
