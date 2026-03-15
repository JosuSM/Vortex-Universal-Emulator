package com.vortex.emulator.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.gpu.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PerformanceViewModel @Inject constructor(
    private val chipsetDetector: ChipsetDetector,
    private val driverManager: DriverManager
) : ViewModel() {

    val chipsetInfo: StateFlow<ChipsetInfo?> = flow {
        emit(chipsetDetector.chipsetInfo)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recommendation: StateFlow<EmulationRecommendation?> = flow {
        emit(chipsetDetector.getRecommendedSettings())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val drivers: StateFlow<List<DriverInfo>> = driverManager.drivers

    val activeDriver: StateFlow<DriverInfo?> = driverManager.activeDriver

    fun activateDriver(driver: DriverInfo) {
        viewModelScope.launch {
            driverManager.activateDriver(driver)
        }
    }
}
