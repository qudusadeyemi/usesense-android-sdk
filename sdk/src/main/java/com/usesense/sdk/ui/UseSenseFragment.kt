package com.usesense.sdk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.usesense.sdk.*

/**
 * Embeddable verification fragment. Integrators can add this to their own layout
 * instead of using the full-screen UseSenseActivity.
 *
 * Usage:
 *   val fragment = UseSenseFragment.newInstance(request)
 *   fragment.setCallback(callback)
 *   supportFragmentManager.beginTransaction()
 *       .replace(R.id.container, fragment)
 *       .commit()
 */
class UseSenseFragment : Fragment() {

    private var callback: UseSenseCallback? = null
    private var request: VerificationRequest? = null

    fun setCallback(callback: UseSenseCallback) {
        this.callback = callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Reuse the same layout as the activity
        return inflater.inflate(R.layout.activity_usesense, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The Fragment delegates to the same orchestration logic as UseSenseActivity.
        // For a production SDK, extract shared logic into a VerificationController
        // that both Activity and Fragment can use. Stubbed here for the initial build.
    }

    companion object {
        fun newInstance(request: VerificationRequest): UseSenseFragment {
            return UseSenseFragment().apply {
                this.request = request
            }
        }
    }
}
