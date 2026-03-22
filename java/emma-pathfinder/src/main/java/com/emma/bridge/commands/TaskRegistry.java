package com.emma.bridge.commands;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * Shared task registry that correlates command-issued task_ids with their type
 * and state. Command handlers register tasks here; TaskListener reads them to
 * emit task_complete/task_failed events with the correct task_id and task_type.
 *
 * Thread-safe: only mutated from the client tick thread (commands execute there,
 * task completion detects there).
 */
public class TaskRegistry {

    private static String activeTaskId = null;
    private static String activeTaskType = null;
    private static long activeTaskStartTime = 0;

    /**
     * Register a new active task. Called by command handlers (goto, mine, build)
     * after issuing the Emmatone command.
     *
     * @param taskType The command type (e.g. "goto", "mine", "build")
     * @return The generated task_id
     */
    public static String registerTask(String taskType) {
        activeTaskId = UUID.randomUUID().toString().substring(0, 8);
        activeTaskType = taskType;
        activeTaskStartTime = System.currentTimeMillis();
        return activeTaskId;
    }

    /**
     * Clear the active task. Called on completion, failure, or cancellation.
     */
    public static void clearTask() {
        activeTaskId = null;
        activeTaskType = null;
        activeTaskStartTime = 0;
    }

    /** @return The current active task ID, or null if no task is running. */
    public static String getActiveTaskId() {
        return activeTaskId;
    }

    /** @return The current active task type (e.g. "goto"), or null. */
    public static String getActiveTaskType() {
        return activeTaskType;
    }

    /** @return When the active task was started (epoch millis), or 0. */
    public static long getActiveTaskStartTime() {
        return activeTaskStartTime;
    }

    /** @return true if a task is currently registered as active. */
    public static boolean hasActiveTask() {
        return activeTaskId != null;
    }

    /**
     * Build a JSON snapshot of the current task state for the status command.
     */
    public static JsonObject toJson() {
        JsonObject task = new JsonObject();
        if (activeTaskId != null) {
            task.addProperty("task_id", activeTaskId);
            task.addProperty("task_type", activeTaskType);
            task.addProperty("duration_ms", System.currentTimeMillis() - activeTaskStartTime);
        } else {
            task.addProperty("task_id", (String) null);
            task.addProperty("task_type", (String) null);
        }
        return task;
    }
}
