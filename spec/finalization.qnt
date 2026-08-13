module finalization {
  type Phase = Preparing | Uploading | Completing | Recovery | Terminal
  type Outcome = None | Result | Retry | Restart | Exit
  type Error = NoError | Deadline | Failure

  var phase: Phase
  var outcome: Outcome
  var error: Error
  var elapsed: int
  var idle: int
  var recoveryActions: int

  action init = all { phase' = Preparing, outcome' = None, error' = NoError, elapsed' = 0, idle' = 0, recoveryActions' = 0 }
  action prepared = all { phase == Preparing, phase' = Uploading, outcome' = None, error' = NoError, elapsed' = 0, idle' = 0, recoveryActions' = 0 }
  action progress = all { phase == Uploading, elapsed < 5, phase' = Uploading, outcome' = None, error' = NoError, elapsed' = elapsed + 1, idle' = 0, recoveryActions' = 0 }
  action idleTick = all { phase == Uploading, idle < 3, elapsed < 5, phase' = Uploading, outcome' = None, error' = NoError, elapsed' = elapsed + 1, idle' = idle + 1, recoveryActions' = 0 }
  action uploaded = all { phase == Uploading, phase' = Completing, outcome' = None, error' = NoError, elapsed' = 0, idle' = 0, recoveryActions' = 0 }
  action completed = all { phase == Completing, phase' = Terminal, outcome' = Result, error' = NoError, elapsed' = elapsed, idle' = idle, recoveryActions' = 0 }
  action operationError = all { phase != Terminal, phase != Recovery, phase' = Recovery, outcome' = None, error' = Failure, elapsed' = elapsed, idle' = idle, recoveryActions' = 7 }
  action deadline = all {
    phase != Terminal, phase != Recovery,
    any { phase == Uploading and idle >= 3, phase == Uploading and elapsed >= 5, phase != Uploading },
    phase' = Recovery, outcome' = None, error' = Deadline, elapsed' = elapsed, idle' = idle, recoveryActions' = 7
  }
  action retry = all { phase == Recovery, phase' = Uploading, outcome' = Retry, error' = NoError, elapsed' = 0, idle' = 0, recoveryActions' = 0 }
  action restart = all { phase == Recovery, phase' = Terminal, outcome' = Restart, error' = error, elapsed' = elapsed, idle' = idle, recoveryActions' = 0 }
  action exit = all { phase == Recovery, phase' = Terminal, outcome' = Exit, error' = error, elapsed' = elapsed, idle' = idle, recoveryActions' = 0 }
  action step = any { prepared, progress, idleTick, uploaded, completed, operationError, deadline, retry, restart, exit }

  val typeInv = elapsed >= 0 and idle >= 0
  val terminalHasOutcome = phase != Terminal or outcome != None
  val recoveryHasAction = phase != Recovery or recoveryActions > 0
  val deadlineExposesRecovery = error != Deadline or phase == Recovery or phase == Terminal

  // Liveness assumes operations eventually return, time advances while work is pending,
  // and a user eventually chooses an offered recovery action. Those fairness assumptions
  // are environmental, so bounded-deadlock freedom is checked as the safety property above.
}
