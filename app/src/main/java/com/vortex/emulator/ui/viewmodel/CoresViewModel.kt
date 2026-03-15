package com.vortex.emulator.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.vortex.emulator.core.CoreInfo
import com.vortex.emulator.core.CoreManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CoresViewModel @Inject constructor(
    private val coreManager: CoreManager
) : ViewModel() {

    val installedCores: StateFlow<List<CoreInfo>> = coreManager.installedCores
    val availableCores: StateFlow<List<CoreInfo>> = coreManager.availableCores
}
