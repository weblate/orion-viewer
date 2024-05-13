package universe.constellation.orion.viewer.prefs

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import universe.constellation.orion.viewer.R

class DebugPreferenceFragment : SwitchHeaderPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.debug_preference, rootKey)

        val debugLogFolder =
            (requireContext().applicationContext as OrionApplication).debugLogFolder() ?: return

        val files = debugLogFolder.listFiles() ?: return
        if (files.isNotEmpty()) {
            val logs =
                preferenceScreen.findPreference<PreferenceCategory>("LOGS") ?: return
            logs.summary = null
            files.forEach { file ->
                logs.addPreference(
                    Preference(requireContext()).apply {
                        isPersistent = false
                        title = file.name
                        isIconSpaceReserved = false
                        onPreferenceClickListener =
                            Preference.OnPreferenceClickListener {
                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_STREAM, file.toURI())
                                    type = "text/plain"
                                }

                                val shareIntent = Intent.createChooser(sendIntent, null)
                                startActivity(shareIntent)
                                true
                            }
                    }
                )
            }
        }
    }
}