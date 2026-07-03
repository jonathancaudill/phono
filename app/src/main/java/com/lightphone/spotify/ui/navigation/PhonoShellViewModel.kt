package com.lightphone.spotify.ui.navigation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhonoShellViewModel : ViewModel() {
    private val _currentTab = MutableStateFlow(PhonoTab.Liked)
    val currentTab: StateFlow<PhonoTab> = _currentTab.asStateFlow()

    fun selectTab(tab: PhonoTab) {
        _currentTab.value = tab
    }
}
