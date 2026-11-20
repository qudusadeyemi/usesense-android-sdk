package com.usesense.sdk.flows

/**
 * When the runner stops driving a run, and what it shows then. Pure helpers
 * so the decision is unit-testable without an Activity. Mirrors `run-state.ts`
 * in the web SDK.
 */
internal object FlowRunSettlement {
    /** States the run never leaves on its own: the runner stops and reports. */
    val TERMINAL_STATES: Set<FlowRunState> = setOf(
        FlowRunState.COMPLETED,
        FlowRunState.ERRORED,
        FlowRunState.CANCELLED,
        FlowRunState.ABANDONED,
    )

    /**
     * True when the run is parked for a human reviewer. The server sets the
     * state to `awaiting_review` and the pending action to `await_review`
     * together; either one is enough.
     */
    fun isAwaitingReview(view: FlowRunView): Boolean =
        view.state == FlowRunState.AWAITING_REVIEW || view.pendingAction is PendingAction.AwaitReview

    /** True when the runner is done with the run: terminal, or held for review. */
    fun isSettled(view: FlowRunView): Boolean = view.state in TERMINAL_STATES || isAwaitingReview(view)

    /** The result the host app's callback receives for a settled run. */
    fun resultFor(view: FlowRunView): FlowRunResult = FlowRunResult(
        flowRunId = view.id,
        state = if (isAwaitingReview(view)) FlowRunState.AWAITING_REVIEW else view.state,
        outcome = view.outcome,
    )

    /** Title and body for the review end screen, with copy overrides applied. */
    fun reviewScreenCopy(copy: FlowCopy?): Pair<String, String> =
        text(copy?.result?.reviewTitle, "Under review") to
            text(copy?.result?.reviewBody, "Your details are being reviewed.")

    /** Title, body and retry label for a step this SDK version cannot show. */
    fun unsupportedStepCopy(copy: FlowCopy?): Triple<String, String, String> = Triple(
        text(copy?.errors?.generic, "Something went wrong"),
        text(copy?.errors?.unsupportedStep, "This step could not be shown here. Try again, or come back later."),
        text(copy?.buttons?.tryAgain, "Try again"),
    )
}
