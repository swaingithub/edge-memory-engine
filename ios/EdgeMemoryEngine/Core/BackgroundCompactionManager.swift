import BackgroundTasks

class BackgroundCompactionManager {
    static let taskIdentifier = "com.edgememory.nightlycompaction"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            guard let processingTask = task as? BGProcessingTask else { return }
            handleNightlyCompaction(task: processingTask)
        }
    }

    static func scheduleNextRun() {
        let request = BGProcessingTaskRequest(identifier: taskIdentifier)
        request.requiresCharging = true
        request.requiresNetworkConnectivity = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 24 * 3600)

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            print("Failed to schedule background task: \\(error)")
        }
    }

    private static func handleNightlyCompaction(task: BGProcessingTask) {
        scheduleNextRun()

        task.expirationHandler = {
            // Cancel any long-running transactions if iOS revokes background time
        }

        Task {
            await DatabaseManager.shared.runNightlyWALCheckpoint()
            task.setTaskCompleted(success: true)
        }
    }
}
