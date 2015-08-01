/**
 * Copyright 2015 PayPal
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.ebay.myriad.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.collections.CollectionUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.mesos.Protos;
import com.ebay.myriad.state.utils.StoreContext;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore;

/**
 * Represents the state of the Myriad scheduler
 */
public class SchedulerState {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerState.class);

    private Map<Protos.TaskID, NodeTask> tasks;
    private Set<Protos.TaskID> pendingTasks;
    private Set<Protos.TaskID> stagingTasks;
    private Set<Protos.TaskID> activeTasks;
    private Set<Protos.TaskID> lostTasks;
    private Set<Protos.TaskID> killableTasks;
    private MyriadState myriadState;
    private Protos.FrameworkID frameworkId;
    private RMStateStore stateStore;

    public SchedulerState(RMStateStore stateStore) {
        this.tasks = new ConcurrentHashMap<>();
        this.pendingTasks = new HashSet<>();
        this.stagingTasks = new HashSet<>();
        this.activeTasks = new HashSet<>();
        this.lostTasks = new HashSet<>();
        this.killableTasks = new HashSet<>();
        this.stateStore = stateStore;
        loadStateStore();
    }

    public void addNodes(Collection<NodeTask> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            LOGGER.info("No nodes to add");
            return;
        }
        for (NodeTask node : nodes) {
            Protos.TaskID taskId = Protos.TaskID.newBuilder().setValue(String.format("nm.%s.%s", node.getProfile().getName(), UUID.randomUUID()))
                    .build();
            addTask(taskId, node);
            LOGGER.info("Marked taskId {} pending, size of pending queue {}", taskId.getValue(), this.pendingTasks.size());
            makeTaskPending(taskId);
        }

    }

    private void addTask(Protos.TaskID taskId, NodeTask node) {
        this.tasks.put(taskId, node);
    }

    public void updateTask(Protos.TaskStatus taskStatus) {
        Objects.requireNonNull(taskStatus, "TaskStatus object shouldn't be null");
        Protos.TaskID taskId = taskStatus.getTaskId();
        if (this.tasks.containsKey(taskId)) {
            this.tasks.get(taskId).setTaskStatus(taskStatus);
        }
        updateStateStore();
    }

    public void makeTaskPending(Protos.TaskID taskId) {
        Objects.requireNonNull(taskId,
                "taskId cannot be empty or null");

        pendingTasks.add(taskId);
        stagingTasks.remove(taskId);
        activeTasks.remove(taskId);
        lostTasks.remove(taskId);
        killableTasks.remove(taskId);
        updateStateStore();
    }

    public void makeTaskStaging(Protos.TaskID taskId) {
        Objects.requireNonNull(taskId,
                "taskId cannot be empty or null");

        pendingTasks.remove(taskId);
        stagingTasks.add(taskId);
        activeTasks.remove(taskId);
        lostTasks.remove(taskId);
        killableTasks.remove(taskId);
        updateStateStore();
    }

    public void makeTaskActive(Protos.TaskID taskId) {
        Objects.requireNonNull(taskId,
                "taskId cannot be empty or null");

        pendingTasks.remove(taskId);
        stagingTasks.remove(taskId);
        activeTasks.add(taskId);
        lostTasks.remove(taskId);
        killableTasks.remove(taskId);
        updateStateStore();
    }

    public void makeTaskLost(Protos.TaskID taskId) {
        Objects.requireNonNull(taskId,
                "taskId cannot be empty or null");

        pendingTasks.remove(taskId);
        stagingTasks.remove(taskId);
        activeTasks.remove(taskId);
        lostTasks.add(taskId);
        killableTasks.remove(taskId);
        updateStateStore();
    }

    public void makeTaskKillable(Protos.TaskID taskId) {
        Objects.requireNonNull(taskId,
                "taskId cannot be empty or null");

        pendingTasks.remove(taskId);
        stagingTasks.remove(taskId);
        activeTasks.remove(taskId);
        lostTasks.remove(taskId);
        killableTasks.add(taskId);
        updateStateStore();
    }

    public Set<Protos.TaskID> getKillableTasks() {
        return this.killableTasks;
    }

    public NodeTask getTask(Protos.TaskID taskId) {
        return this.tasks.get(taskId);
    }

    public void removeTask(Protos.TaskID taskId) {
        this.pendingTasks.remove(taskId);
        this.stagingTasks.remove(taskId);
        this.activeTasks.remove(taskId);
        this.lostTasks.remove(taskId);
        this.killableTasks.remove(taskId);
        this.tasks.remove(taskId);
        updateStateStore();
    }

    public Set<Protos.TaskID> getPendingTaskIds() {
        return this.pendingTasks;
    }

    public Set<Protos.TaskID> getActiveTaskIds() {
        return this.activeTasks;
    }

    public Collection<NodeTask> getActiveTasks() {
        List<NodeTask> activeNodeTasks = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(activeTasks)
                && CollectionUtils.isNotEmpty(tasks.values())) {
            for (Map.Entry<Protos.TaskID, NodeTask> entry : tasks.entrySet()) {
                if (activeTasks.contains(entry.getKey())) {
                    activeNodeTasks.add(entry.getValue());
                }
            }
        }
        return activeNodeTasks;
    }

    public Set<Protos.TaskID> getStagingTaskIds() {
        return this.stagingTasks;
    }

    public Set<Protos.TaskID> getLostTaskIds() {
        return this.lostTasks;
    }

    public MyriadState getMyriadState() {
        return this.myriadState;
    }

    public Collection<Protos.TaskStatus> getTaskStatuses() {
        Collection<Protos.TaskStatus> taskStatuses = new ArrayList<>(this.tasks.size());
        Collection<NodeTask> tasks = this.tasks.values();
        for (NodeTask task : tasks) {
            Protos.TaskStatus taskStatus = task.getTaskStatus();
            if (taskStatus != null) {
                taskStatuses.add(taskStatus);
            }
        }

        return taskStatuses;
    }

    public boolean hasTask(Protos.TaskID taskID) {
        return this.tasks.containsKey(taskID);
    }

    public Protos.FrameworkID getFrameworkID() {
         return this.frameworkId;
    }

    public void setFrameworkId(Protos.FrameworkID newFrameworkId) {
        this.frameworkId = newFrameworkId;
        updateStateStore();
    }

    private void updateStateStore() {
        if (!isMyriadStateStore()) {
            return;
        }
        try {
            StoreContext sc = new StoreContext(frameworkId, tasks, pendingTasks,
                stagingTasks, activeTasks, lostTasks, killableTasks);
            ((MyriadStateStore) stateStore).storeMyriadState(
                sc.toSerializedContext().toByteArray());
            LOGGER.info("Scheduler state stored to state store");
        } catch (Exception e) {
            LOGGER.error("Failed to write scheduler state to state store", e);
        }
    }

    private void loadStateStore() {
        if (!isMyriadStateStore()) {
            return;
        }
        try {
            byte[] stateStoreBytes = ((MyriadStateStore) stateStore).loadMyriadState();
            if (stateStoreBytes != null && stateStoreBytes.length > 0) {
                StoreContext sc = StoreContext.fromSerializedBytes(stateStoreBytes);
                this.frameworkId = sc.getFrameworkId();
                this.tasks.putAll(sc.getTasks());
                this.pendingTasks.addAll(sc.getPendingTasks());
                this.stagingTasks.addAll(sc.getStagingTasks());
                this.activeTasks.addAll(sc.getActiveTasks());
                this.lostTasks.addAll(sc.getLostTasks());
                this.killableTasks.addAll(sc.getKillableTasks());
            }
        }  catch (Exception e) {
            LOGGER.error("Failed to read scheduler state from state store", e);
        }
   }

    private boolean isMyriadStateStore() {
        if (!(stateStore instanceof MyriadStateStore)) {
            LOGGER.error("State store is not an instance of " +
                MyriadStateStore.class.getName() + ". Cannot load/store Scheduler state.");
            return false;
        }
        return true;
    }
}
